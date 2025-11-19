package org.lsposed.lspatch.ui.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.BuildConfig
import java.io.File
import java.io.IOException

val LazyListState.lastVisibleItemIndex
    get() = layoutInfo.visibleItemsInfo.lastOrNull()?.index

val LazyListState.lastItemIndex
    get() = layoutInfo.totalItemsCount.let { if (it == 0) null else it }

val LazyListState.isScrolledToEnd
    get() = lastVisibleItemIndex == lastItemIndex

fun checkIsApkFixedByLSP(context: Context, packageName: String): Boolean {
    return try {
        val app =
            context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        (app.metaData?.containsKey("lspatch") != true)
    } catch (_: PackageManager.NameNotFoundException) {
        Log.e("LSPatch", "Package not found: $packageName")
        false
    } catch (e: Exception) {
        Log.e("LSPatch", "Unexpected error in checkIsApkFixedByLSP", e)
        false
    }
}

fun installApk(context: Context, apkFile: File) {
    try {
        val apkUri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory("android.intent.category.DEFAULT")
            setDataAndType(apkUri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

fun uninstallApkByPackageName(context: Context, packageName: String) = try {
    val intent = Intent(Intent.ACTION_DELETE).apply {
        data = "package:$packageName".toUri()
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
} catch (_: Exception) {
}

class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "${BuildConfig.APPLICATION_ID}.INSTALL_STATUS"

        fun createPendingIntent(context: Context, sessionId: Int): PendingIntent {
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, sessionId, intent, flags)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) {
            return
        }

        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    context.startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
            }

            else -> {
            }
        }
    }
}

suspend fun installApks(context: Context, apkFiles: List<File>): Boolean {
    if (!context.packageManager.canRequestPackageInstalls()) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return false
    }

    apkFiles.forEach {
        if (!it.exists()) {
            return false
        }
    }

    return withContext(Dispatchers.IO) {
        val packageInstaller = context.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        try {
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            apkFiles.forEach { apkFile ->
                session.openWrite(apkFile.name, 0, apkFile.length()).use { outputStream ->
                    apkFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                        session.fsync(outputStream)
                    }
                }
            }

            val pendingIntent = InstallResultReceiver.createPendingIntent(context, sessionId)

            session.commit(pendingIntent.intentSender)
            true
        } catch (_: IOException) {
            session?.abandon()
            false
        } catch (_: Exception) {
            session?.abandon()
            false
        }
    }
}