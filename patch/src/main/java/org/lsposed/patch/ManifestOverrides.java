package org.lsposed.patch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Optional overrides applied to the patched app's manifest.
 *
 * The patcher already rewrites a handful of manifest attributes as a side effect of injecting the
 * loader (the component factory, the debuggable flag, a minimum SDK floor). This groups the ones a
 * caller may *choose* to change, so the manifest surface is one typed object rather than a growing
 * list of loose parameters -- adding another overridable attribute is a field here and a line in
 * {@link ApkPatcher}, nothing more.
 *
 * Every scalar field is nullable and means "leave as the original app had it" when null, so the
 * empty overrides are a no-op and an unpatched attribute is never touched. {@link #addedPermissions}
 * follows the same rule with an empty list.
 */
public final class ManifestOverrides {

    /**
     * A new {@code android:versionCode}, or null to keep the app's own.
     *
     * This is the number the installer orders versions by, distinct from the version name the user
     * sees. Pinning it low -- 1 is the usual choice -- keeps a later patched build from being refused
     * as a downgrade, since the installer rejects a version code below the one already installed; any
     * value is accepted. The app itself normally still reports its real version, which is compiled
     * into its code rather than read from this attribute.
     */
    public final Integer versionCode;

    /** A new {@code android:label} for the app -- what the launcher and settings show it as. */
    public final String label;

    /** A new {@code android:targetSdkVersion}, or null to keep the app's own. */
    public final Integer targetSdkVersion;

    /**
     * Force {@code android:extractNativeLibs}. An app that ships it {@code false} keeps its native
     * libraries page-aligned inside the apk; forcing {@code true} makes the installer extract them,
     * which some patched apps need to load a module's own native code. Null leaves it alone.
     */
    public final Boolean extractNativeLibs;

    /**
     * Force {@code android:usesCleartextTraffic}. Turning it on lets a debugging or
     * traffic-inspecting module reach plain-HTTP endpoints an app would otherwise refuse. Null
     * leaves it alone.
     */
    public final Boolean usesCleartextTraffic;

    /**
     * Extra {@code <uses-permission>} entries to declare, so a module can use a permission the app
     * itself never asked for -- {@code INTERNET} being the usual one. Never null; empty adds nothing.
     * A name the app already declares is a no-op (the manifest editor skips duplicates), so this is
     * always additive and safe to hand the app's own permissions.
     */
    public final List<String> addedPermissions;

    /**
     * Whether to inject a {@code DocumentsProvider} that exposes the app's private data directory
     * through the Storage Access Framework, so an external file manager can browse it without root.
     * The provider class ships in the loader; this only decides whether the {@code <provider>} is
     * declared. Off by default -- it widens the app's data-isolation surface.
     */
    public final boolean injectDocumentsProvider;

    /**
     * Whether to redirect the app's Google Play services traffic to an installed MicroG RE (ReVanced GmsCore), so
     * Google sign-in and push keep working for an app re-signed with LSPatch's key. The redirect ships in the loader;
     * this declares the original certificate in the {@code SPOOFED_PACKAGE_SIGNATURE} metadata MicroG RE reads,
     * renames the app's C2DM actions and permissions to MicroG RE's, and lets the app see MicroG RE. Specific to
     * MicroG RE: upstream microG installs as Google Play services itself and needs none of this.
     */
    public final boolean useMicroGRe;

    /**
     * Whether to declare the original certificate as {@code fake-signature}, with {@code FAKE_PACKAGE_SIGNATURE}, for a
     * ROM with signature spoofing to report in place of LSPatch's. Upstream microG has no metadata of its own for this,
     * so it is how that setup sees the original certificate. Only takes effect on a ROM that implements the spoofing.
     */
    public final boolean declareFakeSignature;

    private ManifestOverrides(Builder b) {
        this.versionCode = b.versionCode;
        this.label = b.label;
        this.targetSdkVersion = b.targetSdkVersion;
        this.extractNativeLibs = b.extractNativeLibs;
        this.usesCleartextTraffic = b.usesCleartextTraffic;
        this.addedPermissions = Collections.unmodifiableList(new ArrayList<>(b.addedPermissions));
        this.injectDocumentsProvider = b.injectDocumentsProvider;
        this.useMicroGRe = b.useMicroGRe;
        this.declareFakeSignature = b.declareFakeSignature;
    }

    /** True when nothing is overridden, so the patcher can skip the work entirely. */
    public boolean isEmpty() {
        return versionCode == null && label == null && targetSdkVersion == null
                && extractNativeLibs == null && usesCleartextTraffic == null
                && addedPermissions.isEmpty() && !injectDocumentsProvider && !useMicroGRe
                && !declareFakeSignature;
    }

    /**
     * Canonicalises a permission the way a user is likely to type it.
     *
     * A bare token ({@code "INTERNET"}, or lower-case {@code "internet"}) is the framework name
     * {@code android.permission.INTERNET} with its prefix and casing left off, so it is completed
     * and upper-cased. Anything already dotted -- a fully-qualified framework name, or a vendor
     * permission like {@code com.android.launcher.permission.INSTALL_SHORTCUT} -- is a real name as
     * written and only trimmed. Blank in, empty out.
     *
     * Shared so the CLI and the manager never drift into two spellings of the same permission.
     */
    public static String normalizePermission(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.indexOf('.') >= 0) return trimmed;
        return "android.permission." + trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    public static ManifestOverrides none() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Integer versionCode;
        private String label;
        private Integer targetSdkVersion;
        private Boolean extractNativeLibs;
        private Boolean usesCleartextTraffic;
        // A set behind an ordered facade: duplicates a caller passes are collapsed here, while the
        // order the user added them in is what the report and the re-patch see.
        private final LinkedHashSet<String> addedPermissions = new LinkedHashSet<>();
        private boolean injectDocumentsProvider;
        private boolean useMicroGRe;
        private boolean declareFakeSignature;

        public Builder versionCode(Integer versionCode) {
            this.versionCode = versionCode;
            return this;
        }

        public Builder label(String label) {
            this.label = (label == null || label.isBlank()) ? null : label;
            return this;
        }

        public Builder targetSdkVersion(Integer targetSdkVersion) {
            this.targetSdkVersion = targetSdkVersion;
            return this;
        }

        public Builder extractNativeLibs(Boolean extractNativeLibs) {
            this.extractNativeLibs = extractNativeLibs;
            return this;
        }

        public Builder usesCleartextTraffic(Boolean usesCleartextTraffic) {
            this.usesCleartextTraffic = usesCleartextTraffic;
            return this;
        }

        /** Adds one permission; blank names are ignored so a stray argument cannot empty a tag. */
        public Builder addPermission(String permission) {
            if (permission != null && !permission.isBlank()) {
                this.addedPermissions.add(permission.trim());
            }
            return this;
        }

        public Builder permissions(List<String> permissions) {
            if (permissions != null) {
                permissions.forEach(this::addPermission);
            }
            return this;
        }

        public Builder injectDocumentsProvider(boolean injectDocumentsProvider) {
            this.injectDocumentsProvider = injectDocumentsProvider;
            return this;
        }

        public Builder useMicroGRe(boolean useMicroGRe) {
            this.useMicroGRe = useMicroGRe;
            return this;
        }

        public Builder declareFakeSignature(boolean declareFakeSignature) {
            this.declareFakeSignature = declareFakeSignature;
            return this;
        }

        public ManifestOverrides build() {
            return new ManifestOverrides(this);
        }
    }
}
