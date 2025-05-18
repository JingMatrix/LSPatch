package org.lsposed.lspatch.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lsposed.lspatch.util.ModuleLoader;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FixedLocalApplicationService extends ILSPApplicationService.Stub {

    private static final String TAG = "LSPatch";
    private static final String PREFS_NAME = "lspatch";
    private static final String KEY_MODULES = "modules";

    private final List<Module> modules = new ArrayList<>();

    public FixedLocalApplicationService(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String moduleString = prefs.getString(KEY_MODULES, "[]");
        Log.i(TAG, "Using fixed local application service. Modules data: " + moduleString);

        try {
            JSONArray modulesArray = new JSONArray(moduleString);
            PackageManager pm = context.getPackageManager();
            for (int i = 0; i < modulesArray.length(); i++) {
                JSONObject moduleObject = modulesArray.getJSONObject(i);
                String packageName = moduleObject.getString("packageName");
                String apkPath = moduleObject.getString("apkPath");
                File apkFile = new File(apkPath);

                if (!apkFile.exists()) {
                    Log.w(TAG, "Module APK not found at cached path: " + apkPath + " for package: " + packageName);
                    try {
                        ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                        apkPath = info.sourceDir;
                        apkFile = new File(apkPath);
                        if (apkFile.exists()) {
                            Log.i(TAG, "Found module APK via PackageManager: " + apkPath);
                        } else {
                            Log.e(TAG, "Module APK still not found via PackageManager for: " + packageName);
                            continue; // Skip this module if APK is not found
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "Module package not found: " + packageName, e);
                        continue; // Skip this module
                    }
                }

                Module module = new Module();
                module.apkPath = apkPath;
                module.packageName = packageName;
                try {
                    module.file = ModuleLoader.loadModule(apkPath);
                    if (module.file != null) {
                        modules.add(module);
                        Log.i(TAG, "Successfully loaded module: " + packageName + " from " + apkPath);
                    } else {
                        Log.e(TAG, "Failed to load module file for: " + packageName);
                    }
                } catch (Exception loadException) {
                    Log.e(TAG, "Error loading module file for: " + packageName + " from " + apkPath, loadException);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing modules JSON", e);
        } catch (Exception e) { // Catch unexpected errors during initialization
            Log.e(TAG, "Unexpected error initializing FixedLocalApplicationService", e);
        }
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        // Consider making this configurable if needed
        return false;
    }

    @Override
    public List<Module> getLegacyModulesList() {
        // Return an immutable list or a copy to prevent external modification
        return Collections.unmodifiableList(modules);
    }

    @Override
    public List<Module> getModulesList() {
        // Currently returns an empty list, behavior maintained
        return new ArrayList<>();
    }

    @Override
    public String getPrefsPath(String packageName) {
        // Consider validating packageName or handling potential exceptions
        return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/").getAbsolutePath();
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) {
        // Currently returns null, behavior maintained
        return null;
    }

    @Override
    public IBinder asBinder() {
        return this;
    }
}