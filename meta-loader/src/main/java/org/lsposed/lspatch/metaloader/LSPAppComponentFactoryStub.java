package org.lsposed.lspatch.metaloader;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.AppComponentFactory;
import android.util.Log;

import org.lsposed.lspatch.share.Constants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

@SuppressLint("UnsafeDynamicallyLoadedCode")
public class LSPAppComponentFactoryStub extends AppComponentFactory {

    private static final String TAG = "LSPatch-MetaLoader";
    private static final Map<String, String> archToLib = Map.of(
            "arm", "armeabi-v7a",
            "arm64", "arm64-v8a",
            "x86", "x86",
            "x86_64", "x86_64"
    );

    public static byte[] dex;

    static {
        final boolean appZygote = ActivityThread.currentActivityThread() == null;
        if (appZygote) {
            Log.i(TAG, "Skip loading liblspatch.so for appZygote");
        } else {
            bootstrap();
        }
    }

    private static void bootstrap() {
        try {
            var cl = Objects.requireNonNull(LSPAppComponentFactoryStub.class.getClassLoader());
            Class<?> VMRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = VMRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Method vmInstructionSet = VMRuntime.getDeclaredMethod("vmInstructionSet");
            vmInstructionSet.setAccessible(true);
            String arch = (String) vmInstructionSet.invoke(getRuntime.invoke(null));
            String libName = archToLib.get(arch);

            Log.i(TAG, "Bootstrap loader from embedment");
            try (var is = cl.getResourceAsStream(Constants.LOADER_DEX_ASSET_PATH);
                 var os = new ByteArrayOutputStream()) {
                transfer(is, os);
                dex = os.toByteArray();
            }
            String soPath = cl.getResource("assets/lspatch/so/" + libName + "/liblspatch.so").getPath().substring(5);

            System.load(soPath);
        } catch (Throwable e) {
            Log.e(TAG, "Error when loading liblspatch.so", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void transfer(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while (-1 != (n = is.read(buffer))) {
            os.write(buffer, 0, n);
        }
    }
}
