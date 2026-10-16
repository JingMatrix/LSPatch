package org.lsposed.lspatch.ui.page

import org.matrix.vector.ui.AppIcon
import org.matrix.vector.ui.show
import org.matrix.vector.ui.SnackbarTone
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.launch
import org.lsposed.lspatch.R
import org.lsposed.lspatch.data.model.ModuleRef
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.PatchOrigin
import org.lsposed.lspatch.data.repository.PatchJobHost
import org.lsposed.lspatch.share.Constants
import org.lsposed.lspatch.share.LSPConfig
import org.lsposed.lspatch.ui.component.DetailTopBar
import org.lsposed.lspatch.ui.component.rememberExportApk
import org.lsposed.lspatch.ui.navigation.ModuleSelection
import org.lsposed.lspatch.ui.navigation.SelectModules
import org.lsposed.lspatch.ui.page.manage.AppActionSheet
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.ui.viewmodel.AppDetailViewModel
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi
import org.matrix.vector.ui.ActionDrawerItem
import org.matrix.vector.ui.ApiBadge
import org.matrix.vector.ui.ModuleRow
import org.matrix.vector.ui.PanelEmptyState
import org.matrix.vector.ui.SharedAlertDialog
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.theme.Mono

/**
 * One patched app, and everything that can be done to it.
 *
 * Opened by tapping a row in Manage; the action sheet that used to open on tap is now the long-press. A sheet is the
 * right shape for a handful of one-shot OS actions and the wrong shape for the thing people actually come here to do,
 * which is to look at what a patched app is running and change it -- that needs a list, room to read a module's
 * description, and a way to review a change before committing it.
 *
 * It opens on a hero -- the app's face, name, and status chips -- then the mode banner that says what editing its
 * modules will cost, the module list, the actions, and the patch details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(navigator: Navigator, packageName: String) {
    // Built with its argument rather than reading one out of a SavedStateHandle: the back stack
    // holds routes, not bundles, so the package name is a value the screen already has.
    val viewModel = viewModel { AppDetailViewModel(packageName) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val exporter = rememberExportApk()

    val app = viewModel.app
    val config = viewModel.config
    val mode = viewModel.mode
    val pending = viewModel.pending

    var showSheet by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var leaveAsked by remember { mutableStateOf(false) }

    val applied = stringResource(R.string.appdetail_scope_applied)
    val applyFailed = stringResource(R.string.appdetail_scope_failed)
    val notAModule = stringResource(R.string.patch_module_not_a_module)
    val busyMessage = stringResource(R.string.patch_busy)
    val forceStopDone = stringResource(R.string.manage_forcestop_done)

    // What the module picker chose, applied once on the way back -- see ModuleSelection.
    val selection by ModuleSelection.pending.collectAsState()
    LaunchedEffect(selection) {
        ModuleSelection.consume(packageName)?.forEach {
            if (it !in viewModel.draft) viewModel.draft.add(it)
        }
    }

    val storageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { uri ->
                scope.launch {
                    LSPPackageManager.getAppInfoFromApks(listOf(uri)).onSuccess { infos ->
                        infos.forEach { info ->
                            viewModel.addFromFile(File(info.app.sourceDir)) {
                                scope.launch { snackbarHost.show(notAModule.format(info.label), SnackbarTone.Failure) }
                            }
                        }
                    }
                }
            }
        }

    // Returning here after a re-patch means looking at a different build of the app, so the page
    // re-reads itself when the apk on disk has changed, and otherwise only refreshes what is cheap.
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose {}
    }

    // Asked once per visit and then believed: a reader who has already said "leave" should not be
    // asked again because the gesture repeated.
    BackHandler(enabled = pending.any && !leaveAsked) { showLeave = true }

    if (app == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (viewModel.loading) CircularProgressIndicator() else Text(stringResource(R.string.error_unknown))
        }
        return
    }

    Scaffold(
        topBar = {
            DetailTopBar(
                label = app.label,
                packageName = app.app.packageName,
                onBack = { navigator.back() },
                actions = {
                    IconButton(onClick = { showSheet = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            // Slides in only when there is something to apply. A bar that is always there is a bar
            // nobody reads, and this one carries the sentence that says what applying will cost.
            AnimatedVisibility(
                visible = pending.any,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                ApplyBar(
                    pending = pending.added to pending.removed,
                    effect =
                        stringResource(
                            if (mode == PatchMode.Local) R.string.appdetail_effect_local
                            else R.string.appdetail_effect_integrated,
                            app.label,
                        ),
                    verb =
                        stringResource(
                            if (mode == PatchMode.Local) R.string.appdetail_apply else R.string.manage_repatch
                        ),
                    onDiscard = { viewModel.discard() },
                    onApply = {
                        if (mode == PatchMode.Local) {
                            viewModel.applyLocalScope { ok ->
                                scope.launch {
                                    snackbarHost.show(
                                        if (ok) applied else applyFailed,
                                        if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                                    )
                                }
                            }
                        } else {
                            scope.launch {
                                if (PatchJobHost.busy) {
                                    snackbarHost.showSnackbar(busyMessage)
                                    return@launch
                                }
                                val modules =
                                    viewModel.draftedBindings().mapNotNull { binding ->
                                        binding.apkPath?.let {
                                            ModuleRef(binding.packageName, it, binding.origin)
                                        }
                                    }
                                beginPatch(
                                    rePatchRequestFor(app, mode = PatchMode.Integrated, modules = modules),
                                    navigator,
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        if (viewModel.loading) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // The app's identity and status, so the page opens on what it is rather than on a
            // heading. The top bar names it in text; the hero shows its face and, at a glance, the
            // handful of facts that decide what the rest of the page can do.
            item {
                AppHero(
                    app = app,
                    mode = mode,
                    loader = config?.lspConfig?.VERSION_CODE,
                    sigBypassLevel = config?.sigBypassLevel ?: 0,
                    moduleCount = viewModel.candidates.count { it.packageName in viewModel.draft },
                )
            }

            // Read before anything is touched: this is where LSPatch admits a Local scope change is
            // deferred to the next start, and an Integrated one costs a rebuild.
            item {
                ModeBanner(
                    text =
                        stringResource(
                            if (mode == PatchMode.Local) R.string.appdetail_local_note
                            else R.string.appdetail_integrated_note,
                            app.label,
                        ),
                    tone =
                        if (mode == PatchMode.Local) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.tertiary,
                    actionLabel =
                        if (mode == PatchMode.Local && ShizukuApi.isPermissionGranted)
                            stringResource(R.string.manage_forcestop)
                        else null,
                    onAction = {
                        scope.launch {
                            ShizukuApi.runShellCommand("am force-stop ${app.app.packageName}")
                            snackbarHost.show(forceStopDone, SnackbarTone.Success)
                        }
                    },
                )
            }

            item { DetailSectionLabel(stringResource(R.string.appdetail_modules), Icons.Rounded.Extension) }

            if (viewModel.candidates.isEmpty()) {
                item {
                    PanelEmptyState(
                        icon = Icons.Rounded.Extension,
                        text = stringResource(R.string.appdetail_no_modules),
                    )
                }
            } else {
                items@ for (module in viewModel.candidates) {
                    item(key = module.packageName) {
                        val chosen = module.packageName in viewModel.draft
                        ModuleRow(
                            name = module.label,
                            versionName = module.versionName.orEmpty(),
                            description = module.manifest?.description.orEmpty(),
                            icon = {
                                val bitmap = module.icon
                                if (bitmap != null) {
                                    Icon(
                                        bitmap = bitmap,
                                        contentDescription = null,
                                        tint = Color.Unspecified,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.Extension,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            },
                            apiBadge = { ApiBadge(label = "API", value = module.apiVersion.toString()) },
                            selected = chosen,
                            // Reserved for a module that genuinely cannot be used -- one whose apk
                            // could not be read out of the host. Dimming "not selected" would grey
                            // out most of a long list and say nothing.
                            dimmed = !module.usable,
                            note =
                                if (!module.usable) {
                                    { Text(stringResource(R.string.patch_module_unreadable)) }
                                } else null,
                            onClick = { if (module.usable) viewModel.toggle(module.packageName) },
                            onIconClick = { if (module.usable) viewModel.toggle(module.packageName) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            // Only Integrated: a Local module has to be installed for the manager to serve it, so
            // there is nothing an apk from storage could contribute. An honest difference between
            // the modes, not a cosmetic one.
            if (mode == PatchMode.Integrated) {
                item {
                    ActionDrawerItem(
                        icon = Icons.Rounded.FolderOpen,
                        title = stringResource(R.string.patch_modules_add_storage),
                        subtitle = null,
                    ) {
                        storageLauncher.launch(APK_AND_BUNDLE_TYPES)
                    }
                }
            } else {
                item {
                    ActionDrawerItem(
                        icon = Icons.Rounded.Extension,
                        title = stringResource(R.string.patch_modules_add_installed),
                        subtitle = stringResource(R.string.manage_module_scope_desc),
                    ) {
                        navigator.go(SelectModules(packageName, viewModel.draft.toList()))
                    }
                }
            }

            item {
                DetailSectionLabel(stringResource(R.string.appdetail_actions), Icons.Rounded.AutoAwesome)
                DetailCard {
                    ActionDrawerItem(
                        icon = Icons.Rounded.AutoAwesome,
                        title = stringResource(R.string.manage_repatch),
                        subtitle = stringResource(R.string.manage_repatch_desc),
                    ) {
                        scope.launch {
                            if (PatchJobHost.busy) {
                                snackbarHost.showSnackbar(busyMessage)
                                return@launch
                            }
                            beginPatch(rePatchRequestFor(app), navigator)
                        }
                    }
                    val loaderBehind =
                        config != null &&
                            !(config.useManager &&
                                config.lspConfig.VERSION_CODE >= Constants.MIN_ROLLING_VERSION_CODE) &&
                            config.lspConfig.VERSION_CODE < LSPConfig.instance.VERSION_CODE
                    if (loaderBehind) {
                        ActionDrawerItem(
                            icon = Icons.Rounded.Upgrade,
                            title = stringResource(R.string.manage_update_loader),
                            subtitle = stringResource(R.string.manage_update_loader_desc),
                        ) {
                            scope.launch {
                                if (PatchJobHost.busy) {
                                    snackbarHost.showSnackbar(busyMessage)
                                    return@launch
                                }
                                // The same routine as a re-patch, so it inherits the stepped progress
                                // view instead of the blank modal spinner it used to show.
                                beginPatch(
                                    rePatchRequestFor(app, origin = PatchOrigin.UpdateLoader),
                                    navigator,
                                )
                            }
                        }
                    }
                    ActionDrawerItem(
                        icon = Icons.Rounded.Save,
                        title = stringResource(R.string.patch_export),
                        subtitle = stringResource(R.string.manage_export_desc),
                    ) {
                        val apks =
                            listOf(File(app.app.sourceDir)) + (app.app.splitSourceDirs?.map { File(it) } ?: emptyList())
                        exporter.export(app.label, apks)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    ActionDrawerItem(
                        icon = Icons.Rounded.SettingsBackupRestore,
                        title = stringResource(R.string.manage_restore_original),
                        subtitle = stringResource(R.string.manage_restore_desc),
                        tint = MaterialTheme.colorScheme.error,
                    ) {
                        showRestore = true
                    }
                }
            }

            item {
                DetailSectionLabel(stringResource(R.string.appdetail_info), Icons.Rounded.Info)
                val appVersion =
                    remember(app.app.packageName) {
                        runCatching {
                            context.packageManager.getPackageInfo(app.app.packageName, 0).versionName
                        }
                            .getOrNull()
                            .orEmpty()
                    }
                DetailCard {
                    InfoRow(stringResource(R.string.appdetail_info_package), app.app.packageName)
                    InfoRow(stringResource(R.string.appdetail_info_app_version), appVersion)
                    InfoRow(
                        stringResource(R.string.appdetail_info_loader),
                        config?.lspConfig?.VERSION_CODE?.toString().orEmpty(),
                    )
                    InfoRow(
                        stringResource(R.string.appdetail_info_sigbypass),
                        "lv${config?.sigBypassLevel ?: 0}",
                    )
                    InfoRow(
                        stringResource(R.string.appdetail_info_inject_dex),
                        yesNo(config?.injectDex == true),
                    )
                    InfoRow(
                        stringResource(R.string.patch_manifest_microg_re),
                        yesNo(config?.useMicroGRe == true),
                    )
                    InfoRow(
                        stringResource(R.string.patch_manifest_fake_signature),
                        yesNo(config?.declareFakeSignature == true),
                    )
                    // Manifest attributes, read from the installed manifest rather than the patch
                    // config: they are baked into the manifest at patch time and are not carried in the
                    // runtime config, so this reflects the app's actual manifest. Grouped and ordered to
                    // match the patch screen's manifest overrides -- debuggable, target SDK, and the
                    // native-lib and cleartext flags.
                    InfoRow(
                        stringResource(R.string.appdetail_info_debuggable),
                        yesNo(app.app.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0),
                    )
                    InfoRow(
                        stringResource(R.string.appdetail_info_target_sdk),
                        app.app.targetSdkVersion.toString(),
                    )
                    InfoRow(
                        stringResource(R.string.patch_manifest_extract_libs),
                        yesNo(app.app.flags and android.content.pm.ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0),
                    )
                    InfoRow(
                        stringResource(R.string.patch_manifest_cleartext),
                        yesNo(app.app.flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0),
                        last = mode != PatchMode.Integrated,
                    )
                }
                if (mode == PatchMode.Integrated) {
                    Text(
                        text = stringResource(R.string.appdetail_integrated_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (showSheet) {
        AppActionSheet(app = app, onDismiss = { showSheet = false })
    }

    if (showRestore) {
        RestoreConfirm(
            label = app.label,
            onDismiss = { showRestore = false },
            onConfirm = {
                showRestore = false
                PatchJobHost.startRestore(app)
                navigator.back()
            },
        )
    }

    if (showLeave) {
        SharedAlertDialog(
            onDismissRequest = { showLeave = false },
            title = { Text(stringResource(R.string.appdetail_leave_title)) },
            text = { Text(stringResource(R.string.appdetail_leave_text, pending.total)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeave = false
                        leaveAsked = true
                        navigator.back()
                    }
                ) {
                    Text(stringResource(R.string.appdetail_leave_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeave = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun yesNo(value: Boolean) = stringResource(if (value) R.string.appdetail_yes else R.string.appdetail_no)

/**
 * The app's identity and its status at a glance.
 *
 * Its face, its name, and the four facts that decide what the rest of the page can do -- what mode it is in, which
 * loader it carries, how it bypasses signatures, and how many modules it reaches -- as chips, so the page opens on the
 * app rather than on a heading.
 */
@Composable
private fun AppHero(
    app: LSPPackageManager.AppInfo,
    mode: PatchMode,
    loader: Int?,
    sigBypassLevel: Int,
    moduleCount: Int,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerLow)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(applicationInfo = app.app, contentDescription = null, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.app.packageName,
                    style = Mono.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val modeColor = if (mode == PatchMode.Local) colors.secondary else colors.tertiary
            HeroChip(
                icon = if (mode == PatchMode.Local) Icons.Rounded.Api else Icons.Rounded.WorkOutline,
                text = stringResource(if (mode == PatchMode.Local) R.string.patch_local else R.string.patch_integrated),
                tone = modeColor,
                strong = true,
            )
            if (loader != null) {
                HeroChip(
                    Icons.Rounded.Dns,
                    stringResource(R.string.appdetail_info_loader) + " " + loader,
                    colors.primary,
                )
            }
            HeroChip(Icons.Rounded.RemoveModerator, "lv" + sigBypassLevel, colors.primary)
            if (moduleCount > 0) {
                HeroChip(Icons.Rounded.Extension, moduleCount.toString(), colors.primary)
            }
        }
    }
}

/** A small status chip in the hero: a tinted pill with an icon and a word. */
@Composable
private fun HeroChip(icon: ImageVector, text: String, tone: Color, strong: Boolean = false) {
    Row(
        modifier =
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(if (strong) tone.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (strong) tone else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            color = if (strong) tone else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * What editing this app's modules will cost, tinted by mode.
 *
 * A banner rather than the grey status note it replaced: the difference between "applies next start" and "rebuilds and
 * reinstalls" is the single most important thing on the page, and should read as a statement, not a footnote.
 */
@Composable
private fun ModeBanner(text: String, tone: Color, actionLabel: String?, onAction: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tone.copy(alpha = 0.10f))
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** A quiet section title in the accent, with its icon, above the group it names. */
@Composable
private fun DetailSectionLabel(text: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** One rounded container grouping a run of rows, so a section reads as one block. */
@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

/** A fact about the patch: label, and the value in the monospace face values are shown in. */
@Composable
private fun InfoRow(label: String, value: String, last: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value.ifBlank { "—" },
            style = Mono.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
    if (!last) {
        HorizontalDivider(
            Modifier.padding(horizontal = 18.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
    }
}

/**
 * The pending edit, what applying it will do, and the two ways out.
 *
 * Only the verb and the sentence differ between the modes -- "Apply" against a database write that lands on the app's
 * next start, "Re-patch" against a rebuild and a reinstall. The shape is the same because the decision is the same one.
 */
@Composable
private fun ApplyBar(
    pending: Pair<Int, Int>,
    effect: String,
    verb: String,
    onDiscard: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.appdetail_pending, pending.first, pending.second),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = effect,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.appdetail_discard)) }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onApply) { Text(verb) }
        }
    }
}

/**
 * Restoring is not undoable and costs the app's data. The dialog says so in full, in plain terms, including *why* --
 * the signatures differ, so Android cannot swap one build for the other.
 */
@Composable
private fun RestoreConfirm(label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    SharedAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SettingsBackupRestore, contentDescription = null) },
        title = { Text(stringResource(R.string.manage_restore_title, label)) },
        text = { Text(stringResource(R.string.manage_restore_text, label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.manage_restore_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
