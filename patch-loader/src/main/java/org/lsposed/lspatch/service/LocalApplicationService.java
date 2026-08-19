package org.lsposed.lspatch.service;

import android.content.Context;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import io.github.libxposed.service.IXposedService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.lsposed.lspatch.loader.util.FileUtils;
import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspatch.util.LoadedModules;
import org.matrix.vector.ipc.IFrameworkService;
import org.matrix.vector.ipc.IProcessChannel;
import org.matrix.vector.ipc.LoadedModule;

/**
 * The {@link IFrameworkService} for embedded (no-manager) mode: it serves the modules the patcher
 * baked into the app's own assets under {@code lspatch/modules}.
 */
public class LocalApplicationService extends IFrameworkService.Stub {

    private static final String TAG = "LSPatch";

    private final List<LoadedModule> modules = new ArrayList<>();

    public LocalApplicationService(Context context) {
        String[] names;
        try {
            names = context.getAssets().list("lspatch/modules");
        } catch (IOException e) {
            Log.e(TAG, "Error when listing embedded modules", e);
            return;
        }
        if (names == null) return;
        for (var name : names) {
            // One malformed module must not stop the others from loading.
            try {
                String packageName = name.substring(0, name.length() - 4);
                String modulePath = context.getCacheDir() + "/lspatch/" + packageName + "/";
                String cacheApkPath;
                try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
                    cacheApkPath = modulePath
                            + sourceFile
                                    .getEntry(Constants.EMBEDDED_MODULES_ASSET_PATH + name)
                                    .getCrc() + ".apk";
                }

                if (!Files.exists(Paths.get(cacheApkPath))) {
                    Log.i(TAG, "Extract module apk: " + packageName);
                    FileUtils.deleteFolderIfExists(Paths.get(modulePath));
                    Files.createDirectories(Paths.get(modulePath));
                    try (var is = context.getAssets().open("lspatch/modules/" + name)) {
                        Files.copy(is, Paths.get(cacheApkPath));
                    }
                }

                // Not installed as an app, so PackageManager can describe neither its identity (appId,
                // version code) nor where it lives; the synthetic ApplicationInfo carries what the
                // framework actually reads.
                var module = LoadedModules.fromApk(
                        packageName,
                        cacheApkPath,
                        -1,
                        0,
                        LoadedModules.syntheticApplicationInfo(packageName, cacheApkPath, null),
                        null,
                        EmbeddedRemoteServices.get(context).moduleService(packageName, IXposedService.PROP_CAP_REMOTE));
                if (module == null) continue;
                modules.add(module);
            } catch (Throwable e) {
                Log.e(TAG, "Error loading embedded module " + name, e);
            }
        }
    }

    @Override
    public boolean isLogMuted() {
        return false;
    }

    @Override
    public List<LoadedModule> getLegacyModules() {
        return modules.stream().filter(m -> m.code.legacy).collect(Collectors.toList());
    }

    @Override
    public List<LoadedModule> getModules() {
        return modules.stream().filter(m -> !m.code.legacy).collect(Collectors.toList());
    }

    @Override
    public String getPrefsPath(String packageName) {
        return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/").getAbsolutePath();
    }

    @Override
    public ParcelFileDescriptor openManagerApk() {
        return null;
    }

    @Override
    public IBinder requestManagerService() {
        return null;
    }

    @Override
    public void attachProcessChannel(IProcessChannel channel) {
        // LSPatch has no daemon to drive hot reload; the channel is accepted and ignored.
    }
}
