/**
 * Metrolist Discover 13.7.0.12 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.space

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Process
import com.metrolist.music.discover.engine.SpacePaths
import java.io.File

/**
 * 13.7.0.12: every profile is a separate app instance on disk (see [SpacePaths]).
 *
 * The active profile is a tiny device-wide registry (shared_prefs/discover_space.xml) read in attachBaseContext, before
 * Hilt builds anything. For "me" the app's contexts are NOT wrapped at all, so the main profile runs on exactly the files
 * it always had. For any other profile the MainActivity and MusicService contexts are wrapped in a [SpaceContext]
 * (the Application routes its storage calls to one, see [SpaceApplication]) that points filesDir/cacheDir/databases/shared_prefs at that profile's own tree, so Metrolist's Room
 * database, its settings DataStore (YouTube account included), player cache, downloads, queue, image cache and every
 * SharedPreferences file are that profile's alone. Switching writes the registry and restarts the process
 * ([SpaceSwitchActivity], a ProcessPhoenix-style relaunch in a tiny separate process).
 */
object ProfileSpace {
    private const val K_ACTIVE = "active"
    private const val K_PENDING = "pendingAuto"
    const val EXTRA_NAME = "space.name"
    const val EXTRA_EMOJI = "space.emoji"
    const val EXTRA_PID = "space.pid"
    const val EXTRA_LAUNCH = "space.launch"

    @Volatile var id: String = SpacePaths.DEFAULT
        private set
    @Volatile private var resolved = false

    private fun registry(ctx: Context) = unwrap(ctx).getSharedPreferences(SpacePaths.REGISTRY_PREFS, Context.MODE_PRIVATE)

    /** The real (unwrapped) context: the registry and the legacy files are always read through it. */
    fun unwrap(ctx: Context): Context {
        var c: Context = ctx
        if (c is Application) c = c.baseContext ?: c
        while (c is SpaceContext) c = c.baseContext
        return c
    }

    /** Reads the active profile once per process (a pending scheduled switch is applied here, at a cold start). */
    fun resolve(base: Context): String {
        if (resolved) return id
        synchronized(this) {
            if (resolved) return id
            id = runCatching {
                val p = base.getSharedPreferences(SpacePaths.REGISTRY_PREFS, Context.MODE_PRIVATE)
                val pending = p.getString(K_PENDING, null)
                if (pending != null) p.edit().putString(K_ACTIVE, SpacePaths.sanitize(pending)).remove(K_PENDING).commit()
                SpacePaths.sanitize(p.getString(K_ACTIVE, null))
            }.getOrDefault(SpacePaths.DEFAULT)
            resolved = true
            return id
        }
    }

    fun wrap(base: Context): Context {
        val s = resolve(base)
        return if (SpacePaths.isLegacy(s)) base else SpaceContext(base, s)
    }

    /** Called from [SpaceApplication.attachBaseContext]: the WebView data-dir suffix (the YouTube login cookies), main process only. */
    fun applyWebViewSuffix(base: Context) {
        val suffix = SpacePaths.webViewSuffix(resolve(base)) ?: return
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            if (Application.getProcessName() == base.packageName) android.webkit.WebView.setDataDirectorySuffix(suffix)
        }
    }

    fun dataDir(ctx: Context): File = unwrap(ctx).filesDir.parentFile ?: File(unwrap(ctx).filesDir, "..")

    /** The main profile's legacy database file (read-only use: seeding a profile's own song metadata). */
    fun legacyDatabase(ctx: Context, name: String): File = unwrap(ctx).getDatabasePath(name)

    /** Writes the registry synchronously (it must be on disk before the process is killed). */
    fun commitActive(ctx: Context, space: String): Boolean =
        registry(ctx).edit().putString(K_ACTIVE, SpacePaths.sanitize(space)).remove(K_PENDING).commit()

    /** A scheduled (auto) switch: applied at the next cold start, never by killing a running app. */
    fun setPending(ctx: Context, space: String?) {
        val e = registry(ctx).edit()
        if (space == null || SpacePaths.sanitize(space) == id) e.remove(K_PENDING) else e.putString(K_PENDING, SpacePaths.sanitize(space))
        e.apply()
    }

    fun pending(ctx: Context): String? = registry(ctx).getString(K_PENDING, null)

    /**
     * Switch: registry first, then a clean restart into the new profile. [ctx] should be an Activity (the relaunch is a
     * user-visible activity start). Returns false when nothing was done.
     */
    fun switchAndRestart(ctx: Context, space: String, name: String, emoji: String): Boolean {
        val target = SpacePaths.sanitize(space)
        if (target == id) return false
        if (!commitActive(ctx, target)) return false
        val app = ctx.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        val i = Intent(app, SpaceSwitchActivity::class.java)
            .putExtra(EXTRA_NAME, name).putExtra(EXTRA_EMOJI, emoji)
            .putExtra(EXTRA_PID, Process.myPid()).putExtra(EXTRA_LAUNCH, launch)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val act = findActivity(ctx)
        if (act != null) { act.startActivity(i); act.overridePendingTransition(0, 0) } else app.startActivity(i)
        return true
    }

    /** Deletes every file of [space] (never the main profile, never the active one). */
    fun deleteFiles(ctx: Context, space: String): Boolean {
        val s = SpacePaths.sanitize(space)
        if (SpacePaths.isLegacy(s) || s == id) return false
        val d = SpacePaths.deletion(s)
        val data = dataDir(ctx)
        var ok = true
        for (rel in d.dirs) { val f = File(data, rel); if (f.exists() && !f.deleteRecursively()) ok = false }
        d.prefsPrefix?.let { pre ->
            val base = unwrap(ctx)
            File(data, "shared_prefs").listFiles()?.filter { it.name.startsWith(pre) }?.forEach { f ->
                val n = f.name.removeSuffix(".xml")
                if (Build.VERSION.SDK_INT >= 24) runCatching { base.deleteSharedPreferences(n) }
                if (f.exists() && !f.delete()) ok = false
            }
        }
        return ok
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}
