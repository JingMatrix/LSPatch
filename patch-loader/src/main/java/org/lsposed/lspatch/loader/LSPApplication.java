package org.lsposed.lspatch.loader;

import static org.lsposed.lspatch.share.Constants.CONFIG_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.lspatch.loader.util.FileUtils;
import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.service.FixedLocalApplicationService;
import org.lsposed.lspatch.service.LocalApplicationService;
import org.lsposed.lspatch.service.RemoteApplicationService;
import org.lsposed.lspd.core.Startup;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    public static void onLoad() throws IOException {
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
        ILSPApplicationService service = null;
        if (config.optBoolean("useManager")) {
            try {
                service = new RemoteApplicationService(context);
                List<Module> m = service.getLegacyModulesList();
                JSONArray modules = new JSONArray();
                for (Module module : m) {
                    JSONObject json = new JSONObject();
                    json.put("packageName", module.packageName);
                    json.put("apkPath", module.apkPath);
                    modules.put(json);
                }
                Log.i(TAG, "Modules fetched from manager: " + modules.toString());
                SharedPreferences prefs = context.getSharedPreferences("lspatch", Context.MODE_PRIVATE);
                prefs.edit().putString("modules", modules.toString()).apply();
                Log.i(TAG, "Modules saved to SharedPreferences");
            } catch (Exception e) { // Catch RemoteException or others during remote service interaction
                Log.e(TAG, "Failed to use RemoteApplicationService, fallback to fixed local service", e);
                // Fallback service is created below if service is still null
            }
        }

        if (service == null) {
            if (config.optBoolean("useManager")) {
                // If remote failed, use FixedLocalApplicationService
                service = new FixedLocalApplicationService(context);
                Log.i(TAG, "Using FixedLocalApplicationService as fallback.");
            } else {
                // If useManager was false, use LocalApplicationService
                service = new LocalApplicationService(context);
                Log.i(TAG, "Using LocalApplicationService.");
            }
        }

        disableProfile(context);
        Startup.initXposed(false, ActivityThread.currentProcessName(), context.getApplicationInfo().dataDir, service);
        Startup.bootstrapXposed();
        // WARN: Since it uses `XResource`, the following class should not be initialized
        // before forkPostCommon is invoke. Otherwise, you will get failure of XResources
        Log.i(TAG, "Load modules");
        LSPLoader.initModules(appLoadedApk);
        Log.i(TAG, "Modules initialized");

        switchAllClassLoader();
        SigBypass.doSigBypass(context, config.optInt("sigBypassLevel"));

        Log.i(TAG, "LSPatch bootstrap completed");
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

        var profileDir = HiddenApiBridge.Environment_getDataProfilesDePackageDirectory(appInfo.uid / PER_USER_RANGE, pkgName);

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