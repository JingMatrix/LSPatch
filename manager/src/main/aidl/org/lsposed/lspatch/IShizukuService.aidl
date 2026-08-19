package org.lsposed.lspatch;

interface IShizukuService {
    // Executes a single program (no shell: no pipes, globs or redirects) and returns its output.
    String runShellCommand(String cmd) = 1;

    // Shizuku's own teardown, and the only one there is: on unbind (or when a non-daemon service's
    // client dies) the server one-way transacts this exact code and nothing else -- it never signals
    // or kills the process. The id is therefore fixed by Shizuku, not ours to choose:
    // ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy is 16777115, which is 16777114 written as
    // an aidl offset from FIRST_CALL_TRANSACTION. Any other id leaves the shell process running for
    // the rest of the boot.
    void destroy() = 16777114;

    // Runs [script] through `sh -c`, so globs, loops and redirects work — used to gather the export
    // archive (tombstones, anr, …). Output is tail-capped like runShellCommand.
    String runShellScript(String script) = 8;

    // --- Continuous log collection (shell UID owns the files; the app reads them back through
    // readFileChunk, never touching the filesystem itself — cross-UID reads of /data/local/tmp are
    // not otherwise permitted). Appended with fresh ids; existing ids are never renumbered. ---

    // Starts a collector that fans one live logcat into two rotating, timestamped streams in
    // [logDir]: "verbose" (every line) and "framework" (every line from a uid in [relevantUids] --
    // the manager, its patched apps and their modules -- plus any fatal line, whoever wrote it).
    // Kills any collector already running first.
    boolean startLogCollector(String logDir, in int[] relevantUids) = 3;

    // Stops the running collector, if any.
    void stopLogCollector() = 4;

    // Whether the collector process is currently alive.
    boolean isLogCollectorRunning() = 5;

    // The rotating parts of one stream ([prefix] = "verbose" or "framework") in [logDir], oldest
    // first, each as "absolutePath\tsizeBytes".
    String[] listLogParts(String logDir, String prefix) = 6;



    // Reads at most [maxBytes] from [path] starting at [offset], as bytes rather than text. Every read
    // of a log goes through this, the screen's tail as much as the export's whole file, for two
    // reasons. A String crosses Binder as UTF-16, which doubles a log that is very nearly ASCII; and
    // the reply shares one ~1 MB buffer with every other transaction the process has in flight, so a
    // single large answer fails the small ones around it. Bytes, in pieces, decoded by the caller
    // once they are joined -- a piece boundary inside a multi-byte character would corrupt it.
    // Returns an empty array at end of file or when the file cannot be read, ending the caller's loop.
    byte[] readFileChunk(String path, long offset, int maxBytes) = 10;

    // Starts a new log part on both streams without stopping the collector or deleting anything: the
    // current parts close and fresh ones open, so collection never has a gap. This is the rootless
    // equivalent of Vector's "start a new log". Returns false when no collector is running.
    boolean startNewLogPart() = 9;

    // The size of a file the shell owns, so a reader can work out where its tail begins before asking
    // for it. Zero when the file does not exist or cannot be read.
    //
    // A fresh id rather than the one the retired readLogPart held: a client binds to whatever shell
    // process is already running, which is only respawned when the service version changes, so an id
    // reused with a different signature would reach the old method and read arguments nobody wrote.
    long fileSize(String path) = 12;

    // Whether this device's logcat writes the writer's uid, which is what the framework stream is
    // routed by and what a one-shot snapshot has to match to show the same lines.
    boolean supportsUidColumn() = 13;

    // Replaces the uid set a running collector routes by, without interrupting collection. An app
    // patched, or a module installed, after the collector started has a uid it has never seen; the
    // caller pushes the current set periodically so that app joins the framework stream in place.
    // Does nothing when no collector is running -- the next start carries the set anyway.
    void updateLogCollectorUids(in int[] relevantUids) = 11;

    // --- Keeping the manager reachable. This process runs as the shell user and is owned by the
    // Shizuku server rather than by the manager, so it is not what a device's background reaper or a
    // force-stop acts on -- which is the whole reason the watchdog lives here and not in the app. ---

    // Starts a supervisor that starts [component] (an "package/class" name, in [userId]) again
    // whenever no process of [packageName] is running, checking every [intervalSeconds]. Started from
    // the shell, that start also clears the stopped state a force-stop leaves behind, which nothing
    // running inside the app can do. Replaces any watchdog already running.
    boolean startManagerWatchdog(String packageName, String component, int userId, int intervalSeconds) = 14;

    // Stops the supervisor, if any.
    void stopManagerWatchdog() = 15;

    // Whether a supervisor is currently running.
    boolean isManagerWatchdogRunning() = 16;
}
