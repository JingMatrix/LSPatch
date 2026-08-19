package org.lsposed.lspatch.manager

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.lspatch.lspApp

/**
 * The launches a patched app made without the manager answering.
 *
 * A patched app that cannot reach the manager loads its modules from its own snapshot and carries on, which is the
 * right thing to do and also invisible: the one party who could report the miss is the one that was not running. So the
 * app counts its misses and hands the count over on the next bind that does reach here, and this is where they are kept
 * until someone reads them.
 *
 * What they mean is worth stating plainly, because the number alone reads as a defect: a device that reaps or
 * force-stops background apps is doing what it was configured to do, and these counts are the evidence of it — the
 * reason to whitelist the manager, not a sign that patching went wrong.
 */
object ModuleDeliveryReports {

    private const val TAG = "LSPatch-Delivery"
    private const val KEY = "module_delivery_fallbacks"

    data class Report(val packageName: String, val fallbacks: Int, val lastFallbackAt: Long)

    private val _reports = MutableStateFlow(load())
    val reports: StateFlow<List<Report>> = _reports.asStateFlow()

    /** The count an app reported, replacing what it reported before -- the app counts, this only keeps. */
    fun record(packageName: String, fallbacks: Int, lastFallbackAt: Long) {
        if (fallbacks <= 0) return
        Log.i(TAG, "$packageName started $fallbacks time(s) without reaching the manager")
        val merged =
            _reports.value.filter { it.packageName != packageName } + Report(packageName, fallbacks, lastFallbackAt)
        _reports.value = merged.sortedByDescending { it.lastFallbackAt }
        save()
    }

    fun clear() {
        _reports.value = emptyList()
        save()
    }

    private fun save() {
        runCatching {
            lspApp.prefs
                .edit()
                .putStringSet(
                    KEY,
                    _reports.value.map { "${it.packageName}|${it.fallbacks}|${it.lastFallbackAt}" }.toSet(),
                )
                .apply()
        }
    }

    private fun load(): List<Report> = runCatching {
        lspApp.prefs
            .getStringSet(KEY, emptySet())
            .orEmpty()
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 3) return@mapNotNull null
                Report(parts[0], parts[1].toIntOrNull() ?: return@mapNotNull null, parts[2].toLongOrNull() ?: 0L)
            }
            .sortedByDescending { it.lastFallbackAt }
    }
        .getOrDefault(emptyList())
}
