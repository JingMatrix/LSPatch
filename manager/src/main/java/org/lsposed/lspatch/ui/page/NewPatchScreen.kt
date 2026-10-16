package org.lsposed.lspatch.ui.page

import org.lsposed.lspatch.ui.component.rememberAppIcon
import org.matrix.vector.ui.show
import org.matrix.vector.ui.SnackbarTone
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.outlined.Ballot
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.lsposed.lspatch.R
import org.lsposed.lspatch.config.Configs
import org.lsposed.lspatch.config.MyKeyStore
import org.lsposed.lspatch.data.model.ModuleBinding
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.PatchOrigin
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchStep
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.data.model.color
import org.lsposed.lspatch.data.model.labelRes
import org.lsposed.lspatch.data.repository.PatchJobHost
import org.lsposed.lspatch.data.repository.PatchOutputStore
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.ui.component.DetailTopBar
import org.lsposed.lspatch.ui.component.PatchLog
import org.lsposed.lspatch.ui.component.PatchStepList
import org.lsposed.lspatch.ui.component.rememberExportApk
import org.lsposed.lspatch.ui.component.settings.KeystoreSetting
import org.lsposed.lspatch.ui.navigation.ModuleSelection
import org.lsposed.lspatch.ui.navigation.SelectModules
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi
import org.matrix.vector.ui.PackageRow
import org.matrix.vector.ui.SharedAlertDialog
import org.matrix.vector.ui.SheetAction
import org.matrix.vector.ui.ToggleRow
import org.matrix.vector.ui.copyToClipboard
import org.matrix.vector.ui.navigation.Navigator

/**
 * Configures one patch, then shows it happening.
 *
 * One screen for deciding and for watching, because it is one act with a pause in it: the app being patched never
 * changes, so leaving it named at the top and swapping the body under it says more than pushing a second screen would.
 *
 * The patch itself belongs to `PatchJobHost`, not to this screen. Leaving is therefore just leaving -- the job carries
 * on and Manage shows it running -- rather than being refused, which is what the old screen did by swallowing the back
 * gesture outright.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPatchScreen(navigator: Navigator, token: String) {
    // Built with its token rather than reading one out of a SavedStateHandle: the back stack holds
    // routes, not bundles, so the token is a value the screen already has.
    val viewModel = viewModel { org.lsposed.lspatch.ui.viewmodel.NewPatchViewModel(token) }
    val step by PatchJobHost.step.collectAsStateWithLifecycle()
    val logLines by PatchJobHost.log.collectAsStateWithLifecycle()
    val request = viewModel.request
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current

    // What the module picker chose, applied once on the way back -- see ModuleSelection.
    val selection by ModuleSelection.pending.collectAsState()
    LaunchedEffect(selection) { ModuleSelection.consume(token)?.let { viewModel.addInstalled(it) } }

    var logExpanded by remember { mutableStateOf(false) }
    var logWrap by rememberSaveable { mutableStateOf(false) }
    var logMenu by remember { mutableStateOf(false) }
    val active by PatchJobHost.active.collectAsStateWithLifecycle()
    // The job on show is only this screen's if it is this screen's request. Another patch may be
    // running — started elsewhere, or still finishing after this screen was left and reopened on a
    // different app — and showing its stages here would attribute them to the wrong app.
    val mine = active?.token == token
    val configuring = !mine || step is PatchStep.Idle
    val showLog = mine && (logExpanded || step is PatchStep.Failed)
    // Everything below reads these rather than the host's raw state, so a job belonging to another
    // app can never render as this one's.
    val shown = if (mine) step else PatchStep.Idle
    val lines = if (mine) logLines else emptyList()
    val hasError = lines.any { it.level == android.util.Log.ERROR }
    val copied = stringResource(R.string.patch_log_copied)
    val exporter = rememberExportApk()

    if (request == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!viewModel.loading) {
                Text(stringResource(R.string.error_unknown))
            } else {
                CircularProgressIndicator()
            }
        }
        return
    }

    Scaffold(
        topBar = {
            DetailTopBar(
                label = request.label,
                packageName = request.packageName,
                onBack = { navigator.back() },
                actions = {
                    if (lines.isNotEmpty()) {
                        IconButton(onClick = { logExpanded = !logExpanded }) {
                            BadgedBox(
                                badge = {
                                    // A patch that failed says so in the log, and a log the reader
                                    // has to think to open is a log they will not open.
                                    if (hasError && !showLog) {
                                        Badge(
                                            modifier = Modifier.size(6.dp),
                                            containerColor = MaterialTheme.colorScheme.error,
                                        ) {}
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Rounded.Terminal,
                                    contentDescription =
                                        stringResource(
                                            if (showLog) R.string.patch_hide_log else R.string.patch_show_log
                                        ),
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { logMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(expanded = logMenu, onDismissRequest = { logMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.logs_word_wrap)) },
                                    leadingIcon = {
                                        Icon(
                                            if (logWrap) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        logWrap = !logWrap
                                        logMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.patch_copy_log)) },
                                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                    onClick = {
                                        logMenu = false
                                        copyToClipboard(context, PatchJobHost.report())
                                        scope.launch { snackbarHost.show(copied, SnackbarTone.Success) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.patch_share_log)) },
                                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                    onClick = {
                                        logMenu = false
                                        // Sent as text rather than a file: a patch report is a few
                                        // kilobytes, and a file needs a provider grant that every
                                        // target has to be willing to take.
                                        val send =
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "LSPatch report: " + request.packageName)
                                                putExtra(Intent.EXTRA_TEXT, PatchJobHost.report())
                                            }
                                        runCatching {
                                            context.startActivity(
                                                Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            PatchBar(
                step = shown,
                request = request,
                onPatch = {
                    logExpanded = false
                    PatchJobHost.start(request)
                },
                onInstall = { PatchJobHost.install() },
                onUninstallAndInstall = { PatchJobHost.install(uninstallFirst = true) },
                onRetry = { PatchJobHost.retry() },
                onExport = { files -> exporter.export(request.label, files) },
                onOpen = {
                    LSPPackageManager.getLaunchIntentForPackage(request.packageName)?.let {
                        runCatching { context.startActivity(it) }
                    }
                    PatchJobHost.acknowledge()
                    navigator.back()
                },
                onDone = {
                    PatchJobHost.acknowledge()
                    navigator.back()
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                showLog ->
                    PatchLog(
                        lines = lines,
                        terminal = shown.terminal,
                        wrap = logWrap,
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                configuring ->
                    ConfigureBody(
                        request = request,
                        modules = viewModel.modules,
                        onMode = viewModel::setMode,
                        onDebuggable = viewModel::setDebuggable,
                        onVersionCode = viewModel::setVersionCodeOverride,
                        onInjectDex = viewModel::setInjectDex,
                        onSigBypass = viewModel::setSigBypassLevel,
                        onRemoveModule = viewModel::removeModule,
                        onAddInstalled = {
                            navigator.go(SelectModules(token, viewModel.modules.map { it.packageName }))
                        },
                        onAddFromStorage = { added -> viewModel.addModules(added) },
                        onLabel = viewModel::setLabelOverride,
                        onExtractNativeLibs = viewModel::setExtractNativeLibs,
                        onCleartext = viewModel::setUsesCleartextTraffic,
                        onAddPermission = viewModel::addPermission,
                        onRemovePermission = viewModel::removePermission,
                        onInjectDocumentsProvider = viewModel::setInjectDocumentsProvider,
                        onUseMicroG = viewModel::setUseMicroG,
                    )
                else ->
                    Column(
                        Modifier.fillMaxSize()
                            // Scrollable, because an expanded step can be taller than the screen -- and
                            // without this the overflow simply had nowhere to go.
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        PatchStepList(
                            step = shown,
                            modules = request.effectiveModules.map { it.packageName },
                            lines = lines,
                        )
                    }
            }
        }
    }
}

/**
 * The patch's settings.
 *
 * Built around the fact that these decisions are not equal. The mode is architectural -- it decides whether the app can
 * run without the manager, and whether its modules can ever be changed again without rebuilding it -- while debuggable,
 * version-code override and the rest are expert flags that most patches leave exactly as they are. Presenting all of
 * them as one flat list of rows made a one-decision task look like a six-decision form, so the mode is given the room
 * it deserves and the flags are folded away behind a summary that says what they currently are.
 */
@Composable
private fun ConfigureBody(
    request: PatchRequest,
    modules: List<ModuleBinding>,
    onMode: (PatchMode) -> Unit,
    onDebuggable: (Boolean) -> Unit,
    onVersionCode: (Int?) -> Unit,
    onInjectDex: (Boolean) -> Unit,
    onSigBypass: (Int) -> Unit,
    onRemoveModule: (String) -> Unit,
    onAddInstalled: () -> Unit,
    onAddFromStorage: (List<ModuleBinding>) -> Unit,
    onLabel: (String) -> Unit,
    onExtractNativeLibs: (Boolean) -> Unit,
    onCleartext: (Boolean) -> Unit,
    onAddPermission: (String) -> Unit,
    onRemovePermission: (String) -> Unit,
    onInjectDocumentsProvider: (Boolean) -> Unit,
    onUseMicroG: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val notAModule = stringResource(R.string.patch_module_not_a_module)
    var advanced by rememberSaveable { mutableStateOf(false) }

    val storageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                LSPPackageManager.getAppInfoFromApks(uris).onSuccess { infos ->
                    val bindings = infos.mapNotNull {
                        LSPPackageManager.moduleBindingFromFile(java.io.File(it.app.sourceDir))
                    }
                    if (bindings.size < infos.size) {
                        snackbarHost.show(notAModule.format(infos.first().label), SnackbarTone.Failure)
                    }
                    onAddFromStorage(bindings)
                }
            }
        }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        TargetStrip(request)

        SectionLabel(stringResource(R.string.patch_mode))
        // Two cards rather than two chips. A chip row reads as a filter over something else; this is
        // the one choice on the screen that changes what the patched app fundamentally is, and the
        // difference between the modes is a sentence, not a word.
        ModeCard(
            selected = request.mode == PatchMode.Local,
            icon = Icons.Rounded.Api,
            title = stringResource(R.string.patch_local),
            summary = stringResource(R.string.patch_local_summary),
            onClick = { onMode(PatchMode.Local) },
        )
        ModeCard(
            selected = request.mode == PatchMode.Integrated,
            icon = Icons.Rounded.WorkOutline,
            title = stringResource(R.string.patch_integrated),
            summary = stringResource(R.string.patch_integrated_summary),
            onClick = { onMode(PatchMode.Integrated) },
        )
        ModeComparison(request.mode)
        if (request.mode == PatchMode.Local && modules.isNotEmpty()) {
            // Said while the choice is still open, not after the set has been destroyed: the modules
            // stay in the draft either way, but a Local patch will not carry them.
            Text(
                text = stringResource(R.string.patch_local_drop_warning, modules.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (request.mode == PatchMode.Local) {
            // The obvious next question in this mode, answered before it is asked: there is no
            // module list here because there is nothing to bake in, and the choice is made later
            // -- on the app's own page, at any time, without patching it again.
            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(R.string.appdetail_modules))
            SettingsGroup {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.patch_local_modules_later),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (request.mode == PatchMode.Integrated) {
            Spacer(Modifier.height(6.dp))
            SectionLabel(
                text = stringResource(R.string.patch_embed_modules),
                trailing = if (modules.isEmpty()) null else modules.size.toString(),
            )
            SettingsGroup {
                if (modules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.patch_modules_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    )
                } else {
                    modules.forEach { module ->
                        EmbeddedModuleRow(module = module, onRemove = { onRemoveModule(module.packageName) })
                    }
                }
                GroupDivider()
                SheetAction(
                    title = stringResource(R.string.patch_modules_add_installed),
                    icon = Icons.Rounded.Extension,
                    onClick = onAddInstalled,
                )
                SheetAction(
                    title = stringResource(R.string.patch_modules_add_storage),
                    icon = Icons.Rounded.FolderOpen,
                    onClick = { storageLauncher.launch(APK_AND_BUNDLE_TYPES) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        // Folded away by default, with the current state spelled out on the header. Six expert
        // controls above the fold make a routine patch look like a form to be filled in, and the
        // defaults here are the right answer almost every time.
        AdvancedSection(
            expanded = advanced,
            chips = advancedChips(request),
            onToggle = { advanced = !advanced },
        ) {
            Column(Modifier.padding(bottom = 4.dp)) {
                // Patch mechanics: how the loader is delivered and how signature checks are answered.
                // Neither writes the app's manifest, so they are kept apart from the overrides below.
                GroupDivider()
                SigBypassRow(level = request.sigBypassLevel, onSelect = onSigBypass)
                ToggleRow(
                    title = stringResource(R.string.patch_inject_dex),
                    icon = Icons.Rounded.Code,
                    subtitle = stringResource(R.string.patch_inject_dex_desc),
                    checked = request.injectDex,
                    onCheckedChange = onInjectDex,
                )
                GroupDivider()
                // Manifest overrides: attributes written into the patched apk's manifest rather than
                // the runtime config, so they reset on a re-patch and each field starts from the app's
                // own value. Grouped together in the order the manifest declares them -- identity,
                // then the boolean flags -- so this list and the patched app's detail page read alike.
                var versionCode by rememberSaveable {
                    mutableStateOf(request.versionCodeOverride?.toString().orEmpty())
                }
                OutlinedTextField(
                    value = versionCode,
                    onValueChange = { entered ->
                        // Digits only, and only while they still fit an int. A keystroke that would
                        // overflow is dropped rather than accepted: the field must never show a
                        // number the request cannot carry, which is what an unparseable entry --
                        // read back as "no override at all" -- would amount to.
                        val digits = entered.filter { it.isDigit() }
                        if (digits.isEmpty() || digits.toIntOrNull() != null) {
                            versionCode = digits
                            onVersionCode(digits.toIntOrNull())
                        }
                    },
                    label = { Text(stringResource(R.string.patch_manifest_version_code)) },
                    placeholder = { Text(stringResource(R.string.patch_manifest_version_code_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Layers, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                var label by rememberSaveable { mutableStateOf(request.labelOverride.orEmpty()) }
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        onLabel(it)
                    },
                    label = { Text(stringResource(R.string.patch_manifest_label)) },
                    placeholder = { Text(stringResource(R.string.patch_manifest_label_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                ToggleRow(
                    title = stringResource(R.string.patch_debuggable),
                    icon = Icons.Rounded.BugReport,
                    checked = request.debuggable,
                    onCheckedChange = onDebuggable,
                )
                ToggleRow(
                    title = stringResource(R.string.patch_manifest_extract_libs),
                    icon = Icons.Rounded.Archive,
                    subtitle = stringResource(R.string.patch_manifest_extract_libs_desc),
                    checked = request.extractNativeLibs,
                    onCheckedChange = onExtractNativeLibs,
                )
                ToggleRow(
                    title = stringResource(R.string.patch_manifest_cleartext),
                    icon = Icons.Rounded.Http,
                    subtitle = stringResource(R.string.patch_manifest_cleartext_desc),
                    checked = request.usesCleartextTraffic,
                    onCheckedChange = onCleartext,
                )
                ToggleRow(
                    title = stringResource(R.string.patch_manifest_documents_provider),
                    icon = Icons.Rounded.FolderOpen,
                    subtitle = stringResource(R.string.patch_manifest_documents_provider_desc),
                    checked = request.injectDocumentsProvider,
                    onCheckedChange = onInjectDocumentsProvider,
                )
                ToggleRow(
                    title = stringResource(R.string.patch_manifest_microg),
                    icon = Icons.Rounded.AccountCircle,
                    subtitle = stringResource(R.string.patch_manifest_microg_desc),
                    checked = request.useMicroG,
                    onCheckedChange = onUseMicroG,
                )
                // Last of the manifest controls: its chip list and field are the tallest thing here,
                // so it sits below the compact toggles rather than pushing them down the screen.
                PermissionEditor(
                    added = request.addedPermissions,
                    onAdd = onAddPermission,
                    onRemove = onRemovePermission,
                )
                GroupDivider()
                KeystoreSetting()
                ToggleRow(
                    title = stringResource(R.string.settings_detail_patch_logs),
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    checked = Configs.detailPatchLogs,
                    onCheckedChange = { Configs.detailPatchLogs = it },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The permissions worth one tap, shortest name first.
 *
 * Not a catalogue -- the field below takes any permission -- just the handful a module most often finds missing,
 * INTERNET at the front because it is the one the request that prompted this feature named. Fully qualified so they
 * read the same here, on the chip, and in the manifest.
 */
private val COMMON_PERMISSIONS =
    listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.QUERY_ALL_PACKAGES",
    )

/** The last dotted segment -- what a permission is called once its namespace is understood. */
private fun permissionShortName(name: String): String = name.substringAfterLast('.')

/**
 * Adds extra `uses-permission` entries to the patched manifest (issue #44).
 *
 * A module can need a permission the host app never declared -- INTERNET most often -- and without it the platform
 * simply denies the call at runtime. The list is additive and de-duplicated: the manifest editor drops a name the app
 * already has, so nothing here can remove or weaken a permission, only add one the app lacked.
 *
 * Three ways in, narrowing as they go: the common ones as one-tap suggestions, a field for anything else (a bare word
 * is completed to `android.permission.*`), and each added permission shown as a chip that removes itself. The short
 * name is what the eye needs; the full name rides along as the chip's accessibility label.
 */
@Composable
private fun PermissionEditor(
    added: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        var entry by rememberSaveable { mutableStateOf("") }
        val commit = {
            if (entry.isNotBlank()) {
                onAdd(entry)
                entry = ""
            }
        }
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            label = { Text(stringResource(R.string.patch_manifest_permissions)) },
            placeholder = { Text(stringResource(R.string.patch_manifest_permissions_hint)) },
            supportingText = { Text(stringResource(R.string.patch_manifest_permissions_desc)) },
            leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            trailingIcon = {
                // Enabled only when there is something to add, so the button's state says whether a
                // press will do anything before it is pressed.
                IconButton(onClick = commit, enabled = entry.isNotBlank()) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.patch_manifest_permissions_add),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Suggestions the app does not already carry an override for; a suggestion vanishes once it
        // has been added, so this row only ever offers work still worth doing.
        val suggestions = COMMON_PERMISSIONS.filter { it !in added }
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                suggestions.forEach { permission ->
                    SuggestionChip(
                        onClick = { onAdd(permission) },
                        label = { Text(permissionShortName(permission)) },
                    )
                }
            }
        }

        if (added.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                added.forEach { permission ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(permission) },
                        label = { Text(permissionShortName(permission)) },
                        trailingIcon = {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.patch_manifest_permissions_remove),
                                modifier = Modifier.size(InputChipDefaults.AvatarSize),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** One folded-away option, as it reads on the collapsed header. */
private data class OptionChipData(
    val label: String,
    val changed: Boolean,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * What the folded-away options currently say, so they can be left folded with confidence.
 *
 * The signature-bypass level is always shown because it always has one; everything else appears only when it is *not*
 * the default. Reading the header therefore answers both questions at once -- what the patch will do, and whether
 * anything here has been touched -- without expanding it.
 */
@Composable
private fun advancedChips(request: PatchRequest): List<OptionChipData> = buildList {
    // The same icon each option carries in the expanded list, so the collapsed header reads as a
    // summary of those rows rather than as a separate vocabulary to learn.
    add(
        OptionChipData(
            "lv${request.sigBypassLevel}",
            request.sigBypassLevel != 2,
            Icons.Rounded.RemoveModerator,
        )
    )
    if (request.debuggable) {
        add(OptionChipData(stringResource(R.string.patch_debuggable), true, Icons.Rounded.BugReport))
    }
    request.versionCodeOverride?.let { versionCode ->
        // The number itself, the way the bypass chip shows its level: which version code was chosen
        // is the whole point of having overridden it.
        add(OptionChipData("vc $versionCode", true, Icons.Rounded.Layers))
    }
    if (request.injectDex) {
        add(OptionChipData(stringResource(R.string.patch_inject_dex), true, Icons.Rounded.Code))
    }
    if (request.addedPermissions.isNotEmpty()) {
        // A count, not the names: several full permission strings would overrun the header, and the
        // fact that any were added is the summary the collapsed row is for.
        add(
            OptionChipData(
                stringResource(R.string.patch_manifest_permissions_count, request.addedPermissions.size),
                true,
                Icons.Rounded.Key,
            )
        )
    }
    if (request.injectDocumentsProvider) {
        add(OptionChipData(stringResource(R.string.patch_manifest_documents_provider), true, Icons.Rounded.FolderOpen))
    }
    if (request.useMicroG) {
        add(OptionChipData(stringResource(R.string.patch_manifest_microg), true, Icons.Rounded.AccountCircle))
    }
    if (!MyKeyStore.useDefault) {
        add(OptionChipData(stringResource(R.string.settings_keystore_custom), true, Icons.Outlined.Ballot))
    }
    if (Configs.detailPatchLogs) {
        add(
            OptionChipData(
                stringResource(R.string.settings_detail_patch_logs),
                false,
                Icons.AutoMirrored.Rounded.Notes,
            )
        )
    }
}

/**
 * What is being patched, as facts the bar above does not carry.
 *
 * The bar already names the app, so repeating the name here would spend the top of the screen saying nothing new. The
 * icon is identity at a glance, and the version and size are the two things worth confirming before spending thirty
 * seconds rebuilding something -- particularly when the apk came from storage and may not be the build the reader
 * thought it was.
 */
@Composable
private fun TargetStrip(request: PatchRequest) {
    val context = LocalContext.current
    val target =
        remember(request.packageName) {
            LSPPackageManager.appList.firstOrNull { it.app.packageName == request.packageName }
        }
    val icon = target?.let { rememberAppIcon(it) }
    val version =
        remember(request.packageName) {
            runCatching {
                lspApp.packageManager.getPackageInfo(request.packageName, 0).versionName
            }
                .getOrNull()
                .orEmpty()
        }
    val size =
        remember(request.target.apkPaths) {
            request.target.apkPaths.sumOf { runCatching { java.io.File(it).length() }.getOrDefault(0L) }
        }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        } else {
            Icon(
                Icons.Rounded.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text =
                    buildList {
                            if (version.isNotBlank()) add("v" + version)
                            if (size > 0) add(Formatter.formatShortFileSize(context, size))
                            if (request.target.apkPaths.size > 1) {
                                add(stringResource(R.string.patch_patched_splits, request.target.apkPaths.size - 1))
                            }
                        }
                        .joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(originLabel(request)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun originLabel(request: PatchRequest): Int =
    when (request.origin) {
        PatchOrigin.New ->
            when (request.target) {
                is PatchTarget.ApkFiles -> R.string.patch_source_storage
                else -> R.string.patch_source_installed
            }
        PatchOrigin.RePatch -> R.string.patch_source_repatch
        PatchOrigin.UpdateLoader -> R.string.patch_source_repatch
    }

/** A quiet section title, in the accent, above the group it names. */
@Composable
private fun SectionLabel(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One rounded container holding a run of related rows.
 *
 * Rows on a bare background have nothing saying where one setting group ends and the next begins except the gap between
 * them, which a long subtitle closes up. A container is the cheapest way to make the grouping structural rather than a
 * matter of spacing.
 */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

/**
 * One of the two patch modes, as a card that carries its own explanation.
 *
 * The selected one is filled and outlined in the accent and carries a tick; the other recedes to a hairline. Both keep
 * the same height and the same text, so choosing does not reflow the page under the thumb that just chose.
 */
@Composable
private fun ModeCard(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container by
        animateColorAsState(
            if (selected) colors.primaryContainer else colors.surfaceContainerLow,
            label = "modeContainer",
        )
    val border by
        animateColorAsState(
            if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.5f),
            label = "modeBorder",
        )
    val onContainer = if (selected) colors.onPrimaryContainer else colors.onSurface

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(container)
                .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(20.dp))
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onContainer,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) onContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
            )
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * What the chosen mode means, as answers to the three questions both modes answer.
 *
 * The two modes were described by a paragraph each, and a reader comparing them had to hold one paragraph in their head
 * while reading the other. They are really answering the same three questions -- where the modules live, whether the
 * set can change later, and where the result will run -- so the questions are fixed in place and only the answers
 * change. Switching mode now swaps three values in three known positions, which is a comparison rather than a re-read.
 */
@Composable
private fun ModeComparison(mode: PatchMode) {
    val local = mode == PatchMode.Local
    val aspects =
        listOf(
            Triple(
                Icons.Rounded.Extension,
                R.string.patch_aspect_modules,
                if (local) R.string.patch_aspect_modules_local else R.string.patch_aspect_modules_integrated,
            ),
            Triple(
                Icons.Rounded.Tune,
                R.string.patch_aspect_scope,
                if (local) R.string.patch_aspect_scope_local else R.string.patch_aspect_scope_integrated,
            ),
            Triple(
                Icons.Rounded.Smartphone,
                R.string.patch_aspect_runs,
                if (local) R.string.patch_aspect_runs_local else R.string.patch_aspect_runs_integrated,
            ),
        )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        aspects.forEach { (icon, label, value) ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(14.dp))
                // A fixed label column, so the three answers start at the same x in both modes and
                // the eye can compare them without re-finding where each line begins.
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(76.dp),
                )
                Spacer(Modifier.width(10.dp))
                Crossfade(targetState = value, label = "modeAspect") { current ->
                    Text(
                        text = stringResource(current),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** One embedded module, with the way to take it back out. */
@Composable
private fun EmbeddedModuleRow(module: ModuleBinding, onRemove: () -> Unit) {
    val originColor = module.origin.color()
    PackageRow(
        icon = {
            val bitmap = module.icon
            if (bitmap != null) {
                Icon(bitmap = bitmap, contentDescription = null, tint = Color.Unspecified)
            } else {
                Icon(Icons.Rounded.Extension, contentDescription = null)
            }
        },
        label = module.label,
        packageName = module.packageName,
        trailing = {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.patch_module_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        additionalContent = {
            Text(
                text = stringResource(module.origin.labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = originColor,
            )
        },
    )
}

/** Signature bypass: the levels stack, so each row adds to the ones checked below it. */
@Composable
private fun SigBypassRow(level: Int, onSelect: (Int) -> Unit) {
    val maxLevel = remember { if ("arm64-v8a" in Build.SUPPORTED_ABIS) 3 else 2 }
    // Graceful clamp: a persisted request carried in at lv3 on a device that cannot do it drops to
    // this device's cap. onSelect updates the viewmodel (setSigBypassLevel), so request.sigBypassLevel
    // becomes 2 on the next recomposition -- no crash, no out-of-range value.
    LaunchedEffect(level, maxLevel) { if (level > maxLevel) onSelect(maxLevel) }
    val shownLevel = level.coerceAtMost(maxLevel)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.RemoveModerator,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.patch_sigbypass),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            // The current level as a tag; nothing checked reads as the lv0 "Off" string in words
            // rather than a bare "lv0", so an off control still names itself without a row to spare.
            Text(
                text = if (shownLevel == 0) stringResource(R.string.patch_sigbypasslv0) else "lv$shownLevel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        // Three cumulative capabilities. A row is on when the chosen level reaches it, so choosing
        // lv2 lights lv1 and lv2 at once -- the picture is "0..N", never "one of four", mirroring
        // doSigBypass's sigBypassLevel >= threshold gating. Tapping a row adds it and everything
        // under it; tapping the topmost lit row takes it back off, the way down to lv0.
        repeat(maxLevel) { i ->
            val threshold = i + 1
            val active = shownLevel >= threshold
            val delta =
                stringResource(
                    when (threshold) {
                        1 -> R.string.patch_sigbypasslv1
                        2 -> R.string.patch_sigbypasslv2
                        else -> R.string.patch_sigbypasslv3
                    }
                )
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(
                            selected = active,
                            role = Role.Checkbox,
                            onClick = { onSelect(if (shownLevel == threshold) threshold - 1 else threshold) },
                        )
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (active) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint =
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = delta,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The expert flags, folded away with their current state on show.
 *
 * A disclosure rather than a separate screen: these are read far more often than they are changed, and the chips answer
 * the only question most readers have about them. The whole thing sits in the same rounded container the other groups
 * use, so a collapsed section reads as one control rather than as a heading that happens to be tappable.
 */
@Composable
private fun AdvancedSection(
    expanded: Boolean,
    chips: List<OptionChipData>,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "advancedChevron")
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .animateContentSize()
    ) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.patch_section_options),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    // One chevron that turns, rather than two icons swapping: the rotation *is* the
                    // statement that this row opens and closes.
                    modifier = Modifier.rotate(rotation),
                )
            }
            if (!expanded && chips.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    modifier = Modifier.padding(start = 38.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chips.forEach { OptionChip(it) }
                }
            }
        }
        if (expanded) content()
    }
}

/**
 * One setting's current value.
 *
 * A changed one is tinted and a default one is not, so a glance at the row separates "this patch is ordinary" from
 * "somebody has been in here" without reading a single label.
 */
@Composable
private fun OptionChip(chip: OptionChipData) {
    val colors = MaterialTheme.colorScheme
    val content = if (chip.changed) colors.onPrimaryContainer else colors.onSurfaceVariant
    Row(
        modifier =
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (chip.changed) colors.primaryContainer else colors.surfaceContainerHighest)
                .padding(start = 7.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            chip.icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = chip.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (chip.changed) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * What can be done right now, and what it will cost.
 *
 * One branch per state, and every action names its own outcome rather than saying "OK": the button that rebuilds an app
 * and the button that replaces it are different acts with different consequences, and the second line says which of
 * those is about to happen.
 */
@Composable
private fun PatchBar(
    step: PatchStep,
    request: PatchRequest,
    onPatch: () -> Unit,
    onInstall: () -> Unit,
    onUninstallAndInstall: () -> Unit,
    onRetry: () -> Unit,
    onExport: (List<java.io.File>) -> Unit,
    onOpen: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                is PatchStep.Patched -> {
                    BarHeadline(
                        icon = { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        title = stringResource(R.string.patch_patched),
                        detail = patchedDetail(step.files.size),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onExport(step.files) },
                        ) {
                            Text(stringResource(R.string.patch_export))
                        }
                        Button(modifier = Modifier.weight(1f), onClick = onInstall) {
                            Text(stringResource(R.string.patch_action_install))
                        }
                    }
                }

                is PatchStep.NeedsUninstall -> {
                    UninstallConfirm(
                        label = request.label,
                        onConfirm = onUninstallAndInstall,
                        onCancel = onDone,
                    )
                    BarHeadline(
                        icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                        title = stringResource(R.string.patch_patched),
                        detail = stringResource(R.string.patch_effect_uninstall),
                    )
                }

                is PatchStep.Failed -> {
                    BarHeadline(
                        icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                        title = stringResource(R.string.patch_status_error),
                        detail = step.reason ?: stringResource(R.string.patch_status_error_desc),
                    )
                    // The patched apk outlives a failed install, so it can still be handed to another
                    // installer -- offered only when this device has one that is not us, and only for
                    // a single apk, which is what such an intent carries.
                    val context = LocalContext.current
                    // The patched apk outlives a failed install, so the two ways of using it anyway are
                    // offered here: hand it to an installer this device has, or save it and install it
                    // however the reader prefers. Each stands on its own, and a device that needs one
                    // may need the other.
                    val outputs by
                        produceState(emptyList<java.io.File>(), step.request?.packageName) {
                            val pkg = step.request?.packageName
                            value = if (pkg == null) emptyList() else PatchOutputStore.outputs(pkg)
                        }
                    val handoff by
                        produceState(emptyList<Intent>(), outputs) {
                            value =
                                if (outputs.isEmpty()) emptyList() else LSPPackageManager.installHandoffIntents(outputs)
                        }
                    val scope = rememberCoroutineScope()
                    val snackbars = LocalSnackbarHost.current
                    if (outputs.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(modifier = Modifier.weight(1f), onClick = { onExport(outputs) }) {
                                Text(stringResource(R.string.patch_export))
                            }
                            if (handoff.isNotEmpty()) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        // A refusal is reported where the press happened.
                                        val failure = LSPPackageManager.startHandoff(context, handoff)
                                        if (failure != null) {
                                            scope.launch { snackbars.show(failure, SnackbarTone.Failure) }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.patch_action_other_installer))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = onDone) {
                            Text(stringResource(R.string.patch_return))
                        }
                        if (step.request != null) {
                            Button(modifier = Modifier.weight(1f), onClick = onRetry) {
                                Text(stringResource(R.string.patch_action_retry))
                            }
                        }
                    }
                }

                is PatchStep.Done -> {
                    BarHeadline(
                        icon = { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        title = stringResource(R.string.patch_install_successfully),
                        detail = null,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = onDone) {
                            Text(stringResource(R.string.patch_return))
                        }
                        Button(modifier = Modifier.weight(1f), onClick = onOpen) {
                            Text(stringResource(R.string.patch_action_open))
                        }
                    }
                }

                is PatchStep.Preparing,
                is PatchStep.Running -> {
                    val running = step as? PatchStep.Running
                    Text(
                        text = stringResource(R.string.patch_status_patching),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Determinate across apks only. Nothing inside a single apk reports progress,
                    // and a bar that invents one is worse than a bar that admits it does not know.
                    if (running != null && running.apkCount > 1) {
                        LinearProgressIndicator(
                            progress = { running.apkIndex.toFloat() / running.apkCount },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                is PatchStep.Installing,
                is PatchStep.Uninstalling,
                is PatchStep.Confirming,
                is PatchStep.Restoring -> {
                    Text(
                        text =
                            stringResource(
                                when (step) {
                                    is PatchStep.Uninstalling -> R.string.uninstalling
                                    is PatchStep.Confirming -> R.string.patch_confirming
                                    is PatchStep.Restoring -> R.string.patch_restoring
                                    else -> R.string.patch_installing
                                }
                            ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                PatchStep.Idle -> {
                    val needsUninstall =
                        remember(request.packageName, MyKeyStore.useDefault) {
                            LSPPackageManager.keystoreConflictsWith(request.packageName)
                        }
                    Text(
                        text =
                            stringResource(
                                if (needsUninstall) R.string.patch_effect_uninstall else R.string.patch_effect_replace
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (needsUninstall) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onPatch, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(request.actionLabel()))
                    }
                }
            }
        }
    }
}

/** The verb names the outcome: a rebuild, a mode change, or a loader refresh are not "OK". */
private fun PatchRequest.actionLabel(): Int =
    when (origin) {
        PatchOrigin.New -> R.string.patch_action_patch
        PatchOrigin.UpdateLoader -> R.string.patch_action_update_loader
        PatchOrigin.RePatch ->
            when (mode) {
                PatchMode.Local -> R.string.patch_action_repatch_local
                PatchMode.Integrated -> R.string.patch_action_repatch_integrated
            }
    }

@Composable
private fun patchedDetail(count: Int): String {
    val hint =
        stringResource(
            if (ShizukuApi.isPermissionGranted) R.string.patch_install_hint_shizuku
            else R.string.patch_install_hint_system
        )
    return if (count > 1) {
        stringResource(R.string.patch_patched_splits, count - 1) + "  ·  " + hint
    } else hint
}

@Composable
private fun BarHeadline(
    icon: @Composable () -> Unit,
    title: String,
    detail: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Asked, not assumed.
 *
 * A patched apk is signed with LSPatch's key and the original with its developer's, so Android will not replace one
 * with the other -- the old app has to go first, and its data with it. That is not something to do quietly on the
 * user's behalf while a progress bar spins.
 */
@Composable
private fun UninstallConfirm(label: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    SharedAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.uninstall)) },
        text = { Text(stringResource(R.string.patch_uninstall_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}
