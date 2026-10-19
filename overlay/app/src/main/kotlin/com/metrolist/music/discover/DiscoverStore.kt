/**
 * Metrolist Discover (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.metrolist.music.discover.engine.Features
import com.metrolist.music.discover.engine.ProfileScope
import com.metrolist.music.discover.engine.Slot

/**
 * The engine's own small database (metrolist_discover.db), separate from Metrolist's song.db so
 * no Room migration of the upstream schema is needed and upstream updates never conflict with it.
 * plays: every playback session, including the short ones Metrolist does not keep (< 30 s) = skips.
 * recs:  what Discover showed, with the feature vector it was ranked on (for the self-tuner).
 * kv:    settings, tuner state, counters, caches.
 */
class DiscoverStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "metrolist_discover.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, songId TEXT NOT NULL, ts INTEGER NOT NULL, playedMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, fromDiscover INTEGER NOT NULL DEFAULT 0, outcome TEXT)")
        db.execSQL("CREATE INDEX plays_song ON plays(songId)")
        db.execSQL("CREATE INDEX plays_ts ON plays(ts)")
        db.execSQL("CREATE TABLE recs (songId TEXT PRIMARY KEY, ts INTEGER NOT NULL, slot TEXT NOT NULL, features TEXT NOT NULL)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addPlay(songId: String, ts: Long, playedMs: Long, durationMs: Long, fromDiscover: Boolean, outcome: String?) {
        writableDatabase.insert("plays", null, ContentValues().apply {
            put("songId", songId); put("ts", ts); put("playedMs", playedMs); put("durationMs", durationMs)
            put("fromDiscover", if (fromDiscover) 1 else 0); put("outcome", outcome)
        })
    }

    data class StoredPlay(val songId: String, val ts: Long, val playedMs: Long, val durationMs: Long, val fromDiscover: Boolean, val outcome: String?)

    fun plays(sinceTs: Long = 0L): List<StoredPlay> {
        val out = ArrayList<StoredPlay>()
        readableDatabase.rawQuery("SELECT songId, ts, playedMs, durationMs, fromDiscover, outcome FROM plays WHERE ts >= ? ORDER BY ts", arrayOf(sinceTs.toString())).use { c ->
            while (c.moveToNext()) out += StoredPlay(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5))
        }
        return out
    }

    /** v2.1 auto-like: how many earlier listens of this song reached 85 % of its length. */
    fun fullListens(songId: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM plays WHERE songId = ? AND durationMs > 0 AND playedMs * 100 >= durationMs * 85", arrayOf(songId)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun putRecs(recs: List<Triple<String, Slot, Features>>, ts: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, slot, f) in recs) db.insertWithOnConflict("recs", null, ContentValues().apply {
                put("songId", id); put("ts", ts); put("slot", slot.name); put("features", f.encode())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("recs", "ts < ?", arrayOf((ts - 14L * 86_400_000L).toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** The recommendation behind this song if Discover showed it in the last [withinMs]. */
    fun rec(songId: String, nowTs: Long, withinMs: Long = 2L * 86_400_000L): Pair<Slot, Features>? =
        readableDatabase.rawQuery("SELECT slot, features, ts FROM recs WHERE songId = ?", arrayOf(songId)).use { c ->
            if (!c.moveToFirst() || nowTs - c.getLong(2) > withinMs) null
            else Features.decode(c.getString(1))?.let { Slot.valueOf(c.getString(0)) to it }
        }

    /** Settings/learning keys go through the active profile (v2.1, see ProfileScope); the default profile keeps the original keys. */
    fun get(k: String): String? = rawGet(ProfileScope.key(k))
    fun put(k: String, v: String?) = rawPut(ProfileScope.key(k), v)

    fun rawGet(k: String): String? = readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    fun rawPut(k: String, v: String?) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    /** All raw keys (with values) starting with [prefix]. */
    fun rawEntries(prefix: String): Map<String, String?> {
        val out = HashMap<String, String?>()
        readableDatabase.rawQuery("SELECT k, v FROM kv WHERE substr(k, 1, ?) = ?", arrayOf(prefix.length.toString(), prefix)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }
    fun rawDeletePrefix(prefix: String) {
        writableDatabase.delete("kv", "substr(k, 1, ?) = ?", arrayOf(prefix.length.toString(), prefix))
    }
    fun getBool(k: String, def: Boolean) = get(k)?.toBooleanStrictOrNull() ?: def
    fun getLong(k: String, def: Long) = get(k)?.toLongOrNull() ?: def

    companion object {
        @Volatile private var instance: DiscoverStore? = null
        fun get(context: Context): DiscoverStore = instance ?: synchronized(this) {
            instance ?: DiscoverStore(context).also {
                instance = it
                // v2.1: restore the active profile before anything reads a setting
                runCatching {
                    val id = it.rawGet("g.activeProfile")
                    val list = com.metrolist.music.discover.engine.Profiles.decode(it.rawGet("g.profiles"))
                    list.firstOrNull { p -> p.id == id }?.let { p -> ProfileScope.active = p.id; ProfileScope.guest = p.guest }
                }
            }
        }

        const val K_TUNER = "tuner.state"
        const val K_THRESHOLDS = "profile.thresholds"
        const val K_BUBBLE = "bubble.enabled"
        const val K_BUBBLE_LAST = "bubble.lastType"
        const val K_BUBBLE_COUNTER = "bubble.counter"
        const val K_CACHE = "discover.cache"
        const val K_CACHE_TS = "discover.cacheTs"
        const val K_MIXES = "mixes.cache"
        const val K_MIXES_TS = "mixes.cacheTs"
        const val K_LIKE_AWARDED = "tuner.likeAwarded"
    }
}
