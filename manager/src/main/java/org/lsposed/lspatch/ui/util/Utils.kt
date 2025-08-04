package org.lsposed.lspatch.ui.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
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
    } catch (e: Exception) {
        Log.e("LSPatch", "installApk", e)
    }
}

fun uninstallApkByPackageName(context: Context, packageName: String) = try {
    val intent = Intent(Intent.ACTION_DELETE).apply {
        data = "package:$packageName".toUri()
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
} catch (e: Exception) {
    Log.e("LSPatch", "uninstallApkByPackageName", e)
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
                Log.d("lspatch", "请求用户确认安装")
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // 安装成功
                Log.d("lspatch", "安装完成")
            }

            else -> {
                // 安装失败
                Log.e("lspatch", "安装失败: $status, $message")
            }
        }
    }
}

/**
 * 安装分包 APK
 * @param context 上下文
 * @param apkFiles APK 文件列表 (包括 base.apk 和 split aab 的 apk)
 * @return 安装是否成功提交（注意：这不代表最终安装成功，只代表安装请求已发出）
 */

suspend fun installApks(context: Context, apkFiles: List<File>): Boolean {
    // 检查权限：应用是否被允许安装未知来源应用
    if (!context.packageManager.canRequestPackageInstalls()) {
        Log.e("lspatch", "没有安装未知来源应用的权限")
        // 引导用户去设置页面开启权限
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return false
    }

    // 检查文件是否存在
    apkFiles.forEach {
        if (!it.exists()) {
            Log.e("lspatch", "APK 文件不存在: ${it.absolutePath}")
            return false
        }
    }

    return withContext(Dispatchers.IO) {
        val packageInstaller = context.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        try {
            // 1. 创建安装会话
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            // 2. 循环将所有 APK 文件写入会话
            apkFiles.forEach { apkFile ->
                Log.d("lspatch", "正在添加 APK 到会话: ${apkFile.name}")
                session.openWrite(apkFile.name, 0, apkFile.length()).use { outputStream ->
                    apkFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                        session.fsync(outputStream)
                    }
                }
            }

            // 3. 创建 PendingIntent 用于接收安装结果
            val pendingIntent = InstallResultReceiver.createPendingIntent(context, sessionId)

            // 4. 提交会话，开始安装
            session.commit(pendingIntent.intentSender)
            Log.d("lspatch", "安装会话已提交，等待用户确认...")
            true
        } catch (e: IOException) {
            Log.e("lspatch", "安装失败 (IO异常)", e)
            // 如果发生错误，放弃会话
            session?.abandon()
            false
        } catch (e: Exception) {
            Log.e("lspatch", "安装失败 (未知异常)", e)
            session?.abandon()
            false
        }
    }
}