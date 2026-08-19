package org.lsposed.lspatch.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspatch.util.LoadedModules;
import org.matrix.vector.ipc.IFrameworkService;
import org.matrix.vector.ipc.IProcessChannel;
import org.matrix.vector.ipc.LoadedModule;

/**
 * The {@link IFrameworkService} for manager mode: it binds the manager's service and forwards module
 * queries to it, so the app is served whatever modules the manager has scoped to it.
 *
 * <p>The manager is an ordinary app and can be gone at any moment -- reaped for memory, force-stopped
 * by a person or by whatever the device calls its battery saver. Three things follow, and this class
 * is where all three are handled. The binding is kept and re-established rather than made once, so a
 * manager that dies mid-session comes back on its own. Every module's service is handed to the
 * framework through a {@link ReconnectingModuleService}, so the module never holds a binder into a
 * process that has gone. And when the manager does not answer at startup at all, the modules are
 * loaded from {@link ModuleSnapshot} -- the same APKs, listed from the host's own copy -- rather than
 * the app starting silently unhooked.</p>
 */
public class RemoteApplicationService implements IFrameworkService {

    private static final String TAG = "LSPatch";

    /**
     * How long the app waits for the manager's binder before starting without it.
     *
     * The bind carries BIND_AUTO_CREATE, so this covers starting the manager's process from nothing.
     * The app's own startup is held open meanwhile, which is why it is short: a miss is no longer
     * fatal -- the snapshot answers instead and the binding stays live for whenever the manager does
     * come up -- so there is nothing to buy by waiting longer.
     */
    private static final long BIND_TIMEOUT_MS = 1500;

    private static final long REBIND_DELAY_MS = 2000;
    private static final long REBIND_MAX_DELAY_MS = 300_000;

    private final Context context;
    private final String managerPackage;
    private final ModuleSnapshot snapshot;
    private final ModuleDeliveryLog deliveryLog;
    private final File stateDir;

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "lspatch-manager-link"));

    /** One per module, for the life of the process; what the framework is handed. */
    private final Map<String, ReconnectingModuleService> moduleServices = new ConcurrentHashMap<>();

    /**
     * This service's own identity, which is the host's and not the manager's.
     *
     * The framework keeps a service only if it can take a binder from it and watch that binder die
     * ({@code VectorServiceClient.init}), so answering with the manager's binder would mean answering
     * with null whenever the manager is away -- and being dropped for the life of the process at the
     * exact moment this class exists to cover. It is a local binder: it never dies, the framework's
     * death watch is therefore a no-op, and the manager's comings and goings are handled here instead
     * of ending the framework's client.
     */
    private final Binder token = new Binder();

    /** Only ever touched through {@link #legacyHandler()}; null on Q and later, where it is never needed. */
    private Handler legacyHandler;

    private volatile IFrameworkService service;
    private volatile boolean bound;
    private volatile boolean everConnected;
    private volatile long rebindDelay = REBIND_DELAY_MS;

    /**
     * The channel the manager drives hot reload over. Created when the framework attaches its own and
     * kept, because the manager loses its side with its process and has to be handed one again.
     */
    private volatile LSPatchProcessChannel processChannel;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            var manager = IFrameworkService.Stub.asInterface(binder);
            service = manager;
            rebindDelay = REBIND_DELAY_MS;
            deliveryLog.recordDelivered();
            if (!everConnected) {
                everConnected = true;
                Log.i(TAG, "Manager binder received");
                connected.countDown();
                return;
            }
            // A reconnection: the framework asked for its modules long ago and will not ask again, so
            // everything the previous manager process was holding has to be re-established from here.
            Log.i(TAG, "Manager is back; restoring what it was holding");
            worker.execute(() -> reestablish(manager));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The binding survives: with BIND_AUTO_CREATE the system restarts the manager and calls
            // back here on its own, and until it does the module services serve what they cached.
            Log.w(TAG, "Manager service died");
            service = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Permanent, unlike a death: the package was replaced or force-stopped, and nothing is
            // coming back on this binding. Only an explicit rebind reaches the manager again.
            Log.w(TAG, "Binding to the manager died; will rebind");
            service = null;
            unbind();
            scheduleRebind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.e(TAG, "Manager refused to serve this app");
            service = null;
            unbind();
            scheduleRebind();
        }
    };

    private final CountDownLatch connected = new CountDownLatch(1);

    public RemoteApplicationService(Context context, String managerPackageName) {
        this.context = context;
        this.managerPackage = (managerPackageName == null || managerPackageName.isEmpty())
                ? Constants.MANAGER_PACKAGE_NAME
                : managerPackageName;
        this.stateDir = new File(context.getNoBackupFilesDir(), "lspatch");
        this.snapshot = new ModuleSnapshot(context);
        this.deliveryLog = new ModuleDeliveryLog(context);

        Log.i(TAG, "Request manager binder from " + managerPackage);
        var start = SystemClock.elapsedRealtime();
        if (bind()) {
            try {
                if (connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.i(TAG, "Manager binder received in " + (SystemClock.elapsedRealtime() - start) + "ms");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // A late bind and one that never lands are the same from here, and only the elapsed time
            // tells them apart. The binding is left in place: the manager may still come up, and when
            // it does it corrects the snapshot for the next launch.
            Log.w(TAG, "Manager did not answer in " + (SystemClock.elapsedRealtime() - start) + "ms");
        } else {
            // The system refuses when it will not start the manager at all -- it is not installed, or
            // its package is in a state the system will not launch from here. Waiting changes nothing,
            // so this is reported on its own and the rebind is left to the schedule.
            Log.e(TAG, "System refused to bind " + managerPackage + "; it may not be installed");
            scheduleRebind();
        }
        deliveryLog.recordFallback();
        if (snapshot.isEmpty()) {
            // Nothing cached and nobody to ask: this app is genuinely running unhooked, and that is
            // worth telling the person holding the phone, because nothing else will.
            toast("LSPatch manager not reachable");
        } else {
            Log.i(TAG, "Loading modules from this app's own snapshot");
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private boolean bind() {
        var intent = new Intent()
                .setComponent(new ComponentName(managerPackage, Constants.MANAGER_SERVICE_NAME))
                .putExtra("packageName", context.getPackageName());
        // TODO: Authentication
        deliveryLog.describeTo(intent);
        try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ok = context.bindService(intent, Context.BIND_AUTO_CREATE, worker, connection);
            } else {
                var contextImplClass = context.getClass();
                var getUserMethod = contextImplClass.getMethod("getUser");
                var bindServiceAsUserMethod = contextImplClass.getDeclaredMethod(
                        "bindServiceAsUser",
                        Intent.class,
                        ServiceConnection.class,
                        int.class,
                        Handler.class,
                        UserHandle.class);
                var userHandle = (UserHandle) getUserMethod.invoke(context);
                ok = Boolean.TRUE.equals(bindServiceAsUserMethod.invoke(
                        context, intent, connection, Context.BIND_AUTO_CREATE, legacyHandler(), userHandle));
            }
            bound = ok;
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "Cannot bind the manager", t);
            bound = false;
            return false;
        }
    }

    /**
     * The thread the pre-Q bind path delivers its callbacks on, made once.
     *
     * That path takes a Handler rather than an Executor, and the binding is now re-established as often as the manager
     * comes and goes -- so a thread built per attempt would be one more looper left running for every rebind on a
     * device old enough to need this path at all.
     */
    private synchronized Handler legacyHandler() {
        if (legacyHandler == null) {
            var thread = new HandlerThread("lspatch-manager-link-legacy");
            thread.start();
            legacyHandler = new Handler(thread.getLooper());
        }
        return legacyHandler;
    }

    private void unbind() {
        if (!bound) return;
        bound = false;
        try {
            context.unbindService(connection);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot release the manager binding", t);
        }
    }

    private void scheduleRebind() {
        var delay = rebindDelay;
        rebindDelay = Math.min(rebindDelay * 2, REBIND_MAX_DELAY_MS);
        worker.schedule(
                () -> {
                    if (service != null) return;
                    Log.i(TAG, "Rebinding the manager");
                    if (!bind()) scheduleRebind();
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Hands a manager that has just come back everything the previous one was holding: the channel it
     * drives hot reload over, which modules this process is running, and a live binder for each of the
     * module services the hook is still using.
     *
     * <p>The module lists are asked for again because that request is what carries all three: the
     * manager records the caller's modules and pushes each module's service to its companion app while
     * answering it, and the answer is where a fresh {@code IModuleService} per module comes from. The
     * code it maps for that answer is nobody's to load -- the modules in this process were loaded long
     * ago -- so it is released rather than left to a finalizer.</p>
     */
    private void reestablish(IFrameworkService manager) {
        var channel = processChannel;
        if (channel != null) {
            try {
                manager.attachProcessChannel(channel);
            } catch (Throwable t) {
                Log.w(TAG, "Could not re-attach the process channel", t);
            }
        }
        for (boolean legacy : new boolean[] {true, false}) {
            List<LoadedModule> served;
            try {
                served = legacy ? manager.getLegacyModules() : manager.getModules();
            } catch (Throwable t) {
                Log.w(TAG, "Could not re-read the module list", t);
                continue;
            }
            if (served == null) continue;
            for (var module : served) {
                if (module == null || module.packageName == null) continue;
                var proxy = moduleServices.get(module.packageName);
                if (proxy != null) proxy.onManagerReconnected(module.service);
                LoadedModules.discard(module);
            }
            snapshot.save(served, legacy);
        }
    }

    /** Replaces each module's manager binder with the host-side proxy the framework will keep. */
    private void adopt(List<LoadedModule> served) {
        for (var module : served) {
            if (module == null || module.packageName == null) continue;
            var proxy = moduleService(module.packageName);
            proxy.setLive(module.service);
            module.service = proxy;
        }
    }

    private ReconnectingModuleService moduleService(String modulePackageName) {
        return moduleServices.computeIfAbsent(modulePackageName, pkg -> new ReconnectingModuleService(pkg, stateDir));
    }

    private List<LoadedModule> modules(boolean legacy) {
        var manager = service;
        if (manager != null) {
            try {
                var served = legacy ? manager.getLegacyModules() : manager.getModules();
                if (served != null) {
                    adopt(served);
                    // Off this thread: the app's own start is waiting on this call, and recording what
                    // was served is for the next launch rather than for this one.
                    worker.execute(() -> snapshot.save(served, legacy));
                    return served;
                }
            } catch (Throwable t) {
                Log.w(TAG, "The manager could not serve this app's modules", t);
            }
        }
        var restored = snapshot.restore(legacy, this::moduleService);
        // Said out loud, because this is the one path where nobody else can say it: the manager did not
        // answer, so the count of what was loaded anyway exists only here.
        Log.i(TAG, "Serving " + restored.size() + (legacy ? " legacy" : "") + " module(s) from the snapshot");
        return restored;
    }

    private void toast(String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // No looper on this thread, or a context that cannot show one: the log line is the report.
            Log.w(TAG, message);
        }
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        var manager = service;
        return manager != null && manager.isLogMuted();
    }

    @Override
    public List<LoadedModule> getLegacyModules() {
        return modules(true);
    }

    @Override
    public List<LoadedModule> getModules() {
        return modules(false);
    }

    @Override
    public String getPrefsPath(String packageName) {
        return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/").getAbsolutePath();
    }

    @Override
    public ParcelFileDescriptor openManagerApk() throws RemoteException {
        var manager = service;
        return manager == null ? null : manager.openManagerApk();
    }

    @Override
    public IBinder requestManagerService() {
        return null;
    }

    @Override
    public void attachProcessChannel(IProcessChannel channel) {
        // The manager drives hot reload but is a plain app, so the framework's own channel -- which
        // gates on the system uid -- would refuse it. Hand the manager an LSPatch channel that runs the
        // in-process swap for it instead; the framework's channel is unused without a daemon.
        var ours = new LSPatchProcessChannel();
        processChannel = ours;
        var manager = service;
        if (manager == null) return;
        try {
            manager.attachProcessChannel(ours);
        } catch (Throwable t) {
            Log.w(TAG, "Could not attach the process channel", t);
        }
    }

    @Override
    public IBinder asBinder() {
        return token;
    }
}
