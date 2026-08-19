package org.lsposed.lspatch.ui.component

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lsposed.lspatch.R
import org.lsposed.lspatch.lspApp
import org.matrix.vector.ui.ActionDrawerHeader
import org.matrix.vector.ui.ActionDrawerItem
import org.matrix.vector.ui.LocalDialogLocalizer

/**
 * What LSPatch needs in order to still be there when a patched app starts, asked once and explained before it is asked.
 *
 * Both grants are the platform's own dialogs, and both are easy to refuse when they arrive out of nowhere — one asks
 * about notifications for an app the person has not seen post one, the other is worded by the system as though the app
 * were misbehaving. Saying first what they are for is the difference between a considered yes and a reflexive no.
 *
 * Neither is required. Refusing costs the ongoing notification (the service still runs, just invisibly) and leaves the
 * manager subject to doze; both remain reachable afterwards from the Shizuku drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayAliveSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current

    var notifications by remember { mutableStateOf(hasNotificationPermission(context)) }
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifications = granted
        }

    val askExemption = rememberBatteryExemptionRequest { exempt = it }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        LocalDialogLocalizer.current {
            ActionDrawerHeader(
                label = stringResource(R.string.stay_alive_title),
                packageName = lspApp.packageName,
                icon = { Icon(Icons.Rounded.BatteryAlert, contentDescription = null) },
                extraContent = {
                    Text(
                        text = stringResource(R.string.stay_alive_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

            ActionDrawerItem(
                icon = if (notifications) Icons.Rounded.Check else Icons.Rounded.Notifications,
                title = stringResource(R.string.stay_alive_notifications),
                subtitle = stringResource(R.string.stay_alive_notifications_summary),
                onClick =
                    if (notifications) null
                    else {
                        { runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) } }
                    },
            )

            ActionDrawerItem(
                icon = if (exempt) Icons.Rounded.Check else Icons.Rounded.BatteryAlert,
                title = stringResource(R.string.background_battery),
                subtitle = stringResource(R.string.background_battery_summary),
                onClick = if (exempt) null else askExemption,
            )

            ActionDrawerItem(
                icon = Icons.Rounded.Check,
                title = stringResource(R.string.stay_alive_done),
                onClick = onDismiss,
            )
        }
    }
}

/**
 * Whether this build even has a notification permission to ask for.
 *
 * Below Android 13 there is none, and a row offering to grant it would be offering nothing.
 */
fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}
    .getOrDefault(false)

/**
 * Opens the platform's own exemption dialog, and says afterwards whether the app is exempt.
 *
 * Asked for a result rather than merely started, because the answer is the person's and arrives long after the ask:
 * reading the state back in the same breath as the request reads it from before the dialog was even drawn, and the row
 * goes on saying the app is not exempt after they have said it may be. The dialog reports a cancelled result whichever
 * button was pressed, so what it left behind is read from the platform rather than taken from the result.
 *
 * The targeted action asks for this one app and is the only form that leads anywhere on most builds; a device that has
 * removed it falls back to the settings list, where the person finds LSPatch themselves. Neither is given
 * FLAG_ACTIVITY_NEW_TASK: an activity started into its own task reports its result immediately and to nobody.
 */
@Composable
fun rememberBatteryExemptionRequest(onAnswered: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onAnswered(isIgnoringBatteryOptimizations(context))
        }
    return {
        val direct =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        if (runCatching { launcher.launch(direct) }.isFailure) {
            runCatching { launcher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
