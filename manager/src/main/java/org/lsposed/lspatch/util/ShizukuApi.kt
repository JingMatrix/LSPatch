package org.lsposed.lspatch.util

import android.content.ComponentName
import android.content.Context
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.pm.*
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.os.SystemClock
import android.os.SystemProperties
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService as IShizukuServer
import org.lsposed.lspatch.IShizukuService
import org.lsposed.lspatch.R
import org.lsposed.lspatch.ShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** What the manager was trying to do when Shizuku failed it — the subject of a [ShizukuFailure]. */
enum class ShizukuOp {
    Grant,
    Install,
    Uninstall,
    PackageQuery,
    Logs,
    Shell,
    Optimize,
}

/**
 * Why a Shizuku-backed operation could not run.
 *
 * They are worth telling apart because each has a different answer: start Shizuku, reconnect to it, grant it, wait for
 * (or restart) the shell service, or report a call that the device itself rejected.
 */
enum class ShizukuReason {
    NotRunning,
    ConnectionLost,
    NotGranted,
    ServiceUnavailable,
    CallFailed,
}

/**
 * One caught Shizuku failure, kept in a shape a reader can act on: what was attempted, why it failed, and — when a
 * throwable was involved — the trace, so the reason survives the trip from the device to a bug report.
 */
data class ShizukuFailure(
    val op: ShizukuOp,
    val reason: ShizukuReason,
    val detail: String,
    val trace: String? = null,
) {
    /** Identity for de-duplication: the same problem from the same operation is one report. */
    val key: String
        get() = "$op/$reason/$detail"
}

/**
 * The manager's Shizuku channel — and the record of everything it could not do.
 *
 * Two capabilities hide behind one grant and fail independently. The *binder channel* (system services wrapped in
 * [ShizukuBinderWrapper]) drives installs, uninstalls and package queries; the *shell service* ([IShizukuService], a
 * process Shizuku starts for us) drives log collection, shell commands and dexopt. A grant enables both, but only the
 * second can also fail to start, so the two are reported apart.
 *
 * State is never trusted between calls, the library's own cache included. Shizuku has no revoke callback and its
 * `checkSelfPermission` short-circuits on a cached grant: a permission taken away in Shizuku's own UI leaves the server
 * alive and every cached flag stale, so [ensureReady] re-reads the live state at each point of use and the cached
 * [isPermissionGranted] is only what the UI paints between actions.
 *
 * Nothing fails silently. Every entry point routes through [guard] or [onService], which record a [ShizukuFailure]
 * rather than returning an empty result no one can explain — and since the logs themselves need Shizuku, that record is
 * the only trace a user without a working Shizuku has.
 */
object ShizukuApi {

    private const val TAG = "ShizukuApi"

    /** Identifies our permission request in Shizuku's result callback; the value is arbitrary. */
    const val PERMISSION_REQUEST_CODE = 114514

    private const val SERVICE_TIMEOUT_MS = 3000L

    /** The setting the package manager reads before verifying what the shell installs. */
    private const val ADB_VERIFY_SETTING = "verifier_verify_adb_installs"

    /**
     * How long a bind may stay in flight before another is allowed.
     *
     * bindUserService returns void and carries no result callback, so a bind that never lands is only noticed by giving
     * up on it. The bound is above the server's own 30s start timeout on purpose: while that timeout runs the record
     * stays marked "starting" and further requests are ignored, so an earlier retry does nothing, and after it the
     * record is gone and a retry really does start a process.
     */
    private const val BIND_TIMEOUT_MS = 40_000L

    private const val MAX_REMEMBERED_FAILURES = 20

    @Volatile private var userService: IShizukuService? = null

    // A bind is not instant: Shizuku starts a process for it, and until that lands every refresh
    // would ask for another, and each surplus request can leave a shell process outliving its
    // client. A request in flight therefore counts as having one.
    @Volatile private var binding = false

    @Volatile private var bindingSince = 0L

    // Whether Shizuku ever answered in this process. The binder arrives as a push from the server,
    // which the app has no call to ask for, so "it answered and then stopped" and "it was never
    // there" are different states even though both leave us without one. They are kept apart so the
    // remedy offered is not "start Shizuku" while Shizuku is running.
    @Volatile private var hadBinder = false

    // The last state refresh() reported, so it reports again only when the state is different.
    @Volatile private var lastLoggedState: String? = null

    // This allows us to "await" the service connection
    private var userServiceDeferred = CompletableDeferred<IShizukuService>()

    private val userServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Log.i(TAG, "Shell service connected")
                binding = false
                val binder = IShizukuService.Stub.asInterface(service)
                userService = binder
                userServiceDeferred.complete(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.w(TAG, "Shell service disconnected")
                binding = false
                userService = null
                userServiceDeferred = CompletableDeferred()
            }
        }

    private fun IBinder.wrap() = ShizukuBinderWrapper(this)

    private fun IInterface.asShizukuBinder() = this.asBinder().wrap()

    private val iPackageManager: IPackageManager by lazy {
        IPackageManager.Stub.asInterface(SystemServiceHelper.getSystemService("package").wrap())
    }

    private val iPackageInstaller: IPackageInstaller by lazy {
        IPackageInstaller.Stub.asInterface(iPackageManager.packageInstaller.asShizukuBinder())
    }

    private val packageInstaller: PackageInstaller by lazy {
        val userId = Process.myUserHandle().hashCode()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, "com.android.shell", null, userId))
        } else {
            Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, "com.android.shell", userId))
        }
    }

    private lateinit var appContext: Context

    var isBinderAvailable by mutableStateOf(false)
        private set

    var isPermissionGranted by mutableStateOf(false)
        private set

    /** The failure waiting to be shown, or null once the reader has seen it. */
    var lastFailure by mutableStateOf<ShizukuFailure?>(null)
        private set

    // Surfaced once each: a background poll (log collection) hits the same wall every few seconds,
    // and a reader told about it repeatedly learns nothing after the first time.
    private val surfaced = mutableSetOf<String>()

    private val history = ArrayDeque<ShizukuFailure>()

    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "init: registering Shizuku listeners")
        Shizuku.addBinderReceivedListenerSticky {
            Log.i(TAG, "Binder received: server API ${serverVersion() ?: "?"}, uid ${serverUid() ?: "?"}")
            refresh()
        }
        Shizuku.addBinderDeadListener {
            Log.w(TAG, "Shizuku binder died")
            // Including a bind that was still in flight: it can never land now, and leaving the latch
            // set would make the reconnection that follows skip its own rebind.
            binding = false
            isBinderAvailable = false
            isPermissionGranted = false
            userService = null
            userServiceDeferred = CompletableDeferred()
            forgetSurfaced()
        }
        // Registered here rather than on a screen: a grant has to bind the shell service, and a
        // screen that only flipped a flag left logs and dexopt dead until the next app start.
        Shizuku.addRequestPermissionResultListener { _, result ->
            Log.i(TAG, "Permission result: $result")
            refresh()
        }
    }

    /**
     * Re-reads Shizuku's live state and returns whether the manager may use it.
     *
     * Cheap enough to call before every use, which is the point: no callback exists for a permission revoked from
     * Shizuku's own UI, so a cached grant is a guess.
     */
    fun refresh(): Boolean {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = alive && serverGrantsAccess()
        if (alive) hadBinder = true
        if (alive != isBinderAvailable) Log.i(TAG, "Shizuku binder ${if (alive) "available" else "gone"}")
        isBinderAvailable = alive
        if (granted != isPermissionGranted) {
            Log.i(
                TAG,
                "Access ${if (granted) "granted" else "withdrawn"} (binder=$alive, shell service=$isShellServiceBound)",
            )
            isPermissionGranted = granted
            // The ground has moved, so a failure recorded against the old state is stale advice.
            forgetSurfaced()
        }
        if (granted) bindUserService() else releaseUserService()
        // Only when it has moved. refresh() is on the log collector's supervisor tick and on the path
        // of every read the Logs screen makes, so tracing each call restated an unchanged answer
        // several times a second -- and now that the framework stream keeps everything the manager
        // says, that trace was most of what a reader saw. A state that has not changed is not news.
        val state = "binder=$alive granted=$granted shellService=$isShellServiceBound"
        if (state != lastLoggedState) {
            lastLoggedState = state
            Log.i(TAG, "State: $state")
        }
        return granted
    }

    /**
     * Asks the Shizuku server itself whether the grant still stands.
     *
     * Deliberately not `Shizuku.checkSelfPermission()`: the library answers from a static that it only re-reads while
     * the cached value is false, so once granted it keeps saying granted for the life of the process. The very call
     * that looks like a live check is the one that cannot see a revoke. The grant lives in the server, so the server is
     * asked — through the binder the library already holds, which costs one transaction.
     */
    private fun serverGrantsAccess(): Boolean {
        val binder = Shizuku.getBinder()
        if (binder == null) {
            Log.v(TAG, "No Shizuku binder yet")
            return false
        }
        return runCatching { IShizukuServer.Stub.asInterface(binder).checkSelfPermission() }
            .onFailure { Log.w(TAG, "checkSelfPermission failed on the server", it) }
            .getOrDefault(false)
    }

    /**
     * The gate every Shizuku-backed action passes through: live state, and a recorded failure naming [op] when the
     * answer is no, so the caller can fall back without going quiet.
     */
    fun ensureReady(op: ShizukuOp): Boolean {
        if (refresh()) return true
        if (isBinderAvailable) {
            record(op, ShizukuReason.NotGranted, "Shizuku is running but has not granted LSPatch access")
        } else {
            record(op, absentReason(), absentDetail())
        }
        return false
    }

    /** Which of the two "no Shizuku" states this is — see [hadBinder]. */
    private fun absentReason(): ShizukuReason =
        if (hadBinder) ShizukuReason.ConnectionLost else ShizukuReason.NotRunning

    private fun absentDetail(): String =
        if (hadBinder) "the connection to Shizuku was lost after it had been working"
        else "the Shizuku service is not running on this device"

    /**
     * The gate for an operation that has somewhere else to go — an install, which the platform installer can still
     * carry with a confirmation.
     *
     * Reports only what contradicts what the app has been claiming: Shizuku granted a moment ago and gone now is the
     * stale grant worth explaining, while a Shizuku that was never there is the fallback working as designed, and
     * announcing it would teach users to ignore the dialog that matters.
     */
    fun ensureReadyOrFallback(op: ShizukuOp): Boolean {
        val claimed = isPermissionGranted
        if (refresh()) return true
        Log.i(TAG, "$op: falling back to the platform installer (was claiming granted: $claimed)")
        if (claimed) {
            val reason = if (isBinderAvailable) ShizukuReason.NotGranted else absentReason()
            record(
                op,
                reason,
                "Shizuku was granted a moment ago and is not now; falling back to the platform installer",
            )
        }
        return false
    }

    /** Asks Shizuku for access. A no-op when Shizuku is not running — there is nobody to ask. */
    fun requestPermission(): Boolean {
        if (refresh()) return true
        if (!isBinderAvailable) {
            record(ShizukuOp.Grant, absentReason(), absentDetail())
            return false
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { record(ShizukuOp.Grant, ShizukuReason.CallFailed, it.toString(), it) }
        return false
    }

    /** Shizuku's own version and uid, for a report; null when it is not running. */
    fun serverVersion(): Int? = runCatching { Shizuku.getVersion() }.getOrNull()

    fun serverUid(): Int? = runCatching { Shizuku.getUid() }.getOrNull()

    /** Whether the shell service is bound — the half of Shizuku that logs and dexopt need. */
    val isShellServiceBound: Boolean
        get() = userService != null

    /** Every failure caught this session, oldest first — the report's Shizuku section. */
    fun recentFailures(): List<ShizukuFailure> = synchronized(history) { history.toList() }

    fun dismissFailure() {
        lastFailure = null
    }

    internal fun record(op: ShizukuOp, reason: ShizukuReason, detail: String, throwable: Throwable? = null) {
        val failure = ShizukuFailure(op, reason, detail, throwable?.stackTraceToString())
        Log.w(TAG, "$op unavailable ($reason): $detail", throwable)
        synchronized(history) {
            history.addLast(failure)
            while (history.size > MAX_REMEMBERED_FAILURES) history.removeFirst()
        }
        val fresh = synchronized(surfaced) { surfaced.add(failure.key) }
        if (fresh) lastFailure = failure
    }

    internal fun forgetSurfaced() {
        synchronized(surfaced) { surfaced.clear() }
    }

    /**
     * Runs a binder call, turning anything it throws into a recorded failure and [fallback].
     *
     * Throwable rather than Exception on purpose: the hidden-API casts below fail with Errors (NoSuchMethodError,
     * ClassCastException) on a ROM whose signatures differ, and a crash there tells the user nothing.
     */
    private inline fun <T> guard(op: ShizukuOp, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            record(op, ShizukuReason.CallFailed, t.toString(), t)
            fallback
        }

    private suspend fun awaitService(op: ShizukuOp): IShizukuService? {
        if (!ensureReady(op)) return null
        if (userService == null) Log.d(TAG, "$op: waiting up to ${SERVICE_TIMEOUT_MS}ms for the shell service")
        val service = userService ?: withTimeoutOrNull(SERVICE_TIMEOUT_MS) { userServiceDeferred.await() }
        if (service == null) {
            record(
                op,
                ShizukuReason.ServiceUnavailable,
                "the Shizuku shell service did not start within ${SERVICE_TIMEOUT_MS / 1000}s",
            )
        }
        return service
    }

    /**
     * Runs [block] on the shell service, off whatever thread asked.
     *
     * A shell call is a synchronous round trip: it returns when the command on the other side has run to completion.
     * Two call sites ran it from a Compose scope on the main dispatcher, so the hop belongs here rather than at each
     * site, where forgetting it is invisible until the call is slow. Doing it once also keeps the gate's own binder
     * traffic off the caller's thread.
     */
    private suspend fun <T> onService(op: ShizukuOp, fallback: T, block: (IShizukuService) -> T): T =
        withContext(Dispatchers.IO) {
            val service = awaitService(op) ?: return@withContext fallback
            guard(op, fallback) { block(service) }
        }

    /**
     * Whether the shell process should outlive this app.
     *
     * Normally it should not -- a shell-uid process left running for the rest of the boot is a leak, and
     * [releaseUserService] exists to avoid exactly that. The exception is the watchdog: its whole job starts when the
     * manager's process ends, so while it is armed the shell service is asked for as a daemon instead. The two
     * lifetimes are two different service records, which is why the args come in pairs and why switching means letting
     * the running one go first.
     */
    @Volatile private var daemonRequested = false

    val shellIsDaemon
        get() = daemonRequested

    /** The args a running service was bound with -- unbinding takes the same ones the bind was given. */
    @Volatile private var boundArgs: Shizuku.UserServiceArgs? = null

    private val transientArgs by lazy { buildServiceArgs(daemon = false) }
    private val daemonArgs by lazy { buildServiceArgs(daemon = true) }

    private val serviceArgs
        get() = if (daemonRequested) daemonArgs else transientArgs

    private fun buildServiceArgs(daemon: Boolean) =
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, ShizukuService::class.java.name))
            .daemon(daemon)
            .tag(if (daemon) "lspatch-shell-daemon" else "lspatch-shell")
            .processNameSuffix("service")
            .debuggable(true)
            // Version the service by the app's version code: on an upgrade Shizuku tears down the old
            // instance and starts a fresh one, so a rebuilt ShizukuService (new AIDL, new collector)
            // actually takes effect instead of the app binding to a stale cached process.
            .version(org.lsposed.lspatch.share.LSPConfig.instance.VERSION_CODE)

    private fun bindUserService() {
        if (userService != null) return
        if (binding && SystemClock.elapsedRealtime() - bindingSince < BIND_TIMEOUT_MS) return
        if (!::appContext.isInitialized) return
        Log.i(TAG, "Binding the shell service (version ${org.lsposed.lspatch.share.LSPConfig.instance.VERSION_CODE})")
        binding = true
        bindingSince = SystemClock.elapsedRealtime()
        try {
            val args = serviceArgs
            boundArgs = args
            Shizuku.bindUserService(args, userServiceConnection)
        } catch (t: Throwable) {
            binding = false
            record(ShizukuOp.Shell, ShizukuReason.CallFailed, t.toString(), t)
        }
    }

    /**
     * Lets go of the shell service and asks Shizuku to stop the process behind it.
     *
     * Dropping the reference alone leaves a shell-uid process running for the rest of the boot: it outlives its client,
     * and the next launch starts another beside it. Unbinding with `remove` is the only word the app has for "I am done
     * with it".
     */
    fun releaseUserService() {
        val wasBound = userService != null
        binding = false
        userService = null
        userServiceDeferred = CompletableDeferred()
        if (!wasBound || !::appContext.isInitialized) return
        Log.i(TAG, "Unbinding the shell service and asking Shizuku to stop it")
        val args = boundArgs ?: serviceArgs
        boundArgs = null
        guard(ShizukuOp.Shell, Unit) { Shizuku.unbindUserService(args, userServiceConnection, true) }
    }

    /**
     * Chooses whether the next shell service is a daemon, letting go of one bound the other way.
     *
     * A service record's lifetime is fixed when it starts, so there is no changing it in place; the running one is
     * released and the next call binds under the new rule.
     */
    @Synchronized
    private fun setShellDaemon(enabled: Boolean) {
        if (daemonRequested == enabled) return
        Log.i(TAG, "Shell service lifetime is now " + if (enabled) "daemon" else "tied to this app")
        val hadService = userService != null
        daemonRequested = enabled
        if (hadService) releaseUserService()
    }

    /**
     * Arms the shell-side watchdog: the shell process starts [component] again whenever it finds no process of
     * [packageName].
     *
     * The user id is this app's own, so a manager installed in a secondary profile is restarted in the profile it lives
     * in rather than in the primary one.
     */
    suspend fun startManagerWatchdog(packageName: String, component: String, intervalSeconds: Int): Boolean {
        setShellDaemon(true)
        val userId = android.os.Process.myUid() / 100000
        val arm = { service: IShizukuService ->
            service.startManagerWatchdog(packageName, component, userId, intervalSeconds)
        }
        if (onService(ShizukuOp.Shell, false, arm)) return true
        // A daemon shell service outlives the app that asked for it -- that is the point of it -- so
        // the one answering here can be from a previous life of this app, running code that predates
        // this call and cannot serve it. Nothing distinguishes that from any other failure except
        // trying again against a process this build started, so it is let go of exactly once.
        Log.i(TAG, "The shell service could not arm the watchdog; replacing it and trying once more")
        releaseUserService()
        return onService(ShizukuOp.Shell, false, arm)
    }

    /** Disarms the watchdog and lets the shell process go back to living only as long as this app does. */
    suspend fun stopManagerWatchdog() {
        if (userService != null) {
            onService(ShizukuOp.Shell, Unit) { it.stopManagerWatchdog() }
        }
        setShellDaemon(false)
    }

    /**
     * How a device answered one request to lift a limit.
     *
     * [Unsupported] is not a failure and must not be reported as one: several of these limits are a vendor's invention
     * and simply do not exist elsewhere, so a device that has never heard of one has nothing to refuse. Telling a
     * person their phone "refused" a setting it does not have sends them looking for a problem that is not there.
     */
    enum class ShellVerdict {
        Accepted,
        Unsupported,
        Refused,
    }

    /**
     * One command the shell was asked to run on the manager's behalf, and what it answered.
     *
     * [label] is what the limit is called, because the command is not what a reader wants to be told was refused;
     * [command] and [output] stay for the log and for a report.
     */
    data class ShellOutcome(
        val label: String,
        val command: String,
        val output: String,
        val verdict: ShellVerdict,
    ) {
        val accepted
            get() = verdict == ShellVerdict.Accepted
    }

    /**
     * Asks the shell to take [packageName] out of the platform's background limits.
     *
     * Every one of these is a request the device is free to refuse -- the doze whitelist and the standby buckets are
     * platform features, the auto-start op is not a platform feature at all and exists only on some vendors' builds --
     * so each is run on its own and reported as it answered. Nothing here is retried or assumed: what the reader is
     * shown is what the shell said.
     */
    suspend fun exemptFromBackgroundLimits(packageName: String): List<ShellOutcome> {
        val commands =
            listOf(
                appContext.getString(R.string.background_limit_doze) to "cmd deviceidle whitelist +$packageName",
                appContext.getString(R.string.background_limit_background) to
                    "cmd appops set $packageName RUN_IN_BACKGROUND allow",
                appContext.getString(R.string.background_limit_any_background) to
                    "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow",
                appContext.getString(R.string.background_limit_standby) to "am set-standby-bucket $packageName active",
                // Vendor-specific and absent from AOSP; asked for because on the devices that reap hardest
                // it is the one that matters, and its refusal elsewhere is harmless and reported plainly.
                appContext.getString(R.string.background_limit_autostart) to
                    "cmd appops set $packageName AUTO_START allow",
            )
        return commands.map { (label, command) ->
            val output = runShellCommand(command)
            val verdict = if (output == null) ShellVerdict.Refused else verdictOf(output)
            val outcome = ShellOutcome(label, command, output?.trim().orEmpty(), verdict)
            if (verdict != ShellVerdict.Accepted) Log.i(TAG, "$label $verdict: $command -> ${outcome.output}")
            outcome
        }
    }

    /**
     * What a shell command's output says about itself.
     *
     * A command that printed nothing did what it was asked. Everything else is read for the one distinction worth
     * making: a device that does not know the setting, versus one that knows it and said no. The first is how AOSP
     * answers a vendor's app-op, and the wording is the platform's own ("Unknown operation string: AUTO_START"), so it
     * is what there is to match on.
     */
    private fun verdictOf(output: String): ShellVerdict {
        val text = output.trim().lowercase()
        if (text.isEmpty()) return ShellVerdict.Accepted
        val absent = listOf("unknown operation", "unknown command", "not found", "no such", "unknown option")
        if (absent.any { text.contains(it) }) return ShellVerdict.Unsupported
        val refusals = listOf("error", "exception", "failure", "failed", "permission", "usage:", "bad ")
        return if (refusals.any { text.contains(it) }) ShellVerdict.Refused else ShellVerdict.Accepted
    }

    /**
     * Opens an install session on the shell installer.
     *
     * The one Shizuku entry point that still throws: the caller owns a session it has to close, and reports the failure
     * as the install's own outcome. Recorded here all the same, so the trace is available to the reader and not only to
     * the patch log.
     */
    fun createPackageInstallerSession(params: PackageInstaller.SessionParams): Pair<Int, PackageInstaller.Session> =
        try {
            val sessionId = packageInstaller.createSession(params)
            val iSession =
                IPackageInstallerSession.Stub.asInterface(iPackageInstaller.openSession(sessionId).asShizukuBinder())
            sessionId to Refine.unsafeCast<PackageInstaller.Session>(PackageInstallerHidden.SessionHidden(iSession))
        } catch (t: Throwable) {
            record(ShizukuOp.Install, ShizukuReason.CallFailed, t.toString(), t)
            throw t
        }

    /**
     * Gives up a session the installer has taken and not finished.
     *
     * A committed session holds its staged copy of the package until something ends it, and an install that is being
     * retried elsewhere has no further use for the one that went quiet.
     */
    fun abandonSession(sessionId: Int) {
        guard(ShizukuOp.Install, Unit) {
            packageInstaller.abandonSession(sessionId)
            Log.i(TAG, "Abandoned session $sessionId")
        }
    }

    /**
     * What the installer still thinks of a session, as one line for a report.
     *
     * Asked when a commit has gone quiet: whether the session is gone, sealed and waiting, or never left the ground is
     * the difference between "the system took it and said nothing" and "it was dropped", which no amount of staring at
     * our own side can tell apart.
     */
    fun describeSession(sessionId: Int): String =
        guard(ShizukuOp.Install, "could not be read") {
            val info = packageInstaller.getSessionInfo(sessionId)
            if (info == null) {
                "gone (abandoned, or applied without reporting)"
            } else {
                // Whether a session is sealed is only readable from a release that has those fields;
                // below it the rest of the line still separates the states worth telling apart.
                val sealed =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        " staged=${info.isStaged} committed=${info.isCommitted}"
                    } else {
                        ""
                    }
                "active=${info.isActive}$sealed progress=${info.progress} " +
                    "installer=${info.installerPackageName} package=${info.appPackageName}"
            }
        }

    /**
     * Whether [packageName] is installed and is not an LSPatch build — null when Shizuku could not answer, which the
     * caller must not read as "no": it means ask someone else.
     */
    fun isPackageInstalledWithoutPatch(packageName: String): Boolean? {
        if (!ensureReady(ShizukuOp.PackageQuery)) return null
        return guard(ShizukuOp.PackageQuery, null) {
            val userId = Process.myUserHandle().hashCode()
            val app =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA.toLong(), userId)
                } else {
                    iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId)
                }
            (app != null) && (app.metaData?.containsKey("lspatch") != true)
        }
    }

    /** Uninstalls through the shell installer. Throws like the session above, and for the reason. */
    fun uninstallPackage(packageName: String, intentSender: IntentSender) {
        try {
            packageInstaller.uninstall(packageName, intentSender)
        } catch (t: Throwable) {
            record(ShizukuOp.Uninstall, ShizukuReason.CallFailed, t.toString(), t)
            throw t
        }
    }

    /** Runs a shell command through the Shizuku user service (shell UID); null if unavailable. */
    suspend fun runShellCommand(command: String): String? =
        onService(ShizukuOp.Shell, null) { it.runShellCommand(command) }

    /** Runs a shell script (`sh -c`) — globs/loops/redirects allowed; null if unavailable. */
    suspend fun runShellScript(script: String): String? = onService(ShizukuOp.Shell, null) { it.runShellScript(script) }

    // --- Continuous log collection (see [ShizukuService]). The shell UID owns the rotating part
    // files; the app reads them back through these wrappers rather than the filesystem. ---

    /** Starts the fan-out log collector; [relevantUids] select the framework stream. */
    suspend fun startLogCollector(logDir: String, relevantUids: IntArray): Boolean =
        onService(ShizukuOp.Logs, false) { it.startLogCollector(logDir, relevantUids) }

    /** Replaces a running collector's uid set in place, so a newly patched app joins without a restart. */
    suspend fun updateLogCollectorUids(relevantUids: IntArray) {
        onService(ShizukuOp.Logs, Unit) { it.updateLogCollectorUids(relevantUids) }
    }

    suspend fun stopLogCollector() {
        onService(ShizukuOp.Logs, Unit) { it.stopLogCollector() }
    }

    /** Rolls both streams to a fresh part without stopping collection or deleting anything. */
    suspend fun startNewLogPart(): Boolean = onService(ShizukuOp.Logs, false) { it.startNewLogPart() }

    suspend fun isLogCollectorRunning(): Boolean = onService(ShizukuOp.Logs, false) { it.isLogCollectorRunning() }

    /** One stream's parts as (absolutePath, sizeBytes), oldest first; empty when none/unavailable. */
    suspend fun listLogParts(logDir: String, prefix: String): List<Pair<String, Long>> =
        onService(ShizukuOp.Logs, emptyList()) { service ->
            service.listLogParts(logDir, prefix).mapNotNull { row ->
                val parts = row.split('\t')
                if (parts.size == 2) parts[0] to (parts[1].toLongOrNull() ?: 0L) else null
            }
        }

    /**
     * The size of a shell-owned file: 0 when it does not exist, and -1 when the shell could not be asked.
     *
     * The two are different answers. A part that has been rotated away is an empty reading; a shell that is gone is a
     * broken one, and reporting the second as the first would tell the reader their log is empty when it is their
     * access that failed.
     */
    suspend fun fileSize(path: String): Long = onService(ShizukuOp.Logs, -1L) { it.fileSize(path) }

    /** Whether this device's logcat writes the uid column; false when the shell cannot be asked. */
    suspend fun supportsUidColumn(): Boolean = onService(ShizukuOp.Logs, false) { it.supportsUidColumn() }

    /**
     * One slice of a shell-side file, or null when the shell is unavailable.
     *
     * A screen wants the tail and an export the whole file; both arrive this way, because the only way past the Binder
     * transaction limit is to ask for it a piece at a time.
     */
    suspend fun readFileChunk(path: String, offset: Long, maxBytes: Int): ByteArray? =
        onService(ShizukuOp.Logs, null) { it.readFileChunk(path, offset, maxBytes) }

    /**
     * Whether the platform verifies packages installed from the shell, or null when it cannot be read.
     *
     * Verification is an asynchronous step the package manager delegates and then waits on, and a session whose
     * verifier never answers stays committed and unfinished. Whether a device verifies what the shell installs is
     * therefore part of describing that device, and belongs in a report about an install that did not complete.
     */
    suspend fun verifiesShellInstalls(): Boolean? {
        val value = runShellCommand("settings get global $ADB_VERIFY_SETTING")?.trim() ?: return null
        // Unset reads as "null" and means the default, which is to verify.
        if (value.isEmpty() || value == "null") return true
        return value.toIntOrNull()?.let { it != 0 }
    }

    suspend fun performDexOptMode(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
            return onService(ShizukuOp.Optimize, false) { service ->
                service.runShellCommand("cmd package compile -m verify -f $packageName").contains("Success")
            }
        }
        // Legacy reflection-based method for older versions
        return withContext(Dispatchers.IO) {
            if (!ensureReady(ShizukuOp.Optimize)) return@withContext false
            legacyDexOptMode(packageName)
        }
    }

    private fun legacyDexOptMode(packageName: String): Boolean =
        guard(ShizukuOp.Optimize, false) {
            iPackageManager.performDexOptMode(
                packageName,
                SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false),
                "verify",
                true,
                true,
                null,
            )
        }
}
