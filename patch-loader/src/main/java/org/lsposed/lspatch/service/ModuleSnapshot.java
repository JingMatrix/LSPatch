package org.lsposed.lspatch.service;

import android.content.Context;
import android.util.Log;
import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.lsposed.lspatch.util.LoadedModules;
import org.matrix.vector.ipc.IModuleService;
import org.matrix.vector.ipc.LoadedModule;

/**
 * What the host remembers about the modules the manager last served it.
 *
 * <p>A patched app cannot depend on the manager being alive when it starts: the manager is an
 * ordinary app, and a device that reaps background processes -- or a person who force-stopped it --
 * leaves the bind unanswered, which used to mean the app started with no modules at all and no way
 * to tell. The module list is not secret and barely changes, so the host keeps its own copy and
 * loads from that when the manager does not answer in time.</p>
 *
 * <p>What is recorded is only <b>identity</b>: which module, which APK, and what the manager said
 * about it. The code itself is read from the module's installed APK at load time by the same {@link
 * LoadedModules#fromApk} the manager-served path uses, so a restored module and a served one are the
 * same dex from the same file -- the fallback changes where the <i>list</i> came from, and nothing
 * else. What it cannot restore is a change made while the manager was unreachable: a module disabled
 * in the manager stays in the snapshot until a launch reaches the manager again, which is the price
 * of loading anything at all in that state.</p>
 */
class ModuleSnapshot {

    private static final String TAG = "LSPatch";

    /** Bumped when the shape below changes; an older or newer file is ignored rather than guessed at. */
    private static final int FORMAT = 1;

    private static final Gson GSON = new Gson();

    /**
     * One module, as much of it as survives without the manager.
     *
     * <p>{@code sourceDir} and {@code nativeLibraryDir} are carried field by field rather than as a
     * parcelled {@link android.content.pm.ApplicationInfo}: a Parcel is a transport, not a storage
     * format, and one written by an older platform is not guaranteed to be readable after a system
     * update -- exactly the moment the snapshot is most needed.</p>
     */
    static class Entry {
        String packageName;
        String apkPath;
        int appId;
        long versionCode;
        boolean legacy;
        String sourceDir;
        String nativeLibraryDir;
    }

    private static class File_ {
        int format;
        long writtenAt;
        List<Entry> modules;
    }

    private final Context context;
    private final File file;

    /** Kept in memory so a save can rewrite the whole table after a call that served only half of it. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private boolean loaded;

    ModuleSnapshot(Context context) {
        // Not the cache directory: a cleared cache is one of the states in which the manager is least
        // likely to be reachable, and the snapshot has to outlive it. No-backup, because it describes
        // this device's installed modules and means nothing restored onto another one.
        this.context = context;
        File dir = new File(context.getNoBackupFilesDir(), "lspatch");
        this.file = new File(dir, "modules.json");
    }

    private synchronized void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        if (!file.isFile()) return;
        try {
            var text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            var parsed = GSON.fromJson(text, File_.class);
            if (parsed == null || parsed.format != FORMAT || parsed.modules == null) {
                Log.i(TAG, "Ignoring a module snapshot this loader does not understand");
                return;
            }
            for (var entry : parsed.modules) {
                if (entry != null && entry.packageName != null) entries.put(entry.packageName, entry);
            }
            Log.i(TAG, "Module snapshot holds " + entries.size() + " module(s)");
        } catch (Throwable t) {
            Log.w(TAG, "Unreadable module snapshot; ignoring it", t);
            entries.clear();
        }
    }

    /**
     * Replaces everything recorded about modules of one kind with what the manager just served.
     *
     * <p>Per kind, because {@code getModules} and {@code getLegacyModules} are two separate calls
     * and each is the whole truth about its own half; merging on package name alone would leave a
     * legacy module that has since been removed sitting in the table forever.</p>
     */
    synchronized void save(List<LoadedModule> served, boolean legacy) {
        loadIfNeeded();
        entries.values().removeIf(entry -> entry.legacy == legacy);
        for (var module : served) {
            if (module == null || module.packageName == null || module.apkPath == null) continue;
            var entry = new Entry();
            entry.packageName = module.packageName;
            entry.apkPath = module.apkPath;
            entry.appId = module.appId;
            entry.versionCode = module.versionCode;
            entry.legacy = legacy;
            if (module.applicationInfo != null) {
                entry.sourceDir = module.applicationInfo.sourceDir;
                entry.nativeLibraryDir = module.applicationInfo.nativeLibraryDir;
            }
            entries.put(entry.packageName, entry);
        }
        write();
    }

    private void write() {
        var payload = new File_();
        payload.format = FORMAT;
        payload.writtenAt = System.currentTimeMillis();
        payload.modules = new ArrayList<>(entries.values());
        try {
            var dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            // Written beside the target and moved into place, so a process killed mid-write leaves the
            // previous snapshot intact rather than a truncated one.
            var tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) {
                Files.deleteIfExists(tmp.toPath());
                Log.w(TAG, "Could not replace the module snapshot");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not write the module snapshot", t);
        }
    }

    /**
     * The modules of one kind, rebuilt from their installed APKs.
     *
     * @param service what to attach as each module's {@code LoadedModule.service}
     */
    synchronized List<LoadedModule> restore(boolean legacy, Function<String, IModuleService> service) {
        loadIfNeeded();
        var modules = new ArrayList<LoadedModule>();
        for (var entry : entries.values()) {
            if (entry.legacy != legacy) continue;
            var apkPath = entry.sourceDir != null ? entry.sourceDir : entry.apkPath;
            if (apkPath == null || !new File(apkPath).isFile()) {
                // Updating a module app moves its APK, so a path recorded before the update points at
                // nothing. Ask this process's own PackageManager where the module lives now -- it may
                // refuse, because package visibility filters what a patched app is allowed to see, and
                // then the module is genuinely unreachable until a launch reaches the manager again.
                apkPath = installedApkPath(entry.packageName);
                if (apkPath == null) {
                    Log.w(TAG, "Snapshot module " + entry.packageName + " cannot be found on this device");
                    continue;
                }
                Log.i(TAG, "Snapshot module " + entry.packageName + " has moved to " + apkPath);
            }
            var info = LoadedModules.syntheticApplicationInfo(entry.packageName, apkPath, entry.nativeLibraryDir);
            var module = LoadedModules.fromApk(
                    entry.packageName,
                    apkPath,
                    entry.appId,
                    entry.versionCode,
                    info,
                    legacy,
                    service.apply(entry.packageName));
            if (module != null) modules.add(module);
        }
        return modules;
    }

    private String installedApkPath(String packageName) {
        try {
            var path = context.getPackageManager().getApplicationInfo(packageName, 0).sourceDir;
            return path != null && new File(path).isFile() ? path : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether anything at all was recorded -- the difference between "degraded" and "nothing to run". */
    synchronized boolean isEmpty() {
        loadIfNeeded();
        return entries.isEmpty();
    }
}
