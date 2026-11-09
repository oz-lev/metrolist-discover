/**
 * Metrolist Discover 13.7.0.12 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.space

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The app's Application base class (hook "space-app" puts it under upstream's App, so Hilt's Hilt_App extends it).
 * The Application's own base context stays the platform's (Android casts it to ContextImpl when it delivers broadcasts,
 * so it must not be wrapped); instead the storage calls made through the Application (= every @ApplicationContext,
 * applicationContext, ContentProvider and FileProvider) are routed to the active profile's [SpaceContext]. For the main
 * profile "me" there is no space and every call is the platform's own, unchanged.
 */
open class SpaceApplication : Application() {
    @Volatile private var space: SpaceContext? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        space = ProfileSpace.wrap(base) as? SpaceContext
        ProfileSpace.applyWebViewSuffix(base)
    }

    override fun getFilesDir(): File = space?.filesDir ?: super.getFilesDir()
    override fun getCacheDir(): File = space?.cacheDir ?: super.getCacheDir()
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
