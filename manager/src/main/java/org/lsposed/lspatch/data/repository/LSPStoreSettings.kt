package org.lsposed.lspatch.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.lspatch.lspApp
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreInstall
import org.matrix.vector.ui.store.StoreSettings

/**
 * LSPatch's [StoreSettings], persisted in the app's `settings` store.
 *
 * All three outlive the process deliberately. The channel decides what counts as an update everywhere, not just in the
 * list that set it; a mute the next visit had forgotten would not be a mute; and the install notes are the only thing
 * that stops the Store offering a release it has already put on this device.
 */
object LSPStoreSettings : StoreSettings {

    private val prefs
        get() = lspApp.prefs

    private val _updateChannel = MutableStateFlow(prefs.getString(KEY_CHANNEL, STABLE) ?: STABLE)
    override val updateChannel: StateFlow<String> = _updateChannel.asStateFlow()

    override fun setUpdateChannel(token: String) {
        prefs.edit().putString(KEY_CHANNEL, token).apply()
        _updateChannel.value = token
    }

    private val _mutedUpdates = MutableStateFlow(prefs.getStringSet(KEY_MUTED, emptySet()).orEmpty().toSet())
    override val mutedUpdates: StateFlow<Set<String>> = _mutedUpdates.asStateFlow()

    override fun setUpdatesMuted(packageName: String, muted: Boolean) {
        val next = if (muted) _mutedUpdates.value + packageName else _mutedUpdates.value - packageName
        // A set of our own going in, and `toSet()` coming out above: `getStringSet` hands back the instance the
        // preferences hold, which the platform documents as not ours to modify.
        prefs.edit().putStringSet(KEY_MUTED, HashSet(next)).apply()
        _mutedUpdates.value = next
    }

    /**
     * Which catalogue release this manager put on the device, per package. See [StoreInstall].
     *
     * A string set like the mute rather than a serialised map: three fields per row, joined by newlines, which neither
     * a package name nor a tag contains. A row that no longer parses is dropped, and rows are never pruned, because the
     * worst either can cost is an update offered a second time.
     */
    private val _storeInstalls = MutableStateFlow(readStoreInstalls())
    override val storeInstalls: StateFlow<Map<String, StoreInstall>> = _storeInstalls.asStateFlow()

    /** Records what the Store installed for [packageName], replacing any earlier note of it. */
    fun noteStoreInstall(packageName: String, install: StoreInstall) {
        val next = _storeInstalls.value + (packageName to install)
        val rows = next.mapTo(HashSet()) { (name, noted) -> encode(name, noted) }
        prefs.edit().putStringSet(KEY_INSTALLS, rows).apply()
        _storeInstalls.value = next
    }

    private fun encode(packageName: String, install: StoreInstall): String =
        "$packageName\n${install.release.tag}\n${install.installed.tag}"

    private fun readStoreInstalls(): Map<String, StoreInstall> =
        prefs
            .getStringSet(KEY_INSTALLS, emptySet())
            .orEmpty()
            .mapNotNull { row ->
                val parts = row.split('\n')
                if (parts.size != 3) return@mapNotNull null
                val release = RepoVersion.parse(parts[1]) ?: return@mapNotNull null
                val installed = RepoVersion.parse(parts[2]) ?: return@mapNotNull null
                parts[0] to StoreInstall(release, installed)
            }
            .toMap()

    private const val STABLE = "stable"
    private const val KEY_CHANNEL = "store_update_channel"
    private const val KEY_MUTED = "store_muted_updates"
    private const val KEY_INSTALLS = "store_installs"
}
