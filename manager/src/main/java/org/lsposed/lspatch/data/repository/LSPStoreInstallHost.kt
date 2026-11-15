package org.lsposed.lspatch.data.repository

import android.content.pm.PackageInstaller
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.util.LSPNetwork
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi
import org.matrix.vector.ui.store.InstallStep
import org.matrix.vector.ui.store.ReleaseAsset
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreInstall
import org.matrix.vector.ui.store.StoreInstallHost

/**
 * LSPatch's store install capability, injected into the shared Details screen so a module can be installed straight
 * from its page — the same install bar Vector shows.
 *
 * A store module is an ordinary APK, so this downloads the chosen asset and hands it to the platform installer through
 * [LSPPackageManager.installApk] — the same session driver the manager uses for patched apps — which shows the OS
 * confirmation dialog. Created per opened module (it needs the package name). LSPatch keeps no device-side module scope
 * (that is the daemon's job in Vector), so [installedScope]/[installedIsLegacy] stay empty and the Information tab
 * falls back to the catalogue's declared scope.
 */
class LSPStoreInstallHost(private val packageName: String) : StoreInstallHost {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _installState = MutableStateFlow<InstallStep>(InstallStep.Idle)
    override val installState: StateFlow<InstallStep> = _installState.asStateFlow()

    // With Shizuku the install is silent (shell installer); without it the platform installer shows
    // its own confirmation. The hint on the confirm dialog follows suit.
    override val silentInstall: Boolean
        get() = ShizukuApi.isPermissionGranted

    override val installedScope: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val installedIsLegacy: StateFlow<Boolean> = MutableStateFlow(false)

    override fun install(asset: ReleaseAsset, releaseVersion: RepoVersion?) {
        val url = asset.downloadUrl
        if (url.isNullOrBlank()) {
            _installState.value = InstallStep.Failed(packageName, "No download URL")
            return
        }
        val step = _installState.value
        if (step is InstallStep.Downloading || step is InstallStep.Installing) return
        scope.launch {
            try {
                _installState.value = InstallStep.Downloading(packageName, 0, asset.size)
                val apk =
                    download(url) { read, total ->
                        _installState.value = InstallStep.Downloading(packageName, read, total)
                    }
                _installState.value = InstallStep.Installing(packageName)
                val (status, message) = LSPPackageManager.installApk(apk)
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    note(releaseVersion)
                    _installState.value = InstallStep.Done(packageName)
                } else {
                    _installState.value = InstallStep.Failed(packageName, message)
                }
            } catch (e: Exception) {
                _installState.value = InstallStep.Failed(packageName, e.message)
            }
        }
    }

    override fun acknowledge() {
        _installState.value = InstallStep.Idle
    }

    /**
     * Records what landed, so the Store stops offering a release it has just installed. See [StoreInstall].
     *
     * Written from the repository's own reading of the device rather than a `getPackageInfo` of our own, because the
     * note is compared against that map and two readings need not agree. The package list is rescanned first: it is
     * what that reading walks, and the broadcast that normally refreshes it need not have arrived yet.
     *
     * A release the details screen could not name is not recorded. The note's whole job is to say which offer was
     * taken, and one that cannot say that is better absent — the offer simply stays.
     */
    private suspend fun note(release: RepoVersion?) {
        if (release == null) return
        runCatching { LSPPackageManager.fetchAppList() }
        val installed = RepoRepository.getInstance(lspApp).readInstalled()[packageName] ?: return
        LSPStoreSettings.noteStoreInstall(packageName, StoreInstall(release, installed))
    }

    // Through the shared client ([LSPNetwork]) so the download resolves the same way as the rest of
    // the manager -- DoH included -- and follows redirects itself.
    private fun download(fileUrl: String, onProgress: (Long, Long) -> Unit): File {
        val out = File(lspApp.cacheDir, "store-${packageName}.apk")
        val request = Request.Builder().url(fileUrl).header("User-Agent", "LSPatch-Manager").build()
        LSPNetwork.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength()
            var read = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, if (total > 0) total else 0)
                    }
                }
            }
        }
        return out
    }
}
