package org.lsposed.lspatch.data.model

/** What a patch is being asked to operate on. */
sealed interface PatchTarget {

    val packageName: String
    val label: String

    /** Every apk of the target, base first. */
    val apkPaths: List<String>

    /** An app already on the device, patched from its installed apks. */
    data class InstalledApp(
        override val packageName: String,
        override val label: String,
        override val apkPaths: List<String>,
    ) : PatchTarget

    /** Apks the user picked from storage, already copied into the temp dir. */
    data class ApkFiles(
        override val packageName: String,
        override val label: String,
        override val apkPaths: List<String>,
    ) : PatchTarget

    /**
     * The original apks recovered from inside an installed patched app, for a re-patch. Distinct
     * from [InstalledApp] because the paths point at extracted copies, not at anything the system
     * knows about, and because a re-patch installs over an app that is already there.
     */
    data class RecoveredOrigin(
        override val packageName: String,
        override val label: String,
        override val apkPaths: List<String>,
    ) : PatchTarget
}

/** Why this patch is being run -- which decides what the action is called and what happens after. */
enum class PatchOrigin {
    /** A brand-new patch of an app that is not patched yet. */
    New,

    /** Rebuilding an already-patched app, possibly with different settings or modules. */
    RePatch,

    /** Rebuilding purely to bring the embedded loader up to this manager's version. */
    UpdateLoader,
}

/** One module to embed, reduced to what the patcher actually needs. */
data class ModuleRef(
    val packageName: String,
    val apkPath: String,
    val origin: ModuleOrigin,
)

/**
 * Everything needed to run one patch -- the single input to every patch in the app.
 *
 * New patches, re-patches, loader updates and the integrated-mode module edit all produce one of
 * these and hand it to the same screen, so there is exactly one place where a patch is configured
 * and exactly one place where it runs. The alternative -- a screen that reconstructs its own inputs
 * from an integer "action" and whatever the previous screen happened to leave behind -- is how the
 * two entry points came to behave differently in the first place.
 *
 * Deliberately made of strings, ints and booleans only. It is persisted as JSON and addressed by
 * [token] rather than passed as a navigation argument, so the configure screen survives process
 * death, and so no `Parcelable` carrying a live `ApplicationInfo` is written into saved instance
 * state.
 */
data class PatchRequest(
    val token: String,
    val target: PatchTarget,
    val mode: PatchMode,
    val debuggable: Boolean = false,
    val sigBypassLevel: Int = 2,
    val injectDex: Boolean = false,
    val modules: List<ModuleRef> = emptyList(),
    // Manifest overrides -- null means "leave the app's own value". Patch-time only: they are baked
    // into the manifest, not recorded in the runtime config, so a re-patch starts them fresh. The
    // version code is the exception: the patched app records the one it was given, so a re-patch
    // offers it back rather than silently letting the app's own number return.
    val versionCodeOverride: Int? = null,
    val labelOverride: String? = null,
    val targetSdkOverride: Int? = null,
    val extractNativeLibs: Boolean = false,
    val usesCleartextTraffic: Boolean = false,
    // Extra uses-permission names, already canonical. Recorded in the patched app's config, unlike
    // the overrides above, so a re-patch that only recovers the original apks still keeps them.
    val addedPermissions: List<String> = emptyList(),
    // Inject a DocumentsProvider exposing the app's private data. Recorded in the config for the same
    // reason as the permissions above.
    val injectDocumentsProvider: Boolean = false,
    // Redirect Google Play services to an installed MicroG. Recorded in the config for the same reason.
    val useMicroG: Boolean = false,
    val origin: PatchOrigin = PatchOrigin.New,
) {
    val packageName: String get() = target.packageName
    val label: String get() = target.label

    /**
     * Modules are only ever embedded in Integrated mode; the patcher refuses `--embed` together
     * with `--manager` outright. Reading them through here means no caller has to remember to
     * clear the list -- and, crucially, means the list is *not* destroyed while the user is still
     * deciding which mode they want.
     */
    val effectiveModules: List<ModuleRef>
        get() = if (mode == PatchMode.Integrated) modules else emptyList()
}
