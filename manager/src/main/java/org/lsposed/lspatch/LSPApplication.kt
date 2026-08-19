package org.lsposed.lspatch

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.lsposed.lspatch.data.repository.PatchOutputStore
import org.lsposed.lspatch.data.repository.PatchRequestStore
import org.lsposed.lspatch.manager.AppBroadcastReceiver
import org.lsposed.lspatch.service.ManagerResidentService
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ManagerMigrate
import org.lsposed.lspatch.util.ShizukuApi
import org.lsposed.lspatch.util.ShizukuDebugTrigger

lateinit var lspApp: LSPApplication

class LSPApplication : Application() {

    lateinit var prefs: SharedPreferences
    lateinit var tmpApkDir: File

    /**
     * Where patched apks land, one directory per package. App-private, so patching needs no storage permission and no
     * user-chosen folder; under `noBackupFilesDir` so a multi-hundred-megabyte intermediate is never swept into a cloud
     * backup.
     */
    lateinit var patchedDir: File

    // A SupervisorJob, not a bare Job: children here are unrelated background work, and a plain job
    // is cancelled for good by the first child that fails. One uncaught patch failure would
    // otherwise take the app list refresh below down with it for the rest of the process's life.
    val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val backgroundWorkStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("")
        lspApp = this
        filesDir.mkdir()
        // Restore settings/db/keystore from a cloaked APK before opening prefs or Room.
        ManagerMigrate.importIfNeeded(this)
        tmpApkDir = cacheDir.resolve("apk").also { it.mkdir() }
        patchedDir = noBackupFilesDir.resolve("patched").also { it.mkdirs() }
        prefs = lspApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
        ShizukuApi.init(this)
        // Debug builds only -- the release twin of this object does nothing.
        ShizukuDebugTrigger.register(this)
    }

    /**
     * The work that is not needed to answer a patched app, run once the process has a reason for it.
     *
     * A process is created for one of two reasons, and only one of them is a person: the manager is launched, or a
     * patched app binds [org.lsposed.lspatch.manager.ModuleService] with BIND_AUTO_CREATE, which starts this process
     * from nothing and then waits on it. That app's wait is bounded, so onCreate keeps only what a module query needs
     * and everything else -- scanning every installed package, sweeping patch output, starting the log collector --
     * moves here, behind the first thing that actually wants it. Idempotent, and safe to call from either.
     */
    fun startBackgroundWork() {
        if (!backgroundWorkStarted.compareAndSet(false, true)) return
        AppBroadcastReceiver.register(this)
        globalScope.launch { LSPPackageManager.fetchAppList() }
        // Patched output survives a crash between patching and installing, so it has to be cleared
        // by someone; the app list is what says which packages still have a reason to keep theirs.
        globalScope.launch { PatchOutputStore.sweep() }
        globalScope.launch { PatchRequestStore.prune() }
        // The service keeps the manager reachable for patched apps, and collects logs once Shizuku is
        // granted; it stands down on its own if nothing on this device is patched. The start is
        // guarded against the background foreground-service restriction.
        ManagerResidentService.start(this)
    }
}
