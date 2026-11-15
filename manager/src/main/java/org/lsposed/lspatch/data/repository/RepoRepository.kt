package org.lsposed.lspatch.data.repository

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.util.LSPNetwork
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreLog
import org.matrix.vector.ui.store.StoreRepository

/**
 * The Store's data for LSPatch: [StoreRepository], with the platform as what knows the device.
 *
 * Only the two things that are LSPatch's own live here: installed versions come from the platform, and log lines go to
 * logcat under one tag. Which hosts are read, how one catalogue is made of them and when the cache answers instead
 * belong to the shared repository, so this manager and Vector read the module repository the same way rather than each
 * its own way.
 */
class RepoRepository private constructor(context: Context) :
    StoreRepository(
        LSPNetwork.client,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        LSPStoreLog,
        { installedVersions(context.applicationContext) },
    ) {

    companion object {
        @Volatile private var instance: RepoRepository? = null

        /** Process-wide, so the catalogue outlives the ViewModel a tab switch destroys. */
        fun getInstance(context: Context): RepoRepository =
            instance ?: synchronized(this) { instance ?: RepoRepository(context).also { instance = it } }

        /**
         * The repository if the Store has been opened, and null otherwise.
         *
         * For a caller with something to tell it rather than something to read — a package event. Building one from
         * there would open an HTTP client and start a catalogue nobody asked to see.
         */
        fun existing(): RepoRepository? = instance
    }
}

private const val TAG = "LSPatch-Store"

private val LSPStoreLog = StoreLog { priority, message, cause ->
    if (priority >= Log.WARN) Log.w(TAG, message, cause) else Log.i(TAG, message, cause)
}

/**
 * What each installed package is at, keyed by package name, or null when the device could not be asked.
 *
 * One `getInstalledPackages` call, which already carries the version of each: asking per package would be a binder
 * round trip apiece over every app on the device, and this is re-read whenever a package arrives or leaves. Null rather
 * than an empty map when the platform refuses, because the shared repository then keeps its last map, and what is
 * installed did not stop being installed.
 */
private suspend fun installedVersions(context: Context): Map<String, RepoVersion>? =
    withContext(Dispatchers.IO) {
        val packages =
            runCatching { context.packageManager.getInstalledPackages(0) }
                .onFailure { Log.w(TAG, "store: installed versions unavailable", it) }
                .getOrNull() ?: return@withContext null
        val versions = HashMap<String, RepoVersion>(packages.size)
        for (info in packages) {
            versions[info.packageName] =
                RepoVersion(PackageInfoCompat.getLongVersionCode(info), info.versionName.orEmpty())
        }
        versions
    }
