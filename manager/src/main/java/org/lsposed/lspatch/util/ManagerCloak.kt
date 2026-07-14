package org.lsposed.lspatch.util

import android.util.Log
import com.android.tools.build.apkzlib.sign.SigningExtension
import com.android.tools.build.apkzlib.sign.SigningOptions
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.wind.meditor.core.ManifestEditor
import com.wind.meditor.property.AttributeItem
import com.wind.meditor.property.ModificationProperty
import com.wind.meditor.utils.NodeValue
import org.lsposed.lspatch.config.Configs
import org.lsposed.lspatch.config.MyKeyStore
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.Constants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

object ManagerCloak {

    private const val TAG = "ManagerCloak"
    private const val MANIFEST = "AndroidManifest.xml"
    private const val RESOURCES_ARSC = "resources.arsc"

    // Android R+ requires resources.arsc STORED and 4-byte aligned.
    private val zFileOptions = ZFileOptions().setAlignmentRule(
        AlignmentRules.compose(
            AlignmentRules.constantForSuffix(".so", 4096),
            AlignmentRules.constantForSuffix(RESOURCES_ARSC, 4)
        )
    )

    /**
     * Rewrites the current manager APK to [newPackageName], embeds optional migrate zip,
     * and signs the result. Returns the signed APK file.
     */
    fun cloak(newPackageName: String, migrateZip: File?, output: File): File {
        val splits = lspApp.applicationInfo.splitSourceDirs
        if (!splits.isNullOrEmpty()) {
            throw IllegalStateException("Split APK managers cannot be cloaked; install a single-APK build")
        }

        val src = File(lspApp.applicationInfo.sourceDir)
        if (!src.isFile) throw IllegalStateException("Manager source APK not found")

        return rebuild(
            src = src,
            output = output,
            migrateZip = migrateZip,
            rewritePackageTo = newPackageName
        )
    }

    /**
     * Takes a stock manager APK (e.g. from GitHub), embeds migrate data, and re-signs it.
     * Package id is forced to [Constants.MANAGER_PACKAGE_NAME].
     */
    fun prepareStockWithMigrate(src: File, migrateZip: File?, output: File): File {
        if (!src.isFile) throw IllegalStateException("Source APK not found")
        return rebuild(
            src = src,
            output = output,
            migrateZip = migrateZip,
            rewritePackageTo = Constants.MANAGER_PACKAGE_NAME
        )
    }

    private fun rebuild(
        src: File,
        output: File,
        migrateZip: File?,
        rewritePackageTo: String
    ): File {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        Log.i(TAG, "Rebuilding manager ${lspApp.packageName} -> $rewritePackageTo")

        ZFile.openReadWrite(output, zFileOptions).use { dst ->
            registerSigner(dst)

            ZipFile(src).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (isSignatureFile(name)) continue
                    // Drop any previous migrate payload; we add a fresh one below.
                    if (name == Constants.MIGRATE_ASSET_PATH) continue

                    zip.getInputStream(entry).use { input ->
                        when {
                            name == MANIFEST -> {
                                val modified = modifyManifest(input, rewritePackageTo)
                                ByteArrayInputStream(modified).use {
                                    dst.add(name, it, true)
                                }
                            }
                            name == RESOURCES_ARSC || name.endsWith(".so") -> {
                                dst.add(name, input, false)
                            }
                            else -> {
                                dst.add(name, input, entry.method != java.util.zip.ZipEntry.STORED)
                            }
                        }
                    }
                }
            }

            if (migrateZip != null && migrateZip.isFile) {
                FileInputStream(migrateZip).use {
                    dst.add(Constants.MIGRATE_ASSET_PATH, it, true)
                }
            }
            dst.realign()
        }

        return output
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        return name.endsWith(".SF") ||
            name.endsWith(".MF") ||
            name.endsWith(".RSA") ||
            name.endsWith(".DSA") ||
            name.endsWith(".EC") ||
            name.endsWith(".IMG")
    }

    private fun modifyManifest(input: InputStream, newPackageName: String): ByteArray {
        val oldPackages = listOf(
            lspApp.packageName,
            Constants.MANAGER_PACKAGE_NAME
        ).distinct()

        fun remapPrefixed(value: String): String {
            for (old in oldPackages) {
                if (value == old || value.startsWith("$old.")) {
                    return newPackageName + value.removePrefix(old)
                }
            }
            return value
        }

        val property = ModificationProperty()
        property.addManifestAttribute(
            AttributeItem(NodeValue.Manifest.PACKAGE, newPackageName).setNamespace(null)
        )
        // Rewrite app-scoped custom permissions (e.g. DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION)
        // so install does not clash with the still-installed original package.
        property.setPermissionMapper { _, permission -> remapPrefixed(permission) }
        property.setAuthorityMapper { authority -> remapPrefixed(authority) }

        val os = ByteArrayOutputStream()
        ManifestEditor(input, os, property).processManifest()
        return os.toByteArray()
    }

    private fun registerSigner(dst: ZFile) {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        if (MyKeyStore.useDefault) {
            val stream = lspApp.classLoader.getResourceAsStream("assets/keystore")
                ?: throw IllegalStateException("Default keystore missing")
            stream.use { keyStore.load(it, "123456".toCharArray()) }
            val entry = keyStore.getEntry("key0", KeyStore.PasswordProtection("123456".toCharArray()))
                    as KeyStore.PrivateKeyEntry
            @Suppress("UNCHECKED_CAST")
            val certs = entry.certificateChain as Array<X509Certificate>
            SigningExtension(
                SigningOptions.builder()
                    .setMinSdkVersion(28)
                    .setV2SigningEnabled(true)
                    .setCertificates(*certs)
                    .setKey(entry.privateKey)
                    .build()
            ).register(dst)
        } else {
            FileInputStream(MyKeyStore.file).use { keyStore.load(it, Configs.keyStorePassword.toCharArray()) }
            val entry = keyStore.getEntry(
                Configs.keyStoreAlias,
                KeyStore.PasswordProtection(Configs.keyStoreAliasPassword.toCharArray())
            ) as KeyStore.PrivateKeyEntry
            @Suppress("UNCHECKED_CAST")
            val certs = entry.certificateChain as Array<X509Certificate>
            SigningExtension(
                SigningOptions.builder()
                    .setMinSdkVersion(28)
                    .setV2SigningEnabled(true)
                    .setCertificates(*certs)
                    .setKey(entry.privateKey)
                    .build()
            ).register(dst)
        }
    }
}
