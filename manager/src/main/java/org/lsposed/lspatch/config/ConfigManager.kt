package org.lsposed.lspatch.config

import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.database.LSPDatabase
import org.lsposed.lspatch.database.entity.Module
import org.lsposed.lspatch.database.entity.Scope
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.manager.ManagerRemoteServices
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LoadedModules
import org.matrix.vector.ipc.LoadedModule

object ConfigManager {

    private const val TAG = "ConfigManager"

    @OptIn(ExperimentalCoroutinesApi::class) private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    private val db: LSPDatabase =
        Room.databaseBuilder(
                lspApp,
                LSPDatabase::class.java,
                "modules_config.db",
            )
            .build()

    private val moduleDao = db.moduleDao()
    private val scopeDao = db.scopeDao()

    suspend fun updateModules(newModules: Map<String, String>) =
        withContext(dispatcher) {
            for (module in moduleDao.getAll()) {
                if (newModules.containsKey(module.pkgName)) continue
                // Absent from the scan does not mean uninstalled: isModuleApk returns false on any
                // transient ZipFile read failure, and deleting the row here cascades away every app's
                // scope that points at it. Delete only when the package is genuinely gone.
                val stillInstalled = runCatching {
                    lspApp.packageManager.getApplicationInfo(module.pkgName, 0)
                }
                    .isSuccess
                if (!stillInstalled) moduleDao.delete(module)
            }
            for ((pkgName, apkPath) in newModules) {
                // insert-if-absent then update the path in place. A REPLACE-based upsert would
                // delete-and-reinsert the row, cascading away every scope row that points at it --
                // which is exactly how enabled modules were being lost on restart.
                moduleDao.insert(Module(pkgName, apkPath))
                moduleDao.updatePath(pkgName, apkPath)
            }
        }

    suspend fun activateModule(pkgName: String, module: Module) =
        withContext(dispatcher) {
            scopeDao.insert(Scope(appPkgName = pkgName, modulePkgName = module.pkgName))
        }

    suspend fun deactivateModule(pkgName: String, module: Module) =
        withContext(dispatcher) {
            scopeDao.delete(Scope(appPkgName = pkgName, modulePkgName = module.pkgName))
        }

    /**
     * Counts up whenever any app's module scope changes.
     *
     * The scope lives in the database, which nothing observes; without a signal, editing an app's modules on one screen
     * left the other screen showing the set from before the edit until the manager was restarted.
     */
    private val _scopeRevision = MutableStateFlow(0)
    val scopeRevision: StateFlow<Int> = _scopeRevision.asStateFlow()

    /**
     * Makes [modules] the complete set of modules enabled for [appPkgName], in one transaction.
     *
     * All of it or none of it: a half-applied scope is a patched app running a module combination the user never chose.
     * The parent [Module] row is ensured for every target first, because the scope table has a foreign key onto it and
     * inserting a scope row alone fails for a module the manager has not catalogued yet.
     */
    suspend fun setScopeForApp(appPkgName: String, modules: Set<String>): Result<Unit> =
        withContext(dispatcher) {
            runCatching {
                var before = emptySet<String>()
                db.withTransaction {
                    before = scopeDao.getModulesForApp(appPkgName).map { it.pkgName }.toSet()
                    (before - modules).forEach {
                        scopeDao.delete(Scope(appPkgName = appPkgName, modulePkgName = it))
                    }
                    (modules - before).forEach { pkg ->
                        val apkPath =
                            runCatching {
                                lspApp.packageManager.getApplicationInfo(pkg, 0).sourceDir
                            }
                                .getOrNull() ?: return@forEach
                        moduleDao.insert(Module(pkg, apkPath))
                        moduleDao.updatePath(pkg, apkPath)
                        scopeDao.insert(Scope(appPkgName = appPkgName, modulePkgName = pkg))
                    }
                }
                LSPPackageManager.invalidateModuleIcons(appPkgName)
                _scopeRevision.value++
                // Whoever gained or lost this app is now describing a different scope to its companion,
                // and a companion holding no service at all is the common case; both are settled by a
                // push, which reaches the module app whether or not it is already running.
                ManagerRemoteServices.pushToCompanionsAsync(before + modules)
                Unit
            }
        }

    /** Drops every module binding for [appPkgName] -- used when its patch is removed entirely. */
    suspend fun clearScopeForApp(appPkgName: String) =
        withContext(dispatcher) {
            runCatching {
                scopeDao.deleteForApp(appPkgName)
                LSPPackageManager.invalidateModuleIcons(appPkgName)
                _scopeRevision.value++
            }
            Unit
        }

    suspend fun getModulesForApp(pkgName: String): List<Module> =
        withContext(dispatcher) {
            return@withContext scopeDao.getModulesForApp(pkgName)
        }

    /** The patched apps that currently have [pkgName] enabled, by package name. */
    suspend fun getAppsForModule(pkgName: String): List<String> =
        withContext(dispatcher) {
            return@withContext scopeDao.getAppsForModule(pkgName)
        }

    // The framework consumes a module's dexes when it loads them, so a fresh LoadedModule is built per
    // request. `legacy` selects which half to build - a module of the other kind has its freshly mapped
    // SharedMemory closed straight away rather than left for the finalizer.
    suspend fun getModuleFilesForApp(pkgName: String, legacy: Boolean): List<LoadedModule> =
        withContext(dispatcher) {
            scopeDao.getModulesForApp(pkgName).mapNotNull { buildLoadedModule(it, legacy) }
        }

    /**
     * A fresh [LoadedModule] for a single module by package, or null if it is not a [legacy]-matching module or cannot
     * be loaded. Hot reload uses this to build the new generation from the module's currently installed apk, the same
     * way [getModuleFilesForApp] builds the ones a host loads.
     */
    suspend fun buildLoadedModule(pkgName: String, legacy: Boolean = false): LoadedModule? =
        withContext(dispatcher) {
            val module = runCatching { moduleDao.getModule(pkgName) }.getOrNull() ?: return@withContext null
            buildLoadedModule(module, legacy)
        }

    private suspend fun buildLoadedModule(module: Module, legacy: Boolean): LoadedModule? {
        if (!File(module.apkPath).exists()) {
            try {
                module.apkPath = lspApp.packageManager.getApplicationInfo(module.pkgName, 0).sourceDir
            } catch (e: PackageManager.NameNotFoundException) {
                moduleDao.delete(moduleDao.getModule(module.pkgName))
                Log.w(TAG, "Module may be uninstalled: ${module.pkgName}")
                return null
            }
            Log.i(TAG, "Module apk path updated: ${module.pkgName}")
        }
        val pm = lspApp.packageManager
        val appInfo =
            try {
                pm.getApplicationInfo(module.pkgName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        val appId = (appInfo?.uid ?: -1).let { uid -> if (uid < 0) -1 else uid % 100000 }
        val versionCode = runCatching {
            pm.getPackageInfo(module.pkgName, 0).longVersionCode
        }
            .getOrDefault(0L)
        return LoadedModules.fromApk(
            module.pkgName,
            module.apkPath,
            appId,
            versionCode,
            appInfo,
            legacy,
            ManagerRemoteServices.moduleService(module.pkgName),
        )
    }
}
