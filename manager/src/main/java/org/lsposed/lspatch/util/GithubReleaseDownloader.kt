package org.lsposed.lspatch.util

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GithubReleaseDownloader {

    private const val TAG = "GithubReleaseDownloader"
    private const val RELEASES_API =
        "https://api.github.com/repos/JingMatrix/LSPatch/releases/latest"
    private const val USER_AGENT = "LSPatch-Manager"

    data class Result(val tagName: String, val assetName: String, val file: File)

    /**
     * Downloads the latest manager APK from GitHub Releases.
     * Prefers `manager.apk`, falls back to `manager-debug.apk`.
     */
    fun downloadLatestManager(dest: File): Result {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val releaseJson = httpGetString(RELEASES_API)
        val root = JSONObject(releaseJson)
        val tag = root.optString("tag_name", "unknown")
        val assets = root.getJSONArray("assets")

        var preferredUrl: String? = null
        var preferredName: String? = null
        var fallbackUrl: String? = null
        var fallbackName: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            val url = asset.getString("browser_download_url")
            when {
                name.equals("manager.apk", ignoreCase = true) -> {
                    preferredUrl = url
                    preferredName = name
                }
                name.equals("manager-debug.apk", ignoreCase = true) -> {
                    fallbackUrl = url
                    fallbackName = name
                }
            }
        }

        val downloadUrl = preferredUrl ?: fallbackUrl
            ?: throw IllegalStateException("No manager APK found in latest GitHub release")
        val assetName = preferredName ?: fallbackName!!

        Log.i(TAG, "Downloading $assetName ($tag) from $downloadUrl")
        httpDownload(downloadUrl, dest)
        if (!dest.isFile || dest.length() == 0L) {
            throw IllegalStateException("Downloaded APK is empty")
        }
        return Result(tag, assetName, dest)
    }

    private fun httpGetString(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("GitHub API HTTP $code: $body")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpDownload(url: String, dest: File) {
        var current = url
        // Follow a few redirects manually for CDN links if needed
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
                connectTimeout = 30_000
                readTimeout = 300_000
                instanceFollowRedirects = false
            }
            try {
                val code = conn.responseCode
                when (code) {
                    in 200..299 -> {
                        conn.inputStream.use { input ->
                            FileOutputStream(dest).use { output -> input.copyTo(output) }
                        }
                        return
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307, 308 -> {
                        current = conn.getHeaderField("Location")
                            ?: throw IllegalStateException("Redirect without Location")
                    }
                    else -> {
                        val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                        throw IllegalStateException("Download HTTP $code: $err")
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects while downloading release APK")
    }
}
