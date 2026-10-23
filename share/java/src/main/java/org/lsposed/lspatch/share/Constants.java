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
     * What a Google Play services replacement is called.
     *
     * Every such build is a fork of the same service implementation and keeps the trailing segment, whether it
     * renamed the rest of itself or not. Requiring it is not ceremony: the redirect sends an app's Google
     * traffic wherever it points, so accepting any package at all would let that traffic be aimed at something
     * that is not a replacement.
     */
    final static public String GMS_REPLACEMENT_SUFFIX = ".gms";

    /**
     * The shape of a replacement that renamed its own namespace, and so its C2DM names with it.
     *
     * Not every one does. A build renamed to {@code <vendor>.android.gms} renames its permissions and push
     * names so it can be installed beside Google's own; one that keeps Google's names for those cannot be, and
     * is meant for a device with no Google Play services to clash with. Only the second question is answered
     * from the name.
     */
    final static public String GMS_VENDOR_NAMESPACE_SUFFIX = ".android.gms";

    /**
     * The C2DM namespace every Google Play services client is built against.
     *
     * Shared because the patcher and the loader have to agree on it exactly: the patcher renames these names in
     * the app's manifest, and the loader rewrites the same names on the intents the app sends, so a rule that
     * drifted between the two would leave pushes addressed to a name nothing declares.
     */
    final static public String GOOGLE_C2DM_PREFIX = "com.google.android.c2dm.";

    /** The metadata key a replacement reads the calling app's certificate from, under its own package. */
    final static public String GMS_REPLACEMENT_SIGNATURE_METADATA_SUFFIX = ".SPOOFED_PACKAGE_SIGNATURE";

    /** Whether [packageName] can be redirected to: a replacement's own package, not Google's. */
    public static boolean isGmsReplacement(String packageName) {
        return packageName != null
                && packageName.endsWith(GMS_REPLACEMENT_SUFFIX)
                && packageName.length() > GMS_REPLACEMENT_SUFFIX.length()
                && !REAL_GMS_PACKAGE_NAME.equals(packageName);
    }

    /**
     * Whether [packageName] renamed Google's namespaces along with its own package.
     *
     * Such a build renames its permissions, its provider authorities and its C2DM names so that it can be
     * installed beside Google's own. A build that keeps Google's names for those cannot be, and serves them
     * under Google's names -- so rewriting them for it would address names nothing serves.
     */
    public static boolean renamesGoogleNamespaces(String packageName) {
        return isGmsReplacement(packageName) && packageName.endsWith(GMS_VENDOR_NAMESPACE_SUFFIX);
    }

    /** The vendor prefix of a replacement that renamed its namespaces, or null when it kept Google's. */
    public static String gmsReplacementVendor(String packageName) {
        if (!renamesGoogleNamespaces(packageName)) return null;
        return packageName.substring(0, packageName.length() - GMS_VENDOR_NAMESPACE_SUFFIX.length());
    }

    /** The C2DM namespace of a replacement that renamed its own, or null when it kept Google's. */
    public static String gmsReplacementC2dmPrefix(String packageName) {
        String vendor = gmsReplacementVendor(packageName);
        return vendor == null ? null : vendor + ".android.c2dm.";
    }

    final static public int SIGBYPASS_LV_DISABLE = 0;
    final static public int SIGBYPASS_LV_PM = 1;
    final static public int SIGBYPASS_LV_PM_OPENAT = 2;
    // Level 3 adds raw-syscall coverage: a packer that reads its own apk with an inline `svc`
    // (to slip past the libc __openat redirect of level 2) has those svc sites instrumented so the
    // read is still pointed at the stored original. See patch-loader bypass_sig.cpp.
    final static public int SIGBYPASS_LV_PM_OPENAT_SVC = 3;
    final static public int SIGBYPASS_LV_MAX = 4;
}
