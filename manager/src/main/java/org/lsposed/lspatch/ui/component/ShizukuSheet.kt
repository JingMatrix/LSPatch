package org.lsposed.lspatch.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.lsposed.lspatch.R
import org.lsposed.lspatch.config.Configs
import org.lsposed.lspatch.manager.ModuleDeliveryReports
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi
import org.matrix.vector.ui.ActionDrawerHeader
import org.matrix.vector.ui.ActionDrawerItem
import org.matrix.vector.ui.LocalDialogLocalizer

/** Shizuku's own package: what the header names, and what the last row opens. */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/**
 * Everything LSPatch does with Shizuku, in the drawer the System card's Shizuku row opens.
 *
 * That row used to launch the Shizuku app, which answered the wrong question: what its reader wants is what LSPatch is
 * doing with the shell, not Shizuku's own screen. Opening the app is still here -- as one row among the others, where
 * it reads as one choice rather than as the only one.
 *
 * A drawer rather than a page because every one of these is a single act on a single subject, which is exactly what a
 * package's action drawer already is; the same header, the same rows, and no screen to navigate away from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reports by ModuleDeliveryReports.reports.collectAsStateWithLifecycle()

    val granted = ShizukuApi.isPermissionGranted
    val version = if (granted) ShizukuApi.serverVersion() else null

    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val askExemption = rememberBatteryExemptionRequest { exempt = it }
    var outcomes by remember { mutableStateOf<List<ShizukuApi.ShellOutcome>?>(null) }
    var asking by remember { mutableStateOf(false) }

    val openShizuku = remember { LSPPackageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        // A sheet is its own window, and a new window gets a fresh set of Android composition locals
        // taken from that window's context -- which drops the in-app language override on the way in.
        LocalDialogLocalizer.current {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionDrawerHeader(
                    label = "Shizuku",
                    packageName = SHIZUKU_PACKAGE,
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                    extraContent = {
                        Text(
                            text =
                                if (version != null) stringResource(R.string.shizuku_available) + " · API $version"
                                else stringResource(R.string.shizuku_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                        )
                    },
                )
                // Beside the name it belongs to rather than among the rows below: everything in that
                // list is something LSPatch does with the shell, and this one leaves for another app.
                if (openShizuku != null) {
                    IconButton(
                        onClick = {
                            onDismiss()
                            runCatching { context.startActivity(openShizuku) }
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.shizuku_open_app),
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

            if (!granted) {
                ActionDrawerItem(
                    icon = Icons.Rounded.Warning,
                    title = stringResource(R.string.shizuku_failure_grant),
                    subtitle = stringResource(R.string.shizuku_failure_hint_not_granted),
                    tint = MaterialTheme.colorScheme.error,
                ) {
                    ShizukuApi.requestPermission()
                }
            }

            // A switch, so it is announced as one: the row carries state as well as an action, and
            // ActionDrawerItem leaves the behaviour to the caller for exactly this case.
            val keepAlive = Configs.keepManagerAlive && granted
            ActionDrawerItem(
                icon = Icons.Rounded.Restore,
                title = stringResource(R.string.background_keep_alive),
                subtitle =
                    if (granted) stringResource(R.string.background_keep_alive_summary)
                    else stringResource(R.string.background_keep_alive_needs_shizuku),
                modifier =
                    Modifier.toggleable(
                        value = keepAlive,
                        enabled = granted,
                        role = Role.Switch,
                        // The watchdog runs inside the shell process; with no Shizuku, nothing to arm.
                        onValueChange = { wanted -> Configs.keepManagerAlive = wanted },
                    ),
                trailing = { Switch(checked = keepAlive, onCheckedChange = null, enabled = granted) },
            )

            if (granted) {
                // Only what this device knows about and said no to. A limit it has never heard of is
                // not one it withheld, and naming it here would send the reader looking for a setting
                // their phone does not have.
                val refused = outcomes?.filter { it.verdict == ShizukuApi.ShellVerdict.Refused }
                ActionDrawerItem(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.background_whitelist),
                    subtitle =
                        when {
                            asking -> stringResource(R.string.background_whitelist_running)
                            // Named rather than counted: which limit this device would not lift is the
                            // whole of what the reader learns from having asked.
                            refused?.isEmpty() == true -> stringResource(R.string.background_whitelist_done)
                            refused != null ->
                                stringResource(
                                    R.string.background_whitelist_refused,
                                    refused.joinToString(", ") { it.label },
                                )
                            else -> stringResource(R.string.background_whitelist_summary)
                        },
                    tint = if (refused?.isNotEmpty() == true) MaterialTheme.colorScheme.error else null,
                ) {
                    if (!asking) {
                        asking = true
                        scope.launch {
                            outcomes = ShizukuApi.exemptFromBackgroundLimits(context.packageName)
                            exempt = isIgnoringBatteryOptimizations(context)
                            asking = false
                        }
                    }
                }
            }

            ActionDrawerItem(
                icon = Icons.Rounded.BatteryAlert,
                title = stringResource(R.string.background_battery),
                subtitle =
                    if (exempt) stringResource(R.string.background_battery_done)
                    else stringResource(R.string.background_battery_summary),
                // A statement once it is granted: there is nothing left to ask for.
                onClick = if (exempt) null else askExemption,
            )

            if (reports.isNotEmpty()) {
                ActionDrawerItem(
                    icon = Icons.Rounded.Warning,
                    title = stringResource(R.string.background_reports_title),
                    // Resolved in composition, one line per report, and only then joined: a string read
                    // from a context at the moment it is needed is read outside composition, where a
                    // configuration change since the screen was drawn has not been seen. `map` is
                    // inline, so each line is still resolved where composition can reach it.
                    subtitle =
                        reports
                            .map {
                                stringResource(
                                    R.string.background_reports_line,
                                    it.packageName,
                                    it.fallbacks,
                                )
                            }
                            .joinToString("\n"),
                    tint = MaterialTheme.colorScheme.error,
                ) {
                    ModuleDeliveryReports.clear()
                }
            }
        }
    }
}
