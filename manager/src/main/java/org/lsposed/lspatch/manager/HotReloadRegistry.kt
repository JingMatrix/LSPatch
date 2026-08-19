package org.lsposed.lspatch.manager

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.matrix.vector.ipc.IProcessChannel

/**
 * The live host processes the manager can reach to drive a hot reload -- its stand-in for the registry Vector's daemon
 * keeps.
 *
 * Each patched host hands the manager its [IProcessChannel] once, while it bootstraps (Vector's
 * `attachProcessChannel`), and asks the manager for its modules once they load. Keyed by the calling `(uid, pid)`,
 * those two facts are the whole of what a reload needs: the channel to call in on, and which modules that process is
 * running. The entry is dropped when the channel dies, so a process that has gone is never named as a target.
 */
object HotReloadRegistry {

    private const val TAG = "LSPatch-HotReload"

    class Target(
        val id: Long,
        val uid: Int,
        val pid: Int,
        val processName: String,
        val channel: IProcessChannel,
    ) {
        @Volatile var modules: List<String> = emptyList()
    }

    // The opaque target id the API hands a module app is just the process it names -- unique while the
    // process lives, and meaningless after, which is exactly the contract HookedProcess.targetId sets.
    private fun idOf(uid: Int, pid: Int): Long = (uid.toLong() shl 32) or (pid.toLong() and 0xFFFFFFFFL)

    private val targets = ConcurrentHashMap<Long, Target>()

    fun attach(uid: Int, pid: Int, processName: String, channel: IProcessChannel) {
        val id = idOf(uid, pid)
        // Logged because nothing else can say it: whether a live host can still be reached is what
        // decides if a reload has anywhere to go, and after a manager restart it is the one fact that
        // says the hosts found their way back.
        Log.i(TAG, "$processName (pid $pid) can be reached for a hot reload")
        val target = Target(id, uid, pid, processName, channel)
        targets[id] = target
        runCatching { channel.asBinder().linkToDeath({ targets.remove(id) }, 0) }
            .onFailure {
                Log.w(TAG, "Could not watch the channel for $processName; dropping it")
                targets.remove(id)
            }
    }

    /** Records which modules the caller's process loaded, so a reload knows which host to reach. */
    fun recordModules(uid: Int, pid: Int, modules: List<String>) {
        targets[idOf(uid, pid)]?.modules = modules
    }

    fun targetsFor(modulePackageName: String): List<Target> = targets.values.filter { modulePackageName in it.modules }

    fun target(id: Long): Target? = targets[id]
}
