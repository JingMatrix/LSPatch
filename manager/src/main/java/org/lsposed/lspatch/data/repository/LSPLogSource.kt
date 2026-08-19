package org.lsposed.lspatch.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.service.ManagerResidentService
import org.lsposed.lspatch.share.LSPConfig
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi
import org.matrix.vector.ui.logs.LogContent
import org.matrix.vector.ui.logs.LogFacetCounter
import org.matrix.vector.ui.logs.LogIndex
import org.matrix.vector.ui.logs.LogLevel
import org.matrix.vector.ui.logs.LogQuery
import org.matrix.vector.ui.logs.LogResetKind
import org.matrix.vector.ui.logs.LogRow
import org.matrix.vector.ui.logs.LogScanResult
import org.matrix.vector.ui.logs.LogSource
import org.matrix.vector.ui.logs.WriterLabeler
import org.matrix.vector.ui.logs.isThrowableHeader

/**
 * LSPatch's Shizuku-backed implementation of the shared Logs screen's [LogSource].
 *
 * Where Vector streams a rotating file from a root daemon, LSPatch has [ManagerResidentService] keep a shell-side
 * collector running continuously (see [ShizukuService]): it fans one live logcat into two rotating, timestamped stream
 * files the shell user owns — `verbose` (every line) and `framework`. This reads those parts back — so the screen's
 * part chevrons are real rotations, and logs captured while the screen was closed are still there — falling back to a
 * one-shot `logcat -d` snapshot only in the gap before the collector has produced anything.
 *
 * The framework stream is routed at collection time by uid alone: a line joins it when it comes from the manager, a
 * patched app or a module (their uids pushed to the collector), or is fatal whoever wrote it. So the read side just
 * parses the already-routed part — no per-line package resolution, and no tag to second-guess.
 */
class LSPLogSource(private val context: Context) : LogSource {

    private val writers = WriterLabeler(context.packageManager)

    override fun writerLabel(uid: Int): String? = writers.label(uid)

    private val _wordWrap = MutableStateFlow(false)
    override val wordWrap: StateFlow<Boolean> = _wordWrap.asStateFlow()

    // Default on -- a trace opens under its line; turning the setting off routes it to the shared
    // trace screen the Logs page navigates to instead.
    private val _tracesInline = MutableStateFlow(true)
    override val tracesInline: StateFlow<Boolean> = _tracesInline.asStateFlow()

    /**
     * Only while the shell can read the device log.
     *
     * Without it the screen shows this process's own log, which logcat hands over unrotated and whole — there are no
     * parts to page through, and the verbose stream would be the same lines again, so the unfold control goes with
     * them.
     */
    override val hasVerboseStream: Boolean
        get() = ShizukuApi.isPermissionGranted

    override suspend fun parts(verbose: Boolean): List<String> =
        if (!ShizukuApi.isPermissionGranted) emptyList()
        else
        // Both streams are collected and rotated on disk now, so both page through real parts: the
        // verbose stream is every line, the framework stream is the uid/crash-routed subset the
        // collector wrote separately (see [ShizukuService]).
        // Only parts with something in them, matching what [open] reads back: a rotation opens an
        // empty part, and listing it would number the chevrons one ahead of the content, with the
        // last one selectable and blank.
        ShizukuApi.listLogParts(ManagerResidentService.LOG_DIR, streamPrefix(verbose))
                .filter { it.second > 0L }
                .map { it.first }

    override suspend fun open(verbose: Boolean, part: String?): Result<LogContent?> {
        // The fallback: an app may always read its own process's log, so a manager without Shizuku
        // is not blind — it just cannot see past itself. Worth more than an empty screen exactly
        // when something is wrong, since a Shizuku that will not start is the case where the
        // manager's own lines are the only account of why.
        if (!ShizukuApi.refresh()) {
            val own =
                withContext(Dispatchers.IO) { ownProcessLog() }
                    ?: return Result.failure(IOException("the device log is not readable without Shizuku"))
            val entries =
                withContext(Dispatchers.Default) {
                    parseLogcat(own).mapIndexed { i, e -> e.copy(index = i) }
                }
            return Result.success(LogcatContent(entries))
        }
        val prefix = streamPrefix(verbose)
        val raw =
            withContext(Dispatchers.IO) {
                if (part != null) {
                    readTail(part)
                } else {
                    // The newest part, whatever is in it. A part just opened by "start a new log" is
                    // empty, and reading the one before it instead would answer a request to start
                    // afresh with the log the reader asked to leave behind.
                    val newest = ShizukuApi.listLogParts(ManagerResidentService.LOG_DIR, prefix).lastOrNull()?.first
                    // Only when the collector has produced no part at all (Shizuku just granted, the
                    // service still spinning up) does a one-shot snapshot stand in for one.
                    if (newest != null) readTail(newest)
                    else ShizukuApi.runShellCommand(snapshotCommand(verbose, ShizukuApi.supportsUidColumn()))
                }
            } ?: return Result.failure(IOException("the Shizuku shell service is unavailable"))

        val entries =
            withContext(Dispatchers.Default) {
                val all = parseLogcat(raw)
                // A collected framework part is already the routed stream, so it needs no further
                // filtering; only the fallback snapshot — a plain tag-filtered logcat standing in for
                // it until the collector produces a part — is left as logcat filtered it.
                // Re-index densely: the window logic addresses entries by position, and the lazy list
                // keys on the entry's index, so the two must agree after any lines were dropped.
                all.mapIndexed { i, e -> if (e.index == i) e else e.copy(index = i) }
            }
        return Result.success(LogcatContent(entries))
    }

    override val canConfigureVerbose: Boolean = false

    override suspend fun isVerboseEnabled(): Boolean = false

    override suspend fun setVerboseEnabled(enabled: Boolean): Boolean = enabled

    override val canSaveArchive: Boolean = true
    override val archiveMimeType: String = "application/zip"

    override fun archiveName(): String =
        "lspatch-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"

    /**
     * A bug-report archive, the LSPatch answer to Vector's daemon report: a zip gathering everything a report can act
     * on, pulled through the Shizuku shell. It carries the whole collected history — both the verbose and the framework
     * stream, part by part — plus the crash artefacts a shell user can reach with adb-level rights: tombstones, ANR
     * traces, and the manager's own process state. There is no separate one-shot logcat (the rotations already are the
     * log) and no dmesg. Every shell capture is best-effort and tail-capped for the Binder limit, so an unreadable one
     * (a tombstone dir a stricter build denies) is simply omitted rather than failing the export.
     */
    override suspend fun saveArchive(uri: Uri, verbose: Boolean): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val out =
                context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("could not open the document to write")
            ZipOutputStream(out.buffered()).use { zip ->
                val c = LSPConfig.instance
                zip.setComment(
                    "LSPatch ${c.VERSION_NAME} (${c.VERSION_CODE}) API ${c.API_CODE} " +
                        "Vector ${c.CORE_VERSION_NAME} ${c.CORE_VERSION_HASH}"
                )

                suspend fun entry(name: String, body: String?) {
                    if (body.isNullOrEmpty()) return
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }

                fun fileEntry(name: String, file: File) {
                    if (!file.exists() || file.length() == 0L) return
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                // A shell-side file copied whole into the archive, a chunk at a time.
                //
                // Reading it in one call cannot work — the Binder transaction limit truncates it —
                // and truncation here is not a smaller report but a wrong one: the head of a
                // tombstone is the crash (process, signal, backtrace) and the head of a log part is
                // everything that led to the failure being reported. The entry is opened only once
                // the first chunk arrives, so an unreadable file leaves no empty entry behind.
                suspend fun shellFileEntry(name: String, path: String) {
                    var offset = 0L
                    var opened = false
                    while (true) {
                        val chunk = ShizukuApi.readFileChunk(path, offset, CHUNK_BYTES) ?: break
                        if (chunk.isEmpty()) break
                        if (!opened) {
                            zip.putNextEntry(ZipEntry(name))
                            opened = true
                        }
                        zip.write(chunk)
                        offset += chunk.size
                        if (chunk.size < CHUNK_BYTES) break
                    }
                    if (opened) zip.closeEntry()
                }

                // A directory captured file-by-file into a zip folder, the way Vector's daemon
                // addDir does it — one entry per file under [prefix], preserving the structure —
                // rather than flattening everything into one blob. The shell lists and reads each
                // file (the app cannot reach these paths cross-UID). Empty when it has no rights.
                suspend fun addDir(prefix: String, dir: String) {
                    val names =
                        ShizukuApi.runShellScript("ls -1 $dir 2>/dev/null")
                            .orEmpty()
                            .lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toList()
                    for (name in names) {
                        shellFileEntry("$prefix/$name", "$dir/$name")
                    }
                }

                // Asked once, so the archive is assembled against one answer: without the shell the
                // entries that need it are skipped outright rather than each failing its way to a
                // dialog that would tell the reader an export they just completed went wrong.
                val shizuku = ShizukuApi.refresh()

                entry("device.txt", deviceReport())
                // First, and written by the app itself rather than through the shell: when Shizuku
                // is the thing that is broken, every other entry below is empty, and this one is
                // the whole report.
                entry("shizuku.txt", shizukuReport())
                entry("packages.txt", packageReport())
                // Who, if anyone, can install an apk on this device. A silent install through the
                // shell needs no installer app, but a session that asks for confirmation needs one
                // to host the dialog, and the fallback through the platform installer needs one
                // outright -- so a report about an install that would not go through has to say
                // whether the device still has one.
                entry("installers.txt", installerReport())
                // The patch reports: the app's own account of every recent patch, install and
                // restore, with the installer's status and message. For a report about an install
                // that would not go through, this is the entry that answers the question — and the
                // one the reader cannot fetch themselves, since it lives in app-private storage.
                context.noBackupFilesDir
                    .resolve("patch-logs")
                    .listFiles()
                    ?.sortedBy { it.name }
                    ?.forEach { fileEntry("patch-reports/${it.name}", it) }

                // A verbatim copy of the module/scope database (the app owns it, so no shell is
                // needed), the way Vector's report ships modules_config.db. The -wal/-shm side
                // files go too, so the copy can be replayed to the exact committed + pending state.
                val db = context.getDatabasePath(CONFIG_DB)
                fileEntry("database/${db.name}", db)
                fileEntry("database/${db.name}-wal", File("${db.path}-wal"))
                fileEntry("database/${db.name}-shm", File("${db.path}-shm"))
                // Both streams' collected rotations, oldest first — the report's own history. With
                // no shell there is no history to ship, so the archive carries what the screen is
                // showing instead: this process's own log, which is the whole of what can be read.
                // An export that silently dropped it would be a report about a broken Shizuku with
                // none of the lines describing the breakage.
                if (shizuku) {
                    for (prefix in listOf("verbose", "framework")) {
                        ShizukuApi.listLogParts(ManagerResidentService.LOG_DIR, prefix).forEach { (path, _) ->
                            shellFileEntry("logs/${path.substringAfterLast('/')}", path)
                        }
                    }
                } else {
                    entry("logs/own-process.log", ownProcessLog())
                }
                // Native crash dumps, as their own folder. (ANR traces are omitted: /data/anr is
                // not readable at the shell's rights — it needs a root dumpstate/bugreport.)
                if (shizuku) addDir("tombstones", "/data/tombstones")
                // "self": the manager's own live process state, each file on its own like Vector's
                // proc/<pid> folder, so a report shows what the app was doing. Read straight from
                // /proc/self — a process may always read its own — rather than through the shell,
                // which would make the most self-descriptive part of the report the first to vanish
                // when the shell is what failed.
                entry("self/status", readOwnProc("status"))
                entry("self/cmdline", readOwnProc("cmdline")?.replace('\u0000', ' ')?.trim())
                entry("self/maps", readOwnProc("maps"))
                if (shizuku) {
                    entry("getprop.txt", ShizukuApi.runShellCommand("getprop"))
                    entry("ps.txt", ShizukuApi.runShellCommand("ps -A -o PID,PPID,USER,NAME"))
                }
            }
        }
    }

    /**
     * What Shizuku is doing, and everything it refused this session — traces included.
     *
     * The app's own record, not the shell's: it is the one part of the archive that survives a Shizuku that never
     * worked, which is exactly the report that is hardest to answer without it.
     */
    private fun shizukuReport(): String = buildString {
        val granted = ShizukuApi.refresh()
        appendLine("Granted: $granted")
        appendLine("Binder available: ${ShizukuApi.isBinderAvailable}")
        appendLine("Server API: ${ShizukuApi.serverVersion() ?: "-"}")
        appendLine("Server uid: ${ShizukuApi.serverUid() ?: "-"}")
        appendLine("Shell service bound: ${ShizukuApi.isShellServiceBound}")
        val failures = ShizukuApi.recentFailures()
        appendLine()
        appendLine("Failures: ${failures.size}")
        failures.forEach { failure ->
            appendLine()
            appendLine("[${failure.op}] ${failure.reason}: ${failure.detail}")
            failure.trace?.let { appendLine(it.trimEnd()) }
        }
    }

    /** Build, framework and device facts — the header of any report. */
    private fun deviceReport(): String {
        val c = LSPConfig.instance
        return buildString {
            appendLine("LSPatch: ${c.VERSION_NAME} (${c.VERSION_CODE})")
            appendLine("Xposed API: ${c.API_CODE}")
            appendLine("Vector: ${c.CORE_VERSION_NAME} (${c.CORE_VERSION_CODE}) ${c.CORE_VERSION_HASH}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
        }
    }

    /**
     * The device's install machinery, as the manager can see it without any privilege.
     *
     * Reads the platform's own answers -- what resolves an install intent, whether those packages are enabled, whether
     * this app may request an install at all -- rather than assuming the stock installer is present. Replacing it is
     * something people do.
     */
    private suspend fun installerReport(): String {
        val pm = context.packageManager
        return buildString {
            appendLine(
                "Can request installs: " +
                    runCatching { pm.canRequestPackageInstalls() }.getOrElse { "unknown (${it.javaClass.simpleName})" }
            )
            appendLine()
            // Both schemes, because they do not resolve alike and the difference decides how an apk
            // could be handed to another installer at all: an app-private file can only travel as a
            // content uri, and a handler that registers only `file` cannot receive one.
            for (action in listOf(Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_VIEW)) {
                for (scheme in listOf("content://lspatch.example/apk", "file:///apk")) {
                    appendLine("Handles ${action.substringAfterLast('.')} of $scheme:")
                    appendLine(handlersOf(installIntent(action, scheme)))
                }
            }
            // The confirmation a committed session can ask for is shown by an installer component,
            // named rather than resolved, so whether anything answers it decides whether a session
            // that asks can ever be completed.
            appendLine("Verifies shell installs: ${ShizukuApi.verifiesShellInstalls() ?: "unknown"}")
            appendLine()
            appendLine("Handles CONFIRM_INSTALL:")
            appendLine(handlersOf(Intent("android.content.pm.action.CONFIRM_INSTALL")))
            appendLine("Known installer packages:")
            for (name in KNOWN_INSTALLERS) appendLine("  $name: ${packageState(name)}")
        }
    }

    private fun installIntent(action: String, uri: String): Intent =
        Intent(action)
            .setDataAndType(uri.toUri(), "application/vnd.android.package-archive")
            .addCategory(Intent.CATEGORY_DEFAULT)

    private fun handlersOf(intent: Intent): String = runCatching {
        val handlers = context.packageManager.queryIntentActivities(intent, 0)
        if (handlers.isEmpty()) "  (none)"
        else
            handlers.joinToString("\n") {
                "  ${it.activityInfo.packageName}/${it.activityInfo.name} enabled=${it.activityInfo.enabled}"
            }
    }
        .getOrElse { "  (query failed: $it)" }

    /** Installed, enabled, and at what version -- or absent, which is the answer that matters here. */
    private fun packageState(packageName: String): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(packageName, 0)
        val setting = pm.getApplicationEnabledSetting(packageName)
        "installed ${info.versionName}, enabledSetting=$setting, appEnabled=${info.applicationInfo?.enabled}"
    }
        .getOrElse { "not installed" }

    /** Every patched app and module by package name, so a report names exactly what is in play. */
    private fun packageReport(): String {
        val modules = LSPPackageManager.appList.filter { it.isModule }
        val patched = LSPPackageManager.appList.filter { it.app.metaData?.containsKey("lspatch") == true }
        return buildString {
            appendLine("Modules (${modules.size}):")
            if (modules.isEmpty()) appendLine("  (none)")
            else modules.forEach { appendLine("  ${it.app.packageName}  ${it.label}") }
            appendLine()
            appendLine("Applications (${patched.size}):")
            if (patched.isEmpty()) appendLine("  (none)")
            else patched.forEach { appendLine("  ${it.app.packageName}  ${it.label}") }
        }
    }

    // ROTATE, like Vector -- not a destructive clear. The old clear stopped the collector, wiped both
    // streams and ran `logcat -c`, then restarted; the restart raced the wipe and framework collection
    // could come back empty (the reported "clear stops the framework log" bug). Starting a new part
    // keeps the collector running, so there is never a gap.
    // Rotation is the collector's, so it is offered only while there is a collector to ask.
    override val resetKind: LogResetKind?
        get() = if (ShizukuApi.isPermissionGranted) LogResetKind.ROTATE else null

    override suspend fun reset(verbose: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            // Non-destructive: the collector closes the current parts and opens fresh ones, deleting
            // nothing -- the closed parts stay as chevrons until the rotation cap prunes them. If no
            // collector is running yet (Shizuku just granted), starting it opens fresh parts, the same
            // fresh-slate outcome.
            if (ShizukuApi.isLogCollectorRunning()) {
                ShizukuApi.startNewLogPart()
            } else {
                ShizukuApi.startLogCollector(
                    ManagerResidentService.LOG_DIR,
                    ManagerResidentService.relevantUids(context),
                )
            }
        }

    override fun setWordWrap(enabled: Boolean) {
        _wordWrap.value = enabled
    }

    override fun setTracesInline(inline: Boolean) {
        _tracesInline.value = inline
    }

    /** The collector's file prefix for each stream — the two it fans logcat into. */
    private fun streamPrefix(verbose: Boolean): String = if (verbose) "verbose" else "framework"

    /**
     * This process's own log, read without any privilege.
     *
     * logcat hands an app the entries of its own uid and no others, so no permission is needed and none is asked for;
     * `--pid` narrows that to this process. Run in-process rather than through the shell service, because the whole
     * point is that the shell service is what is missing.
     */
    private fun ownProcessLog(): String? = runCatching {
        // `-v uid` here too: an entry parsed without that column has no writer to select by, and the
        // search terms that name one would answer "no matches" on a screen that is showing its lines.
        val command = arrayOf("logcat", "-d", "-v", "uid", "-v", "threadtime", "--pid=${android.os.Process.myPid()}")
        val process = Runtime.getRuntime().exec(command)
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.takeIf { it.isNotBlank() }
    }
        .getOrNull()

    /** One of this process's own /proc files, or null when it cannot be read. */
    private fun readOwnProc(name: String): String? = runCatching {
        File("/proc/self/$name").readText().takeIf { it.isNotBlank() }
    }
        .getOrNull()

    /**
     * The newest [LIVE_MAX] bytes of a part, fetched a piece at a time.
     *
     * A whole tail asked for in one call comes back in one reply, and a reply shares a single buffer of about a
     * megabyte with every other transaction the process has in flight -- so the live tail, re-read on every poll, could
     * exhaust it and fail the small calls around it ("transaction failed on small parcel", which is the buffer being
     * full rather than anything having died). Asked for in pieces, no single reply is large enough to matter.
     *
     * Bytes, not text: the log is very nearly ASCII, and a String would cross as UTF-16 and double it. The pieces are
     * joined before they are decoded, since a boundary inside a multi-byte character would corrupt both sides of it;
     * the first line may still be a fragment, which the parser drops as it always has.
     */
    private suspend fun readTail(path: String): String? {
        val size = ShizukuApi.fileSize(path)
        // Told apart on purpose: no shell is a failure to report, while a part that is empty or has
        // been rotated away is simply an empty reading.
        if (size < 0L) return null
        if (size == 0L) return ""
        var offset = (size - LIVE_MAX).coerceAtLeast(0L)
        val out = java.io.ByteArrayOutputStream()
        while (offset < size) {
            val chunk = ShizukuApi.readFileChunk(path, offset, CHUNK_BYTES) ?: return null
            if (chunk.isEmpty()) break
            out.write(chunk)
            offset += chunk.size
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * The one-shot stand-in used only until the collector has produced a part.
     *
     * Selected the same way the collector routes — by uid — so the stand-in shows the same log the stream will, rather
     * than a differently-filtered one. A tag list stood here before and could not name what it did not know: a module
     * chooses its own tag, so the module the reader came to see was precisely what it left out.
     */
    private fun snapshotCommand(verbose: Boolean, uidColumn: Boolean): String =
        if (verbose) "logcat -d ${if (uidColumn) "-v uid " else ""}-v threadtime -t 4000"
        else if (!uidColumn) {
            // Without the uid column there is nothing to select writers by and nothing to select them
            // with, so the stand-in is the whole recent log rather than a command this logcat would
            // refuse -- which would leave the screen blank, the one thing this exists to avoid.
            "logcat -d -b main -b crash -b system -v threadtime -t 4000"
        } else {
            val uids = ManagerResidentService.relevantUids(context).joinToString(",")
            // No -t here, though the verbose form has one: logd applies -t itself, over every buffer,
            // and --uid is applied afterwards by logcat on what it was sent. The last N lines of a
            // chatty device can hold almost nothing from these uids, so the window would decide the
            // answer. The transport tail-caps the reply instead, which cuts from the far end.
            "logcat -d -b main -b crash -b system -v uid -v threadtime --uid=$uids"
        }

    private companion object {
        /** How much of a part's tail the screen reads, in bytes; fetched in [CHUNK_BYTES] pieces. */
        const val LIVE_MAX = 400_000L

        /**
         * How much of a file one call carries back.
         *
         * Not "as much as a transaction allows": the ~1 MB is a buffer the whole process shares with everything else in
         * flight, so a reply sized to fill it starves its neighbours instead of failing alone. A quarter of that leaves
         * room for the calls happening around it and still crosses a part in a handful of round trips.
         */
        const val CHUNK_BYTES = 128 * 1024

        /** The Room database of modules and their scopes; copied into the export. */
        const val CONFIG_DB = "modules_config.db"

        /** The stock installers, listed by name so the report says "absent" rather than saying nothing. */
        val KNOWN_INSTALLERS =
            listOf(
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.android.permissioncontroller",
            )
    }
}

/**
 * A [LogContent] over an in-memory `threadtime` snapshot.
 *
 * The snapshot is bounded and already parsed, so this satisfies the windowed reader contract trivially: the "index" is
 * a dense line map, a window is a slice of the list, and a scan is one pass over it. `threadtime` carries no multi-line
 * writev the way the daemon's own framing does, so each element is already one logical entry — a plain line, or a crash
 * [parseLogcat] folded, whose frames live in its [LogRow.Entry.continuation] and whose own line stays its header.
 * Either way one index addresses one entry, so [entryStart] is the identity: a window boundary lands on an entry that
 * already carries its whole trace.
 */
class LogcatContent(private val entries: List<LogRow.Entry>) : LogContent {

    override suspend fun index(): LogIndex = LogIndex(LongArray(entries.size + 1) { it.toLong() }, droppedLeading = 0)

    override suspend fun readRows(index: LogIndex, lines: IntArray): List<LogRow> {
        val rows = ArrayList<LogRow>(lines.size + 8)
        var lastDate: String? = null
        for (line in lines) {
            val entry = entries.getOrNull(line) ?: continue
            if (entry.date != lastDate) {
                lastDate = entry.date
                rows.add(LogRow.DayBreak(line, entry.date))
            }
            rows.add(entry)
        }
        return rows
    }

    override fun entryStart(index: LogIndex, line: Int): Int = line

    override suspend fun scan(
        index: LogIndex,
        query: LogQuery,
        onProgress: (Float) -> Unit,
    ): LogScanResult {
        val matches = if (query.isActive) ArrayList<Int>() else null
        val facets = LogFacetCounter(query)
        val total = entries.size.coerceAtLeast(1)
        entries.forEachIndexed { i, entry ->
            if (facets.add(entry)) matches?.add(i)
            if (i and 0x1FF == 0) onProgress(i.toFloat() / total)
        }
        onProgress(1f)
        return LogScanResult(matches = matches?.toIntArray(), facets = facets.facets())
    }

    override fun close() {}
}

// "MM-DD HH:MM:SS.mmm  PID  TID L Tag: message" — the threadtime format, and the same with the
// writer's uid ahead of the pid, which is how the collector records it (`-v uid -v threadtime`). The
// uid column is optional here rather than a second pattern: a plain line makes the group swallow its
// pid, then finds a level where it needs a tid, and backtracks out of the group onto the right
// reading. A uid the platform spells out by name ("radio") is not a number and reads as unknown.
private val THREADTIME =
    Regex("""^(\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3})\s+(?:(\S+)\s+)?(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?):\s?(.*)$""")

/**
 * Parses a `logcat -v threadtime` dump into entries, dropping anything that is not a log line.
 *
 * `threadtime` reprints the prefix on every physical line, so a crash arrives as dozens of separate entries — one `E
 * AndroidRuntime: FATAL EXCEPTION…` header and a run of `\tat …` / `Caused by:` / `… N more` lines, or a native
 * tombstone under `DEBUG`/`libc`. Left flat they show as dozens of rows and the shared backtrace UI, which folds an
 * entry's [LogRow.Entry.continuation] into a foldable trace, never sees one. So a second pass folds each crash block
 * into a single entry whose header line stays the message and whose following lines become the continuation — the shape
 * Vector's own `LogFile` produces, so the same StackTrace renderer applies unchanged.
 */
fun parseLogcat(raw: String): List<LogRow.Entry> {
    // Pass one: every physical threadtime line, still flat.
    val flat = ArrayList<LogRow.Entry>()
    for (line in raw.lineSequence()) {
        val match = THREADTIME.matchEntire(line) ?: continue
        flat +=
            LogRow.Entry(
                index = flat.size,
                date = match.groupValues[1],
                time = match.groupValues[2],
                // -1, not 0: 0 is root, and a line whose uid could not be read is not root's.
                uid = match.groupValues[3].toIntOrNull() ?: -1,
                pid = match.groupValues[4].toIntOrNull() ?: 0,
                tid = match.groupValues[5].toIntOrNull() ?: 0,
                level = LogLevel.of(match.groupValues[6][0]),
                tag = match.groupValues[7].trim(),
                message = match.groupValues[8],
            )
    }
    return foldCrashes(flat)
}

/** The tags that carry a crash dump, each line of which threadtime reprints under the same tag. */
private fun isCrashTag(tag: String): Boolean = tag == "AndroidRuntime" || tag == "DEBUG" || tag == "libc"

private val JAVA_MORE = Regex("""^\s*\.\.\. \d+ more$""")
private val NATIVE_FRAME = Regex("""^\s*#\d+\s+pc\b.*""")

// Flush-left lines a native tombstone prints between its indented registers and frames.
private val NATIVE_MARKERS =
    listOf(
        "*** ",
        "Build fingerprint:",
        "Revision:",
        "ABI:",
        "Timestamp:",
        "Process uptime:",
        "Cmdline:",
        "pid:",
        "signal ",
        "Abort message:",
        "backtrace:",
        "stack:",
        "memory near",
        "code around",
    )

/**
 * Whether [message] continues the crash its header began, rather than starting something new.
 *
 * Deliberately generous, because the tag is already pinned to one crash stream and the pid to one process: a run of
 * same-tag, same-pid lines under `AndroidRuntime`/`DEBUG`/`libc` is a single dump. Indented lines (frames, registers,
 * memory) are caught by their leading whitespace; the flush-left links of a Java chain and the headings of a native
 * dump are named out.
 */
/**
 * Whether [message] is a frame of a Java stack trace.
 *
 * Stricter than [continuesCrash], and deliberately so: this is the test applied under *ordinary* tags, where a leading
 * space means nothing in particular and swallowing every indented line would fold unrelated output into whatever
 * happened to precede it. Only the shapes `Throwable` actually prints are accepted.
 */
private fun continuesJavaTrace(message: String): Boolean {
    val trimmed = message.trimStart()
    if (trimmed.startsWith("at ")) return true
    if (JAVA_MORE.matches(message)) return true
    if (trimmed.startsWith("Caused by:") || trimmed.startsWith("Suppressed:")) return true
    return false
}

private fun continuesCrash(message: String): Boolean {
    if (message.isBlank()) return true
    if (message[0] == '\t' || message[0] == ' ') return true // frames, registers, memory dumps
    if (message.startsWith("at ")) return true
    if (JAVA_MORE.matches(message)) return true
    if (message.startsWith("Caused by:") || message.startsWith("Suppressed:")) return true
    if (message.startsWith("Process:")) return true // AndroidRuntime's second line
    if (isThrowableHeader(message)) return true // the thrown type, whether or not obfuscation kept its suffix
    if (NATIVE_FRAME.matches(message)) return true
    return NATIVE_MARKERS.any(message::startsWith)
}

/**
 * Folds each trace or crash block into one entry, then re-indexes densely.
 *
 * Two rules, because there are two kinds of block. A **crash-tagged** line (`AndroidRuntime`, `DEBUG`, `libc`) opens a
 * dump whose continuation is generous: the tag already pins it to one crash stream, so indented frames, registers and
 * native headings all belong to it.
 *
 * Every **other** tag can still print a stack trace -- `Log.w(TAG, msg, throwable)` puts the message and then its `at
 * …` frames under that tag, and a great deal of the manager's own diagnostics are logged exactly that way. Those fold
 * under the stricter [continuesJavaTrace] test, and only when a trace actually follows -- either the next line is
 * already a frame, or, for a **banner** (a line that is not itself a throwable header, the notice a logger prints above
 * the exception it is about), the next line is a throwable header and the one after it a frame. A banner then absorbs
 * that header and its frames, so the whole notice reads as one entry; a header-led trace does not absorb a *second*
 * top-level header, so two back-to-back traces stay two entries.
 *
 * Dense re-indexing keeps position and [LogRow.index] in step, which the windowed reader and the lazy list both rely
 * on.
 */
private fun foldCrashes(flat: List<LogRow.Entry>): List<LogRow.Entry> {
    val out = ArrayList<LogRow.Entry>(flat.size)
    var i = 0
    while (i < flat.size) {
        val head = flat[i]
        val crash = isCrashTag(head.tag)
        fun sameStream(row: LogRow.Entry?) = row != null && row.tag == head.tag && row.pid == head.pid
        val next = flat.getOrNull(i + 1)
        val banner = !crash && !isThrowableHeader(head.message)
        val opensTrace =
            sameStream(next) &&
                (continuesJavaTrace(next!!.message) ||
                    (banner &&
                        isThrowableHeader(next.message) &&
                        sameStream(flat.getOrNull(i + 2)) &&
                        continuesJavaTrace(flat[i + 2].message)))
        if (!crash && !opensTrace) {
            out += head
            i++
            continue
        }
        val continues: (String) -> Boolean =
            when {
                crash -> ::continuesCrash
                banner -> { msg ->
                    continuesJavaTrace(msg) || isThrowableHeader(msg)
                }
                else -> ::continuesJavaTrace
            }
        val continuation = ArrayList<String>()
        var j = i + 1
        while (j < flat.size) {
            val row = flat[j]
            if (row.tag != head.tag || row.pid != head.pid || !continues(row.message)) break
            continuation += row.message
            j++
        }
        out += if (continuation.isEmpty()) head else head.copy(continuation = continuation)
        i = j
    }
    return out.mapIndexed { idx, e -> if (e.index == idx) e else e.copy(index = idx) }
}
