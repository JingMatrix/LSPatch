package org.lsposed.lspatch.util

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.pm.PackageInfoCompat
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.Constants

/**
 * A Google Play services replacement installed on this device.
 *
 * Carries what the chooser shows: the build names itself through [label] and [applicationInfo], and [versionName]
 * is worth showing because these builds are updated often and two of them side by side are otherwise
 * indistinguishable.
 */
data class GmsReplacement(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val applicationInfo: ApplicationInfo,
)

/**
 * The Google Play services replacements this device actually has.
 *
 * Found by asking, not by reading names. A replacement is whatever answers the services a Play services client
 * binds, and builds differ in what they call themselves: one renames itself and its permissions so it can sit
 * beside Google's own, another keeps Google's names and replaces it outright. Querying the sign-in service is
 * how a client finds Google Play services in the first place, so anything that answers it is by definition a
 * candidate, whatever it is called.
 *
 * Needs package visibility, which the manager holds; Google's own package is dropped, since it is what is being
 * replaced.
 */
object GmsReplacements {

    /** The action a Play services client binds to sign in, and so the one a replacement has to answer. */
    private const val SIGN_IN_SERVICE = "com.google.android.gms.auth.api.signin.service.START"

    /**
     * The vendor token of a replacement's package -- what distinguishes one build from another.
     *
     * The trailing segments every such build shares are dropped first, so what is left is the part that names
     * the vendor rather than the service: `app.revanced.android.gms` and `org.microg.gms` give `revanced` and
     * `microg`, not `android` and `gms`.
     */
    fun shortName(packageName: String): String =
        packageName
            .removeSuffix(Constants.GMS_REPLACEMENT_SUFFIX)
            .removeSuffix(".android")
            .substringAfterLast('.')

    fun installed(): List<GmsReplacement> =
        runCatching {
            lspApp.packageManager
                .queryIntentServices(Intent(SIGN_IN_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
                .filter { Constants.isGmsReplacement(it) }
                .sorted()
                .mapNotNull { describe(it) }
        }.getOrDefault(emptyList())

    fun isInstalled(packageName: String): Boolean =
        runCatching { lspApp.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun describe(packageName: String): GmsReplacement? =
        runCatching {
            val info = lspApp.packageManager.getPackageInfo(packageName, 0)
            val app = info.applicationInfo ?: return null
            GmsReplacement(
                packageName = packageName,
                label = lspApp.packageManager.getApplicationLabel(app).toString(),
                versionName = info.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                applicationInfo = app,
            )
        }.getOrNull()
}
