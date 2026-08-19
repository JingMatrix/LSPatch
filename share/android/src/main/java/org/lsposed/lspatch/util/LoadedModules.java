package org.lsposed.lspatch.util;

import android.content.pm.ApplicationInfo;
import android.util.Log;
import org.matrix.vector.ipc.IModuleService;
import org.matrix.vector.ipc.LoadedModule;

/**
 * Builds the {@link LoadedModule} a framework hands an injected process, out of a module APK on disk.
 *
 * <p>Every mode reaches a module the same way in the end -- read the APK with {@link ModuleLoader},
 * describe who the module is, attach the service its remote calls travel over -- and only the three
 * inputs differ: an embedded module is extracted from the host's own assets and has no installed
 * identity, a manager-served module is an installed app PackageManager can describe, and a module
 * restored from a host's snapshot is an installed app described by what the snapshot recorded. This
 * is that one shared step, so the three callers differ in what they pass rather than in what they
 * build.</p>
 */
public final class LoadedModules {

    private static final String TAG = "LSPatch";

    private LoadedModules() {}

    /**
     * Describes a module that {@link android.content.pm.PackageManager} cannot.
     *
     * <p>An embedded module is not installed as an app at all, and a process restoring its modules
     * from a snapshot may not be allowed to see the module's package (package visibility filters a
     * patched app's query for anything it does not declare). The framework only reads the package
     * name, the APK location and the native library directory off this, so carrying those is enough.</p>
     */
    public static ApplicationInfo syntheticApplicationInfo(
            String packageName, String apkPath, String nativeLibraryDir) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        info.sourceDir = apkPath;
        info.publicSourceDir = apkPath;
        info.nativeLibraryDir = nativeLibraryDir;
        return info;
    }

    /**
     * Reads {@code apkPath} into a module ready to be served, or null when it is not loadable -- or
     * not of the requested kind.
     *
     * @param requireLegacy null to accept the module whatever it is; true or false to accept only a
     *                      legacy or only a modern one. A module of the other kind has the dexes just
     *                      mapped for it closed here rather than left to a finalizer, because the two
     *                      kinds are served by separate calls and the caller of one never sees the
     *                      other's memory.
     */
    public static LoadedModule fromApk(
            String packageName,
            String apkPath,
            int appId,
            long versionCode,
            ApplicationInfo applicationInfo,
            Boolean requireLegacy,
            IModuleService service) {
        var code = ModuleLoader.loadModule(apkPath);
        if (code == null) {
            Log.w(TAG, "Failed to load module " + packageName + " from " + apkPath);
            return null;
        }
        if (requireLegacy != null && code.legacy != requireLegacy) {
            code.preLoadedDexes.forEach(dex -> {
                try {
                    dex.close();
                } catch (Throwable ignored) {
                }
            });
            return null;
        }
        var module = new LoadedModule();
        module.packageName = packageName;
        module.apkPath = apkPath;
        module.appId = appId;
        module.versionCode = versionCode;
        module.code = code;
        module.applicationInfo =
                applicationInfo != null ? applicationInfo : syntheticApplicationInfo(packageName, apkPath, null);
        module.service = service;
        return module;
    }

    /** Releases the shared memory of a module nobody is going to load. */
    public static void discard(LoadedModule module) {
        if (module == null || module.code == null || module.code.preLoadedDexes == null) return;
        for (var dex : module.code.preLoadedDexes) {
            try {
                dex.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
