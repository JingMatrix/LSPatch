package org.lsposed.lspatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.lsposed.lspatch.R
import org.lsposed.lspatch.config.Configs
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi

/**
 * Keeps the manager present for as long as anything on this device depends on it.
 *
 * A patched app reaches the manager the moment it starts, and a module app is handed its service by the manager pushing
 * one; both are answered late, or not at all, if the manager's process has been reaped in the meantime. A foreground
 * service does not make the process unkillable — nothing an ordinary app can do makes it unkillable, and a force-stop
 * ends it whatever it is doing — but it does move the process out of the bucket ordinary memory pressure empties first,
 * which covers the common case at the cost of one silent notification.
 *
 * Two jobs ride on that presence. Log collection, which needs the process alive anyway because the Shizuku binding
 * lives in it, is supervised here as before: the shell-side `logcat -f` collector is started once Shizuku is granted
 * and restarted if it dies. And, when the person has asked for it, the shell-side watchdog is armed — see
 * [ShizukuApi.startManagerWatchdog], which is the only part of this that survives the manager's own death.
 *
 * The service is deliberately cheap: once everything is healthy the loop is a binder round trip every few seconds that
 * does nothing, and the notification sits at minimum importance with no badge.
 */
class ManagerResidentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The supervisor loop, started once however often the service is asked to start.
     *
     * onStartCommand runs again on every start request and on the system's own restart of a sticky service, and each
     * run used to add another loop to the same scope: several supervisors then raced to start a collector, each tearing
     * down what it took to be the running one, leaving orphaned `logcat` children nobody drained.
     */
    private var supervisor: Job? = null

    private var collecting = false

    /**
     * Whether the watchdog's state has been stated once since this process started.
     *
     * The shell process outlives the manager, so a watchdog armed by a previous life of this app may still be running
     * with nobody here knowing it; the first tick therefore says what it wants either way, and later ticks only speak
     * when there is something to say.
     */
    private var watchdogReconciled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (supervisor?.isActive == true) return START_STICKY
        supervisor = scope.launch {
            while (isActive) {
                // Both of the answers below are read off the app list, and neither is right until
                // every installed package has been scanned. Guarded, because this is the one call in
                // the tick that is not: a package scan can throw (a package uninstalled mid-scan, a
                // device with enough apps to burst a binder transaction), and an exception here
                // leaves the loop's scope with no handler -- taking the process down, and with it
                // everything this service is here to keep, since the supervisor is only ever started
                // again by a fresh start command.
                runCatching { LSPPackageManager.ensureAppList() }
                if (nothingToServe()) {
                    // A fresh install with nothing patched has no one to be reachable for, and an
                    // ongoing notification for that would be a claim about work that is not happening.
                    Log.i(TAG, "Nothing patched on this device; standing down")
                    stopSelf()
                    return@launch
                }
                // refresh() rather than ensureReady(): this tick repeats forever, and a device
                // without Shizuku would otherwise report the same thing to the reader on a loop.
                // The Logs screen already explains an absent Shizuku in place.
                val shizuku = ShizukuApi.refresh()
                if (shizuku) {
                    val uids = relevantUids(this@ManagerResidentService)
                    if (ShizukuApi.isLogCollectorRunning()) {
                        // An app patched, or a module installed, since the collector started has a
                        // uid it has never seen. Pushing the current set on every tick is what lets
                        // it join the stream in place, without a restart and without a gap.
                        ShizukuApi.updateLogCollectorUids(uids)
                    } else {
                        ShizukuApi.startLogCollector(LOG_DIR, uids)
                    }
                    armWatchdog()
                }
                val nowCollecting = shizuku && ShizukuApi.isLogCollectorRunning()
                if (nowCollecting != collecting) {
                    collecting = nowCollecting
                    // The notification says what the service is actually doing; collecting or merely
                    // present are different claims and only one of them is true at a time.
                    startAsForeground()
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
        // Restarted by the system if it is killed, so presence resumes without the user reopening the
        // app. Everything above is re-established by the loop on the next tick.
        return START_STICKY
    }

    /**
     * Whether anything on this device depends on the manager being reachable.
     *
     * An empty app list means it has not been read yet, which is not the same as an empty answer; the next tick asks
     * again rather than this one guessing.
     */
    private fun nothingToServe(): Boolean {
        val apps = LSPPackageManager.appList
        if (apps.isEmpty()) return false
        return apps.none { it.isModule || it.app.metaData?.containsKey("lspatch") == true }
    }

    /**
     * Hands the keep-alive over to the shell process, or takes it back.
     *
     * Re-stated on every tick rather than once: the Shizuku user service is a separate process with its own lifetime,
     * so it may have been started after the last time this was said, and it is the one that has to be told again.
     */
    private suspend fun armWatchdog() {
        if (Configs.keepManagerAlive) {
            ShizukuApi.startManagerWatchdog(packageName, WATCHDOG_COMPONENT, WATCHDOG_INTERVAL_S)
        } else if (!watchdogReconciled || ShizukuApi.shellIsDaemon) {
            ShizukuApi.stopManagerWatchdog()
        }
        watchdogReconciled = true
    }

    override fun onDestroy() {
        scope.cancel()
        // Turning monitoring off should not leave a logcat pinned to the buffer. Best effort on a
        // detached scope, since this one is already cancelled.
        CoroutineScope(Dispatchers.IO).launch {
            ShizukuApi.stopLogCollector()
            // And hand the shell process back: nothing else in the app needs it while monitoring is
            // off, and an unbound one would sit there until the device reboots. The watchdog, if it is
            // armed, is deliberately left running -- it is the one thing whose job starts when this
            // process ends.
            if (!Configs.keepManagerAlive) ShizukuApi.releaseUserService()
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        // API 34 requires a declared foreground-service type at the call site; neither log collection
        // nor being reachable maps to a standard bucket, so it is "special use" (declared in the
        // manifest too).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(if (collecting) R.string.log_service_title else R.string.resident_service_title))
            .setContentText(getString(if (collecting) R.string.log_service_text else R.string.resident_service_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.log_service_channel),
                    NotificationManager.IMPORTANCE_MIN,
                )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        /** Shell-owned; the app reads parts back through Shizuku, never directly (cross-UID). */
        const val LOG_DIR = "/data/local/tmp/lspatch-logs"

        /** What the shell-side watchdog starts to bring the manager back -- this service itself. */
        private const val WATCHDOG_COMPONENT = "org.lsposed.lspatch/.service.ManagerResidentService"

        private const val WATCHDOG_INTERVAL_S = 120

        /**
         * The uids whose lines belong in the framework stream: the manager itself, and every patched app and module.
         * Each is read straight off its [android.content.pm.ApplicationInfo], so no extra PackageManager round trip is
         * needed; the collector matches lines by these and by nothing else.
         *
         * Empty of everything but the manager until the package scan has run, which is why the caller waits for it.
         */
        fun relevantUids(context: Context): IntArray {
            val own = context.applicationInfo.uid
            val others = LinkedHashSet<Int>()
            LSPPackageManager.appList
                .filter { it.isModule || it.app.metaData?.containsKey("lspatch") == true }
                .forEach { if (it.app.uid != own) others.add(it.app.uid) }
            return intArrayOf(own) + others.toIntArray()
        }

        private const val TAG = "LSPatch-Resident"

        private const val CHANNEL_ID = "lspatch_log_monitor"
        private const val NOTIF_ID = 0x15
        private const val CHECK_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, ManagerResidentService::class.java)
            // Background-start restrictions (Android 12+) throw when there is no foreground reason to
            // start; the caller starts this from a user-visible launch or from a boot broadcast, both
            // of which are allowed, and the guard keeps a stray background start from taking the app
            // down.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ManagerResidentService::class.java)) }
        }
    }
}
