package org.lsposed.lspatch

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.lsposed.lspatch.data.repository.PatchOutputStore
import org.lsposed.lspatch.data.repository.PatchRequestStore
import org.lsposed.lspatch.manager.AppBroadcastReceiver
import org.lsposed.lspatch.service.LogCollectorService
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ManagerMigrate
import org.lsposed.lspatch.util.ShizukuApi
import java.io.File

lateinit var lspApp: LSPApplication

class LSPApplication : Application() {

    lateinit var prefs: SharedPreferences
    lateinit var tmpApkDir: File

    /**
     * Where patched apks land, one directory per package. App-private, so patching needs no storage
     * permission and no user-chosen folder; under `noBackupFilesDir` so a multi-hundred-megabyte
     * intermediate is never swept into a cloud backup.
     */
    lateinit var patchedDir: File

    // A SupervisorJob, not a bare Job: children here are unrelated background work, and a plain job
    // is cancelled for good by the first child that fails. One uncaught patch failure would
    // otherwise take the app list refresh below down with it for the rest of the process's life.
    val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        AppBroadcastReceiver.register(this)
        globalScope.launch { LSPPackageManager.fetchAppList() }
        // Patched output survives a crash between patching and installing, so it has to be cleared
        // by someone; the app list is what says which packages still have a reason to keep theirs.
        globalScope.launch { PatchOutputStore.sweep() }
        globalScope.launch { PatchRequestStore.prune() }
        // Begin collecting logs as soon as the app is alive. The service itself waits for Shizuku
        // before starting the shell-side collector, and the start is guarded against the background
        // foreground-service restriction — this runs on the launch that brings the app up.
        LogCollectorService.start(this)
    }
}
