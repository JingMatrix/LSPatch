package org.lsposed.lspatch.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstallerHidden.SessionParamsHidden
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dev.rikka.tools.refine.Refine
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.config.Configs
import org.lsposed.lspatch.config.MyKeyStore
import org.lsposed.lspatch.data.model.ModuleBinding
import org.lsposed.lspatch.data.model.ModuleOrigin
import org.lsposed.lspatch.lspApp
import org.matrix.vector.ui.AppIconCache
import org.lsposed.lspatch.share.Constants
import org.matrix.vector.ui.module.ModuleDetection

object LSPPackageManager {

    private const val TAG = "LSPPackageManager"
    private const val SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"

    const val STATUS_USER_CANCELLED = -2

    /**
     * How long a *silent* installer action may run before its silence is taken as an answer.
     *
     * The bound is a compromise between two costs. Waiting minutes for a result that is not coming is time the reader
     * spends watching a screen that will not change; giving up early ends an install that was still working, since a
     * commit waits on verification performed by another application, whose duration is a property of the device rather
     * than of the package. So the bound is generous rather than tight, and expiry is not read as failure on its own:
     * the package manager is asked whether the package arrived anyway, so an install slower than the deadline is
     * recognised rather than repeated.
     */
    private const val SILENT_ACTION_TIMEOUT_MS = 60_000L

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /**
     * Asks the package manager to install without waiting on a verifier.
     *
     * Declared here because the hidden-api stubs do not carry it. It claims no privilege the session does not already
     * hold: the service keeps it only for a session already marked as coming from the shell and allowing test packages,
     * the first of which it sets itself for a shell caller, and strips it from every other caller.
     *
     * It is also narrower than it sounds. For a shell install the service honours the request only when the package is
     * already installed and the artifact is debuggable; a first install -- which includes every install that had to
     * remove a differently-signed predecessor -- is verified regardless. Only a device-wide setting covers that case,
     * and that setting is the device's to hold, not this app's to change.
     */
    private const val INSTALL_DISABLE_VERIFICATION = 0x00080000

    /**
     * Asks the package manager to install for every user rather than only the installer's own.
     *
     * Declared here because the hidden-api stubs do not carry it. It is what `pm install` sets for itself by default,
     * and it is the other half of the all-users uninstall: an app that was present in several users is replaced by the
     * patched build in all of them, instead of landing in the primary one and leaving the other users without the app
     * they had -- and, for a secondary user, with no way to get it back short of patching again. Set only for the
     * shell session, which holds the cross-user permission it needs; an app does not.
     */
    private const val INSTALL_ALL_USERS = 0x00000040

    private const val INSTALL_ACTION = "org.lsposed.lspatch.action.INSTALL_RESULT"

    @Parcelize
    class AppInfo(val app: ApplicationInfo, val label: String, val isModule: Boolean = false) : Parcelable {
        val isXposedModule: Boolean
            get() = isModule

        // An LSPatch build carries its Base64 PatchConfig in the manifest's "lspatch" meta-data; its
        // presence is the marker, read the same way whether the ApplicationInfo comes from an
        // installed package or from a package archive on disk.
        val isLSPatched: Boolean
            get() = app.metaData?.containsKey("lspatch") == true
    }

    // A module is either a legacy one (manifest xposedminversion / assets/xposed_init) or a modern
    // one, marked only by META-INF/xposed/java_init.list. Only the APK scan sees the modern kind, so
    // it is computed once at fetch time rather than in a property getter.
    private fun isModuleApk(app: ApplicationInfo): Boolean {
        if (app.metaData?.get("xposedminversion") != null) return true
        val sourceDir = app.sourceDir ?: return false
        return runCatching {
                java.util.zip.ZipFile(sourceDir).use { zip ->
                    zip.getEntry("META-INF/xposed/java_init.list") != null || zip.getEntry("assets/xposed_init") != null
                }
            }
            .getOrDefault(false)
    }

    var appList by mutableStateOf(listOf<AppInfo>())
        private set

    /**
     * The size icons are rasterised at, once.
     *
     * The launcher's own icon dimension: large enough for every row that draws one and small enough
     * that the shared cache holds a few hundred of them.
     */
    private val iconSizePx by lazy {
        lspApp.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
    }

    // One scan at a time. Two callers overlapping -- the manager's own start-up scan and the log
    // collector waiting for the uid set -- both walk every installed package, and doing it twice at
    // the busiest moment there is costs a second full enumeration for nothing.
    private val scanning = Mutex()

    /**
     * Scans only if nothing has scanned yet.
     *
     * The emptiness is checked while holding the lock, not before waiting for it: the manager's own start-up scan and
     * the log collector's wait for the uid set begin together, and a check outside would see an empty list, queue
     * behind the scan that was filling it, and then walk every installed package a second time -- decoding an icon
     * apiece -- at the busiest moment there is.
     */
    suspend fun ensureAppList() {
        scanning.withLock { if (appList.isEmpty()) scan() }
    }

    suspend fun fetchAppList() {
        scanning.withLock { scan() }
    }

    private suspend fun scan() {
        withContext(Dispatchers.IO) {
            val pm = lspApp.packageManager
            val collection = mutableListOf<AppInfo>()
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach {
                val label = pm.getApplicationLabel(it)
                collection.add(AppInfo(it, label.toString(), isModuleApk(it)))
            }
            collection.sortWith(compareBy(Collator.getInstance(Locale.getDefault()), AppInfo::label))
            val modules = buildMap {
                collection.forEach { if (it.isXposedModule) put(it.app.packageName, it.app.sourceDir) }
            }
            ConfigManager.updateModules(modules)
            appList = collection
        }
    }

    /**
     * This app's icon, if it has been rasterised already; null while it has not.
     *
     * Icons are no longer decoded for every installed package during the scan: that walked hundreds
     * of drawables at the one moment the manager is busiest and held every one of them for the life
     * of the process. They are rasterised on demand into the shared [AppIconCache], which is bounded
     * -- so this answers null for one that has not been asked for yet, or was evicted. A caller that
     * draws a single icon should use the shared `AppIcon` composable, which loads it and redraws;
     * this is for the callers that need the bitmap itself.
     */
    fun cachedIcon(appInfo: AppInfo): ImageBitmap? =
        AppIconCache.cached(AppIconCache.keyFor(appInfo.app, iconSizePx))

    /** Rasterises this app's icon, or returns the cached one. */
    suspend fun loadIcon(appInfo: AppInfo): ImageBitmap? =
        AppIconCache.load(appInfo.app, lspApp.packageManager, iconSizePx)

    // Module icons for a patched app, keyed by the patched app's package so re-scrolling the list
    // does not re-open the apk or re-extract embedded modules. A Local patch draws on the manager's
    // installed-module icons; an Integrated patch has its modules baked into the apk, so their icons
    // are only recoverable by extracting each entry and loading it as an archive.
    //
    // Concurrent, because the Manage screen fills it from two collectors at once -- one on the installed list, one on
    // the scope revision -- and both subscriptions deliver their first value the moment the screen opens.
    private val moduleIconsCache = ConcurrentHashMap<String, List<ImageBitmap>>()

    /**
     * The icons of the modules a patched app reaches, mirroring how a module row shows the apps it reaches. Local
     * (manager-backed) patches resolve modules from the live scope and reuse the already-loaded installed icons;
     * Integrated patches read the apks baked under [Constants.EMBEDDED_MODULES_ASSET_PATH]. A module whose icon can't
     * be loaded is skipped.
     */
    suspend fun moduleIconsFor(appInfo: AppInfo, useManager: Boolean): List<ImageBitmap> {
        val pkg = appInfo.app.packageName
        moduleIconsCache[pkg]?.let {
            return it
        }
        val icons =
            if (useManager) {
                withContext(Dispatchers.IO) {
                    ConfigManager.getModulesForApp(pkg).mapNotNull { module ->
                        // Prefer the already-loaded installed icon; fall back to loading it on demand
                        // for a module that installed after the app list was last fetched.
                        runCatching {
                                val info = lspApp.packageManager.getApplicationInfo(module.pkgName, 0)
                                AppIconCache.load(info, lspApp.packageManager, iconSizePx)
                            }
                            .getOrNull()
                    }
                }
            } else {
                // Derived from the same enumeration the detail page reads, so a row's reach band and
                // the page it opens can never disagree about which modules an app carries.
                embeddedModulesOf(appInfo).mapNotNull { it.icon }
            }
        moduleIconsCache[pkg] = icons
        return icons
    }

    /** Drops the cached module reach for [packageName], after its scope or its apk has changed. */
    fun invalidateModuleIcons(packageName: String) {
        moduleIconsCache.remove(packageName)
        embeddedModulesCache.remove(packageName)
    }

    // Keyed by package and by the host apk's timestamp+length: re-patching an app replaces its apk,
    // and a cache that ignored that would keep showing the module set the app used to carry.
    private val embeddedModulesCache = ConcurrentHashMap<String, Pair<String, List<ModuleBinding>>>()

    /**
     * One extraction at a time, so the check on [embeddedModulesCache] and the fill that follows it are one step.
     *
     * Without it two callers both miss the cache and both unpack the same module, which is not merely wasted work: the
     * apk one of them is writing is the apk the other has handed to the platform to read resources out of.
     */
    private val extracting = Mutex()

    /**
     * The modules baked into an Integrated patched app, read out of its own apk.
     *
     * The entry name under [Constants.EMBEDDED_MODULES_ASSET_PATH] *is* the module's package name -- the loader depends
     * on exactly that when it enumerates them at runtime -- so the set can be listed without installing anything. Each
     * entry is extracted once and inspected as an archive, which is what lets an embedded module render with the same
     * name, version, API badge and description an installed one gets.
     */
    suspend fun embeddedModulesOf(appInfo: AppInfo): List<ModuleBinding> {
        val pkg = appInfo.app.packageName
        val apkPath = appInfo.app.sourceDir ?: return emptyList()
        val stamp = runCatching { File(apkPath).let { "${it.lastModified()}:${it.length()}" } }.getOrDefault("")
        embeddedModulesCache[pkg]?.let { (cachedStamp, cached) -> if (cachedStamp == stamp) return cached }

        return extracting.withLock {
            // Asked again under the lock, because whoever held it was most likely unpacking this same app: the two
            // Manage-screen subscriptions that drive this both deliver their first value as the screen opens, so the
            // second caller is here to read what the first one wrote, not to write it again.
            embeddedModulesCache[pkg]?.let { (cachedStamp, cached) ->
                if (cachedStamp == stamp) return@withLock cached
            }
            val bindings =
                withContext(Dispatchers.IO) {
                    runCatching { unpackEmbeddedModules(pkg, apkPath, stamp) }.getOrDefault(emptyList())
                }
            embeddedModulesCache[pkg] = stamp to bindings
            bindings
        }
    }

    /**
     * Unpacks the module apks a host carries into a directory named for that host apk's [stamp], and reads each one.
     *
     * Naming the directory after the apk it came out of, and publishing each file by rename, is what makes this safe to
     * call more than once. Reading an apk means handing its path to the platform, which *maps* the archive rather than
     * copying it, and the mapping outlives the call -- the framework caches an open archive by path. Unpacking over
     * that path truncates a file some `AssetManager` is still reading through, and a mapped page that falls past the
     * end of its file raises `SIGBUS`, which is not an exception anything can catch: the process is gone.
     *
     * So no file is ever written where a file already is. A new version of the host apk unpacks into a directory of its
     * own, and within a directory the copy lands on a staging name and is moved into place, so a file that exists at
     * all is a whole one and can be read again rather than written again. Nothing here deletes: what an unpacking
     * returns is a set of paths its caller goes on holding, and a directory this left behind is swept at the next start
     * by [sweepEmbeddedModules], where no one holds a path into it yet.
     */
    private fun unpackEmbeddedModules(pkg: String, apkPath: String, stamp: String): List<ModuleBinding> {
        val root = lspApp.cacheDir.resolve("embedded-modules").resolve(pkg)
        val outDir = root.resolve(stamp.replace(':', '-').ifEmpty { "unstamped" }).also { it.mkdirs() }
        return java.util.zip.ZipFile(apkPath).use { zip ->
            zip.entries()
                .asSequence()
                .filter {
                    !it.isDirectory &&
                        it.name.startsWith(Constants.EMBEDDED_MODULES_ASSET_PATH) &&
                        it.name != Constants.EMBEDDED_MODULES_ASSET_PATH
                }
                .mapNotNull { entry ->
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isEmpty()) return@mapNotNull null
                    runCatching {
                        val apk = outDir.resolve(fileName)
                        if (!apk.exists()) {
                            // Never mapped by anything, so this one is safe to write over.
                            val staging = outDir.resolve("$fileName.part")
                            zip.getInputStream(entry).use { input ->
                                staging.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (!staging.renameTo(apk)) {
                                staging.delete()
                                throw IOException("Could not publish $fileName for $pkg")
                            }
                        }
                        bindingFromArchive(apk, ModuleOrigin.Embedded)
                            // The entry name is authoritative for the package: it is what the
                            // loader keys on, so a manifest disagreeing with it would still
                            // load under the name written here.
                            ?.copy(packageName = fileName.removeSuffix(".apk"))
                    }
                        .getOrNull()
                }
                .toList()
        }
    }

    /**
     * Drops every unpacked embedded module, at a moment when no one can be reading one.
     *
     * The unpacking itself never deletes, because the paths it returns are held by whoever asked for them -- a screen
     * still showing a module it listed, a patch request naming the apk to embed -- and a directory removed under those
     * turns a readable path into a missing one. A start is the moment that cannot be true of: nothing has been listed
     * yet, so nothing is holding a path. Taken under the same lock as an unpacking, so it cannot land in the middle of
     * one.
     */
    suspend fun sweepEmbeddedModules() {
        extracting.withLock {
            withContext(Dispatchers.IO) { lspApp.cacheDir.resolve("embedded-modules").deleteRecursively() }
            embeddedModulesCache.clear()
        }
    }

    /** Every installed Xposed module, as bindings a patch can embed or a scope can enable. */
    suspend fun installedModuleBindings(): List<ModuleBinding> =
        withContext(Dispatchers.IO) {
            appList
                .filter { it.isXposedModule }
                .map { info ->
                    val pm = lspApp.packageManager
                    val manifest = runCatching { ModuleDetection.inspect(info.app, pm) }.getOrNull()
                    val pkgInfo = runCatching { pm.getPackageInfo(info.app.packageName, 0) }.getOrNull()
                    ModuleBinding(
                        packageName = info.app.packageName,
                        label = info.label,
                        versionName = pkgInfo?.versionName,
                        versionCode = pkgInfo?.longVersionCode ?: 0L,
                        manifest = manifest,
                        icon = AppIconCache.loadBlocking(info.app, pm, iconSizePx),
                        apkPath = info.app.sourceDir,
                        origin = ModuleOrigin.Installed,
                    )
                }
        }

    /** Reads a module apk the user picked from storage. Null when it is not a module at all. */
    suspend fun moduleBindingFromFile(apk: File): ModuleBinding? =
        withContext(Dispatchers.IO) {
            bindingFromArchive(apk, ModuleOrigin.Picked)?.takeIf { it.manifest?.isModule == true }
        }

    /**
     * Inspects an apk sitting on disk rather than installed.
     *
     * [PackageManager.GET_META_DATA] is load-bearing, not defensive: `ModuleDetection.inspect` decides a module is
     * legacy by looking for the `xposedminversion` key in `metaData`, so without the flag every legacy module comes
     * back as "not a module" and silently disappears from the list it is supposed to be in.
     */
    private fun bindingFromArchive(apk: File, origin: ModuleOrigin): ModuleBinding? {
        val pm = lspApp.packageManager
        val pkgInfo = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA) ?: return null
        val info = pkgInfo.applicationInfo ?: return null
        // getPackageArchiveInfo leaves these unset; both the icon loader and the module inspector
        // need them to resolve resources and entries out of the archive.
        info.sourceDir = apk.absolutePath
        info.publicSourceDir = apk.absolutePath
        return ModuleBinding(
            packageName = info.packageName ?: pkgInfo.packageName,
            label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkgInfo.packageName),
            versionName = pkgInfo.versionName,
            versionCode = pkgInfo.longVersionCode,
            manifest = runCatching { ModuleDetection.inspect(info, pm) }.getOrNull(),
            icon = runCatching { AppIconCache.loadBlocking(info, pm, iconSizePx) }.getOrNull(),
            apkPath = apk.absolutePath,
            origin = origin,
        )
    }

    /**
     * Where [packageName] is installed right now, base first -- or null when the package manager does not list it.
     *
     * Every install of a package writes its apks into a freshly named directory, so a path read once describes where
     * the app was at that moment, not where it is. Anything holding on to such a path has to ask again before using it.
     */
    fun installedApkPaths(packageName: String): List<String>? = runCatching {
        val app = lspApp.packageManager.getApplicationInfo(packageName, 0)
        listOf(app.sourceDir) + (app.splitSourceDirs ?: emptyArray())
    }
        .getOrNull()

    /**
     * Whether [packageName] is on the device as a record without its apks.
     *
     * A package can be kept while its files are removed, which leaves it out of an ordinary lookup and yet not
     * uninstalled. The distinction is invisible from a missing path alone, and is the difference between an app that
     * can be restored by opening it and one that is gone.
     */
    fun isArchivedPackage(packageName: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            runCatching {
                lspApp.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES),
                )
            }
                .isSuccess

    suspend fun cleanTmpApkDir() {
        withContext(Dispatchers.IO) {
            lspApp.tmpApkDir.listFiles()?.forEach(File::delete)
        }
    }

    /**
     * Installs [apks] as one package, through Shizuku's shell installer or the platform one.
     *
     * The single install path in the app. It replaces four near-identical session bodies that differed only in which
     * installer they opened and where they read from -- and, in two of them, in a filter for
     * [Constants.PATCH_FILE_SUFFIX] that made restoring an original app impossible, since an apk recovered from inside
     * a patched one is named `base.apk`.
     *
     * Every file goes into one session, so an app and its splits install atomically or not at all. Lengths are declared
     * exactly: these are plain files, unlike the storage-access documents the old paths read, which reported their size
     * only sometimes.
     */
    suspend fun installFiles(apks: List<File>, useShizuku: Boolean): Pair<Int, String?> {
        // Asked again here rather than trusted from the caller: Shizuku has no revoke callback, so a
        // grant read a screen ago may already be gone. A lost one falls back to the platform
        // installer -- which still installs, only with a confirmation -- instead of failing.
        val shizuku = useShizuku && ShizukuApi.ensureReadyOrFallback(ShizukuOp.Install)
        Log.i(TAG, "Install ${apks.size} apk(s), shizuku=$shizuku")
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        withContext(Dispatchers.IO) {
            runCatching {
                if (apks.isEmpty()) throw IOException("No apk to install")
                apks
                    .firstOrNull { !it.exists() || it.length() == 0L }
                    ?.let { throw IOException("${it.name} is missing or empty") }

                // The silent path can end in three ways, and only two of them are answers: it
                // succeeded, it failed for a stated reason, or it produced nothing -- because it said
                // nothing, or because the channel carrying it gave out. Neither kind of nothing says
                // anything about the apk, so it is retried through the installer the user can see
                // rather than reported as a failure they cannot act on. A stated failure is not
                // retried -- it is the answer.
                val outcome =
                    (if (shizuku) trySilentInstall(apks) else null)
                        ?: run {
                            if (shizuku) {
                                Log.w(TAG, "The shell installer gave no answer; retrying with the platform installer")
                            }
                            installThroughPlatform(apks)
                        }
                status = outcome.first
                message = outcome.second
            }
                .onFailure {
                    status = PackageInstaller.STATUS_FAILURE
                    message = it.message + "\n" + it.stackTraceToString()
                    Log.e(TAG, "Install failed", it)
                }
        }
        // Logged, not merely returned: the caller writes this into the in-app patch report, and the
        // device that cannot install is exactly the one whose owner sends a logcat instead.
        Log.i(TAG, "Install result: status=$status message=$message")
        return Pair(status, message)
    }

    /** The silent install, or null when it produced no answer -- whether by silence or by failing outright. */
    private suspend fun trySilentInstall(apks: List<File>): Pair<Int, String?>? =
        try {
            installThroughShell(apks)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "The shell installer could not be used", t)
            null
        }

    /** The session parameters both installers use; the shell one may ask for more than an app can. */
    private fun installParams(shizuku: Boolean): PackageInstaller.SessionParams {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (shizuku) {
            var flags = Refine.unsafeCast<SessionParamsHidden>(params).installFlags
            // Verification is a step the package manager hands to another app and then waits on, and a
            // session whose verifier never answers stays committed and unfinished with nothing to
            // report. Asking to skip it costs nothing and helps where the service allows it.
            flags =
                flags or
                    INSTALL_ALL_USERS or
                    PackageManagerHidden.INSTALL_ALLOW_TEST or
                    PackageManagerHidden.INSTALL_REPLACE_EXISTING or
                    INSTALL_DISABLE_VERIFICATION
            Refine.unsafeCast<SessionParamsHidden>(params).installFlags = flags
        }
        return params
    }

    /**
     * The silent install, or null when the installer never answered.
     *
     * Null is deliberately not a failure: it is the one outcome worth trying somewhere else. The session is described
     * into the log on the way out, since whether the installer still holds it, has committed it, or no longer has it at
     * all is not visible from this side of the call.
     */
    private suspend fun installThroughShell(apks: List<File>): Pair<Int, String?>? {
        val startedAt = System.currentTimeMillis()
        val (sessionId, session) = ShizukuApi.createPackageInstallerSession(installParams(shizuku = true))
        session.use {
            apks.forEach { apk -> session.writeApk(apk) }
            var result: Intent? = null
            // Bounded, because an unbounded wait has no way to end: a commit whose status never
            // arrives leaves the screen saying "installing" with nothing written anywhere.
            val answered =
                withTimeoutOrNull(SILENT_ACTION_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                            // A request for confirmation is not an outcome: the dialog has to be
                            // shown and the wait continued. Read as a status it would report a
                            // number that describes nothing.
                            if (
                                intent.getIntExtra(
                                    PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE,
                                ) == PackageInstaller.STATUS_PENDING_USER_ACTION
                            ) {
                                Log.i(TAG, "Silent install session $sessionId asked for user confirmation")
                                // Only keep waiting if the confirmation is on screen; otherwise the
                                // request itself is the outcome.
                                if (launchConfirmation(intent)) return@IIntentSenderAdaptor
                            }
                            result = intent
                            if (cont.isActive) cont.resume(Unit)
                        }
                        session.commit(IntentSenderHelper.newIntentSender(adapter))
                    }
                }
            if (answered == null) {
                Log.w(
                    TAG,
                    "Silent install: no result in ${SILENT_ACTION_TIMEOUT_MS / 1000}s; " +
                        "session $sessionId ${ShizukuApi.describeSession(sessionId)}",
                )
                // Silence is not proof that nothing happened: a status can be lost while the install
                // itself succeeds, and an install slower than the deadline finishes all the same. Ask
                // the package manager what is installed before treating the wait as a failure.
                arrivedSince(apks, startedAt)?.let { installed ->
                    Log.i(TAG, "Install completed without reporting: $installed")
                    return PackageInstaller.STATUS_SUCCESS to "installed without a status"
                }
                // Otherwise nothing is coming, and a session the installer has taken keeps its staged
                // copy until something ends it. The work continues elsewhere, so this one is given up
                // rather than left holding storage and a claim on the package name.
                ShizukuApi.abandonSession(sessionId)
                return null
            }
            val intent = result ?: return null
            Log.i(TAG, "Silent install session $sessionId reported ${describeExtras(intent)}")
            return outcomeOf(intent)
        }
    }

    /**
     * The package [apks] describe, if it is installed and was updated since [since], or null.
     *
     * Read from the package manager rather than from the installer, because the question is not what the session did
     * but what the device now has.
     */
    private fun arrivedSince(apks: List<File>, since: Long): String? {
        val pm = lspApp.packageManager
        val name =
            runCatching { pm.getPackageArchiveInfo(apks.first().absolutePath, 0)?.packageName }.getOrNull()
                ?: return null
        val info = runCatching { pm.getPackageInfo(name, 0) }.getOrNull() ?: return null
        return if (info.lastUpdateTime >= since) name else null
    }

    /** The platform install, which shows the OS confirmation and always reports back. */
    private suspend fun installThroughPlatform(apks: List<File>): Pair<Int, String?> {
        val installer = lspApp.packageManager.packageInstaller
        val sessionId = installer.createSession(installParams(shizuku = false))
        installer.openSession(sessionId).use { session ->
            apks.forEach { apk -> session.writeApk(apk) }
            val result = awaitUserAction("$INSTALL_ACTION.$sessionId", sessionId) { sender -> session.commit(sender) }
            Log.i(TAG, "Platform install session $sessionId reported ${describeExtras(result)}")
            return outcomeOf(result)
        }
    }

    /**
     * The installer's answer as a status and a sentence.
     *
     * A request for confirmation arrives here only when it could not be shown, in which case why it could not is
     * written into the answer beforehand: a bare status code that describes nothing is worse than no answer.
     */
    private fun outcomeOf(intent: Intent): Pair<Int, String?> {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        return status to intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
    }

    /** Everything the installer put in its answer, since the status alone often explains nothing. */
    private fun describeExtras(intent: Intent): String {
        val extras = intent.extras ?: return "no extras"
        return extras.keySet().joinToString(prefix = "{", postfix = "}") { key ->
            @Suppress("DEPRECATION") "$key=${extras.get(key)}"
        }
    }

    /**
     * Every way this device offers to hand [apks] to another installer, most direct first, or empty when it offers
     * none.
     *
     * Three facts shape the list. The artifact is app-private, so it can only travel as a content uri behind a grant.
     * The two actions do not resolve alike -- one reaches the stock installer only for a content uri, and a device may
     * answer one and not the other -- so both are asked rather than one assumed. And this manager answers such an
     * intent itself, so it is excluded from what counts as a handler.
     *
     * One artifact only. A split app is several, and no action here carries more than one.
     */
    fun installHandoffIntents(apks: List<File>): List<Intent> {
        val apk = apks.singleOrNull()?.takeIf { it.exists() && it.length() > 0 } ?: return emptyList()
        val uri =
            runCatching { FileProvider.getUriForFile(lspApp, "${lspApp.packageName}.patched", apk) }
                .onFailure { Log.w(TAG, "Cannot share ${apk.name} for hand-off", it) }
                .getOrNull() ?: return emptyList()
        return listOf(Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_VIEW).mapNotNull { action ->
            val intent =
                Intent(action)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val handlers = runCatching {
                lspApp.packageManager.queryIntentActivities(intent, 0)
            }
                .getOrDefault(emptyList())
            val others = handlers.map { it.activityInfo.packageName }.filter { it != lspApp.packageName }
            if (others.isEmpty()) {
                null
            } else {
                Log.i(TAG, "Hand-off available via $action to $others")
                intent
            }
        }
    }

    /**
     * Starts the first hand-off the system accepts, and returns the reason when none is accepted.
     *
     * Resolving an intent and being allowed to start it are different questions, and a device can answer the first and
     * refuse the second. Every attempt is logged with what it was and how it ended, so a hand-off that does nothing can
     * be told from one that was never attempted.
     */
    fun startHandoff(context: Context, intents: List<Intent>): String? {
        if (intents.isEmpty()) return "no application on this device answers an install intent"
        var last: String? = null
        for (intent in intents) {
            Log.i(TAG, "Hand-off: starting ${intent.action}")
            val failure = runCatching { context.startActivity(intent) }.exceptionOrNull()
            if (failure == null) {
                Log.i(TAG, "Hand-off: ${intent.action} accepted")
                return null
            }
            Log.w(TAG, "Hand-off: ${intent.action} refused", failure)
            last = failure.toString()
        }
        // A picker names no component, which a system can allow where it refuses a direct start.
        val chooser = Intent.createChooser(intents.first(), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val failure = runCatching { context.startActivity(chooser) }.exceptionOrNull()
        if (failure == null) {
            Log.i(TAG, "Hand-off: chooser accepted")
            return null
        }
        Log.e(TAG, "Hand-off: every candidate refused", failure)
        return failure.toString().ifBlank { last }
    }

    /**
     * Opens the installer's own confirmation for a session that asked for one, and reports whether it could be shown.
     *
     * A confirmation that cannot be confirmed will never be answered, so a failure to show it ends the wait rather than
     * reproducing the hang deadlines exist to prevent. It can fail for more than one reason -- the request may carry no
     * intent, or name a component the device cannot resolve, or be refused -- so the reason is taken from the refusal
     * and recorded on the answer instead of being named in advance.
     */
    private fun launchConfirmation(intent: Intent): Boolean {
        @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        if (confirm == null) {
            Log.w(TAG, "The installer asked for user action without an intent to show")
            intent.noteUnshownConfirmation("the install needs a confirmation, and none was offered to show")
            return false
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val refusal = runCatching { lspApp.startActivity(confirm) }.exceptionOrNull() ?: return true
        Log.w(TAG, "Could not open the install confirmation", refusal)
        intent.noteUnshownConfirmation("the install needs a confirmation that could not be shown: $refusal")
        return false
    }

    /** Records why a confirmation went unshown, unless the installer already said something of its own. */
    private fun Intent.noteUnshownConfirmation(reason: String) {
        if (getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).isNullOrEmpty()) {
            putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, reason)
        }
    }

    private fun PackageInstaller.Session.writeApk(apk: File) {
        Log.d(TAG, "Add ${apk.name}")
        apk.inputStream().use { input ->
            openWrite(apk.name, 0, apk.length()).use { output ->
                input.copyTo(output)
                fsync(output)
            }
        }
    }

    /**
     * Uninstalls [packageName] through the platform installer (OS confirmation UI). Used, without Shizuku, to clear a
     * differently-signed original before a system install can replace it.
     */
    suspend fun uninstallBySystem(packageName: String, recordedApks: List<String> = emptyList()): Pair<Int, String?> {
        Log.i(TAG, "Perform system uninstall of $packageName")
        val context = lspApp
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        withContext(Dispatchers.IO) {
            runCatching {
                val result =
                    awaitUserAction("$INSTALL_ACTION.uninstall.$packageName", packageName.hashCode()) { sender ->
                        context.packageManager.packageInstaller.uninstall(packageName, sender)
                    }
                status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            }
                .onFailure {
                    status = PackageInstaller.STATUS_FAILURE
                    message = it.message + "\n" + it.stackTraceToString()
                }
        }
        // The platform installer acts for the user that called it and has no flag for the others: an app
        // this user shares with a secondary user or a work profile is still on record afterwards, keeping
        // its certificate, and the install this uninstall was performed for would be refused with a bare
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE. Said here instead, where the reader can act on it.
        val leftOver = knownPackage(packageName, 0) != null || signersOfSurvivingApk(recordedApks) != null
        if (status == PackageInstaller.STATUS_SUCCESS && leftOver) {
            status = PackageInstaller.STATUS_FAILURE
            message =
                "$packageName was removed for this user but is still on record: another user has it, or its " +
                    "data was kept. One apk and one certificate serve every user, so a patched build cannot " +
                    "replace it while that record stands. Removing it for all users needs Shizuku, or adb: " +
                    "pm uninstall $packageName"
        }
        Log.i(TAG, "System uninstall result: status=$status message=$message")
        return Pair(status, message)
    }

    /**
     * Installs a single downloaded apk — a store module or the manager's own self-update.
     *
     * Prefers Shizuku, which installs silently through the shell installer just as patched-app installs do, so no
     * confirmation dialog is needed. Only when Shizuku is unavailable does it fall back to the platform installer,
     * which shows the OS confirm dialog.
     */
    suspend fun installApk(apk: File): Pair<Int, String?> = installFiles(listOf(apk), useShizuku = true)

    /**
     * Whether [packageName] is installed and is NOT already an LSPatch build — read through the app's own
     * PackageManager (no Shizuku), so it is usable on the system-install fallback path.
     */
    fun isInstalledWithoutPatch(packageName: String): Boolean {
        val info = knownPackage(packageName, PackageManager.GET_META_DATA) ?: return false
        return info.applicationInfo?.metaData?.containsKey("lspatch") != true
    }

    /**
     * What the package manager will tell THIS user about [packageName], or null when it says nothing.
     *
     * [PackageManager.MATCH_UNINSTALLED_PACKAGES] covers one case the plain read does not: a package removed here
     * with its data kept, whose certificate is still on record. It does NOT reach a package that is installed in
     * another user and not in this one -- measured on Android 16, where it answers nothing for exactly that state.
     * Seeing another user's install needs MATCH_ANY_USER and INTERACT_ACROSS_USERS, which an app does not hold, so
     * that case is decided by [signersOfSurvivingApk] from the apk still on disk instead.
     */
    private fun knownPackage(packageName: String, flags: Int): android.content.pm.PackageInfo? =
        runCatching { lspApp.packageManager.getPackageInfo(packageName, flags) }.getOrNull()
            ?: runCatching {
                lspApp.packageManager.getPackageInfo(packageName, flags or PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
                .getOrNull()

    /**
     * Whether an already-installed [packageName] would have to be uninstalled before [patchedApk] can replace it — i.e.
     * Android would reject the update because the signing certificates differ.
     *
     * This compares the actual signers rather than assuming any non-LSPatch build clashes: a user signing with a custom
     * keystore that matches the installed app produces a patched apk Android will accept as an in-place update, and
     * that case must not be sent to the uninstall prompt. Signers are readable by any app, so no Shizuku is involved.
     *
     * Returns false when nothing is installed under that name (a clean install). Only when a signature cannot be read
     * on either side does it fall back to [isInstalledWithoutPatch], so an undetermined case still errs toward asking
     * rather than firing an install Android will refuse.
     */
    fun signatureBlocksUpdate(packageName: String, patchedApk: File, recordedApks: List<String> = emptyList()): Boolean {
        val installed =
            installedSigners(packageName) ?: signersOfSurvivingApk(recordedApks) ?: return false
        val patched = archiveSigners(patchedApk)
        if (installed.isEmpty() || patched.isEmpty()) return isInstalledWithoutPatch(packageName)
        return installed != patched
    }

    /**
     * Signers read from a recorded apk of the package that is still on disk, or null when none is.
     *
     * The one piece of evidence an unprivileged app has about a package installed in another user and not in this
     * one. These paths come from the package manager at a moment the app WAS visible here, they point into
     * `/data/app`, and that directory is deleted when the last user's install goes -- so a file still there means
     * the package is still installed somewhere, and its certificate is still what an install has to match. The apk
     * itself is world-readable, as every installed apk is, so nothing is needed to read it.
     *
     * Only ever called with paths the system owns ([PatchTarget.InstalledApp]); an apk the manager extracted itself
     * would exist regardless of what is installed and would prove nothing.
     */
    private fun signersOfSurvivingApk(recordedApks: List<String>): Set<Signature>? =
        recordedApks
            .asSequence()
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.let { apk ->
                archiveSigners(apk).takeIf { it.isNotEmpty() }?.also {
                    Log.i(TAG, "Read signers from ${apk.path}: still installed for another user")
                }
            }

    /** Signers on record for a package under any user; null when none is, empty when unreadable. */
    private fun installedSigners(packageName: String): Set<Signature>? =
        knownPackage(packageName, PackageManager.GET_SIGNING_CERTIFICATES)?.let { signersOf(it) }

    /** Signers of an apk on disk; empty when the archive cannot be read. */
    private fun archiveSigners(apk: File): Set<Signature> {
        val info =
            lspApp.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return emptySet()
        return signersOf(info)
    }

    /** The set of certificates that currently authenticate a package or archive (minSdk 28, so v2+ signing info). */
    private fun signersOf(info: android.content.pm.PackageInfo): Set<Signature> =
        info.signingInfo?.apkContentsSigners?.toSet().orEmpty()

    /**
     * Whether patching [packageName] would need an uninstall, judged before the apk is built so the pre-patch preview
     * can warn honestly. Compares the installed app's signers with the certificate of the currently-selected keystore
     * -- the same certificate the patched apk will carry -- so a custom keystore that matches the app is not
     * mislabelled as a conflict. Falls back to [isInstalledWithoutPatch] only when a signature cannot be read.
     */
    fun keystoreConflictsWith(packageName: String, recordedApks: List<String> = emptyList()): Boolean {
        val installed =
            installedSigners(packageName) ?: signersOfSurvivingApk(recordedApks) ?: return false
        val signer = selectedKeystoreSigner()
        if (installed.isEmpty() || signer == null) return isInstalledWithoutPatch(packageName)
        return installed != setOf(signer)
    }

    /** The signing certificate of the selected keystore as a [Signature]; null when it cannot be read. */
    private fun selectedKeystoreSigner(): Signature? = runCatching {
        val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        val cert =
            if (MyKeyStore.useDefault) {
                (lspApp.classLoader.getResourceAsStream("assets/keystore") ?: return null).use {
                    keyStore.load(it, "123456".toCharArray())
                }
                keyStore.getCertificate("key0")
            } else {
                java.io.FileInputStream(MyKeyStore.file).use {
                    keyStore.load(it, Configs.keyStorePassword.toCharArray())
                }
                keyStore.getCertificate(Configs.keyStoreAlias)
            }
        cert?.let { Signature(it.encoded) }
    }
        .getOrNull()

    /**
     * Drives one platform-installer action (install/uninstall) to completion. Registers a result receiver, hands the
     * installer a broadcast [IntentSender] via [commit], transparently launches the OS confirmation dialog on
     * STATUS_PENDING_USER_ACTION, and resumes with the terminal Intent.
     */
    private suspend fun awaitUserAction(
        action: String,
        requestCode: Int,
        commit: (IntentSender) -> Unit,
    ): Intent = suspendCoroutine { cont ->
        val context = lspApp
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION && launchConfirmation(intent)) {
                        return
                    }
                    runCatching { context.unregisterReceiver(this) }
                    cont.resume(intent)
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val pending =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        try {
            commit(pending.intentSender)
        } catch (t: Throwable) {
            // Nothing will fire the receiver now; unregister it and fail instead of hanging forever.
            runCatching { context.unregisterReceiver(receiver) }
            cont.resumeWithException(t)
        }
    }

    suspend fun uninstall(packageName: String): Pair<Int, String?> {
        Log.i(TAG, "Uninstall $packageName through Shizuku")
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        withContext(Dispatchers.IO) {
            runCatching {
                var result: Intent? = null
                withTimeoutOrNull(SILENT_ACTION_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                            result = intent
                            if (cont.isActive) cont.resume(Unit)
                        }
                        val intentSender = IntentSenderHelper.newIntentSender(adapter)
                        ShizukuApi.uninstallPackage(packageName, intentSender)
                    }
                }
                    ?: throw IOException(
                        "the uninstaller did not report a result within ${SILENT_ACTION_TIMEOUT_MS / 1000}s"
                    )
                result?.let {
                    status = it.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    message = it.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                } ?: throw IOException("Intent is null")
            }
                .onFailure {
                    status = PackageInstaller.STATUS_FAILURE
                    message = "Exception happened\n$it"
                }
        }
        Log.i(TAG, "Uninstall result: status=$status message=$message")
        return Pair(status, message)
    }

    suspend fun getAppInfoFromApks(apks: List<Uri>): Result<List<AppInfo>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                // Expand each selection into plain apk files first: a selection may be a single apk,
                // or an app bundle (.xapk/.apks/.apkm) — a zip whose entries are the base and split
                // apks of one app. A plain apk is itself a zip, but its entries are the manifest, dex
                // and resources, never a nested *.apk, so that presence cleanly tells the two apart.
                val apkFiles = mutableListOf<File>()
                for (uri in apks) {
                    val src = DocumentFile.fromSingleUri(lspApp, uri) ?: throw IOException("DocumentFile is null")
                    val copied = lspApp.tmpApkDir.resolve(src.name ?: "selected")
                    val input = lspApp.contentResolver.openInputStream(uri) ?: throw IOException("InputStream is null")
                    input.use {
                        copied.outputStream().use { output -> input.copyTo(output) }
                    }
                    val extracted = extractBundle(copied)
                    if (extracted != null) {
                        // The container zip itself is not an apk to patch; keep only its contents.
                        apkFiles.addAll(extracted)
                        copied.delete()
                    } else {
                        apkFiles.add(copied)
                    }
                }

                var primary: ApplicationInfo? = null
                val splits = mutableListOf<String>()
                val appInfos = apkFiles.mapNotNull { dst ->
                    val appInfo =
                        lspApp.packageManager
                            .getPackageArchiveInfo(
                                dst.absolutePath,
                                PackageManager.GET_META_DATA,
                            )
                            ?.applicationInfo
                    appInfo?.sourceDir = dst.absolutePath
                    if (appInfo == null) {
                        splits.add(dst.absolutePath)
                        return@mapNotNull null
                    }
                    if (primary == null) {
                        primary = appInfo
                    }
                    val label = lspApp.packageManager.getApplicationLabel(appInfo).toString()
                    AppInfo(appInfo, label, isModuleApk(appInfo))
                }
                // TODO: Check selected apks are from the same app
                primary?.splitSourceDirs = splits.toTypedArray()
                if (appInfos.isEmpty()) throw IOException("No apks")
                appInfos
            }
                .recoverCatching { t ->
                    cleanTmpApkDir()
                    Log.e(TAG, "Failed to load apks", t)
                    throw t
                }
        }
    }

    /**
     * The apks inside an app bundle, or null when [file] is a plain apk (or not a readable zip).
     *
     * A bundle (.xapk/.apks/.apkm) is a zip carrying a base apk and its splits, and is not itself an apk. A plain apk
     * is itself an apk -- it carries `AndroidManifest.xml` at its root -- and stays that even when it ships nested
     * `*.apk` files as assets (a skin, a plugin, an embedded installer). Telling the two apart by the presence of a
     * nested `*.apk` alone is wrong: an app that embeds one would be mistaken for a bundle, its real apk discarded as
     * the container, and one of its assets patched in its place. The root manifest is what distinguishes them, so a
     * file that has one is used as-is; only a container that is not an apk is expanded into its members, each into
     * tmpApkDir under its own basename for the split-install path to pick up.
     */
    private fun extractBundle(file: File): List<File>? = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            // Itself an apk (root manifest) -> not a bundle, whatever nested apks it may carry as assets.
            if (zip.getEntry("AndroidManifest.xml") != null) return@use null
            val apkEntries =
                zip.entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('/').endsWith(".apk", ignoreCase = true) }
                    .toList()
            if (apkEntries.isEmpty()) {
                null
            } else {
                apkEntries.map { entry ->
                    val out = lspApp.tmpApkDir.resolve(entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out
                }
            }
        }
    }
        .getOrNull()

    fun getLaunchIntentForPackage(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(Intent.CATEGORY_INFO)
        intentToResolve.setPackage(packageName)
        var ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) {
            intentToResolve.removeCategory(Intent.CATEGORY_INFO)
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER)
            intentToResolve.setPackage(packageName)
            ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)
        }

        if (ris.size <= 0) return null

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name,
            )
    }

    fun getSettingsIntent(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(SETTINGS_CATEGORY)
        intentToResolve.setPackage(packageName)
        val ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) return getLaunchIntentForPackage(packageName)

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name,
            )
    }
}
