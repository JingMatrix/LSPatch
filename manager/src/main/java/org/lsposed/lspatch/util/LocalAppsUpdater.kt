package org.lsposed.lspatch.util

import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.config.Configs
import org.lsposed.lspatch.config.MyKeyStore
import org.lsposed.lspatch.data.repository.PatchInputs
import org.lsposed.lspatch.data.repository.PatchOutputStore
import org.lsposed.lspatch.share.PatchConfig
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import org.lsposed.patch.ApkPatcher
import org.lsposed.patch.KeystoreSpec
import org.lsposed.patch.ManifestOverrides
import org.lsposed.patch.PatchSpec
import org.lsposed.patch.util.Logger

/**
 * Retargets every installed manager-mode app at a (possibly renamed) manager package.
 *
 * A manager-mode app binds its manager by package name, so reinstalling the manager under a new id
 * would orphan every app patched against the old one. Each patched app carries the original it was
 * built from, so it is recovered and patched again with nothing changed but the recorded manager
 * package -- the same recovery a re-patch uses ([PatchInputs]), and the same engine ([ApkPatcher]).
 *
 * Best-effort per app: one app that cannot be recovered or reinstalled is reported and skipped, not
 * allowed to abort the rest -- a half-retargeted set is the situation this exists to get out of, not
 * one to leave behind on the first error.
 */
object LocalAppsUpdater {

    private const val TAG = "LocalAppsUpdater"

    /** Which packages were retargeted and which failed, with a reason, so a partial run is legible. */
    data class Result(val updated: List<String>, val failed: List<Pair<String, String>>)

    private val logger = object : Logger() {
        override fun d(msg: String) {
            if (verbose) Log.d(TAG, msg)
        }

        override fun i(msg: String) {
            Log.i(TAG, msg)
        }

        override fun e(msg: String) {
            Log.e(TAG, msg)
        }
    }

    /** Installed manager-mode apps -- the only ones that bind a manager package at runtime. */
    fun managerModeApps(): List<AppInfo> =
        LSPPackageManager.appList.filter { PatchInputs.configOf(it)?.useManager == true }

    /**
     * Re-patches every manager-mode app so its config binds [managerPackageName].
     *
     * @return the packages updated and the ones that failed, so the caller can surface a partial
     *         outcome rather than a bare success or a single aborting throw.
     */
    suspend fun updateAllForManager(managerPackageName: String): Result = withContext(Dispatchers.IO) {
        val apps = managerModeApps()
        Log.i(TAG, "Retargeting ${apps.size} manager-mode app(s) at $managerPackageName")
        val updated = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (app in apps) {
            val pkg = app.app.packageName
            runCatching { updateOne(app, managerPackageName) }
                .onSuccess { updated.add(pkg) }
                .onFailure { e ->
                    Log.e(TAG, "Failed to retarget $pkg", e)
                    failed.add(pkg to (e.message ?: e.javaClass.simpleName))
                }
        }
        Result(updated, failed)
    }

    private suspend fun updateOne(app: AppInfo, managerPackageName: String) {
        val pkg = app.app.packageName
        Log.i(TAG, "Retarget loader for $pkg")
        val config = PatchInputs.configOf(app)
            ?: throw IllegalStateException("$pkg carries no patch config")
        try {
            val recovered = PatchInputs.fromInstalledPatchedApp(app)
            if (recovered.originApks.isEmpty()) {
                throw IllegalStateException("no original apk recovered for $pkg")
            }
            val spec = specFor(pkg, recovered.originApks, config, managerPackageName)
            val produced = ApkPatcher(logger, spec).patch()
            if (produced.isEmpty()) throw java.io.IOException("patcher produced no apk for $pkg")
            val (status, message) = LSPPackageManager.installFiles(produced, ShizukuApi.isPermissionGranted)
            if (status != PackageInstaller.STATUS_SUCCESS) {
                throw java.io.IOException("install failed: $message")
            }
        } finally {
            PatchInputs.discard(pkg)
        }
    }

    /**
     * Rebuilds the spec the app was patched with, changed only in the manager it binds.
     *
     * Restores what the runtime config records -- version code, added permissions, dex injection,
     * documents provider -- but not the patch-time-only manifest overrides (label, target sdk),
     * which a re-patch cannot recover for any installed app; this matches the manager's own
     * loader-update path.
     */
    private suspend fun specFor(
        packageName: String,
        originApks: List<java.io.File>,
        config: PatchConfig,
        managerPackageName: String,
    ): PatchSpec =
        PatchSpec.builder()
            .apks(originApks)
            .outputDir(PatchOutputStore.prepare(packageName))
            .useManager(true)
            .debuggable(config.debuggable)
            .sigBypassLevel(config.sigBypassLevel)
            .injectDex(config.injectDex)
            .forceOverwrite(true)
            .verbose(Configs.detailPatchLogs)
            .managerPackageName(managerPackageName)
            .manifestOverrides(
                ManifestOverrides.builder()
                    .versionCode(config.versionCode)
                    .permissions(config.addedPermissions?.toList() ?: emptyList())
                    .injectDocumentsProvider(config.injectDocumentsProvider)
                    .build()
            )
            .keystore(
                if (MyKeyStore.useDefault) KeystoreSpec.builtIn()
                else KeystoreSpec.of(
                    MyKeyStore.file,
                    Configs.keyStorePassword,
                    Configs.keyStoreAlias,
                    Configs.keyStoreAliasPassword,
                )
            )
            .build()
}
