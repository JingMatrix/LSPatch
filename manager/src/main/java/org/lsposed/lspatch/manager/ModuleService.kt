package org.lsposed.lspatch.manager

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.launch
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.util.LSPPackageManager

class ModuleService : Service() {

    companion object {
        private const val TAG = "ModuleService"

        /** What a host reports about launches it had to make without this manager; see [ModuleDeliveryReports]. */
        private const val EXTRA_FALLBACKS = "fallbackLaunches"
        private const val EXTRA_LAST_FALLBACK_AT = "lastFallbackAt"
    }

    override fun onBind(intent: Intent): IBinder? {
        val packageName = intent.getStringExtra("packageName") ?: return null
        // Who the caller is cannot be established here: onBind does not run inside the binder
        // transaction that triggered it, so Binder.getCallingUid() answers with this manager's own uid
        // rather than the app's. It is established where it can be -- every call on ManagerService
        // resolves the calling uid while a transaction is live, and serves only that uid's modules --
        // so this hands out a binder that is harmless until the caller identifies itself by making a
        // call on it.
        Log.i(TAG, "$packageName requests binder")
        recordDelivery(packageName, intent)
        // After the binder, never before it: this bind may be what created the process, and the app
        // on the other end is holding its own startup open until this call returns. The rest of the
        // manager's start-up work is posted so it lands once that app is on its way.
        lspApp.globalScope.launch { lspApp.startBackgroundWork() }
        return ManagerService.asBinder()
    }

    /**
     * Takes a host's count of the launches it had to make without this manager.
     *
     * The package name is the caller's own claim, for the reason above, so it is kept only when it names an app this
     * device has actually patched. That is as far as it can be checked, and it is enough for what the count is: a
     * number shown to the person who owns the device, about their own apps, that changes nothing but what they are
     * told.
     */
    private fun recordDelivery(packageName: String, intent: Intent) {
        val fallbacks = intent.getIntExtra(EXTRA_FALLBACKS, 0)
        if (fallbacks <= 0) return
        val patched =
            LSPPackageManager.appList.any {
                it.app.packageName == packageName && it.app.metaData?.containsKey("lspatch") == true
            }
        if (!patched) {
            Log.w(TAG, "Ignoring a delivery report from $packageName, which is not a patched app here")
            return
        }
        ModuleDeliveryReports.record(packageName, fallbacks, intent.getLongExtra(EXTRA_LAST_FALLBACK_AT, 0L))
    }
}
