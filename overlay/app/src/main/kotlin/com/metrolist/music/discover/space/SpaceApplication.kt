/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.space

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The app's Application base class (hook "space-app" puts it under upstream's App, so Hilt's Hilt_App extends it).
 * Routes every storage call on Application (= every @ApplicationContext, applicationContext, ContentProvider and FileProvider)
 * to the active profile's [SpaceContext]. For "me" there is no SpaceContext and every call stays on the legacy files.
 */
open class SpaceApplication : Application() {
    @Volatile private var space: SpaceContext? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        space = ProfileSpace.wrap(base) as? SpaceContext
        ProfileSpace.applyWebViewSuffix(base)
    }

    override fun getDataDir(): File = space?.dataDir ?: super.getDataDir()
    override fun getFilesDir(): File = space?.filesDir ?: super.getFilesDir()
    override fun getNoBackupFilesDir(): File = space?.noBackupFilesDir ?: super.getNoBackupFilesDir()
    override fun getDir(name: String, mode: Int): File = space?.getDir(name, mode) ?: super.getDir(name, mode)
    override fun getCacheDir(): File = space?.cacheDir ?: super.getCacheDir()
    override fun getCodeCacheDir(): File = space?.codeCacheDir ?: super.getCodeCacheDir()
    override fun getExternalFilesDir(type: String?): File? = space?.getExternalFilesDir(type) ?: super.getExternalFilesDir(type)
    override fun getExternalCacheDir(): File? = space?.externalCacheDir ?: super.getExternalCacheDir()
    override fun getFileStreamPath(name: String): File = space?.getFileStreamPath(name) ?: super.getFileStreamPath(name)
    override fun openFileInput(name: String): FileInputStream = space?.openFileInput(name) ?: super.openFileInput(name)
    override fun openFileOutput(name: String, mode: Int): FileOutputStream = space?.openFileOutput(name, mode) ?: super.openFileOutput(name, mode)
    override fun deleteFile(name: String): Boolean = space?.deleteFile(name) ?: super.deleteFile(name)
    override fun fileList(): Array<String> = space?.fileList() ?: super.fileList()
    override fun getDatabasePath(name: String): File = space?.getDatabasePath(name) ?: super.getDatabasePath(name)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        space?.openOrCreateDatabase(name, mode, factory) ?: super.openOrCreateDatabase(name, mode, factory)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        space?.openOrCreateDatabase(name, mode, factory, errorHandler) ?: super.openOrCreateDatabase(name, mode, factory, errorHandler)

    override fun deleteDatabase(name: String): Boolean = space?.deleteDatabase(name) ?: super.deleteDatabase(name)
    override fun databaseList(): Array<String> = space?.databaseList() ?: super.databaseList()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        space?.getSharedPreferences(name, mode) ?: super.getSharedPreferences(name, mode)

    override fun deleteSharedPreferences(name: String): Boolean = space?.deleteSharedPreferences(name) ?: super.deleteSharedPreferences(name)
}
