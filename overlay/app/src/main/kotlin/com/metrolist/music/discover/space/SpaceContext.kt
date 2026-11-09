/**
 * Metrolist Discover 13.7.0.12 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.space

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.metrolist.music.discover.engine.SpacePaths
import java.io.File

/**
 * The storage of one non-main profile: the app's own private locations, re-rooted under spaces/<id> (see [SpacePaths]).
 * Everything that reaches files through a Context (Room via getDatabasePath/openOrCreateDatabase, DataStore via filesDir,
 * media3's caches and download index, Coil's disk cache, SharedPreferences, FileProvider roots) follows automatically.
 */
class SpaceContext(base: Context, val space: String) : ContextWrapper(base) {
    private val data: File = base.filesDir.parentFile ?: base.filesDir

    private fun dir(rel: String): File = File(data, rel).apply { if (!isDirectory) mkdirs() }

    override fun getFilesDir(): File = dir(SpacePaths.filesDir(space))

    override fun getCacheDir(): File = File(baseContext.cacheDir, "spaces/" + SpacePaths.sanitize(space)).apply { if (!isDirectory) mkdirs() }

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
