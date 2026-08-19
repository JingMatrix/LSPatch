package org.lsposed.lspatch.service;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import io.github.libxposed.service.IXposedService;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.matrix.vector.ipc.IModuleService;
import org.matrix.vector.ipc.IRemotePreferenceCallback;

/**
 * A module's service as the hook sees it, standing in for the manager's own so the hook never holds
 * a binder to a process that has gone.
 *
 * <p>{@code LoadedModule.service} is handed to the framework once, while the process bootstraps, and
 * the framework keeps it for the life of the process. Pointing it straight at the manager therefore
 * makes the manager's death permanent for that app: the proxy stays dead even after the manager is
 * back, because nothing re-delivers a {@code LoadedModule}. This lives in the host instead and
 * forwards to whichever manager binder is current, so a restart is invisible to the module.</p>
 *
 * <p>Everything here is read-only -- {@link IModuleService} has no writing method, because a hooked
 * process runs as the app it was injected into rather than as the module, and writes go through the
 * module app's own {@code IXposedService}. That is what makes caching safe: there is no local write
 * that could diverge from the store, so a value served from the cache is only ever an older read,
 * never a conflicting one.</p>
 */
class ReconnectingModuleService extends IModuleService.Stub {

    private static final String TAG = "LSPatch";

    private final String modulePackageName;
    private final File cacheDir;

    private volatile IModuleService live;

    private volatile long properties = IXposedService.PROP_CAP_REMOTE;
    private volatile String[] fileNames = new String[0];

    /** Last value served for a preference group, by group. */
    private final Map<String, HashMap<String, Object>> prefsCache = new ConcurrentHashMap<>();

    /**
     * The subscriptions the hook made, kept so they can be made again against a new manager binder.
     * A subscription is registered with the manager, so it dies with the manager's process; the hook
     * subscribes once and would otherwise never hear another change.
     */
    private final Map<String, IRemotePreferenceCallback> subscriptions = new ConcurrentHashMap<>();

    ReconnectingModuleService(String modulePackageName, File stateDir) {
        this.modulePackageName = modulePackageName;
        this.cacheDir = new File(new File(stateDir, "prefs"), sanitize(modulePackageName));
    }

    /** Points the proxy at the manager binder that is current, or at nothing when it has gone. */
    void setLive(IModuleService live) {
        this.live = live;
    }

    /**
     * Re-establishes what the previous manager process was holding, and tells the hook what changed
     * while it was gone.
     *
     * <p>The manager only pushes a preference change when the module app writes one, so a hook that
     * merely re-subscribes would keep serving whatever it last saw until the next write. Reading each
     * subscribed group and delivering the difference is what closes that window; the difference is
     * shaped exactly like the editor's own diff, so the hook handles it on the path it already has.</p>
     */
    void onManagerReconnected(IModuleService live) {
        this.live = live;
        for (var subscription : subscriptions.entrySet()) {
            var group = subscription.getKey();
            try {
                var fresh = readMap(live.requestRemotePreferences(group, subscription.getValue()));
                var previous = prefsCache.get(group);
                store(group, fresh);
                var diff = diff(previous, fresh);
                if (diff != null) subscription.getValue().onRemotePreferencesChanged(diff);
            } catch (Throwable t) {
                Log.w(TAG, "Could not restore the subscription to " + modulePackageName + "/" + group, t);
            }
        }
    }

    @Override
    public long getFrameworkProperties() {
        var live = this.live;
        if (live == null) return properties;
        try {
            properties = live.getFrameworkProperties();
        } catch (Throwable t) {
            Log.w(TAG, "getFrameworkProperties fell back to the last known value", t);
        }
        return properties;
    }

    @Override
    public Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback) {
        if (callback != null) subscriptions.put(group, callback);
        var live = this.live;
        if (live != null) {
            try {
                var values = readMap(live.requestRemotePreferences(group, callback));
                store(group, values);
                return bundle(values);
            } catch (Throwable t) {
                Log.w(TAG, "Reading " + modulePackageName + "/" + group + " from the manager failed", t);
            }
        }
        return bundle(cached(group));
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) {
        var live = this.live;
        if (live == null) {
            // No local copy of the module's remote files is kept, so with the manager gone the file is
            // simply not there -- which is the case the API already documents a null return for.
            Log.d(TAG, "Remote file " + path + " is unavailable while the manager is unreachable");
            return null;
        }
        try {
            return live.openRemoteFile(path);
        } catch (Throwable t) {
            Log.w(TAG, "openRemoteFile " + path + " failed", t);
            return null;
        }
    }

    @Override
    public String[] getRemoteFileNames() {
        var live = this.live;
        if (live != null) {
            try {
                var names = live.getRemoteFileNames();
                if (names != null) fileNames = names;
            } catch (Throwable t) {
                Log.w(TAG, "getRemoteFileNames fell back to the last known list", t);
            }
        }
        return fileNames;
    }

    private static Bundle bundle(HashMap<String, Object> values) {
        var bundle = new Bundle();
        bundle.putSerializable("map", values);
        return bundle;
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, Object> readMap(Bundle bundle) {
        if (bundle == null) return new HashMap<>();
        var map = bundle.getSerializable("map");
        return map instanceof HashMap ? (HashMap<String, Object>) map : new HashMap<>();
    }

    /**
     * The diff between two reads, in the shape {@code RemotePreferences.Editor} produces, or null
     * when nothing changed.
     */
    private static Bundle diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null) return null;
        var removed = new HashSet<String>();
        for (var key : before.keySet()) {
            if (!after.containsKey(key)) removed.add(key);
        }
        var changed = new HashMap<String, Object>();
        for (var entry : after.entrySet()) {
            var old = before.get(entry.getKey());
            if (old == null || !old.equals(entry.getValue())) changed.put(entry.getKey(), entry.getValue());
        }
        if (removed.isEmpty() && changed.isEmpty()) return null;
        var diff = new Bundle();
        if (!removed.isEmpty()) diff.putSerializable("delete", removed);
        if (!changed.isEmpty()) diff.putSerializable("put", changed);
        return diff;
    }

    private HashMap<String, Object> cached(String group) {
        var values = prefsCache.get(group);
        if (values != null) return values;
        values = readFromDisk(group);
        prefsCache.put(group, values);
        return values;
    }

    private void store(String group, HashMap<String, Object> values) {
        var previous = prefsCache.put(group, values);
        if (values.equals(previous)) return;
        writeToDisk(group, values);
    }

    /**
     * The cache outlives the process, because the launch that needs it most is the one where the app
     * starts cold and the manager never comes up at all.
     *
     * <p>Java serialization, as in the store this mirrors. Only values that survived the manager's own
     * deserialization ever reach here, and the manager can no more load a module-defined class than
     * this process can -- so what is written is always platform types the host can read back.</p>
     */
    private HashMap<String, Object> readFromDisk(String group) {
        var file = new File(cacheDir, sanitize(group) + ".prefs");
        if (!file.isFile()) return new HashMap<>();
        try (var in = new ObjectInputStream(Files.newInputStream(file.toPath()))) {
            var read = in.readObject();
            if (read instanceof HashMap) {
                //noinspection unchecked
                return (HashMap<String, Object>) read;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unreadable preference cache for " + modulePackageName + "/" + group, t);
        }
        return new HashMap<>();
    }

    private void writeToDisk(String group, HashMap<String, Object> values) {
        var file = new File(cacheDir, sanitize(group) + ".prefs");
        try {
            cacheDir.mkdirs();
            var tmp = new File(file.getPath() + ".tmp");
            try (var out = new ObjectOutputStream(Files.newOutputStream(tmp.toPath()))) {
                out.writeObject(values);
            }
            if (!tmp.renameTo(file)) Files.deleteIfExists(tmp.toPath());
        } catch (Throwable t) {
            Log.w(TAG, "Could not cache " + modulePackageName + "/" + group, t);
        }
    }

    /** A group or package name is free-form; a file name is not. */
    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_") + "-" + Integer.toHexString(name.hashCode());
    }
}
