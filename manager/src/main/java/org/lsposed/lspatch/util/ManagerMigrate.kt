package org.lsposed.lspatch.util

import android.content.Context
import android.util.Log
import org.lsposed.lspatch.config.MyKeyStore
import org.lsposed.lspatch.share.Constants
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Embeds / restores manager settings across a package-name change.
 * Migrate payload is written into the cloaked APK as [Constants.MIGRATE_ASSET_PATH]
 * so the new package can import without Shizuku on first launch.
 */
object ManagerMigrate {

    private const val TAG = "ManagerMigrate"
    private const val MARKER = ".cloak_migrate_done"
    private const val ENTRY_PREFS = "settings.xml"
    private const val ENTRY_KEYSTORE = "keystore.bks"
    private const val ENTRY_DB = "modules_config.db"
    private const val ENTRY_DB_WAL = "modules_config.db-wal"
    private const val ENTRY_DB_SHM = "modules_config.db-shm"

    fun createMigrateZip(context: Context, dest: File): File {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        runCatching {
            val dbFile = context.getDatabasePath("modules_config.db")
            if (dbFile.exists()) {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { it.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close() }
            }
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
            fun putFile(entryName: String, file: File) {
                if (!file.exists()) return
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { it.copyTo(zos) }
                zos.closeEntry()
            }

            putFile(ENTRY_PREFS, File(context.applicationInfo.dataDir, "shared_prefs/settings.xml"))
            putFile(ENTRY_KEYSTORE, MyKeyStore.file)
            putFile(ENTRY_DB, context.getDatabasePath("modules_config.db"))
            putFile(ENTRY_DB_WAL, context.getDatabasePath("modules_config.db-wal"))
            putFile(ENTRY_DB_SHM, context.getDatabasePath("modules_config.db-shm"))
        }
        return dest
    }

    fun importIfNeeded(context: Context) {
        val marker = File(context.filesDir, MARKER)
        if (marker.exists()) return

        val apk = File(context.applicationInfo.sourceDir)
        if (!apk.exists()) return

        try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(Constants.MIGRATE_ASSET_PATH) ?: run {
                    // Stock / non-cloaked install: mark done so we don't keep checking.
                    context.filesDir.mkdirs()
                    marker.createNewFile()
                    return
                }
                Log.i(TAG, "Importing migrate payload from manager APK")
                ZipInputStream(zip.getInputStream(entry)).use { zis ->
                    var zipEntry = zis.nextEntry
                    while (zipEntry != null) {
                        when (zipEntry.name) {
                            ENTRY_PREFS -> {
                                val out = File(context.applicationInfo.dataDir, "shared_prefs/settings.xml")
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { zis.copyTo(it) }
                            }
                            ENTRY_KEYSTORE -> {
                                context.filesDir.mkdirs()
                                FileOutputStream(File(context.filesDir, "keystore.bks")).use { zis.copyTo(it) }
                            }
                            ENTRY_DB -> writeDb(context, "modules_config.db", zis)
                            ENTRY_DB_WAL -> writeDb(context, "modules_config.db-wal", zis)
                            ENTRY_DB_SHM -> writeDb(context, "modules_config.db-shm", zis)
                        }
                        zis.closeEntry()
                        zipEntry = zis.nextEntry
                    }
                }
            }
            context.filesDir.mkdirs()
            marker.createNewFile()
            Log.i(TAG, "Migrate import completed")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to import migrate payload", t)
        }
    }

    private fun writeDb(context: Context, name: String, zis: ZipInputStream) {
        val out = context.getDatabasePath(name)
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { zis.copyTo(it) }
    }
}
