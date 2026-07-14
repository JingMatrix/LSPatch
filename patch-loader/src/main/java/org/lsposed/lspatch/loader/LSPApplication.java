package org.lsposed.lspatch.loader;

import static org.lsposed.lspatch.share.Constants.CONFIG_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import org.lsposed.lspatch.loader.util.FileUtils;
import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.service.EmbeddedRemoteServices;
import org.lsposed.lspatch.service.LocalApplicationService;
import org.lsposed.lspatch.service.RemoteApplicationService;
import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspatch.share.LSPConfig;
import org.lsposed.lspatch.share.remote.FrameworkInfo;
import org.lsposed.lspatch.share.remote.LSPatchXposedService;
import org.matrix.vector.Startup;
import org.matrix.vector.ipc.IFrameworkService;
import org.matrix.vector.impl.core.VectorModuleManager;
import org.matrix.vector.impl.di.LegacyFrameworkDelegate;
import org.matrix.vector.impl.di.VectorBootstrap;
import org.json.JSONObject;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedInit;

import io.github.libxposed.service.IXposedService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedHelpers;
import hidden.HiddenApiBridge;

/**
 * Created by Windysha
 */
@SuppressWarnings("unused")
public class LSPApplication {

    private static final String TAG = "LSPatch";
    private static final int FIRST_APP_ZYGOTE_ISOLATED_UID = 90000;
    private static final int PER_USER_RANGE = 100000;

    private static ActivityThread activityThread;
    private static LoadedApk stubLoadedApk;
    private static LoadedApk appLoadedApk;

    private static JSONObject config;

    public static boolean isIsolated() {
        return (android.os.Process.myUid() % PER_USER_RANGE) >= FIRST_APP_ZYGOTE_ISOLATED_UID;
    }

    public static void onLoad() throws RemoteException, IOException {
        if (isIsolated()) {
            XLog.d(TAG, "Skip isolated process");
            return;
        }
        activityThread = ActivityThread.currentActivityThread();
        var context = createLoadedApkWithContext();
        if (context == null) {
            XLog.e(TAG, "Error when creating context");
            return;
        }

        Log.d(TAG, "Initialize service client");
        IFrameworkService service;
        if (config.optBoolean("useManager")) {
            var managerPackage = config.optString("managerPackageName", Constants.MANAGER_PACKAGE_NAME);
            if (managerPackage.isEmpty()) managerPackage = Constants.MANAGER_PACKAGE_NAME;
            Log.i(TAG, "Manager package: " + managerPackage);
            service = new RemoteApplicationService(context, managerPackage);
        } else {
            service = new LocalApplicationService(context);
        }

        disableProfile(context);

        // patch_loader.cpp built the framework loader with a parent that already defines XResources'
        // synthetic super, so XResources resolves cleanly. Publish that parent as Vector's
        // dummyClassLoader so XposedBridge.initXResources() short-circuits instead of building its own
        // device-Resources super and re-parenting the framework loader.
        ClassLoader frameworkLoader = XposedBridge.class.getClassLoader();
        if (frameworkLoader != null && frameworkLoader.getParent() != null) {
            XposedBridge.dummyClassLoader = frameworkLoader.getParent();
        }

        Startup.initXposed(false, ActivityThread.currentProcessName(), context.getApplicationInfo().dataDir, service);
        Startup.bootstrapXposed(false);
        // The target app's LoadedApk was built by hand (createLoadedApkWithContext) before these hooks
        // existed, so its constructor never registered it with LoadedApkTracker. Register it now so
        // createAppFactory / createOrUpdateClassLoaderLocked drive its modern + legacy package
        // lifecycle exactly once when realizeLoadedApk() builds the class loader below -- and, crucially,
        // so onPackageLoaded fires *before* the app's AppComponentFactory static initializer runs. A
        // packed app can wire an anti-tamper check into that <clinit> (e.g. via a hijacked
        // androidx.core.app.CoreComponentFactory -> System.loadLibrary -> JNI_OnLoad), and until the
        // lifecycle and signature bypass are armed it would run unopposed.
        Startup.trackLoadedApk(appLoadedApk);

        // WARN: Since it uses `XResource`, the following class should not be initialized
        // before forkPostCommon is invoke. Otherwise, you will get failure of XResources
        Log.i(TAG, "Load modules");
        loadModulesAndDeliver(context);
        Log.i(TAG, "Modules initialized");

        // Install signature bypass before the class loader -- and thus the app factory's <clinit> -- is
        // realized, so a signature/APK read performed from that initializer already sees the spoof.
        SigBypass.doSigBypass(context, config.optInt("sigBypassLevel"));

        // Realize the target's class loader now that hooks, modules and signature bypass are all armed.
        // getClassLoader() -> createOrUpdateClassLoaderLocked -> createAppFactory triggers
        // onPackageLoaded (pre-<clinit>) then, on return, onPackageReady and legacy handleLoadPackage.
        realizeLoadedApk();

        switchAllClassLoader();

        // Only after the app class loader exists, and before installContentProviders runs on return
        // from makeApplication, so the injected DocumentsProvider is resolvable when the platform
        // instantiates it.
        if (config.optBoolean("injectDocumentsProvider")) {
            bridgeDocumentsProviderClass();
        }

        Log.i(TAG, "LSPatch bootstrap completed");
    }

    /** The loader class the app's class loader must be taught to resolve for issue #65. */
    private static final String DOCUMENTS_PROVIDER_CLASS = "org.lsposed.lspatch.loader.LSPatchDocumentsProvider";

    /**
     * Makes the injected DocumentsProvider resolvable by the app's class loader.
     *
     * A manifest-declared component is instantiated by the platform from the app's class loader,
     * which holds only the original apk and its splits; the provider class lives in the loader's own
     * in-memory dex, which that loader never consults, so installContentProviders fails it at startup.
     * The class cannot simply be grafted onto the app loader's dex path: ART binds an in-memory dex to
     * the class-loader context it was defined under, so defining the class a second time elsewhere is
     * rejected (the "ClassLoaderContext mismatch" the log shows).
     *
     * Instead, splice a filtering loader in front of the app loader as its parent. Standard delegation
     * consults it on every lookup, but it answers with exactly one class -- the provider, loaded by the
     * loader that already owns it -- and defers everything else to the app loader's real parent. The
     * app's own classes are still found in its own dexes exactly as before; only the one class the app
     * never had now resolves. The provider depends only on the framework, so nothing else is pulled in.
     */
    private static void bridgeDocumentsProviderClass() {
        try {
            ClassLoader appClassLoader = appLoadedApk.getClassLoader();
            final ClassLoader loaderClassLoader = LSPApplication.class.getClassLoader();
            ClassLoader realParent = appClassLoader.getParent();
            ClassLoader bridge = new ClassLoader(realParent) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (DOCUMENTS_PROVIDER_CLASS.equals(name)) {
                        return loaderClassLoader.loadClass(name);
                    }
                    throw new ClassNotFoundException(name);
                }
            };
            XposedHelpers.setObjectField(appClassLoader, "parent", bridge);
            Log.i(TAG, "Documents provider: bridged into the app class loader");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to expose the documents provider to the app class loader", t);
        }
    }

    /**
     * Loads the modern modules and delivers embed-mode service binders for the already-built target
     * {@code LoadedApk}.
     *
     * <p>The package lifecycle itself is no longer replayed here. {@link #onLoad} registers the target
     * with {@code LoadedApkTracker} ({@code Startup.trackLoadedApk}) so the {@code createAppFactory} and
     * {@code createOrUpdateClassLoaderLocked} hooks {@code bootstrapXposed} installs dispatch
     * {@code onPackageLoaded} (before the app factory {@code <clinit>}), {@code onPackageReady} and the
     * legacy {@code handleLoadPackage} exactly once when {@link #realizeLoadedApk} builds the class
     * loader. Only the resource-directory registration -- normally done by the {@code LoadedApk}
     * constructor hook, which never saw this hand-built instance -- is still performed manually.</p>
     */
    private static void loadModulesAndDeliver(Context context) {
        // Instantiate modern (libxposed) modules; guarded internally so the app-attach hook, were it
        // ever to fire, cannot load a second generation on top.
        try {
            XposedInit.loadModules(activityThread);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load modern modules", t);
        }

        var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(appLoadedApk, "mApplicationInfo");
        var resDir = (String) XposedHelpers.getObjectField(appLoadedApk, "mResDir");
        var packageName = appInfo.packageName;

        // Embed mode only: hand each embedded module its io.github.libxposed.service.IXposedService
        // binder in-process, so a module that reads or writes its config through XposedServiceHelper --
        // rather than through the hook-side XposedInterface -- works even with no companion app. In
        // manager mode this is deliberately skipped: the hook reads through its manager-backed
        // IModuleService (LoadedModule.service) and the companion app is handed the IXposedService by
        // the manager's push, both onto the one manager store. Delivering a host-local IXposedService
        // here as well would let the hook read a store the companion never writes.
        // Pass asBinder() (not the typed Stub) so the module's asInterface builds a marshalling proxy
        // across the class-loader boundary.
        if (!config.optBoolean("useManager")) {
            FrameworkInfo frameworkInfo = new FrameworkInfo("LSPatch",
                    LSPConfig.instance.VERSION_NAME + " (" + LSPConfig.instance.VERSION_CODE + ")",
                    LSPConfig.instance.VERSION_CODE, IXposedService.PROP_CAP_REMOTE);
            for (String modulePkg : VectorModuleManager.INSTANCE.loadedModulePackages()) {
                try {
                    ClassLoader moduleCl = VectorModuleManager.INSTANCE.getModuleClassLoader(modulePkg);
                    if (moduleCl == null) continue;
                    Class<?> helper = moduleCl.loadClass("io.github.libxposed.service.XposedServiceHelper");
                    if (helper.getClassLoader() == LSPatchXposedService.class.getClassLoader()) {
                        // The module did not bundle the service client, so loadClass fell back to the
                        // framework's own XposedServiceHelper, which carries no module listener. Nothing
                        // to deliver -- a module that uses the service ships its own copy.
                        continue;
                    }
                    var stub = EmbeddedRemoteServices.get(context).xposedService(modulePkg, frameworkInfo, packageName);
                    Method onBinderReceived = helper.getDeclaredMethod("onBinderReceived", IBinder.class);
                    onBinderReceived.setAccessible(true);
                    onBinderReceived.invoke(null, stub.asBinder());
                    Log.i(TAG, "Delivered XposedService to " + modulePkg);
                } catch (ClassNotFoundException e) {
                    // Module does not use the libxposed service API; nothing to deliver.
                } catch (Throwable t) {
                    Log.w(TAG, "XposedService delivery failed for " + modulePkg, t);
                }
            }
        }

        // Register the resource directory for legacy resource hooking. The LoadedApk constructor hook
        // normally does this, but the target's LoadedApk was hand-built before that hook existed.
        // Guarded on its own: registering the res dir loads XResources, whose runtime superclass may be
        // unavailable, and that must not stop the rest of bootstrap. The package lifecycle callbacks
        // (onPackageLoaded/onPackageReady/handleLoadPackage) are dispatched by the LoadedApk hooks when
        // realizeLoadedApk() builds the class loader -- see onLoad and loadModulesAndDeliver's javadoc.
        LegacyFrameworkDelegate delegate = VectorBootstrap.INSTANCE.getDelegate();
        if (delegate != null) {
            try {
                if (!delegate.isResourceHookingDisabled()) {
                    delegate.setPackageNameForResDir(packageName, resDir);
                }
            } catch (Throwable t) {
                // Resource hooking needs XResources, whose runtime superclass is provided by a dummy
                // class loader inserted as the framework loader's parent. On this device that parent
                // satisfies a plain loadClass, but not the superclass resolution ART performs while
                // defining XResources, so XResources fails to link. Method hooking does not touch this
                // path, so it is only warned about; see the project notes for the open investigation.
                Log.w(TAG, "setPackageNameForResDir failed; legacy resource hooking is unavailable", t);
            }
        }
    }

    private static Context createLoadedApkWithContext() {
        try {
            var mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication");

            stubLoadedApk = (LoadedApk) XposedHelpers.getObjectField(mBoundApplication, "info");
            var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(mBoundApplication, "appInfo");
            var compatInfo = (CompatibilityInfo) XposedHelpers.getObjectField(mBoundApplication, "compatInfo");
            var baseClassLoader = stubLoadedApk.getClassLoader();

            try (var is = baseClassLoader.getResourceAsStream(CONFIG_ASSET_PATH)) {
                BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                config = new JSONObject(streamReader.lines().collect(Collectors.joining()));
            } catch (Throwable e) {
                Log.e(TAG, "Failed to parse config file", e);
                return null;
            }
            Log.i(TAG, "Use manager: " + config.optBoolean("useManager"));
            Log.i(TAG, "Signature bypass level: " + config.optInt("sigBypassLevel"));

            Path originPath = Paths.get(appInfo.dataDir, "cache/lspatch/origin/");
            Path cacheApkPath;
            try (ZipFile sourceFile = new ZipFile(appInfo.sourceDir)) {
                cacheApkPath = originPath.resolve(sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH).getCrc() + ".apk");
            }

            appInfo.sourceDir = cacheApkPath.toString();
            appInfo.publicSourceDir = cacheApkPath.toString();
            if (config.has("appComponentFactory")) {
                appInfo.appComponentFactory = config.optString("appComponentFactory");
            } else {
                // The original app declared no AppComponentFactory. The patched manifest points it
                // at the metaloader stub, which the original apk does not contain, so clearing it
                // keeps the class loader from being built against a class that cannot be found.
                appInfo.appComponentFactory = null;
            }

            if (!Files.exists(cacheApkPath)) {
                Log.i(TAG, "Extract original apk");
                FileUtils.deleteFolderIfExists(originPath);
                Files.createDirectories(originPath);
                try (InputStream is = baseClassLoader.getResourceAsStream(ORIGINAL_APK_ASSET_PATH)) {
                    Files.copy(is, cacheApkPath);
                }
            }
            cacheApkPath.toFile().setWritable(false);

            var mPackages = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mPackages");
            mPackages.remove(appInfo.packageName);
            appLoadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);
            XposedHelpers.setObjectField(mBoundApplication, "info", appLoadedApk);
            // The class loader is deliberately NOT built here. Building it runs the app's
            // AppComponentFactory <clinit>, which a packed app can turn into a native anti-tamper gate;
            // it must not run until the LoadedApk hooks, modules and signature bypass are armed. onLoad
            // arms them and then calls realizeLoadedApk().
            Log.i(TAG, "hooked app initialized: " + appLoadedApk);

            var context = (Context) XposedHelpers.callStaticMethod(Class.forName("android.app.ContextImpl"), "createAppContext", activityThread, stubLoadedApk);
            if (config.has("appComponentFactory")) {
                try {
                    context.getClassLoader().loadClass(appInfo.appComponentFactory);
                } catch (ClassNotFoundException e) { // This will happen on some strange shells like 360
                    Log.w(TAG, "Original AppComponentFactory not found: " + appInfo.appComponentFactory);
                    appInfo.appComponentFactory = null;
                }
            }
            return context;
        } catch (Throwable e) {
            Log.e(TAG, "createLoadedApk", e);
            return null;
        }
    }

    /**
     * Builds the target app's class loader, now that the LoadedApk hooks, modules and signature bypass
     * are armed. This is the point where the app's {@code AppComponentFactory} is instantiated and its
     * {@code <clinit>} runs; the {@code createAppFactory} hook fires {@code onPackageLoaded} immediately
     * before that, and the {@code createOrUpdateClassLoaderLocked} hook fires {@code onPackageReady} and
     * the legacy {@code handleLoadPackage} on return. It then repoints any ActivityClientRecord still
     * holding the stub LoadedApk at the real one.
     */
    private static void realizeLoadedApk() {
        appLoadedApk.getClassLoader();

        var activityClientRecordClass = XposedHelpers.findClass("android.app.ActivityThread$ActivityClientRecord", ActivityThread.class.getClassLoader());
        var fixActivityClientRecord = (BiConsumer<Object, Object>) (k, v) -> {
            if (activityClientRecordClass.isInstance(v)) {
                var pkgInfo = XposedHelpers.getObjectField(v, "packageInfo");
                if (pkgInfo == stubLoadedApk) {
                    Log.d(TAG, "fix loadedapk from ActivityClientRecord");
                    XposedHelpers.setObjectField(v, "packageInfo", appLoadedApk);
                }
            }
        };
        var mActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mActivities");
        mActivities.forEach(fixActivityClientRecord);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                var mLaunchingActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mLaunchingActivities");
                mLaunchingActivities.forEach(fixActivityClientRecord);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void disableProfile(Context context) {
        final ArrayList<String> codePaths = new ArrayList<>();
        var appInfo = context.getApplicationInfo();
        var pkgName = context.getPackageName();
        if (appInfo == null) return;
        if ((appInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            codePaths.add(appInfo.sourceDir);
        }
        if (appInfo.splitSourceDirs != null) {
            Collections.addAll(codePaths, appInfo.splitSourceDirs);
        }

        if (codePaths.isEmpty()) {
            // If there are no code paths there's no need to setup a profile file and register with
            // the runtime,
            return;
        }

        // AOSP's Environment.getDataProfilesDePackageDirectory(userId, pkg); the HiddenApiBridge no
        // longer exposes it, and the path has been stable for many releases.
        var profileDir = new File("/data/misc/profiles/cur/" + (appInfo.uid / PER_USER_RANGE) + "/" + pkgName);

        var attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------"));

        for (int i = codePaths.size() - 1; i >= 0; i--) {
            String splitName = i == 0 ? null : appInfo.splitNames[i - 1];
            File curProfileFile = new File(profileDir, splitName == null ? "primary.prof" : splitName + ".split.prof").getAbsoluteFile();
            Log.d(TAG, "Processing " + curProfileFile.getAbsolutePath());
            try {
                if (!curProfileFile.exists()) {
                    Files.createFile(curProfileFile.toPath(), attrs);
                    continue;
                }
                if (!curProfileFile.canWrite() && Files.size(curProfileFile.toPath()) == 0) {
                    Log.d(TAG, "Skip profile " + curProfileFile.getAbsolutePath());
                    continue;
                }
                if (curProfileFile.exists() && !curProfileFile.delete()) {
                    try (var writer = new FileOutputStream(curProfileFile)) {
                        Log.d(TAG, "Failed to delete, try to clear content " + curProfileFile.getAbsolutePath());
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to delete and clear profile file " + curProfileFile.getAbsolutePath(), e);
                    }
                    Os.chmod(curProfileFile.getAbsolutePath(), 00400);
                }
            } catch (Throwable e) {
                Log.e(TAG, "Failed to disable profile file " + curProfileFile.getAbsolutePath(), e);
            }
        }
    }

    private static void switchAllClassLoader() {
        var fields = LoadedApk.class.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType() == ClassLoader.class) {
                var obj = XposedHelpers.getObjectField(appLoadedApk, field.getName());
                XposedHelpers.setObjectField(stubLoadedApk, field.getName(), obj);
            }
        }
    }
}
