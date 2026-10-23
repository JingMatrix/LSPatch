package org.lsposed.lspatch.ui.component.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Ballot
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.R
import org.lsposed.lspatch.config.Configs
import org.lsposed.lspatch.config.MyKeyStore
import org.lsposed.lspatch.ui.component.SheetBody
import org.matrix.vector.ui.sheetRowColors
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * The signing-keystore chooser. Consulted whenever a patched apk is re-signed, so it sits with the
 * patch options rather than on a settings screen of its own.
 *
 * The row opens a bottom sheet, not a tap-anchored popup that then opened a cramped centre dialog.
 * The two choices and -- when the custom one is picked -- the form to configure it now live in one
 * surface the width of the screen, with each field carrying its own error instead of four sharing a
 * single line at the top. Built-in is one tap; custom reveals its form in place and validates the
 * key before it is accepted, so a wrong password is caught here rather than at the end of a patch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoreSetting() {
    var showSheet by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { showSheet = true },
        leadingContent = { Icon(Icons.Outlined.Ballot, contentDescription = null) },
        supportingContent = {
            Text(
                stringResource(
                    if (MyKeyStore.useDefault) R.string.settings_keystore_default
                    else R.string.settings_keystore_custom
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
    ) { Text(stringResource(R.string.settings_keystore)) }

    if (showSheet) {
        KeystoreSheet(onDismiss = { showSheet = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeystoreSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    // Which choice is showing, seeded from what is in force. Local to the sheet: nothing is written
    // until the custom form is validated or built-in is applied, so opening the sheet and closing it
    // changes nothing.
    var custom by rememberSaveable { mutableStateOf(!MyKeyStore.useDefault) }

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
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Ballot,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.settings_keystore),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChoiceCard(
                        selected = !custom,
                        icon = Icons.Rounded.Shield,
                        title = stringResource(R.string.settings_keystore_default),
                        subtitle = stringResource(R.string.settings_keystore_default_desc),
                        onClick = {
                            // Built-in is the whole choice, so it commits and closes rather than
                            // waiting for a confirm the custom form needs but this does not.
                            custom = false
                            scope.launch {
                                MyKeyStore.reset()
                                sheetState.hide()
                                onDismiss()
                            }
                        },
                    )
                    ChoiceCard(
                        selected = custom,
                        icon = Icons.Rounded.Key,
                        title = stringResource(R.string.settings_keystore_custom),
                        subtitle = stringResource(R.string.settings_keystore_custom_desc),
                        onClick = { custom = true },
                    )
                }

                AnimatedVisibility(visible = custom) {
                    CustomKeystoreForm(
                        onSaved = {
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                            }
                        },
                    )
                }

            }
        }
    }
}


/**
 * The custom-keystore form, inline in the sheet.
 *
 * Each field owns its error, so a wrong alias points at the alias rather than at a single line above
 * four fields that never said which one was wrong. The key is validated -- loaded, the alias found,
 * the alias password tried -- before it is accepted, because the alternative is a patch that runs
 * for thirty seconds and then fails at the signing step with the same information available now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomKeystoreForm(onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fileName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf(Configs.keyStorePassword) }
    var alias by rememberSaveable { mutableStateOf(Configs.keyStoreAlias) }
    var aliasPassword by rememberSaveable { mutableStateOf(Configs.keyStoreAliasPassword) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }

    var fileError by rememberSaveable { mutableStateOf<Int?>(null) }
    var passwordError by rememberSaveable { mutableStateOf<Int?>(null) }
    var aliasError by rememberSaveable { mutableStateOf<Int?>(null) }
    var aliasPasswordError by rememberSaveable { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                MyKeyStore.tmpFile.outputStream().use { output -> input?.copyTo(output) }
            }
        }
        fileName = uri.lastPathSegment?.substringAfterLast('/') ?: uri.path.orEmpty()
        fileError = null
    }

    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The file picker as a row rather than a read-only text field pretending to be tappable.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { picker.launch("*/*") }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = if (fileError != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = fileName.ifBlank { stringResource(R.string.settings_keystore_file) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (fileName.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                fileError?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        val visual = if (reveal) VisualTransformation.None else PasswordVisualTransformation()
        KeyField(
            value = password,
            onChange = { password = it; passwordError = null },
            label = stringResource(R.string.settings_keystore_password),
            leading = Icons.Rounded.Lock,
            errorRes = passwordError,
            visual = visual,
            trailing = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = null,
                    )
                }
            },
        )
        KeyField(
            value = alias,
            onChange = { alias = it; aliasError = null },
            label = stringResource(R.string.settings_keystore_alias),
            leading = Icons.Rounded.Key,
            errorRes = aliasError,
        )
        KeyField(
            value = aliasPassword,
            onChange = { aliasPassword = it; aliasPasswordError = null },
            label = stringResource(R.string.settings_keystore_alias_password),
            leading = Icons.Rounded.Lock,
            errorRes = aliasPasswordError,
            visual = visual,
        )

        Button(
            onClick = {
                fileError = null; passwordError = null; aliasError = null; aliasPasswordError = null
                if (fileName.isBlank() && !MyKeyStore.tmpFile.exists()) {
                    fileError = R.string.settings_keystore_wrong_keystore
                    return@Button
                }
                validating = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        validate(password, alias, aliasPassword)
                    }
                    validating = false
                    when (result) {
                        KeyResult.Ok -> {
                            MyKeyStore.setCustom(password, alias, aliasPassword)
                            onSaved()
                        }
                        KeyResult.BadKeystore -> fileError = R.string.settings_keystore_wrong_keystore
                        KeyResult.BadPassword -> passwordError = R.string.settings_keystore_wrong_password
                        KeyResult.BadAlias -> aliasError = R.string.settings_keystore_wrong_alias
                        KeyResult.BadAliasPassword ->
                            aliasPasswordError = R.string.settings_keystore_wrong_alias_password
                    }
                }
            },
            enabled = !validating,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(stringResource(android.R.string.ok))
        }
        Spacer(Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    leading: ImageVector,
    errorRes: Int?,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        leadingIcon = { Icon(leading, contentDescription = null) },
        trailingIcon = trailing,
        singleLine = true,
        isError = errorRes != null,
        visualTransformation = visual,
        keyboardOptions = KeyboardOptions.Default,
        supportingText = errorRes?.let { { Text(stringResource(it)) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private enum class KeyResult { Ok, BadKeystore, BadPassword, BadAlias, BadAliasPassword }

/** Loads the picked keystore and confirms the alias and its password actually open. */
private fun validate(password: String, alias: String, aliasPassword: String): KeyResult {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    try {
        MyKeyStore.tmpFile.inputStream().use { keyStore.load(it, password.toCharArray()) }
    } catch (e: IOException) {
        return if (e.message == "KeyStore integrity check failed.") KeyResult.BadPassword
        else KeyResult.BadKeystore
    } catch (e: GeneralSecurityException) {
        return KeyResult.BadKeystore
    }
    if (!keyStore.containsAlias(alias)) return KeyResult.BadAlias
    return try {
        keyStore.getKey(alias, aliasPassword.toCharArray())
        KeyResult.Ok
    } catch (e: GeneralSecurityException) {
        KeyResult.BadAliasPassword
    }
}
