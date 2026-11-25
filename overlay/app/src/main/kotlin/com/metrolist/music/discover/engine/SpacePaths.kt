/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

/**
 * 13.7.0.15: complete per-profile isolation ("space").
 *
 * The main profile "me" keeps the app's original, legacy locations exactly (databases/song.db, databases/metrolist_discover.db,
 * files/datastore/settings, files/exoplayer, files/download, shared_prefs/<name>.xml, cache/...), so Oz loses zero data.
 * Every other profile gets its own complete tree under the app's data dir:
 *   spaces/<id>/databases/<name>   (song.db, discover_profile.db, exoplayer_internal.db, androidx.work.workdb, ...)
 *   spaces/<id>/files/...          (settings DataStore incl. YouTube account, player cache, downloads, persistent queue)
 *   spaces/<id>/no_backup/...      (noBackupFilesDir)
 *   spaces/<id>/app_<name>/...     (Context.getDir)
 *   cache/spaces/<id>/...          (Coil image cache, share files)
 *   code_cache/spaces/<id>/...     (codeCacheDir)
 *   shared_prefs/sp_<id>__<name>.xml (including androidx.work prefs)
 *   app_webview_space_<id>         (WebView cookies, Android 9+)
 *   external files/cache subdir: spaces/<id>
 *
 * ONLY the cross-profile registry itself stays device-wide:
 *   - shared_prefs/discover_space.xml (which profile is active + pending scheduled switch)
 *   - databases/metrolist_discover.db (legacy "me" DB + global g.* registry keys: profile list, auto-switch, yomi toggle)
 * Per-profile Discover plays, recs, taste state, saved mixes, feedback and settings for any non-"me" profile live in
 * its own physical database: spaces/<id>/databases/discover_profile.db.
 */
object SpacePaths {
    const val DEFAULT = ProfileScope.DEFAULT
    const val REGISTRY_PREFS = "discover_space"
    const val LEGACY_DISCOVER_DB = "metrolist_discover.db"
    const val PROFILE_DISCOVER_DB = "discover_profile.db"
    const val PREFS_PREFIX = "sp_"
    const val PREFS_SEP = "__"
    const val WEBVIEW_SUFFIX_PREFIX = "space_"

    /** Databases that stay device-wide: ONLY the legacy/registry Discover DB (non-me profiles use discover_profile.db). */
    val GLOBAL_DBS = setOf(LEGACY_DISCOVER_DB)
    /** SharedPreferences files that stay device-wide: ONLY the active-space registry. */
    val GLOBAL_PREFS = setOf(REGISTRY_PREFS)

    /** Profile ids become directory and file names: only [A-Za-z0-9_-], at most 40 chars; anything else (or blank) is "me". */
    fun sanitize(id: String?): String {
        val s = id?.trim().orEmpty()
        if (s.isEmpty() || s.length > 40) return DEFAULT
        return if (s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) s else DEFAULT
    }

    fun isLegacy(space: String): Boolean = sanitize(space) == DEFAULT

    private fun baseName(name: String): String = name.removeSuffix("-wal").removeSuffix("-shm").removeSuffix("-journal")

    fun isGlobalDb(name: String): Boolean = baseName(name) in GLOBAL_DBS

    fun isGlobalPrefs(name: String): Boolean = name in GLOBAL_PREFS

    /** The space's root (relative to the data dir); null for the legacy main profile. */
    fun root(space: String): String? = if (isLegacy(space)) null else "spaces/${sanitize(space)}"

    fun filesDir(space: String): String = root(space)?.let { "$it/files" } ?: "files"
    fun cacheDir(space: String): String = if (isLegacy(space)) "cache" else "cache/spaces/${sanitize(space)}"
    fun codeCacheDir(space: String): String = if (isLegacy(space)) "code_cache" else "code_cache/spaces/${sanitize(space)}"
    fun noBackupDir(space: String): String = root(space)?.let { "$it/no_backup" } ?: "no_backup"
    fun customDir(space: String, name: String): String = root(space)?.let { "$it/app_$name" } ?: "app_$name"
    fun databasesDir(space: String): String = root(space)?.let { "$it/databases" } ?: "databases"
    fun externalSubdir(space: String): String? = root(space)

    /** Which Discover SQLite filename holds this profile's own plays, recs and settings. */
    fun discoverDbName(space: String): String = if (isLegacy(space)) LEGACY_DISCOVER_DB else PROFILE_DISCOVER_DB

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
     * Everything to remove when [space] is deleted: whole directories (relative to the data dir), external subdir,
     * plus the shared_prefs name prefix. The main profile is never deletable, so it returns nothing.
     */
    data class Deletion(val dirs: List<String>, val prefsPrefix: String?, val externalSubdir: String? = null)

    fun deletion(space: String): Deletion {
        if (isLegacy(space)) return Deletion(emptyList(), null, null)
        val id = sanitize(space)
        return Deletion(
            dirs = listOf("spaces/$id", "cache/spaces/$id", "code_cache/spaces/$id", "app_webview_$WEBVIEW_SUFFIX_PREFIX$id"),
            prefsPrefix = "$PREFS_PREFIX$id$PREFS_SEP",
            externalSubdir = "spaces/$id",
        )
    }
}

/**
 * 13.7.0.12/15: whose rows the active space's own Metrolist database holds. A profile other than "me" has a database of its
 * own, so every row in it is that profile's. The main profile keeps the legacy song.db, which also holds the listens made
 * in other profiles between 13.7.0.10 and 13.7.0.12: those stay attributed by the old timeline, and every row from [epoch]
 * on is "me"'s, whatever the timeline said last.
 */
object SpaceOwnership {
    data class Attribution(val marks: List<SwitchMark>, val default: String)

    fun attribution(space: String, timeline: List<SwitchMark>, epoch: Long?): Attribution =
        if (!SpacePaths.isLegacy(space)) Attribution(emptyList(), space)
        else Attribution(if (epoch == null) timeline else ProfileTimeline.append(timeline, epoch, ProfileScope.DEFAULT), ProfileScope.DEFAULT)

    /**
     * Which of Discover's own logged plays the space may know about:
     * its own; for "me" also the other profiles' plays from before [epoch] (only to tell its legacy song.db rows apart).
     */
    fun visiblePlays(space: String, plays: List<OwnedPlay>, epoch: Long?): List<OwnedPlay> =
        if (!SpacePaths.isLegacy(space)) plays.filter { it.owner == space }
        else plays.filter { it.owner == space || (epoch != null && it.play.timestampMs < epoch) }

    /**
     * 13.7.0.15: identifies event rows in the main profile's legacy song.db that were actually played while another
     * profile was active BEFORE [epoch] (when song.db was still shared in 13.7.0.10–11).
     * Used ONLY when the user explicitly triggers the optional cleanup tool in Profiles settings — never auto-deleted!
     */
    fun leakedLegacyEvents(
        events: List<PlayEvent>,
        ownPlays: List<OwnedPlay>,
        timeline: List<SwitchMark>,
        epoch: Long?,
    ): List<PlayEvent> {
        if (epoch == null || events.isEmpty()) return emptyList()
        val attr = attribution(ProfileScope.DEFAULT, timeline, epoch)
        val preEpoch = events.filter { it.timestampMs < epoch }
        if (preEpoch.isEmpty()) return emptyList()
        val merged = ProfileIsolation.mergePlays(preEpoch, ownPlays.filter { it.play.timestampMs < epoch }, attr.marks, default = attr.default)
        val foreignKeys = merged.filter { it.owner != ProfileScope.DEFAULT }.map { it.play.trackId to it.play.timestampMs }.toHashSet()
        return preEpoch.filter { (it.trackId to it.timestampMs) in foreignKeys }
    }
}
