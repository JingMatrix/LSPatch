package org.lsposed.lspatch.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.lsposed.lspatch.service.ManagerResidentService

/**
 * Brings the manager back up after the two events that end its process without anyone asking it to: the device
 * rebooting, and the manager itself being updated.
 *
 * Starting a foreground service from the background is refused on Android 12 and later, with an exemption for exactly
 * these broadcasts -- which is the reason this exists at all rather than the work being left to the next time someone
 * opens the app.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LSPatch-Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        Log.i(TAG, "Restoring the resident service after ${intent.action}")
        ManagerResidentService.start(context)
    }
}
