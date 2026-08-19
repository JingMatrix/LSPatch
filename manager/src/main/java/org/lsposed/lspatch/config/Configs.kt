package org.lsposed.lspatch.config

import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.ui.util.delegateStateOf
import org.lsposed.lspatch.ui.util.getValue
import org.lsposed.lspatch.ui.util.setValue

object Configs {

    private const val PREFS_KEYSTORE_PASSWORD = "keystore_password"
    private const val PREFS_KEYSTORE_ALIAS = "keystore_alias"
    private const val PREFS_KEYSTORE_ALIAS_PASSWORD = "keystore_alias_password"
    private const val PREFS_DETAIL_PATCH_LOGS = "detail_patch_logs"
    private const val PREFS_KEEP_MANAGER_ALIVE = "keep_manager_alive"
    private const val PREFS_ASKED_STAY_ALIVE = "asked_stay_alive"

    var keyStorePassword by
        delegateStateOf(lspApp.prefs.getString(PREFS_KEYSTORE_PASSWORD, "123456")!!) {
            lspApp.prefs.edit().putString(PREFS_KEYSTORE_PASSWORD, it).apply()
        }

    var keyStoreAlias by
        delegateStateOf(lspApp.prefs.getString(PREFS_KEYSTORE_ALIAS, "key0")!!) {
            lspApp.prefs.edit().putString(PREFS_KEYSTORE_ALIAS, it).apply()
        }

    var keyStoreAliasPassword by
        delegateStateOf(lspApp.prefs.getString(PREFS_KEYSTORE_ALIAS_PASSWORD, "123456")!!) {
            lspApp.prefs.edit().putString(PREFS_KEYSTORE_ALIAS_PASSWORD, it).apply()
        }

    var detailPatchLogs by
        delegateStateOf(lspApp.prefs.getBoolean(PREFS_DETAIL_PATCH_LOGS, true)) {
            lspApp.prefs.edit().putBoolean(PREFS_DETAIL_PATCH_LOGS, it).apply()
        }

    /**
     * Whether the Shizuku shell process should start the manager again whenever it finds it gone.
     *
     * Off unless asked for: reviving a process someone has just force-stopped is the opposite of what they asked the
     * system to do, and it is only the right answer once they have said that keeping patched apps working matters more.
     */
    var keepManagerAlive by
        delegateStateOf(lspApp.prefs.getBoolean(PREFS_KEEP_MANAGER_ALIVE, false)) {
            lspApp.prefs.edit().putBoolean(PREFS_KEEP_MANAGER_ALIVE, it).apply()
        }

    /**
     * Whether the person has been shown, once, what LSPatch needs in order to stay reachable.
     *
     * Asked once and not again whatever they answered: the same two grants are always reachable from the Shizuku
     * drawer, and a prompt that returns every launch is one people learn to dismiss without reading.
     */
    var askedStayAlive by
        delegateStateOf(lspApp.prefs.getBoolean(PREFS_ASKED_STAY_ALIVE, false)) {
            lspApp.prefs.edit().putBoolean(PREFS_ASKED_STAY_ALIVE, it).apply()
        }
}
