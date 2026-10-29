package org.lsposed.lspatch.ui.component.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.lsposed.lspatch.R
import org.lsposed.lspatch.share.Constants
import org.lsposed.lspatch.util.GmsReplacements
import org.matrix.vector.ui.AppIcon
import org.matrix.vector.ui.PackageRow
import org.matrix.vector.ui.ToggleRow
import org.lsposed.lspatch.ui.component.SheetBody
import org.matrix.vector.ui.sheetRowColors

/**
 * How the patched app reaches Google Play services.
 *
 * Three states, and they are what the patch actually does rather than a property it sets. Leaving it alone keeps
 * Google's own package, which refuses a re-signed caller wherever it checks the certificate. Redirecting sends the
 * app's calls to a replacement installed under its own name, which is not one change but three: intents and
 * provider calls are rewritten at runtime, the push names in the manifest are rewritten to that vendor for good,
 * and the original certificate is declared for it to report. Declaring the certificate alone is for a replacement
 * that installs as Google Play services itself, where nothing needs redirecting and a ROM does the reporting.
 *
 * Each choice therefore states its consequences instead of naming a mechanism: the cost of redirecting is a
 * manifest the app keeps, so the reader is told that before choosing rather than after patching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmsSetting(
    replacement: String?,
    declareFakeSignature: Boolean,
    keepPushWithGms: Boolean,
    needsPackageVisibility: Boolean,
    onChange: (replacement: String?, fake: Boolean) -> Unit,
    onKeepPushWithGms: (Boolean) -> Unit,
    onAddPackageVisibility: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { showSheet = true },
        leadingContent = { Icon(Icons.Rounded.CloudSync, contentDescription = null) },
        supportingContent = {
            Text(
                replacement?.let { stringResource(R.string.patch_gms_redirect_to, GmsReplacements.shortName(it)) }
                    ?: stringResource(
                        if (declareFakeSignature) R.string.patch_gms_declare_only
                        else R.string.patch_gms_unchanged
                    )
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = sheetRowColors,
    ) { Text(stringResource(R.string.patch_gms)) }

    if (showSheet) {
        GmsSheet(
            replacement = replacement,
            declareFakeSignature = declareFakeSignature,
            keepPushWithGms = keepPushWithGms,
            needsPackageVisibility = needsPackageVisibility,
            onChange = onChange,
            onKeepPushWithGms = onKeepPushWithGms,
            onAddPackageVisibility = onAddPackageVisibility,
            onDismiss = { showSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GmsSheet(
    replacement: String?,
    declareFakeSignature: Boolean,
    keepPushWithGms: Boolean,
    needsPackageVisibility: Boolean,
    onChange: (replacement: String?, fake: Boolean) -> Unit,
    onKeepPushWithGms: (Boolean) -> Unit,
    onAddPackageVisibility: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // Which card is open, not what is stored: picking "redirect" has to show the list before there is anything to
    // store, and a reader who opens it and changes nothing must leave the patch as it was.
    var redirecting by remember { mutableStateOf(replacement != null) }

    // The sheet consumes the window insets by default, so the body's imePadding would see nothing left to
    // apply; the inset is passed through for SheetBody to act on instead.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        SheetBody {
            Column(Modifier.padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.patch_gms),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(R.string.patch_gms_why),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                )

                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Two cards, both of which can be turned back off, and at most one on. Doing nothing is the
                    // state with neither selected rather than a card of its own: it is the absence of a change, and
                    // giving it a row made the reader choose something in order to choose nothing.
                    ChoiceCard(
                        selected = declareFakeSignature,
                        icon = Icons.Rounded.VerifiedUser,
                        title = stringResource(R.string.patch_gms_declare_only),
                        subtitle = stringResource(R.string.patch_gms_declare_only_desc),
                        role = Role.Checkbox,
                        onClick = {
                            redirecting = false
                            onChange(null, !declareFakeSignature)
                        },
                    )
                    ChoiceCard(
                        selected = redirecting,
                        icon = Icons.Rounded.SwapHoriz,
                        title = stringResource(R.string.patch_gms_redirect),
                        subtitle = stringResource(R.string.patch_gms_redirect_desc),
                        role = Role.Checkbox,
                        onClick = {
                            if (redirecting) {
                                redirecting = false
                                onChange(null, false)
                            } else {
                                // Opening the field is not yet a destination: this only drops the other card's
                                // choice, and the package stays as it is until one is typed or picked.
                                redirecting = true
                                onChange(replacement, false)
                            }
                        },
                    )
                }

                AnimatedVisibility(visible = redirecting) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        ReplacementField(replacement = replacement, onChange = { onChange(it, false) })
                        if (replacement != null && needsPackageVisibility) {
                            Text(
                                text = stringResource(R.string.patch_gms_needs_visibility),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onAddPackageVisibility() }
                                        .padding(vertical = 8.dp),
                            )
                        }
                    }
                }

                // Only once there is a replacement: with none, every push already stays with Google Play services.
                AnimatedVisibility(visible = redirecting && replacement != null) {
                    ToggleRow(
                        title = stringResource(R.string.patch_gms_keep_push),
                        icon = Icons.Rounded.NotificationsActive,
                        subtitle = stringResource(R.string.patch_gms_keep_push_desc),
                        checked = keepPushWithGms,
                        onCheckedChange = onKeepPushWithGms,
                    )
                }
            }
        }
    }
}

/**
 * Where to send the app's Google calls.
 *
 * A package name typed in, with what this device has offered as a shortcut. Nothing is pre-chosen: the builds that
 * exist are not a list this release can hold, and a name shipped here would outlive the project it points at. The
 * shortcut only appears when the device actually has something to offer, so a reader with none sees a plain field
 * rather than an empty menu.
 *
 * Only the shape is required. Whether the build is present is a question for the device that runs the apk, not the
 * one that patches it, so an absent package is reported and still accepted.
 */
@Composable
private fun ReplacementField(replacement: String?, onChange: (String?) -> Unit) {
    val onDevice = remember { GmsReplacements.installed() }
    var text by rememberSaveable { mutableStateOf(replacement.orEmpty()) }
    var listOpen by remember { mutableStateOf(false) }

    val typed = text.trim()
    val wellFormed = typed.isNotEmpty() && Constants.isGmsReplacement(typed)
    val malformed = text.isNotBlank() && !wellFormed
    val installed = remember(typed) { wellFormed && GmsReplacements.isInstalled(typed) }

    fun accept(value: String) {
        text = value
        val candidate = value.trim()
        // Anything that is not yet a usable package clears the choice instead of leaving the last valid one
        // stored: otherwise editing a name that was already accepted leaves the field showing one package while
        // the patch still carries the other, and the reader is given no sign of it.
        onChange(if (Constants.isGmsReplacement(candidate)) candidate else null)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { accept(it) },
        label = { Text(stringResource(R.string.patch_gms_pick)) },
        placeholder = { Text(stringResource(R.string.patch_gms_custom_hint)) },
        isError = malformed,
        singleLine = true,
        trailingIcon =
            if (onDevice.isEmpty()) null
            else
                ({
                    IconButton(onClick = { listOpen = !listOpen }) {
                        Icon(
                            if (listOpen) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                        )
                    }
                }),
        supportingText =
            when {
                malformed -> ({ Text(stringResource(R.string.patch_gms_custom_invalid)) })
                wellFormed ->
                    ({
                        Text(
                            text =
                                stringResource(
                                    if (installed) R.string.patch_gms_installed
                                    else R.string.patch_gms_not_installed
                                ),
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (installed) 1f else 0.45f
                                ),
                        )
                    })
                else -> null
            },
        modifier = Modifier.fillMaxWidth(),
    )

    // Opened in place rather than as a popup: a popup anchored from inside a sheet is positioned against the
    // sheet's own window, not the field.
    AnimatedVisibility(visible = listOpen) {
        Column {
            onDevice.forEach { candidate ->
                PackageRow(
                    icon = { AppIcon(applicationInfo = candidate.applicationInfo, contentDescription = candidate.label) },
                    label = candidate.label,
                    packageName = candidate.packageName,
                    checked = typed == candidate.packageName,
                    modifier =
                        Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                            accept(candidate.packageName)
                            listOpen = false
                        },
                ) {
                    // These builds are updated often, and two of them side by side are otherwise
                    // indistinguishable; the version is what tells the reader which one this is.
                    Text(
                        text =
                            (candidate.versionName ?: "?") + "  \u00b7  " +
                                stringResource(R.string.patch_gms_version_code, candidate.versionCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
