/**
 * Metrolist Discover 13.7.0.12 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

/**
 * 13.7.0.12: every profile is its own app instance on disk ("space").
 *
 * The main profile "me" keeps the app's original, legacy locations exactly (databases/song.db, files/datastore/settings,
 * files/exoplayer, files/download, shared_prefs/<name>.xml, cache/...), so nothing of the existing library moves or is
 * migrated. Every other profile gets its own tree under the app's data dir:
 *   spaces/<id>/databases/<name>   (Metrolist's song.db, media3's exoplayer_internal.db incl. the download index, ...)
 *   spaces/<id>/files/...          (settings DataStore incl. YouTube cookie/visitorData/account, player cache, downloads, queue)
 *   cache/spaces/<id>/...          (image cache, share files)
 *   shared_prefs/sp_<id>__<name>.xml
 *   app_webview_space_<id>         (WebView data dir suffix: the YouTube login's cookies)
 * A few files stay device-wide on purpose (see [GLOBAL_DBS] / [GLOBAL_PREFS]): the profile registry itself, Discover's own
 * small database (every row there already carries its profile id since 13.7.0.10), and WorkManager's job database
 * (Android's job scheduler is device state; a job always runs in the profile that is active when it runs).
 * All paths here are relative to the app's data dir (the parent of filesDir) and use '/'.
 */
object SpacePaths {
    const val DEFAULT = ProfileScope.DEFAULT
    const val REGISTRY_PREFS = "discover_space"
    const val PREFS_PREFIX = "sp_"
    const val PREFS_SEP = "__"
    const val WEBVIEW_SUFFIX_PREFIX = "space_"

    /** Databases that stay where they are for every profile. */
    val GLOBAL_DBS = setOf("metrolist_discover.db")
    private val GLOBAL_DB_PREFIXES = listOf("androidx.work")
    /** SharedPreferences files that stay where they are for every profile. */
    val GLOBAL_PREFS = setOf(REGISTRY_PREFS)
    private val GLOBAL_PREFS_PREFIXES = listOf("androidx.work")

    /** Profile ids become directory and file names: only [A-Za-z0-9_-], at most 40 chars; anything else (or blank) is "me". */
    fun sanitize(id: String?): String {
        val s = id?.trim().orEmpty()
        if (s.isEmpty() || s.length > 40) return DEFAULT
        return if (s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) s else DEFAULT
    }

    fun isLegacy(space: String): Boolean = sanitize(space) == DEFAULT

    private fun baseName(name: String): String = name.removeSuffix("-wal").removeSuffix("-shm").removeSuffix("-journal")

    fun isGlobalDb(name: String): Boolean {
        val b = baseName(name)
        return b in GLOBAL_DBS || GLOBAL_DB_PREFIXES.any { b.startsWith(it) }
    }

    fun isGlobalPrefs(name: String): Boolean = name in GLOBAL_PREFS || GLOBAL_PREFS_PREFIXES.any { name.startsWith(it) }

    /** The space's root (relative to the data dir); null for the legacy main profile. */
    fun root(space: String): String? = if (isLegacy(space)) null else "spaces/${sanitize(space)}"

    fun filesDir(space: String): String = root(space)?.let { "$it/files" } ?: "files"
    fun cacheDir(space: String): String = if (isLegacy(space)) "cache" else "cache/spaces/${sanitize(space)}"
    fun databasesDir(space: String): String = root(space)?.let { "$it/databases" } ?: "databases"

    /** Where a database called [name] lives for [space]. An absolute path (contains '/') is never moved. */
    fun databasePath(space: String, name: String): String =
        if (name.contains('/') || isLegacy(space) || isGlobalDb(name)) (if (name.contains('/')) name else "databases/$name")
        else "${databasesDir(space)}/$name"

    /** The SharedPreferences file name used for [name] in [space]. */
    fun prefsName(space: String, name: String): String =
        if (isLegacy(space) || isGlobalPrefs(name)) name else "$PREFS_PREFIX${sanitize(space)}$PREFS_SEP$name"

    fun webViewSuffix(space: String): String? = if (isLegacy(space)) null else WEBVIEW_SUFFIX_PREFIX + sanitize(space)

    /** Does a shared_prefs file (without .xml) belong to [space]'s own namespace? */
    fun prefsBelongTo(space: String, fileName: String): Boolean =
        !isLegacy(space) && fileName.removeSuffix(".xml").startsWith("$PREFS_PREFIX${sanitize(space)}$PREFS_SEP")

    /**
     * Everything to remove when [space] is deleted: whole directories (relative to the data dir) plus the shared_prefs
     * name prefix. The main profile is never deletable, so it returns nothing (its legacy files are never touched).
     */
    data class Deletion(val dirs: List<String>, val prefsPrefix: String?)

    fun deletion(space: String): Deletion {
        if (isLegacy(space)) return Deletion(emptyList(), null)
        val id = sanitize(space)
        return Deletion(listOf("spaces/$id", "cache/spaces/$id", "app_webview_$WEBVIEW_SUFFIX_PREFIX$id"), "$PREFS_PREFIX$id$PREFS_SEP")
    }
}

/**
 * 13.7.0.12: whose rows the active space's own Metrolist database holds. A profile other than "me" has a database of its
 * own, so every row in it is that profile's. The main profile keeps the legacy song.db, which also holds the listens made
 * in other profiles between 13.7.0.10 and this version: those stay attributed by the old timeline, and every row from the
 * first start of this version ([epoch]) on is "me"'s, whatever the timeline said last.
 */
object SpaceOwnership {
    data class Attribution(val marks: List<SwitchMark>, val default: String)

    fun attribution(space: String, timeline: List<SwitchMark>, epoch: Long?): Attribution =
        if (!SpacePaths.isLegacy(space)) Attribution(emptyList(), space)
        else Attribution(if (epoch == null) timeline else ProfileTimeline.append(timeline, epoch, ProfileScope.DEFAULT), ProfileScope.DEFAULT)

    /**
     * Which of Discover's own logged plays (shared file, every row tagged with its profile) the space may know about:
     * its own; for "me" also the other profiles' plays from before [epoch] (they matter only to tell its legacy rows apart).
     */
    fun visiblePlays(space: String, plays: List<OwnedPlay>, epoch: Long?): List<OwnedPlay> =
        if (!SpacePaths.isLegacy(space)) plays.filter { it.owner == space }
        else plays.filter { it.owner == space || (epoch != null && it.play.timestampMs < epoch) }
}
