package org.lsposed.lspatch.data.repository

import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lsposed.lspatch.Patcher
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.data.model.LogLine
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchStage
import org.lsposed.lspatch.data.model.PatchStep
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi
import org.lsposed.lspatch.util.ShizukuOp
import org.lsposed.patch.util.Logger

/**
 * The one patch job in flight, and everything watching it.
 *
 * Hosted on the application scope rather than in the patch screen's ViewModel. A patch takes tens of seconds and cannot
 * be cancelled part-way without leaving a half-written apk, so tying it to a screen meant the screen had to refuse to
 * be left -- the old flow swallowed the back gesture outright while patching. Here, leaving is simply leaving: the job
 * carries on, the Manage screen can show that it is running, and coming back re-attaches to it.
 *
 * Patching and installing are deliberately separate acts. `start` finishes at [PatchStep.Patched] and waits; a
 * thirty-second wait must not end in an unbidden system install prompt under a button the user pressed labelled
 * "Patch".
 */
object PatchJobHost {

    private const val TAG = "PatchJobHost"

    private val _step = MutableStateFlow<PatchStep>(PatchStep.Idle)
    val step: StateFlow<PatchStep> = _step.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    /**
     * The request the current job belongs to, so a patch screen can tell whether the job on show is its own. Inferring
     * it from the step alone does not work: the install and restore states carry a package name rather than a request.
     */
    private val _active = MutableStateFlow<PatchRequest?>(null)
    val active: StateFlow<PatchRequest?> = _active.asStateFlow()

    private var job: Job? = null

    /** When the current record began, so every line can carry how far into the job it arrived. */
    @Volatile private var startedAt: Long = 0L

    /** The stage currently running, stamped onto each line so a step can show its own output. */
    @Volatile private var currentStage: PatchStage? = null

    /** True while something is happening that a second job would interfere with. */
    val busy: Boolean
        get() = job?.isActive == true

    private fun append(level: Int, msg: String) {
        val elapsed = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt
        _log.value = _log.value + LogLine(level, msg, elapsed, currentStage)
    }

    /** Preamble lines, written before the clock starts so they carry no elapsed time. */
    private fun appendHeader(lines: List<String>) {
        _log.value = _log.value + lines.map { LogLine(android.util.Log.INFO, it, 0L) }
    }

    /** The whole record as one shareable document. */
    fun report(): String = PatchReport.render(_log.value)

    /**
     * Persists the finished record.
     *
     * Written on every terminal outcome, not only on failure: a patch that succeeded but produced a broken app is
     * exactly the case where the successful run's report is the thing worth having.
     */
    private suspend fun archive(packageName: String) {
        PatchLogStore.write(packageName, report())
    }

    private class JobLogger(private val request: PatchRequest) : Logger() {
        override fun d(msg: String) {
            if (verbose) {
                Log.d(TAG, msg)
                append(android.util.Log.DEBUG, msg)
            }
        }

        override fun i(msg: String) {
            Log.i(TAG, msg)
            append(android.util.Log.INFO, msg)
        }

        override fun e(msg: String) {
            Log.e(TAG, msg)
            append(android.util.Log.ERROR, msg)
        }

        override fun stage(stage: Stage, index: Int, total: Int) {
            val mapped =
                when (stage) {
                    Stage.READING -> PatchStage.ReadingApk
                    Stage.SIGNING -> PatchStage.SigningSetup
                    Stage.PACKING_SPLIT -> PatchStage.PackingSplit
                    Stage.REWRITING -> PatchStage.RewritingManifest
                    Stage.INJECTING -> PatchStage.InjectingLoader
                    Stage.EMBEDDING -> PatchStage.EmbeddingModules
                    Stage.WRITING -> PatchStage.WritingAndSigning
                    Stage.DONE -> PatchStage.Finished
                }
            currentStage = mapped
            _step.value = PatchStep.Running(request, mapped, index, total)
        }
    }

    /**
     * Runs [request], unless a job is already in flight.
     *
     * A second request is refused rather than queued: two patches at once would contend for the package installer, and
     * a queue would leave the user watching a job they did not ask for yet.
     */
    fun start(requested: PatchRequest): Boolean {
        if (busy) return false
        val (request, note) = resolvedNow(requested)
        _log.value = emptyList()
        _active.value = request
        _step.value = PatchStep.Preparing(request)
        appendHeader(PatchReport.preamble(request, note))
        currentStage = null
        startedAt = System.currentTimeMillis()
        job =
            lspApp.globalScope.launch {
                runCatching {
                    val logger = JobLogger(request).also { it.verbose = true }
                    val files = Patcher.patch(logger, request)
                    appendHeader(PatchReport.outcome(files, System.currentTimeMillis() - startedAt))
                    _step.value = PatchStep.Patched(request, files)
                    // Success: the output is in its own app-private directory and the install to come
                    // reads that, so the copied *inputs* are done with. On failure they are kept, because
                    // a Retry re-reads them -- an apk picked from storage has no other copy on disk, and
                    // cleaning here would make its Retry fail with "source apk does not exist".
                    LSPPackageManager.cleanTmpApkDir()
                }
                    .onFailure { t ->
                        appendHeader(PatchReport.failure(t))
                        _step.value = PatchStep.Failed(request.label, t.message, request)
                    }
                archive(request.packageName)
            }
        return true
    }

    /**
     * [request] with an installed target's apks as the system reports them now, and a note when that differs from what
     * the request carries.
     *
     * A request records where its target was when it was built, then survives editing, storage and a re-run, so the
     * moment it is built and the moment it is used are not the same one -- and between them an update, a reinstall or
     * an uninstall moves or removes exactly those files. The recorded paths are therefore treated as a name for the app
     * rather than as its location, and the location is read again here. Apks picked from storage are copies this app
     * owns, and nothing outside it can move them.
     */
    private fun resolvedNow(request: PatchRequest): Pair<PatchRequest, String?> {
        val target = request.target as? PatchTarget.InstalledApp ?: return request to null
        val live = LSPPackageManager.installedApkPaths(target.packageName)
        if (live == null) {
            // Absent and absent-with-a-record are different answers to "what happened to it", and a
            // report that cannot separate them leaves the question open.
            val kept = LSPPackageManager.isArchivedPackage(target.packageName)
            val note =
                if (kept) "${target.packageName} is archived: the device keeps the package, not its apks"
                else "${target.packageName} is not installed; the recorded paths are used unchanged"
            Log.w(TAG, note)
            return request to note
        }
        if (live == target.apkPaths) return request to null
        Log.i(TAG, "${target.packageName} moved: recorded ${target.apkPaths}, now $live")
        return request.copy(target = target.copy(apkPaths = live)) to
            "read again: ${target.packageName} has moved since this request was built"
    }

    /**
     * Installs what the current [PatchStep.Patched] produced.
     *
     * A differently-signed app already installed cannot be replaced, so that case stops at [PatchStep.NeedsUninstall]
     * and asks rather than uninstalling silently: the answer costs the user everything the app has saved.
     */
    fun install(uninstallFirst: Boolean = false) {
        val current = _step.value
        val (request, files) =
            when (current) {
                is PatchStep.Patched -> current.request to current.files
                is PatchStep.NeedsUninstall -> current.request to current.files
                else -> return
            }
        if (busy) return
        job =
            lspApp.globalScope.launch {
                val useShizuku = ShizukuApi.ensureReadyOrFallback(ShizukuOp.Install)
                val pkg = request.packageName
                append(
                    android.util.Log.INFO,
                    "Install: ${files.size} apk(s) via ${if (useShizuku) "Shizuku shell" else "platform installer"}",
                )
                if (!uninstallFirst && needsUninstall(pkg, useShizuku)) {
                    // Worth recording even though it is not a failure: it is the moment the flow stops
                    // and waits, and a report that skips it looks like one that simply ended.
                    append(android.util.Log.WARN, "A differently-signed $pkg is installed; asking to uninstall first")
                    _step.value = PatchStep.NeedsUninstall(request, files)
                    return@launch
                }
                if (uninstallFirst) {
                    _step.value = PatchStep.Uninstalling(pkg)
                    append(android.util.Log.INFO, "Uninstalling $pkg")
                    val (status, message) = uninstall(pkg, useShizuku)
                    append(statusLevel(status), "Uninstall: ${describe(status, message)}")
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        archive(pkg)
                        _step.value = PatchStep.Failed(request.label, message, request)
                        return@launch
                    }
                }
                _step.value = PatchStep.Installing(pkg)
                val (status, message) = LSPPackageManager.installFiles(files, useShizuku)
                append(statusLevel(status), "Install: ${describe(status, message)}")
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    PatchOutputStore.discard(pkg)
                    PatchRequestStore.drop(request.token)
                    PatchInputs.discard(pkg)
                    LSPPackageManager.invalidateModuleIcons(pkg)
                    LSPPackageManager.fetchAppList()
                    archive(pkg)
                    _step.value = PatchStep.Done(pkg, request.label)
                } else {
                    archive(pkg)
                    _step.value = PatchStep.Failed(request.label, message, request)
                }
            }
    }

    /**
     * Puts [app] back to the original apk stored inside its patched build.
     *
     * Always destructive, and not by choice: the patched app is signed with LSPatch's keystore and the original with
     * its developer's, so Android will not let one replace the other. The old one has to go first, and its data goes
     * with it. The recovered apks are kept until the job leaves this state, so a failed or declined install can be
     * retried rather than leaving nothing.
     */
    fun startRestore(app: LSPPackageManager.AppInfo) {
        if (busy) return
        val pkg = app.app.packageName
        _log.value = emptyList()
        _active.value = null
        appendHeader(PatchReport.restorePreamble(app.label, pkg))
        startedAt = System.currentTimeMillis()
        _step.value = PatchStep.Restoring(pkg, app.label)
        job =
            lspApp.globalScope.launch {
                runCatching {
                    val useShizuku = ShizukuApi.ensureReadyOrFallback(ShizukuOp.Install)
                    append(android.util.Log.INFO, "Recovering the original apk of $pkg")
                    val recovered = PatchInputs.fromInstalledPatchedApp(app)
                    if (recovered.originApks.isEmpty()) throw java.io.IOException("No original apk found")

                    // Before the uninstall: once the app is gone the scope rows describe a package that
                    // no longer exists, and a later reinstall would silently inherit them.
                    ConfigManager.clearScopeForApp(pkg)

                    _step.value = PatchStep.Uninstalling(pkg)
                    val (uninstallStatus, uninstallMessage) = uninstall(pkg, useShizuku)
                    append(statusLevel(uninstallStatus), "Uninstall: ${describe(uninstallStatus, uninstallMessage)}")
                    if (uninstallStatus != PackageInstaller.STATUS_SUCCESS) {
                        throw java.io.IOException(uninstallMessage ?: "Uninstall failed")
                    }

                    _step.value = PatchStep.Installing(pkg)
                    append(android.util.Log.INFO, "Installing ${recovered.originApks.size} original apk(s)")
                    val (status, message) = LSPPackageManager.installFiles(recovered.originApks, useShizuku)
                    append(statusLevel(status), "Install: ${describe(status, message)}")
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        throw java.io.IOException(message ?: "Install failed")
                    }
                    PatchInputs.discard(pkg)
                    PatchOutputStore.discard(pkg)
                    LSPPackageManager.invalidateModuleIcons(pkg)
                    LSPPackageManager.fetchAppList()
                    _step.value = PatchStep.Done(pkg, app.label)
                }
                    .onFailure { t ->
                        appendHeader(PatchReport.failure(t))
                        _step.value = PatchStep.Failed(app.label, t.message, null)
                    }
                archive(pkg)
            }
    }

    /** Re-runs the request that failed, from the beginning. */
    fun retry() {
        val failed = _step.value as? PatchStep.Failed ?: return
        val request = failed.request ?: return
        _step.value = PatchStep.Idle
        start(request)
    }

    /** Clears a finished job, so nothing keeps reporting an outcome the user has already seen. */
    fun acknowledge() {
        if (busy) return
        _active.value = null
        _step.value = PatchStep.Idle
        _log.value = emptyList()
        // The job is over for good now, so any temp input a failed patch kept for a Retry can go.
        lspApp.globalScope.launch { LSPPackageManager.cleanTmpApkDir() }
    }

    /** An installer status as a sentence, with the code kept for the cases with no message. */
    private fun describe(status: Int, message: String?): String {
        val name =
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> "success"
                PackageInstaller.STATUS_FAILURE -> "failure"
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
                PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted"
                PackageInstaller.STATUS_FAILURE_INVALID -> "invalid apk"
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
                LSPPackageManager.STATUS_USER_CANCELLED -> "cancelled by the user"
                else -> "status $status"
            }
        return if (message.isNullOrBlank()) name else "$name -- $message"
    }

    private fun statusLevel(status: Int) =
        if (status == PackageInstaller.STATUS_SUCCESS) android.util.Log.INFO else android.util.Log.ERROR

    // Shizuku answering "I could not tell" must not read as "nothing to uninstall": that would send
    // a differently-signed install at a package Android will refuse. The app's own PackageManager
    // answers in its place, and the failure is already recorded for the reader.
    private fun needsUninstall(packageName: String, useShizuku: Boolean): Boolean =
        (if (useShizuku) ShizukuApi.isPackageInstalledWithoutPatch(packageName) else null)
            ?: LSPPackageManager.isInstalledWithoutPatch(packageName)

    private suspend fun uninstall(packageName: String, useShizuku: Boolean): Pair<Int, String?> =
        if (useShizuku) LSPPackageManager.uninstall(packageName) else LSPPackageManager.uninstallBySystem(packageName)
}
