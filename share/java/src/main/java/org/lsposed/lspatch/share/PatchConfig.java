package org.lsposed.lspatch.share;

public class PatchConfig {

    public final boolean useManager;
    public final boolean debuggable;

    /** The chosen {@code android:versionCode}, or null when the app kept its own. */
    public final Integer versionCode;
    public final int sigBypassLevel;
    public final String originalSignature;
    public final String appComponentFactory;
    /**
     * Whether the loader dex was injected straight into the host package rather than kept as an
     * asset. Recorded so a re-patch driven from an installed app can reproduce the choice; older
     * patched apps have no such key, and Gson leaves the primitive {@code false} for them -- which
     * is exactly what those apps were built with.
     */
    public final boolean injectDex;

    /**
     * The {@code <uses-permission>} names the patch added on top of the app's own, or null for an
     * apk patched before this was recorded. Kept here, unlike the other manifest overrides, because
     * a re-patch recovers the *original* apks -- which never had these -- so without this record a
     * loader update would silently drop the permissions a module depends on.
     */
    public final String[] addedPermissions;

    /**
     * Whether a {@code DocumentsProvider} exposing the app's private data was injected. Recorded for
     * the same reason as {@link #addedPermissions}: a re-patch recovers the original apks, which
     * never carried it, so without this the option would silently turn itself off on a loader update.
     */
    public final boolean injectDocumentsProvider;

    /**
     * The replacement the app's Google Play services traffic is redirected to, or null when it is not.
     *
     * A package name rather than a flag: the metadata key the patcher writes and the C2DM names it renames are
     * both derived from it, so the build the user chose has to survive into the apk. Read by the loader, and
     * recorded for the same reason as {@link #addedPermissions}: a re-patch would otherwise silently drop it.
     */
    public final String gmsReplacementPackage;

    /**
     * Whether pushes stay with Google Play services while everything else goes to {@link #gmsReplacementPackage}.
     * Read by the loader, and recorded for the same reason as {@link #addedPermissions}.
     */
    public final boolean keepPushWithGms;

    /** Whether {@code fake-signature} was declared. Recorded for the same reason as {@link #addedPermissions}. */
    public final boolean declareFakeSignature;

    /**
     * The manager package a manager-mode app should bind to for module loading, or null for an
     * apk patched before this was recorded (and for integrated apps, which never bind a manager).
     * Recorded so the manager can be reinstalled under a different package name without orphaning
     * already-patched apps: a loader update rewrites this field, and older apks fall back to the
     * built-in {@link Constants#MANAGER_PACKAGE_NAME} via {@link #resolveManagerPackageName()}.
     */
    public final String managerPackageName;
    public final LSPConfig lspConfig;

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            Integer versionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex,
            String[] addedPermissions,
            boolean injectDocumentsProvider,
            String gmsReplacementPackage,
            boolean keepPushWithGms,
            boolean declareFakeSignature,
            String managerPackageName
    ) {
        this.useManager = useManager;
        this.debuggable = debuggable;
        this.versionCode = versionCode;
        this.sigBypassLevel = sigBypassLevel;
        this.originalSignature = originalSignature;
        this.appComponentFactory = appComponentFactory;
        this.injectDex = injectDex;
        this.addedPermissions = addedPermissions;
        this.injectDocumentsProvider = injectDocumentsProvider;
        this.gmsReplacementPackage = (gmsReplacementPackage != null && !gmsReplacementPackage.isEmpty())
                ? gmsReplacementPackage
                : null;
        this.keepPushWithGms = keepPushWithGms;
        this.declareFakeSignature = declareFakeSignature;
        if (useManager) {
            this.managerPackageName = (managerPackageName != null && !managerPackageName.isEmpty())
                    ? managerPackageName
                    : Constants.MANAGER_PACKAGE_NAME;
        } else {
            this.managerPackageName = null;
        }
        this.lspConfig = LSPConfig.instance;
    }

    /** Resolved manager package for a manager-mode app; falls back for configs created before this field existed. */
    public String resolveManagerPackageName() {
        if (managerPackageName != null && !managerPackageName.isEmpty()) {
            return managerPackageName;
        }
        return Constants.MANAGER_PACKAGE_NAME;
    }
}
