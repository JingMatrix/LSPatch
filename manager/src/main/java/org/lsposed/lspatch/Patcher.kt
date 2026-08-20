package org.lsposed.lspatch

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.config.Configs
import org.lsposed.lspatch.config.MyKeyStore
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.repository.PatchOutputStore
import org.lsposed.patch.ApkPatcher
import org.lsposed.patch.KeystoreSpec
import org.lsposed.patch.ManifestOverrides
import org.lsposed.patch.PatchSpec
import org.lsposed.patch.util.Logger

object Patcher {

    /**
     * Translates a [PatchRequest] into the patcher's own spec.
     *
     * Built directly rather than rendered into command-line flags for the patcher to parse back: every value here is
     * already typed, and the round trip through argv was only ever an artefact of the engine and the CLI having been
     * the same class.
     */
    private fun PatchRequest.toSpec(outputDir: File): PatchSpec =
        PatchSpec.builder()
            .apks(target.apkPaths.map(::File))
            .outputDir(outputDir)
            .useManager(mode.useManager)
            .debuggable(debuggable)
            .sigBypassLevel(sigBypassLevel)
            .injectDex(injectDex)
            // The output directory is cleared before every run, so anything still there is a
            // leftover rather than something worth protecting.
            .forceOverwrite(true)
            .verbose(Configs.detailPatchLogs)
            .modules(effectiveModules.map { File(it.apkPath) })
            // Record the manager's own current package so a manager-mode app keeps reaching it after
            // the manager is reinstalled under a cloaked package name; integrated apps bind nothing.
            .managerPackageName(if (mode.useManager) lspApp.packageName else null)
            .manifestOverrides(
                ManifestOverrides.builder()
                    .versionCode(versionCodeOverride)
                    .label(labelOverride)
                    .targetSdkVersion(targetSdkOverride)
                    .extractNativeLibs(if (extractNativeLibs) true else null)
                    .usesCleartextTraffic(if (usesCleartextTraffic) true else null)
                    .permissions(addedPermissions)
                    .injectDocumentsProvider(injectDocumentsProvider)
                    .build()
            )
            .keystore(
                if (MyKeyStore.useDefault) KeystoreSpec.builtIn()
                else
                    KeystoreSpec.of(
                        MyKeyStore.file,
                        Configs.keyStorePassword,
                        Configs.keyStoreAlias,
                        Configs.keyStoreAliasPassword,
                    )
            )
            .build()

    /**
     * Runs [request] and returns the apks it produced.
     *
     * The result stays where it was written -- app-private, one directory per package. It used to be copied on to a
     * folder the user had picked through the storage access framework, which meant every patch depended on a persisted
     * grant; the entry point that never asked for one therefore failed at this exact point, every time.
     */
    suspend fun patch(logger: Logger, request: PatchRequest): List<File> =
        withContext(Dispatchers.IO) {
            // Checked here rather than left to the engine: by this point the paths are the ones the
            // system reports for the target now, so their absence is a fact about the app and can be
            // said as one, instead of surfacing as the engine's own exception type and message.
            val missing = request.target.apkPaths.filterNot { File(it).exists() }
            if (missing.isNotEmpty()) {
                throw java.io.IOException(
                    "${request.label} has no apk at ${missing.first()}" +
                        if (missing.size > 1) " (and ${missing.size - 1} more)" else ""
                )
            }
            val outputDir = PatchOutputStore.prepare(request.packageName)
            val produced = ApkPatcher(logger, request.toSpec(outputDir)).patch()
            if (produced.isEmpty()) throw java.io.IOException("The patcher produced no apk")
            produced
        }
}
