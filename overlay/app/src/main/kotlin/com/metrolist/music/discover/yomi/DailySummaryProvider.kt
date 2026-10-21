/**
 * Metrolist Discover (fork addition) - GPL-3.0
 *
 * Local, read-only "daily listening summary" API for Oz's journal app יומי (Yomi). Nothing leaves the phone.
 *
 *   content://com.metrolist.music.oz.daily/day/{yyyy-MM-dd}
 *   content://com.metrolist.music.oz.daily/range/{from}/{to}      (inclusive, at most 366 days)
 *
 * One row per day (days without plays included, with zeros/nulls). Columns: see [COLUMNS] and README-OZ.md.
 * Computed from Metrolist's own play history (the Room `event` table), in the phone's local time.
 *
 * Access control WITHOUT any manifest permission: query() checks the calling uid/package and only answers
 * packages named com.oz.yomi* and this app itself (SecurityException otherwise). The Discover setting
 * "שיתוף סיכום יומי ליומי" (default ON) turns the answers into empty cursors when off.
 */
package com.metrolist.music.discover.yomi

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.metrolist.music.discover.engine.DailyPlay
import com.metrolist.music.discover.engine.DailySummaries
import com.metrolist.music.discover.engine.DailySummary
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.engine.VibeCorrections
import com.metrolist.music.discover.engine.VibeEstimator
import com.metrolist.music.discover.v2.V2EntryPoint
import com.metrolist.music.discover.v2.V2Settings
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate

class DailySummaryProvider : ContentProvider() {

    /** Built from the authority the manifest actually declares (${applicationId}.daily = com.metrolist.music.oz.daily in releases). */
    private var matcher = buildMatcher(AUTHORITY)

    override fun attachInfo(context: android.content.Context?, info: android.content.pm.ProviderInfo?) {
        super.attachInfo(context, info)
        info?.authority?.split(";")?.firstOrNull()?.let { matcher = buildMatcher(it) }
    }

    override fun onCreate(): Boolean = true // nothing heavy here: Application.onCreate (Hilt) runs after providers are created

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        enforceCaller()
        val ctx = context ?: return null
        val (from, to) = parse(uri) ?: throw IllegalArgumentException("Unknown URI $uri (use /day/yyyy-MM-dd or /range/from/to)")
        require(!to.isBefore(from)) { "to is before from" }
        require(java.time.temporal.ChronoUnit.DAYS.between(from, to) < DailySummaries.MAX_RANGE_DAYS) { "range longer than ${DailySummaries.MAX_RANGE_DAYS} days" }
        val cols: Array<String> = projection?.map { it }?.toTypedArray() ?: COLUMNS
        cols.forEach { require(it in COLUMNS) { "Unknown column $it" } }
        val enabled = isSharingEnabled(ctx)
        val out = MatrixCursor(cols)
        out.extras = Bundle().apply { putBoolean(EXTRA_ENABLED, enabled); putInt(EXTRA_API_VERSION, API_VERSION) }
        if (!enabled) return out

        val token = Binder.clearCallingIdentity() // read our own database as ourselves
        try {
            val rows = compute(ctx, from, to)
            for (s in rows) out.addRow(cols.map { value(s, it) })
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return out
    }

    private fun compute(ctx: android.content.Context, from: LocalDate, to: LocalDate): List<DailySummary> {
        val db = EntryPointAccessors.fromApplication(ctx.applicationContext, V2EntryPoint::class.java).database().openHelper.readableDatabase
        val (startMs, endMs) = DailySummaries.windowMs(from, to)
        val plays = ArrayList<DailyPlay>()
        db.query("SELECT songId, timestamp, playTime FROM event WHERE timestamp >= ? AND timestamp < ?", arrayOf<Any?>(startMs, endMs)).use { c ->
            while (c.moveToNext()) if (!c.isNull(0) && !c.isNull(1)) plays += DailyPlay(c.getString(0), c.getLong(1), if (c.isNull(2)) 0L else c.getLong(2))
        }
        val ids = plays.map { it.songId }.distinct()
        val tracks = HashMap<String, TrackMeta>()
        for (chunk in ids.chunked(400)) {
            val qs = chunk.joinToString(",") { "?" }
            val artists = HashMap<String, MutableList<Pair<String, String>>>()
            db.query("SELECT m.songId, m.artistId, a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId IN ($qs) ORDER BY m.songId, m.position", chunk.toTypedArray<Any?>()).use { c ->
                while (c.moveToNext()) artists.getOrPut(c.getString(0)) { ArrayList() } += c.getString(1) to (c.getString(2) ?: "")
            }
            db.query("SELECT id, title, duration, albumId FROM song WHERE id IN ($qs)", chunk.toTypedArray<Any?>()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val a = artists[id].orEmpty()
                    tracks[id] = TrackMeta(id, c.getString(1) ?: "", a.map { it.first }, a.map { it.second },
                        if (c.isNull(3)) null else c.getString(3), if (c.isNull(2)) -1 else c.getInt(2))
                }
            }
        }
        val estimator = runCatching { VibeEstimator(corrections = VibeCorrections.decode(V2Settings.get(ctx).corrections)) }.getOrNull()
        val mood: ((TrackMeta) -> com.metrolist.music.discover.engine.Vibe)? = estimator?.let { e -> { t -> e.own(t) } }
        return DailySummaries.range(plays, tracks, from, to, mood)
    }

    /** Throws SecurityException unless the caller is this app or a com.oz.yomi* package. */
    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val ctx = context ?: throw SecurityException("no context")
        val self = ctx.packageName
        val uidPackages = ctx.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        // getCallingPackage() is the package the system verified for this call (it must belong to the calling uid)
        val claimed = runCatching { callingPackage }.getOrNull()
        val ok = if (claimed != null) claimed in uidPackages && DailySummaries.callerAllowed(claimed, self)
        else uidPackages.isNotEmpty() && uidPackages.all { DailySummaries.callerAllowed(it, self) }
        if (!ok) throw SecurityException("Metrolist daily summary: package ${claimed ?: uidPackages} is not allowed (only com.oz.yomi*)")
    }

    private fun parse(uri: Uri): Pair<LocalDate, LocalDate>? = when (matcher.match(uri)) {
        DAY -> DailySummaries.parseDate(uri.pathSegments.getOrNull(1))?.let { it to it }
        RANGE -> {
            val f = DailySummaries.parseDate(uri.pathSegments.getOrNull(1))
            val t = DailySummaries.parseDate(uri.pathSegments.getOrNull(2))
            if (f != null && t != null) f to t else null
        }
        else -> null
    }

    private fun value(s: DailySummary, col: String): Any? = when (col) {
        C_DATE -> s.date.toString()
        C_MINUTES -> s.minutesListened
        C_PLAYS -> s.plays
        C_UNIQUE -> s.uniqueTracks
        C_TOP_TRACK -> s.topTrack
        C_TOP_ARTIST -> s.topArtist
        C_TOP5 -> s.top5Json()
        C_FIRST -> s.firstPlayTime
        C_LAST -> s.lastPlayTime
        C_MOOD -> s.moodHint
        C_TOP_TRACK_ID -> s.topTrackId
        else -> null
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        DAY -> "vnd.android.cursor.item/vnd.$AUTHORITY_SUFFIX.day"
        RANGE -> "vnd.android.cursor.dir/vnd.$AUTHORITY_SUFFIX.day"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    companion object {
        const val AUTHORITY = "com.metrolist.music.oz.daily"
        private const val AUTHORITY_SUFFIX = "com.metrolist.music.oz.daily"
        const val API_VERSION = 1
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_API_VERSION = "api_version"

        const val C_DATE = "date"
        const val C_MINUTES = "minutes_listened"
        const val C_PLAYS = "plays"
        const val C_UNIQUE = "unique_tracks"
        const val C_TOP_TRACK = "top_track"
        const val C_TOP_ARTIST = "top_artist"
        const val C_TOP5 = "top5_json"
        const val C_FIRST = "first_play_time"
        const val C_LAST = "last_play_time"
        const val C_MOOD = "mood_hint"
        const val C_TOP_TRACK_ID = "top_track_id"
        val COLUMNS = arrayOf(C_DATE, C_MINUTES, C_PLAYS, C_UNIQUE, C_TOP_TRACK, C_TOP_ARTIST, C_TOP5, C_FIRST, C_LAST, C_MOOD, C_TOP_TRACK_ID)

        private const val DAY = 1
        private const val RANGE = 2
        private fun buildMatcher(authority: String) = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "day/*", DAY)
            addURI(authority, "range/*/*", RANGE)
        }

        /** The Discover setting "שיתוף סיכום יומי ליומי" (global, all profiles), default ON. */
        fun isSharingEnabled(ctx: android.content.Context): Boolean = runCatching { V2Settings.get(ctx).yomiShare }.getOrDefault(true)
    }
}
