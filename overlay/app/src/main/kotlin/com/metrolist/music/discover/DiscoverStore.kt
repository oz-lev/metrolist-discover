/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.metrolist.music.discover.engine.Features
import com.metrolist.music.discover.engine.ProfileScope
import com.metrolist.music.discover.engine.Slot
import com.metrolist.music.discover.engine.SpacePaths
import com.metrolist.music.discover.space.ProfileSpace

/**
 * 13.7.0.15: physical per-profile Discover DB isolation!
 *  - For the main profile ("me"): uses legacy `databases/metrolist_discover.db` directly (zero migration risk, zero data loss).
 *  - For every other profile `<id>`: uses its OWN physical SQLite file `spaces/<id>/databases/discover_profile.db`
 *    for plays, recs, taste state, feedback, saved mixes, quick-add playlist and all profile settings!
 *    On first start in 13.7.0.15, any pre-existing rows for `<id>` in `metrolist_discover.db` are migrated once into
 *    `spaces/<id>/databases/discover_profile.db`.
 *  - Only global registry keys (`g.*`: profile list, auto-switch rules, yomi toggle, legacy timeline) live in the
 *    unwrapped registry DB (`metrolist_discover.db`).
 */
class DiscoverStore private constructor(context: Context) :
    SQLiteOpenHelper(ProfileSpace.unwrap(context.applicationContext), SpacePaths.LEGACY_DISCOVER_DB, null, 2) {

    private val spaceId: String = ProfileSpace.resolve(ProfileSpace.unwrap(context.applicationContext))
    private val isLegacySpace: Boolean = SpacePaths.isLegacy(spaceId)

    private class ProfileDbHelper(ctx: Context) :
        SQLiteOpenHelper(ctx, SpacePaths.PROFILE_DISCOVER_DB, null, 2) {
        override fun onCreate(db: SQLiteDatabase) = createSchema(db)
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = upgradeSchema(db, oldVersion)
    }

    /** Per-profile isolated DB for non-"me" spaces; null for "me" (which uses this legacy helper directly). */
    private val profileHelper: ProfileDbHelper? = if (isLegacySpace) null else ProfileDbHelper(context.applicationContext)

    private fun pRead(): SQLiteDatabase = profileHelper?.readableDatabase ?: readableDatabase
    private fun pWrite(): SQLiteDatabase = profileHelper?.writableDatabase ?: writableDatabase

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = upgradeSchema(db, oldVersion)

    fun addPlay(songId: String, ts: Long, playedMs: Long, durationMs: Long, fromDiscover: Boolean, outcome: String?, profileId: String = ProfileScope.active) {
        pWrite().insert("plays", null, ContentValues().apply {
            put("songId", songId); put("ts", ts); put("playedMs", playedMs); put("durationMs", durationMs)
            put("fromDiscover", if (fromDiscover) 1 else 0); put("outcome", outcome); put("profileId", profileId)
        })
    }

    data class StoredPlay(val songId: String, val ts: Long, val playedMs: Long, val durationMs: Long, val fromDiscover: Boolean, val outcome: String?, val profileId: String = ProfileScope.DEFAULT)

    /** Plays of one profile (for non-"me", reads strictly from its own isolated discover_profile.db). */
    fun plays(sinceTs: Long = 0L, profile: String? = ProfileScope.historyOwner()): List<StoredPlay> {
        val db = if (!isLegacySpace && (profile == null || profile == spaceId)) pRead() else readableDatabase
        val out = ArrayList<StoredPlay>()
        val sql = "SELECT songId, ts, playedMs, durationMs, fromDiscover, outcome, profileId FROM plays WHERE ts >= ?" + (if (profile != null) " AND profileId = ?" else "") + " ORDER BY ts"
        val args = if (profile != null) arrayOf(sinceTs.toString(), profile) else arrayOf(sinceTs.toString())
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += StoredPlay(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5), c.getString(6) ?: ProfileScope.DEFAULT)
        }
        return out
    }

    /** Every play visible to this space: for non-"me" ONLY its own isolated DB; for "me" legacy DB (for pre-epoch attribution). */
    fun allPlays(): List<StoredPlay> = if (isLegacySpace) plays(0L, null) else plays(0L, spaceId)

    fun deletePlaysOf(profile: String) {
        writableDatabase.delete("plays", "profileId = ?", arrayOf(profile))
        writableDatabase.delete("recs", "profileId = ?", arrayOf(profile))
        if (!isLegacySpace && profile == spaceId) {
            pWrite().delete("plays", null, null)
            pWrite().delete("recs", null, null)
        }
    }

    /** v2.1 auto-like: how many earlier listens of this song reached 85 % of its length in this profile. */
    fun fullListens(songId: String, profile: String = ProfileScope.historyOwner()): Int =
        pRead().rawQuery("SELECT COUNT(*) FROM plays WHERE songId = ? AND profileId = ? AND durationMs > 0 AND playedMs * 100 >= durationMs * 85", arrayOf(songId, profile)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    // ---------------------------------------------------------------- profile timeline (global registry)
    fun timeline(): List<com.metrolist.music.discover.engine.SwitchMark> = com.metrolist.music.discover.engine.ProfileTimeline.decode(rawGet(K_TIMELINE))
    fun markSwitch(ts: Long, profileId: String) {
        rawPut(K_TIMELINE, com.metrolist.music.discover.engine.ProfileTimeline.encode(com.metrolist.music.discover.engine.ProfileTimeline.append(timeline(), ts, profileId)))
    }
    fun reassignTimeline(fromId: String, toId: String) {
        rawPut(K_TIMELINE, com.metrolist.music.discover.engine.ProfileTimeline.encode(com.metrolist.music.discover.engine.ProfileTimeline.reassign(timeline(), fromId, toId)))
    }

    fun putRecs(recs: List<Triple<String, Slot, Features>>, ts: Long, profile: String = ProfileScope.active) {
        val db = pWrite()
        db.beginTransaction()
        try {
            for ((id, slot, f) in recs) db.insertWithOnConflict("recs", null, ContentValues().apply {
                put("profileId", profile); put("songId", id); put("ts", ts); put("slot", slot.name); put("features", f.encode())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("recs", "ts < ?", arrayOf((ts - 14L * 86_400_000L).toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** The recommendation behind this song if Discover showed it in the last [withinMs]. */
    fun rec(songId: String, nowTs: Long, withinMs: Long = 2L * 86_400_000L, profile: String = ProfileScope.active): Pair<Slot, Features>? =
        pRead().rawQuery("SELECT slot, features, ts FROM recs WHERE songId = ? AND profileId = ?", arrayOf(songId, profile)).use { c ->
            if (!c.moveToFirst() || nowTs - c.getLong(2) > withinMs) null
            else Features.decode(c.getString(1))?.let { Slot.valueOf(c.getString(0)) to it }
        }

    /** Per-profile KV: non-"me" profiles read/write ONLY their own discover_profile.db; "me" uses legacy kv table. */
    fun get(k: String): String? = if (!isLegacySpace && ProfileScope.scoped(k)) profileKvGet(k) else rawGet(ProfileScope.key(k))
    fun put(k: String, v: String?) {
        if (!isLegacySpace && ProfileScope.scoped(k)) profileKvPut(k, v) else rawPut(ProfileScope.key(k), v)
    }

    private fun profileKvGet(k: String): String? =
        pRead().rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun profileKvPut(k: String, v: String?) {
        pWrite().insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Reads all per-profile settings/learning keys of the active profile (for duplicate). */
    fun activeProfileEntries(): Map<String, String?> {
        if (isLegacySpace) return rawEntries("").filter { ProfileScope.scoped(it.key) }
        val out = HashMap<String, String?>()
        pRead().rawQuery("SELECT k, v FROM kv", null).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    /** Global registry KV (metrolist_discover.db). */
    fun rawGet(k: String): String? = readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    fun rawPut(k: String, v: String?) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** All raw keys (with values) starting with [prefix] in the registry DB. */
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

    /**
     * 13.7.0.15, once per non-"me" profile: migrates any pre-13.7.0.15 plays, recs and p.<id>.* kv rows from the shared
     * legacy metrolist_discover.db into this profile's own isolated spaces/<id>/databases/discover_profile.db.
     */
    private fun migrateToProfileDb() {
        if (isLegacySpace || profileHelper == null) return
        val flag = "g.profdb15.$spaceId"
        if (rawGet(flag) != null) return
        val dst = profileHelper.writableDatabase
        val src = readableDatabase
        dst.beginTransaction()
        try {
            src.rawQuery("SELECT songId, ts, playedMs, durationMs, fromDiscover, outcome, profileId FROM plays WHERE profileId = ?", arrayOf(spaceId)).use { c ->
                while (c.moveToNext()) {
                    dst.insert("plays", null, ContentValues().apply {
                        put("songId", c.getString(0)); put("ts", c.getLong(1)); put("playedMs", c.getLong(2))
                        put("durationMs", c.getLong(3)); put("fromDiscover", c.getInt(4)); put("outcome", c.getString(5)); put("profileId", c.getString(6))
                    })
                }
            }
            src.rawQuery("SELECT profileId, songId, ts, slot, features FROM recs WHERE profileId = ?", arrayOf(spaceId)).use { c ->
                while (c.moveToNext()) {
                    dst.insertWithOnConflict("recs", null, ContentValues().apply {
                        put("profileId", c.getString(0)); put("songId", c.getString(1)); put("ts", c.getLong(2))
                        put("slot", c.getString(3)); put("features", c.getString(4))
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            val pre = "p.$spaceId."
            src.rawQuery("SELECT k, v FROM kv WHERE substr(k, 1, ?) = ?", arrayOf(pre.length.toString(), pre)).use { c ->
                while (c.moveToNext()) {
                    val bare = c.getString(0).removePrefix(pre)
                    if (bare.isNotBlank()) dst.insertWithOnConflict("kv", null, ContentValues().apply {
                        put("k", bare); put("v", c.getString(1))
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            dst.setTransactionSuccessful()
        } finally { dst.endTransaction() }
        rawPut(flag, "1")
    }

    companion object {
        @Volatile private var instance: DiscoverStore? = null
        fun get(context: Context): DiscoverStore = instance ?: synchronized(this) {
            instance ?: DiscoverStore(context).also {
                instance = it
                runCatching { it.migrateToProfileDb() }
                runCatching { it.reconcileSpace(context) }
                runCatching { it.migrateIsolation() }
            }
        }

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, songId TEXT NOT NULL, ts INTEGER NOT NULL, playedMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, fromDiscover INTEGER NOT NULL DEFAULT 0, outcome TEXT, profileId TEXT NOT NULL DEFAULT 'me')")
            db.execSQL("CREATE INDEX plays_song ON plays(songId)")
            db.execSQL("CREATE INDEX plays_ts ON plays(ts)")
            db.execSQL("CREATE INDEX plays_profile ON plays(profileId, ts)")
            db.execSQL("CREATE TABLE recs (profileId TEXT NOT NULL DEFAULT 'me', songId TEXT NOT NULL, ts INTEGER NOT NULL, slot TEXT NOT NULL, features TEXT NOT NULL, PRIMARY KEY (profileId, songId))")
            db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
        }

        private fun upgradeSchema(db: SQLiteDatabase, oldVersion: Int) {
            if (oldVersion < 2) {
                db.beginTransaction()
                try {
                    db.execSQL("ALTER TABLE plays ADD COLUMN profileId TEXT NOT NULL DEFAULT 'me'")
                    db.execSQL("CREATE INDEX IF NOT EXISTS plays_profile ON plays(profileId, ts)")
                    db.execSQL("CREATE TABLE recs2 (profileId TEXT NOT NULL DEFAULT 'me', songId TEXT NOT NULL, ts INTEGER NOT NULL, slot TEXT NOT NULL, features TEXT NOT NULL, PRIMARY KEY (profileId, songId))")
                    db.execSQL("INSERT OR REPLACE INTO recs2 (profileId, songId, ts, slot, features) SELECT 'me', songId, ts, slot, features FROM recs")
                    db.execSQL("DROP TABLE recs")
                    db.execSQL("ALTER TABLE recs2 RENAME TO recs")
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
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
        const val K_TIMELINE = "g.timeline"
        const val K_ISO = "g.iso1310"
        const val K_SPACE_EPOCH = "g.space1312"
    }

    private fun migrateIsolation() {
        if (rawGet(K_ISO) != null) return
        val now = DiscoverHooks.nowTs()
        if (ProfileScope.active != ProfileScope.DEFAULT) markSwitch(now, ProfileScope.active)
        val ids = com.metrolist.music.discover.engine.Profiles.decode(rawGet("g.profiles")).map { it.id }.filter { it != ProfileScope.DEFAULT }
        for (id in ids) for (k in ProfileScope.MIGRATE_COPY) {
            val v = rawGet(k) ?: continue
            val pk = ProfileScope.key(k, id)
            if (rawGet(pk) == null) rawPut(pk, v)
        }
        rawPut(K_ISO, now.toString())
    }

    fun spaceEpoch(): Long? = rawGet(K_SPACE_EPOCH)?.toLongOrNull()

    private fun reconcileSpace(context: Context) {
        val space = ProfileSpace.resolve(ProfileSpace.unwrap(context))
        val list = com.metrolist.music.discover.engine.Profiles.decode(rawGet("g.profiles"))
        val p = list.firstOrNull { it.id == space } ?: com.metrolist.music.discover.engine.Profiles.PRESETS.firstOrNull { it.id == space }
        ProfileScope.borrowMain = false
        ProfileScope.active = p?.id ?: ProfileScope.DEFAULT
        ProfileScope.guest = p?.guest ?: false
        val now = DiscoverHooks.nowTs()
        if (rawGet(K_SPACE_EPOCH) == null) rawPut(K_SPACE_EPOCH, now.toString())
        val id = ProfileScope.active
        if (rawGet("g.init.$id") == null) {
            com.metrolist.music.discover.engine.Profiles.presetDefaults(id).forEach { (k, v) ->
                if (get(k) == null) put(k, v)
            }
            rawPut("g.init.$id", "1")
        }
        if (rawGet("g.activeProfile") != id) {
            rawPut("g.activeProfile", id)
            markSwitch(now, id)
        }
        com.metrolist.music.discover.v21.V21Profiles.onSpaceStart(context.applicationContext)
    }
}
