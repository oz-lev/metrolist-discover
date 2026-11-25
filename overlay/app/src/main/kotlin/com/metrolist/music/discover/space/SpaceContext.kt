/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.space

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.metrolist.music.discover.engine.SpacePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 13.7.0.15: complete storage isolation for one non-main profile ("space").
 * Re-roots filesDir, cacheDir, codeCacheDir, noBackupFilesDir, dataDir, getDir, openFileInput/Output/deleteFile/fileList,
 * externalFilesDir/externalCacheDir, databases (including discover_profile.db and androidx.work.workdb), and SharedPreferences.
 */
class SpaceContext(base: Context, val space: String) : ContextWrapper(base) {
    private val data: File = base.filesDir.parentFile ?: base.filesDir

    private fun dir(rel: String): File = File(data, rel).apply { if (!isDirectory) mkdirs() }

    private fun safeChild(parent: File, name: String): File {
        require(!name.contains(File.separatorChar)) { "File $name contains a path separator" }
        return File(parent, name)
    }

    override fun getDataDir(): File = SpacePaths.root(space)?.let { dir(it) } ?: super.getDataDir()

    override fun getFilesDir(): File = dir(SpacePaths.filesDir(space))

    override fun getNoBackupFilesDir(): File = dir(SpacePaths.noBackupDir(space))

    override fun getDir(name: String, mode: Int): File = dir(SpacePaths.customDir(space, name))

    override fun getCacheDir(): File = File(baseContext.cacheDir, "spaces/" + SpacePaths.sanitize(space)).apply { if (!isDirectory) mkdirs() }

    override fun getCodeCacheDir(): File = File(baseContext.codeCacheDir, "spaces/" + SpacePaths.sanitize(space)).apply { if (!isDirectory) mkdirs() }

    override fun getExternalFilesDir(type: String?): File? {
        val baseExt = baseContext.getExternalFilesDir(type) ?: return null
        val sub = SpacePaths.externalSubdir(space) ?: return baseExt
        return File(baseExt, sub).apply { if (!isDirectory) mkdirs() }
    }

    override fun getExternalCacheDir(): File? {
        val baseExt = baseContext.externalCacheDir ?: return null
        val sub = SpacePaths.externalSubdir(space) ?: return baseExt
        return File(baseExt, sub).apply { if (!isDirectory) mkdirs() }
    }

    override fun getFileStreamPath(name: String): File = safeChild(filesDir, name)

    override fun openFileInput(name: String): FileInputStream = FileInputStream(safeChild(filesDir, name))

    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val append = (mode and Context.MODE_APPEND) != 0
        val f = safeChild(filesDir, name)
        f.parentFile?.mkdirs()
        return FileOutputStream(f, append)
    }

    override fun deleteFile(name: String): Boolean = safeChild(filesDir, name).delete()

    override fun fileList(): Array<String> = filesDir.list() ?: emptyArray()

    override fun getDatabasePath(name: String): File {
        if (name.contains(File.separatorChar)) return File(name)
        if (SpacePaths.isGlobalDb(name)) return baseContext.getDatabasePath(name)
        return File(dir(SpacePaths.databasesDir(space)), name)
    }

    private fun flags(mode: Int): Int = SQLiteDatabase.CREATE_IF_NECESSARY or
        (if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0) or
        (if (mode and Context.MODE_NO_LOCALIZED_COLLATORS != 0) SQLiteDatabase.NO_LOCALIZED_COLLATORS else 0)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        if (SpacePaths.isGlobalDb(name)) baseContext.openOrCreateDatabase(name, mode, factory)
        else SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, flags(mode))

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        if (SpacePaths.isGlobalDb(name)) baseContext.openOrCreateDatabase(name, mode, factory, errorHandler)
        else SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, flags(mode), errorHandler)

    override fun deleteDatabase(name: String): Boolean =
        if (SpacePaths.isGlobalDb(name)) baseContext.deleteDatabase(name) else SQLiteDatabase.deleteDatabase(getDatabasePath(name))

    override fun databaseList(): Array<String> = dir(SpacePaths.databasesDir(space)).list() ?: emptyArray()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences(SpacePaths.prefsName(space, name ?: "null"), mode)

    override fun deleteSharedPreferences(name: String): Boolean =
        baseContext.deleteSharedPreferences(SpacePaths.prefsName(space, name))

    override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?): Boolean = false

    override fun moveDatabaseFrom(sourceContext: Context?, name: String?): Boolean = false
}
