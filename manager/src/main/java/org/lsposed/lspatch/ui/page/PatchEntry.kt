package org.lsposed.lspatch.ui.page

import java.util.UUID
import org.lsposed.lspatch.data.model.ModuleRef
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.PatchOrigin
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.data.model.mode
import org.lsposed.lspatch.data.repository.PatchInputs
import org.lsposed.lspatch.data.repository.PatchRequestStore
import org.lsposed.lspatch.ui.navigation.NewPatch
import org.lsposed.lspatch.ui.navigation.SelectPatchTarget
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import org.matrix.vector.ui.navigation.Navigator

/**
 * The one way into patching.
 *
 * Home and Manage both call this, and every path onwards -- an installed app, apks from storage, a re-patch, a loader
 * update, an integrated-mode module edit -- converges on one persisted [PatchRequest] and one screen. The two entry
 * points used to differ: Manage demanded a storage folder first and Home did not, which is why patching from Home
 * failed every time.
 */
fun startNewPatch(navigator: Navigator) {
    navigator.go(SelectPatchTarget)
}

/** Persists [request] and opens the patch screen on it. */
suspend fun beginPatch(request: PatchRequest, navigator: Navigator) {
    val token = PatchRequestStore.put(request)
    navigator.go(NewPatch(token = token))
}

/** A fresh patch of an installed app, at the defaults. */
fun newRequestFor(app: AppInfo): PatchRequest =
    PatchRequest(
        token = UUID.randomUUID().toString(),
        target =
            PatchTarget.InstalledApp(
                packageName = app.app.packageName,
                label = app.label,
                apkPaths = listOf(app.app.sourceDir) + (app.app.splitSourceDirs ?: emptyArray()),
            ),
        mode = PatchMode.Local,
        origin = PatchOrigin.New,
    )

/**
 * Rebuilds an already-patched app from the originals stored inside it.
 *
 * The settings it was built with come back with it, so a re-patch is a change to one thing rather than a form filled in
 * again from scratch -- and [modules], when given, replaces the embedded set, which is how an Integrated app's module
 * list is edited at all.
 */
suspend fun rePatchRequestFor(
    app: AppInfo,
    mode: PatchMode? = null,
    modules: List<ModuleRef>? = null,
    origin: PatchOrigin = PatchOrigin.RePatch,
): PatchRequest {
    val recovered = PatchInputs.fromInstalledPatchedApp(app)
    val config = recovered.config
    val embedded =
        recovered.embeddedModules.mapNotNull { binding ->
            binding.apkPath?.let { ModuleRef(binding.packageName, it, binding.origin) }
        }
    return PatchRequest(
        token = UUID.randomUUID().toString(),
        target =
            PatchTarget.RecoveredOrigin(
                packageName = app.app.packageName,
                label = app.label,
                apkPaths = recovered.originApks.map { it.absolutePath },
            ),
        mode = mode ?: config?.mode ?: PatchMode.Local,
        debuggable = config?.debuggable ?: false,
        versionCodeOverride = config?.versionCode,
        // Recovered from the recorded config, not the original apks -- those never carried them.
        addedPermissions = config?.addedPermissions?.toList() ?: emptyList(),
        injectDocumentsProvider = config?.injectDocumentsProvider ?: false,
        gmsReplacementPackage = config?.gmsReplacementPackage,
        keepPushWithGms = config?.keepPushWithGms ?: false,
        declareFakeSignature = config?.declareFakeSignature ?: false,
        sigBypassLevel = config?.sigBypassLevel ?: 2,
        injectDex = config?.injectDex ?: false,
        modules = modules ?: embedded,
        origin = origin,
    )
}
