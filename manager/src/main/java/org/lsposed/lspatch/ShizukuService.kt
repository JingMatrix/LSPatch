package org.lsposed.lspatch

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class ShizukuService : IShizukuService.Stub() {

    init {
        Log.i(TAG, "Shell service starting: pid ${android.os.Process.myPid()}, uid ${android.os.Process.myUid()}")
        reapPreviousInstances()
    }

    /**
     * Kills any earlier instance of this same service still running.
     *
     * A client that dies without unbinding -- a force-stop, a crash, an upgrade -- leaves its shell process behind:
     * several were seen alive at once, surviving force-stops of the manager. Only a shell-uid process may kill another,
     * and this is one, so the newest instance clears its own predecessors. The match is this process's exact name read
     * from `/proc`, not a hardcoded package, so a renamed (cloaked) manager reaps its own strays and nothing else's.
     */
    private fun reapPreviousInstances() {
        runCatching {
            val self = android.os.Process.myPid()
            val name = File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
            if (name.isEmpty()) return
            val ps =
                Runtime.getRuntime()
                    .exec(arrayOf("sh", "-c", "ps -A -o PID,NAME"))
                    .inputStream
                    .bufferedReader()
                    .readText()
            ps.lineSequence().forEach { line ->
                val columns = line.trim().split(Regex("\\s+"))
                if (columns.size < 2 || columns[1] != name) return@forEach
                val pid = columns[0].toIntOrNull() ?: return@forEach
                if (pid != self) {
                    Log.i(TAG, "Reaping a previous instance of this service: pid $pid")
                    android.os.Process.killProcess(pid)
                }
            }
        }
    }

    /**
     * The running `logcat` collector, or null. It streams to this service's own stdout, which a [readerThread] drains
     * and fans into two rotating on-disk streams; a pipe nobody drained would eventually block logcat, so the reader
     * must keep running for the collector's whole life.
     */
    @Volatile private var collector: Process? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var running = false

    /**
     * Which collector a reader belongs to.
     *
     * `running` alone cannot stop the right reader: a restart clears it and sets it again, so a reader that had not yet
     * noticed the clear went on writing next to its replacement -- two readers, two live `logcat` children, and every
     * line collected twice (seen on disk as pairs of parts opened milliseconds apart with byte-identical sizes). Each
     * reader captures the generation it was started for and stops as soon as that is no longer the current one.
     */
    @Volatile private var generation = 0

    /**
     * The uids whose every line belongs in the framework stream: the manager, its patched apps and their modules.
     *
     * Read by the reader thread on each line rather than captured when the collector started, because the set changes
     * while collection runs -- an app is patched, a module installed -- and [updateLogCollectorUids] replaces it in
     * place. Volatile assignment of an immutable set, so the reader never sees a half-built one.
     */
    @Volatile private var relevantUids: Set<Int> = emptySet()

    /** The probe's answer, which cannot change while this process lives. */
    @Volatile private var cachedUidColumn: Boolean? = null

    // Set by startNewLogPart(), consumed by the reader thread on its next line: it rotates both
    // writers to fresh parts. A flag rather than a direct call because RotatingWriter is not
    // thread-safe (one writer per reader thread), so only the reader may touch it.
    @Volatile private var rotateRequested = false

    override fun runShellCommand(cmd: String): String {
        Log.v(TAG, "runShellCommand: $cmd")
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val combined = output + error
            // The result crosses Binder as a UTF-16 String — two bytes per char — so ~512K chars
            // already approaches the 1 MB transaction limit and a large `logcat` dump would throw
            // TransactionTooLargeException on the way back, reaching the caller as null. Keep the
            // tail: for a log dump the most recent lines are the ones worth reading.
            if (combined.length > MAX_OUTPUT_CHARS) combined.takeLast(MAX_OUTPUT_CHARS) else combined
        } catch (e: Exception) {
            Log.w(TAG, "runShellCommand failed: $cmd", e)
            e.stackTraceToString()
        }
    }

    /** Runs a shell script via `sh -c`; combined stdout+stderr, tail-capped to MAX_OUTPUT_CHARS for Binder. */
    override fun runShellScript(script: String): String {
        Log.v(TAG, "runShellScript: ${script.take(200)}")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", script))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val combined = output + error
            if (combined.length > MAX_OUTPUT_CHARS) combined.takeLast(MAX_OUTPUT_CHARS) else combined
        } catch (e: Exception) {
            Log.w(TAG, "runShellScript failed", e)
            e.stackTraceToString()
        }
    }

    /**
     * Starts continuous log collection, fanning one live `logcat` into two rotating streams named
     * `verbose_<timestamp>.log` (every line) and `framework_<timestamp>.log`. A line joins the framework stream when it
     * comes from one of [relevantUids] -- the manager, a patched app or a module, resolved by the caller -- or is
     * fatal, whoever wrote it, so a tombstone or a native abort is kept even though it is logged by a system process.
     * Nothing is judged by its tag: a module names its own, so no list of tags could know it in advance, and one that
     * did not know it dropped exactly the lines the reader opened the screen for.
     *
     * Timestamped names sort chronologically by name, so no meaningless numeric suffixes.
     */
    @Synchronized
    override fun startLogCollector(logDir: String, relevantUids: IntArray): Boolean {
        Log.i(TAG, "Starting the log collector in $logDir for ${relevantUids.size} uid(s)")
        return try {
            stopLogCollector()
            // Kill any stray collector a previous service instance left behind. When Shizuku respawns
            // the user service this process's fields are fresh and do not know the old child, which
            // keeps writing to the same dir; matching the exact invocation kills only our collectors.
            runCatching {
                Runtime.getRuntime().exec(arrayOf("pkill", "-f", LOGCAT_MATCH)).waitFor()
            }
            // The shell UID owns this directory; the app never opens the files itself (a cross-UID
            // read of /data/local/tmp is not permitted) — it asks for them back through readFileChunk.
            Runtime.getRuntime().exec(arrayOf("mkdir", "-p", logDir)).waitFor()
            Runtime.getRuntime().exec(arrayOf("chmod", "777", logDir)).waitFor()

            // Collection is per-boot: /data/local/tmp survives a reboot, so any part older than this
            // boot is from a previous session and is cleared, rather than growing the log across boots.
            val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            File(logDir).listFiles()?.forEach { f ->
                if (f.name.endsWith(".log") && f.lastModified() < bootTime) runCatching { f.delete() }
            }

            // `-v uid` adds the writer's uid to each line, which is what the framework stream is
            // routed by. Asking logcat is worth a probe because reading it back from /proc cannot
            // answer for a process that has already exited -- a crash tail, or anything replayed
            // from the buffer -- and a recycled pid answers for the wrong one.
            val uidAware = probeUidColumn()
            val command = buildList {
                addAll(listOf("logcat", "-b", "main", "-b", "crash", "-b", "system"))
                if (uidAware) addAll(listOf("-v", "uid"))
                addAll(listOf("-v", "threadtime"))
            }
            val builder = ProcessBuilder(command)
            builder.redirectErrorStream(true)
            val process = builder.start()
            collector = process
            running = true

            this.relevantUids = relevantUids.toHashSet()
            val mine = ++generation
            val thread = Thread { runReader(process, logDir, mine) }
            thread.isDaemon = true
            thread.start()
            readerThread = thread
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Drains the collector's output and fans each line into the verbose stream (always) and the framework stream (when
     * it belongs to a relevant uid). A wrapped multi-line message has no header on its continuation lines, so those
     * inherit the routing of the entry they belong to.
     */
    private fun runReader(process: Process, logDir: String, mine: Int) {
        val verbose = RotatingWriter(logDir, "verbose")
        val framework = RotatingWriter(logDir, "framework")
        val pidUid = HashMap<Int, Int>()
        var lastWentToFramework = false
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                // A newer collector has taken over; this reader's writers close in the finally block
                // rather than going on writing beside its replacement.
                if (mine != generation) return@forEachLine
                // A "start a new log" request rolls both streams to fresh parts before this line, so
                // the new part begins here and the closed one stays on disk as a chevron.
                if (rotateRequested) {
                    rotateRequested = false
                    verbose.rotate()
                    framework.rotate()
                }
                verbose.write(line)
                val toFramework = routes(line, pidUid) ?: lastWentToFramework
                if (toFramework) framework.write(line)
                lastWentToFramework = toFramework
            }
        } catch (e: Exception) {
            // The stream closes when the collector is killed; nothing to do but let the reader end.
        } finally {
            verbose.close()
            framework.close()
        }
    }

    /**
     * Whether a line belongs in the framework stream, or null when it carries no header -- a continuation line, which
     * belongs wherever the entry it continues went.
     *
     * The whole rule is the writer's uid, plus fatals from anyone: what a patched app, a module or the manager says is
     * the log, whatever tag it says it under. [UID_HEADER] is the uid-aware form logcat writes under `-v uid`; the
     * plain form is still parsed because the uid column is only printed where the platform permits it, and there the
     * uid has to be read from /proc -- which only answers while the process is alive.
     */
    private fun routes(line: String, pidUid: MutableMap<Int, Int>): Boolean? {
        val stamped = UID_HEADER.find(line)
        if (stamped != null) {
            // A system uid the platform spells out by name ("radio", "wifi") is never one of ours, so
            // it fails to parse into an int and is left to the fatal rule alone.
            val uid = stamped.groupValues[1].toIntOrNull() ?: -1
            val level = stamped.groupValues[4].firstOrNull() ?: ' '
            return uid in relevantUids || level == 'F'
        }
        val header = HEADER.find(line) ?: return null
        val pid = header.groupValues[1].toIntOrNull() ?: -1
        val level = header.groupValues[2].firstOrNull() ?: ' '
        return lookupUid(pid, pidUid) in relevantUids || level == 'F'
    }

    /**
     * Whether this device's `logcat` writes the uid column, asked once and remembered.
     *
     * The flag is what the framework stream is routed by, and a collector started with an argument the binary rejects
     * collects nothing at all, so it is only used once logcat has accepted it. Acceptance is the exit status: a binary
     * that does not know the modifier fails outright. The output is only consulted to catch the opposite case -- a
     * platform that takes the flag and prints nothing for it -- which is why lines in the plain form are a "no" while
     * no lines at all are not. An empty buffer is common (a collector restarting because the buffer was cleared is one
     * of the reasons it restarts) and used to answer "no" for a device that supports it perfectly well, leaving the
     * routing to read uids from /proc, which cannot answer for a process that has already exited.
     */
    private fun probeUidColumn(): Boolean {
        cachedUidColumn?.let {
            return it
        }
        val answer = runCatching {
            val probe =
                ProcessBuilder("logcat", "-d", "-b", "main", "-v", "uid", "-v", "threadtime", "-t", "50")
                    // Drained as one stream: stderr left unread can fill its pipe and block a
                    // process this waits on, and this runs inside the collector's lock.
                    .redirectErrorStream(true)
                    .start()
            val output = probe.inputStream.bufferedReader().use { it.readText() }
            val accepted = probe.waitFor() == 0
            probe.destroy()
            when {
                !accepted -> false
                output.lineSequence().any { UID_HEADER.containsMatchIn(it) } -> true
                // Lines, but none carrying a uid: the flag was taken and does nothing.
                output.lineSequence().any { HEADER.containsMatchIn(it) } -> false
                // Nothing to judge by, and the flag was accepted.
                else -> true
            }
        }
            .getOrDefault(false)
        cachedUidColumn = answer
        return answer
    }

    override fun supportsUidColumn(): Boolean = probeUidColumn()

    /**
     * The uid behind a pid, remembered once resolved.
     *
     * Only a resolved uid is cached. A pid whose process is already gone -- every line replayed from the buffer at
     * startup, and the tail of anything that just crashed -- reads back as -1, and remembering that would answer for
     * the live process the kernel later hands the same pid to. The cache is dropped whole once it grows past what a
     * device's pid space makes plausible, so a long collection cannot accumulate stale entries either.
     */
    private fun lookupUid(pid: Int, cache: MutableMap<Int, Int>): Int {
        cache[pid]?.let {
            return it
        }
        val uid = readUid(pid)
        if (uid >= 0) {
            if (cache.size >= MAX_PID_CACHE) cache.clear()
            cache[pid] = uid
        }
        return uid
    }

    /** The uid a pid runs as, read from /proc; -1 when it cannot be resolved (already gone). */
    private fun readUid(pid: Int): Int {
        if (pid <= 0) return -1
        return try {
            val status = File("/proc/$pid/status")
            if (!status.exists()) return -1
            status.bufferedReader().useLines { lines ->
                for (l in lines) {
                    if (l.startsWith("Uid:")) {
                        return l.substringAfter("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: -1
                    }
                }
            }
            -1
        } catch (e: Exception) {
            -1
        }
    }

    @Synchronized
    override fun updateLogCollectorUids(relevantUids: IntArray) {
        // No restart and no gap: the reader reads this set per line, so a uid added here reaches the
        // very next line the new app writes.
        if (!running) return
        val updated = relevantUids.toHashSet()
        if (updated != this.relevantUids) {
            Log.i(TAG, "The collector now routes ${updated.size} uid(s)")
            this.relevantUids = updated
        }
    }

    // Synchronized with startLogCollector: two callers arriving together each stopped what they
    // believed to be the running collector and started their own, and the one whose process was
    // registered after the other's stop had already read the field was left alive with nobody
    // draining it. One at a time, so a start always tears down exactly the collector that precedes it.
    @Synchronized
    override fun stopLogCollector() {
        Log.i(TAG, "Stopping the log collector")
        running = false
        // Retires this generation, so a reader still draining a buffered chunk stops at its next line
        // instead of writing beside whatever replaces it.
        generation++
        runCatching { collector?.destroy() }
        collector = null
        runCatching {
            readerThread?.interrupt()
            readerThread?.join(500)
        }
        readerThread = null
    }

    override fun startNewLogPart(): Boolean {
        // No destructive clear: just ask the reader to open fresh parts. Nothing stops, nothing is
        // deleted here (the rotation cap prunes old parts on its own), so framework collection has no
        // gap -- the rootless equivalent of Vector's daemon opening a new log part.
        if (!running || collector?.isAlive != true) return false
        rotateRequested = true
        return true
    }

    override fun isLogCollectorRunning(): Boolean = running && collector?.isAlive == true

    override fun listLogParts(logDir: String, prefix: String): Array<String> {
        return try {
            val dir = File(logDir)
            val pattern = Regex("""^${Regex.escape(prefix)}_.*\.log$""")
            val files = dir.listFiles { f -> pattern.matches(f.name) } ?: return emptyArray()
            // The names carry a timestamp, so lexicographic order is chronological: oldest first,
            // the live part last — the order the reader pages through.
            files.sortedBy { it.name }.map { "${it.absolutePath}\t${it.length()}" }.toTypedArray()
        } catch (e: Exception) {
            emptyArray()
        }
    }

    override fun fileSize(path: String): Long = runCatching { File(path).length() }.getOrDefault(0L)

    override fun readFileChunk(path: String, offset: Long, maxBytes: Int): ByteArray {
        return try {
            RandomAccessFile(File(path), "r").use { file ->
                val remaining = file.length() - offset
                if (remaining <= 0L) return ByteArray(0)
                file.seek(offset)
                val buffer = ByteArray(minOf(remaining, maxBytes.toLong()).toInt())
                file.readFully(buffer)
                buffer
            }
        } catch (e: Exception) {
            Log.w(TAG, "readFileChunk failed: $path", e)
            ByteArray(0)
        }
    }

    override fun destroy() {
        Log.i(TAG, "Shell service destroyed")
        stopLogCollector()
        stopManagerWatchdog()
        exitProcess(0)
    }

    // --- Keeping the manager reachable ---

    @Volatile private var watchdog: Thread? = null

    /**
     * What the running watchdog was asked to watch.
     *
     * The manager re-states its wish on every tick of its own supervisor loop -- it has to, because this process
     * outlives it and may have been started since the last time it spoke -- so without this a thread would be torn down
     * and built again every few seconds for no change at all.
     */
    @Volatile private var watching: String? = null

    /**
     * Starts the manager again whenever it is found gone.
     *
     * This runs as the shell user, in a process the Shizuku server owns rather than the manager: a device's background
     * reaper and a force-stop both act on the manager's package and leave this one untouched, which is what makes a
     * watchdog here able to do something no code inside the manager can. Starting a component from the shell also
     * clears the stopped state a force-stop leaves behind, so the manager is not merely restarted but made reachable
     * again.
     *
     * It gives up after [MAX_WATCHDOG_FAILURES] starts in a row that changed nothing -- the manager uninstalled, or a
     * device that refuses the start outright -- rather than retrying forever at the cost of the battery it was meant to
     * protect.
     */
    @Synchronized
    override fun startManagerWatchdog(
        packageName: String,
        component: String,
        userId: Int,
        intervalSeconds: Int,
    ): Boolean {
        val wanted = "$packageName|$component|$userId|$intervalSeconds"
        if (wanted == watching && watchdog?.isAlive == true) return true
        stopManagerWatchdog()
        if (packageName.isEmpty() || component.isEmpty()) return false
        watching = wanted
        val interval = intervalSeconds.coerceIn(30, 3600) * 1000L
        Log.i(TAG, "Watching $packageName; restarting $component (user $userId) every ${interval}ms")
        val thread = Thread {
            var failures = 0
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(interval)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (isProcessRunning(packageName)) {
                    failures = 0
                    continue
                }
                Log.i(TAG, "$packageName is not running; starting $component")
                val output = runShellCommand("am start-foreground-service --user $userId -n $component")
                // The command reports its own refusals, and they are the interesting case: a device
                // that will not let the shell start this component says so here and nowhere else.
                if (output.isNotBlank()) Log.i(TAG, "start-foreground-service: ${output.trim()}")
                failures = if (isProcessRunning(packageName)) 0 else failures + 1
                if (failures >= MAX_WATCHDOG_FAILURES) {
                    Log.w(TAG, "Giving up on $packageName after $failures starts that changed nothing")
                    return@Thread
                }
            }
        }
        thread.isDaemon = true
        thread.name = "lspatch-manager-watchdog"
        watchdog = thread
        thread.start()
        return true
    }

    @Synchronized
    override fun stopManagerWatchdog() {
        watchdog?.let {
            Log.i(TAG, "Stopping the manager watchdog")
            it.interrupt()
        }
        watchdog = null
        watching = null
    }

    override fun isManagerWatchdogRunning(): Boolean = watchdog?.isAlive == true

    /**
     * Whether the manager's own process exists.
     *
     * By process name rather than by asking the activity manager: an app's main process is named after its package, the
     * same `ps` this service already reads to reap its own strays, and it costs no privileged call.
     *
     * The match is exact, and that is the whole of it: this service runs as `<package>:service`, so a prefix match
     * would find *itself* and report the manager alive for as long as the watchdog that asked was running -- which is
     * every moment it could ever have acted.
     */
    private fun isProcessRunning(packageName: String): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A -o NAME")).inputStream.bufferedReader().useLines { lines ->
            lines.any { it.trim() == packageName }
        }
    }
        .getOrElse {
            Log.w(TAG, "Cannot tell whether $packageName is running", it)
            // Assumed alive: a failed read must not turn into a restart the device did not need.
            true
        }

    /**
     * A rotating writer for one stream. Lines append to `<prefix>_<timestamp>.log` until it exceeds [MAX_PART_BYTES];
     * then it opens a fresh timestamped part and prunes the oldest so at most [MAX_PARTS] survive per prefix. Not
     * thread-safe — one writer per reader thread.
     */
    private class RotatingWriter(private val dir: String, private val prefix: String) {
        private var writer: BufferedWriter? = null
        private var written = 0L
        private var lastStamp = ""
        private var stampCounter = 0

        private fun stamp(): String {
            val now = STAMP_FORMAT.format(Date())
            // Two parts opened in the same millisecond would collide and one would clobber the other;
            // disambiguate with a counter so both are kept and still sort next to each other.
            return if (now == lastStamp) {
                "${now}_${++stampCounter}"
            } else {
                lastStamp = now
                stampCounter = 0
                now
            }
        }

        private fun openNew() {
            runCatching {
                writer?.flush()
                writer?.close()
            }
            prune()
            val file = File(dir, "${prefix}_${stamp()}.log")
            writer = BufferedWriter(FileWriter(file, false))
            written = 0L
            runCatching { file.setReadable(true, false) }
        }

        private fun prune() {
            val pattern = Regex("""^${Regex.escape(prefix)}_.*\.log$""")
            val files = File(dir).listFiles { f -> pattern.matches(f.name) } ?: return
            // Keep room for the part about to be opened: drop oldest until at most MAX_PARTS-1 remain.
            files
                .sortedBy { it.name }
                .dropLast((MAX_PARTS - 1).coerceAtLeast(0))
                .forEach { runCatching { it.delete() } }
        }

        fun write(line: String) {
            try {
                if (writer == null || written + line.length + 1 > MAX_PART_BYTES) openNew()
                writer?.write(line)
                writer?.write("\n")
                written += line.length + 1
                // Flush every line rather than at the buffer's convenience. The framework stream is
                // sparse — its 8 KB buffer would take an age to fill, so the live part read back from
                // disk stayed empty while older, rotation-flushed parts showed fine (the "empty
                // framework, only older parts work" bug). A crash collector also wants its last lines
                // already on disk when the process it was watching dies mid-buffer.
                writer?.flush()
            } catch (e: Exception) {
                // A single failed line must not tear the reader down; the next write retries.
            }
        }

        /** Closes the current part and opens a fresh one now, keeping the closed part on disk. */
        fun rotate() = openNew()

        fun close() {
            runCatching {
                writer?.flush()
                writer?.close()
            }
            writer = null
        }
    }

    private companion object {
        /** Its own tag, so `adb logcat -s LSPatchShell:V` follows the shell half alone. */
        const val TAG = "LSPatchShell"

        /**
         * Tail cap on a shell command's output.
         *
         * It crosses as a String, which Binder writes as UTF-16 -- two bytes a character -- into the ~1 MB buffer the
         * whole process shares with every other transaction in flight. The old cap put 800 KB in that buffer and the
         * calls around it began failing as "transaction failed on small parcel", which is the buffer being full rather
         * than anything having died. A quarter of the buffer leaves room for its neighbours.
         */
        const val MAX_OUTPUT_CHARS = 128_000

        /** Consecutive restarts that changed nothing before the watchdog concludes it cannot help. */
        const val MAX_WATCHDOG_FAILURES = 5

        /** ~4 MB per part, eight parts per stream — ~32 MB of history apiece at most. */
        const val MAX_PART_BYTES = 4L * 1024 * 1024
        const val MAX_PARTS = 8

        /** Matches exactly the collector we spawn, so pkill kills stray collectors and nothing else. */
        const val LOGCAT_MATCH = "logcat -b main -b crash -b system"

        val STAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

        /** Beyond this many resolved pids the cache is dropped whole rather than kept growing. */
        const val MAX_PID_CACHE = 4096

        // "MM-DD HH:MM:SS.mmm  PID  TID L TAG:" — enough of the threadtime header to route by.
        val HEADER = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.*?):""")

        // The same header as logcat writes it under `-v uid`: "MM-DD HH:MM:SS.mmm  UID  PID  TID L".
        // The uid is numeric for an app and a name for some system uids, so it is captured as text and
        // parsed by the caller. Three columns before the level, against the plain form's two, is what
        // tells the two apart — no line can match both.
        val UID_HEADER = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+(\S+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s""")
    }
}
