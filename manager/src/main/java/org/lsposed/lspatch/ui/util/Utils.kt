package org.lsposed.lspatch.ui.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

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