package org.lsposed.lspd.nativebridge;

public class SigBypass {
    public static native void enableOpenatHook(String origApkPath, String cacheApkPath);

    public static native void addIORule(String origPath, String redirectPath);

    public static native void enableIO();

    public static native void hideXposed();

    public static native void initSeccomp();
}
