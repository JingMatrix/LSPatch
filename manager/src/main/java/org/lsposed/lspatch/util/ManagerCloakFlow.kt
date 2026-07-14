package org.lsposed.lspatch.util

import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.Constants

object ManagerCloakFlow {

    private const val TAG = "ManagerCloakFlow"

    sealed class Progress {
        data class Message(val text: String) : Progress()
        data class Success(val newPackageName: String) : Progress()
        data class Error(val message: String) : Progress()
    }

    /**
     * Full cloak flow: export data → rebuild APK → install → update Local apps → launch new → uninstall old.
     * [onProgress] is always invoked on the Main thread.
     */
    suspend fun run(
        newPackageName: String,
        onProgress: (Progress) -> Unit
    ) {
        suspend fun emit(progress: Progress) = withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }

        val oldPackage = lspApp.packageName
        try {
            if (!PackageNameValidator.isValid(newPackageName)) {
                emit(Progress.Error("Invalid package name"))
                return
            }
            if (newPackageName == oldPackage) {
                emit(Progress.Error("Package name is unchanged"))
                return
            }
            if (!ShizukuApi.isPermissionGranted) {
                emit(Progress.Error("Shizuku is required"))
                return
            }

            withContext(Dispatchers.IO) {
                emit(Progress.Message("Preparing migrate data…"))
                val workDir = lspApp.cacheDir.resolve("cloak").also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
                val migrateZip = ManagerMigrate.createMigrateZip(lspApp, workDir.resolve("migrate.zip"))

                emit(Progress.Message("Rebuilding manager APK…"))
                val cloakedApk = ManagerCloak.cloak(
                    newPackageName,
                    migrateZip,
                    workDir.resolve("manager-$newPackageName.apk")
                )

                emit(Progress.Message("Installing cloaked manager…"))
                val (installStatus, installMessage) =
                    LSPPackageManager.installFiles(listOf(cloakedApk), ShizukuApi.isPermissionGranted)
                if (installStatus != PackageInstaller.STATUS_SUCCESS) {
                    emit(Progress.Error("Install failed: $installMessage"))
                    return@withContext
                }

                // Retarget before removing the old package, so an app is never left pointing at a
                // manager that no longer exists. Failures are reported, not fatal: the new manager
                // is already installed, and a missed app can be retargeted again from it.
                emit(Progress.Message("Updating manager-mode apps…"))
                val retarget = LocalAppsUpdater.updateAllForManager(newPackageName)
                if (retarget.failed.isNotEmpty()) {
                    emit(Progress.Message(
                        "Retargeted ${retarget.updated.size}, ${retarget.failed.size} failed: " +
                                retarget.failed.joinToString { it.first }
                    ))
                }

                emit(Progress.Message("Launching new manager…"))
                val launch = LSPPackageManager.getLaunchIntentForPackage(newPackageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    withContext(Dispatchers.Main) {
                        lspApp.startActivity(launch)
                    }
                }

                emit(Progress.Message("Removing old package…"))
                val (uninstallStatus, uninstallMessage) = LSPPackageManager.uninstall(oldPackage)
                if (uninstallStatus != PackageInstaller.STATUS_SUCCESS) {
                    Log.w(TAG, "Uninstall old package failed: $uninstallMessage")
                }

                workDir.deleteRecursively()
                emit(Progress.Success(newPackageName))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Cloak failed", t)
            emit(Progress.Error(t.message ?: t.toString()))
        }
    }

    /**
     * Revert a cloaked manager back to [Constants.MANAGER_PACKAGE_NAME] using the latest
     * GitHub release APK, then uninstall this (random-package) app.
     */
    suspend fun revertToOriginal(onProgress: (Progress) -> Unit) {
        suspend fun emit(progress: Progress) = withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }

        val currentPackage = lspApp.packageName
        val original = Constants.MANAGER_PACKAGE_NAME
        try {
            if (currentPackage == original) {
                emit(Progress.Error("Already using the original package name"))
                return
            }
            if (!ShizukuApi.isPermissionGranted) {
                emit(Progress.Error("Shizuku is required"))
                return
            }

            withContext(Dispatchers.IO) {
                val workDir = lspApp.cacheDir.resolve("cloak-revert").also {
                    it.deleteRecursively()
                    it.mkdirs()
                }

                emit(Progress.Message("Downloading latest manager from GitHub…"))
                val downloaded = GithubReleaseDownloader.downloadLatestManager(
                    workDir.resolve("manager-github.apk")
                )
                emit(Progress.Message("Downloaded ${downloaded.assetName} (${downloaded.tagName})"))

                emit(Progress.Message("Preparing migrate data…"))
                val migrateZip = ManagerMigrate.createMigrateZip(lspApp, workDir.resolve("migrate.zip"))

                emit(Progress.Message("Preparing original package APK…"))
                val stockApk = ManagerCloak.prepareStockWithMigrate(
                    downloaded.file,
                    migrateZip,
                    workDir.resolve("manager-original.apk")
                )

                emit(Progress.Message("Installing original manager…"))
                val (installStatus, installMessage) =
                    LSPPackageManager.installFiles(listOf(stockApk), ShizukuApi.isPermissionGranted)
                if (installStatus != PackageInstaller.STATUS_SUCCESS) {
                    emit(Progress.Error("Install failed: $installMessage"))
                    return@withContext
                }

                emit(Progress.Message("Updating manager-mode apps…"))
                val retarget = LocalAppsUpdater.updateAllForManager(original)
                if (retarget.failed.isNotEmpty()) {
                    emit(Progress.Message(
                        "Retargeted ${retarget.updated.size}, ${retarget.failed.size} failed: " +
                                retarget.failed.joinToString { it.first }
                    ))
                }

                emit(Progress.Message("Launching original manager…"))
                val launch = LSPPackageManager.getLaunchIntentForPackage(original)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    withContext(Dispatchers.Main) {
                        lspApp.startActivity(launch)
                    }
                }

                emit(Progress.Message("Removing cloaked package…"))
                val (uninstallStatus, uninstallMessage) = LSPPackageManager.uninstall(currentPackage)
                if (uninstallStatus != PackageInstaller.STATUS_SUCCESS) {
                    Log.w(TAG, "Uninstall cloaked package failed: $uninstallMessage")
                }

                workDir.deleteRecursively()
                emit(Progress.Success(original))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Revert failed", t)
            emit(Progress.Error(t.message ?: t.toString()))
        }
    }
}
