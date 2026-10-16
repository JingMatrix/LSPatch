package org.lsposed.lspatch.share;

public class Constants {

    final static public String CONFIG_ASSET_PATH = "assets/lspatch/config.json";
    final static public String LOADER_DEX_ASSET_PATH = "assets/lspatch/loader.dex";
    final static public String META_LOADER_DEX_ASSET_PATH = "assets/lspatch/metaloader.dex";
    final static public String ORIGINAL_APK_ASSET_PATH = "assets/lspatch/origin.apk";
    final static public String EMBEDDED_MODULES_ASSET_PATH = "assets/lspatch/modules/";

    final static public String PATCH_FILE_SUFFIX = "-lspatched.apk";
    final static public String PROXY_APP_COMPONENT_FACTORY = "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub";
    /**
     * The {@code DocumentsProvider} baked into the loader, and the suffix its authority is built
     * from: {@code <packageName>.lspatch.documents}. Per-package so two patched apps never collide,
     * and shared here so the patcher writes the same authority the provider is registered under.
     */
    final static public String DOCUMENTS_PROVIDER_CLASS = "org.lsposed.lspatch.loader.LSPatchDocumentsProvider";
    final static public String DOCUMENTS_PROVIDER_AUTHORITY_SUFFIX = ".lspatch.documents";
    final static public String MANAGER_PACKAGE_NAME = "org.lsposed.lspatch";
    final static public String MANAGER_SERVICE_NAME = "org.lsposed.lspatch.manager.ModuleService";
    final static public String MIGRATE_ASSET_PATH = "assets/lspatch/migrate.zip";
    final static public int MIN_ROLLING_VERSION_CODE = 348;

    final static public String REAL_GMS_PACKAGE_NAME = "com.google.android.gms";
    /**
     * ReVanced GmsCore, the MicroG build that reads a calling app's certificate from the app's manifest metadata
     * {@code <this package>.SPOOFED_PACKAGE_SIGNATURE} rather than relying on a ROM's signature spoofing.
     */
    final static public String MICROG_RE_PACKAGE_NAME = "app.revanced.android.gms";

    final static public int SIGBYPASS_LV_DISABLE = 0;
    final static public int SIGBYPASS_LV_PM = 1;
    final static public int SIGBYPASS_LV_PM_OPENAT = 2;
    // Level 3 adds raw-syscall coverage: a packer that reads its own apk with an inline `svc`
    // (to slip past the libc __openat redirect of level 2) has those svc sites instrumented so the
    // read is still pointed at the stored original. See patch-loader bypass_sig.cpp.
    final static public int SIGBYPASS_LV_PM_OPENAT_SVC = 3;
    final static public int SIGBYPASS_LV_MAX = 4;
}
