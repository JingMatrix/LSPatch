package org.lsposed.lspatch.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.launch
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.util.LSPPackageManager

class AppBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppBroadcastReceiver"

        private val actions =
            setOf(
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED,
            )

        fun register(context: Context) {
            val filter =
                IntentFilter().apply {
                    actions.forEach(::addAction)
                    addDataScheme("package")
                }
            context.registerReceiver(AppBroadcastReceiver(), filter)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in actions) {
            lspApp.globalScope.launch {
                Log.i(TAG, "Received intent: $intent")
                LSPPackageManager.fetchAppList()
                // A module app that was just installed or replaced has a fresh process with no service
                // in it -- the push it would normally get rides on a patched app's module query, which
                // may not happen for hours. The package event is the one moment the manager knows the
                // module app exists and is worth reaching, so it is handed its service here.
                val changed = intent.data?.schemeSpecificPart
                if (
                    intent.action != Intent.ACTION_PACKAGE_REMOVED &&
                        changed != null &&
                        LSPPackageManager.appList.any { it.app.packageName == changed && it.isModule }
                ) {
                    ManagerRemoteServices.pushToCompanionsAsync(listOf(changed))
                }
            }
        }
    }
}
