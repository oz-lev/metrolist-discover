/**
 * Metrolist Discover (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.discover.engine.Candidate
import com.metrolist.music.discover.engine.CandidateBuilder
import com.metrolist.music.discover.engine.CandidateSource
import com.metrolist.music.discover.engine.DiscoverConfig
import com.metrolist.music.discover.engine.DiscoverEngine
import com.metrolist.music.discover.engine.Evaluator
import com.metrolist.music.discover.engine.ExplicitSignal
import com.metrolist.music.discover.engine.History
import com.metrolist.music.discover.engine.MixBuilder
import com.metrolist.music.discover.engine.Outcome
import com.metrolist.music.discover.engine.PlayEvent
import com.metrolist.music.discover.engine.Reason
import com.metrolist.music.discover.engine.SelfTuner
import com.metrolist.music.discover.engine.SignalKind
import com.metrolist.music.discover.engine.Slot
import com.metrolist.music.discover.engine.StatsFacts
import com.metrolist.music.discover.engine.TasteProfile
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.engine.TunerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A recommendation as the UI shows it (also what is cached between app starts). */
data class DiscoverItem(val track: TrackMeta, val reason: Reason, val slot: Slot, val seed: String?)

data class DiscoverCounters(val plays: Int, val completionRate: Double, val likeRate: Double, val skipRate: Double)

/**
 * Glue between Metrolist's database / innertube calls and the pure engine.
 * Reads only stable columns of upstream tables, never writes to them.
 */
class DiscoverRepository(private val context: Context, private val database: MusicDatabase) {
    private val store get() = DiscoverStore.get(context)
    private val config = DiscoverConfig()
    private val tuner = SelfTuner(config.weights, config.exploreRatio)

    fun tunerState(): TunerState = TunerState.decode(store.get(DiscoverStore.K_TUNER)) ?: tuner.reset()
    fun resetLearning() { store.put(DiscoverStore.K_TUNER, tuner.reset().encode()); store.put(DiscoverStore.K_LIKE_AWARDED, "") }
    fun exploreRatio(): Double = tuner.exploreRatio(tunerState())

    // ---------------------------------------------------------------- history
    fun loadHistory(): History {
        val db = database.openHelper.readableDatabase
        val artists = HashMap<String, MutableList<Pair<String, String>>>()
        db.query("SELECT m.songId, m.artistId, a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId ORDER BY m.songId, m.position").use { c ->
            while (c.moveToNext()) artists.getOrPut(c.getString(0)) { ArrayList() } += c.getString(1) to (c.getString(2) ?: "")
        }
        val tracks = HashMap<String, TrackMeta>()
        db.query("SELECT id, title, duration, albumId, thumbnailUrl FROM song").use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val a = artists[id].orEmpty()
                tracks[id] = TrackMeta(id, c.getString(1) ?: "", a.map { it.first }, a.map { it.second },
                    if (c.isNull(3)) null else c.getString(3), if (c.isNull(2)) -1 else c.getInt(2), if (c.isNull(4)) null else c.getString(4))
            }
        }
        val plays = ArrayList<PlayEvent>()
        val eventKeys = HashSet<String>()
        db.query("SELECT songId, timestamp, playTime FROM event").use { c ->
            while (c.moveToNext()) {
                val e = PlayEvent(c.getString(0), c.getLong(1), c.getLong(2))
                plays += e; eventKeys += e.trackId + "@" + e.timestampMs / 900_000L
            }
        }
        // our own log adds what Metrolist does not keep: the short (skipped) sessions
        for (p in store.plays()) {
            val k = p.ts / 900_000L
            val dup = eventKeys.contains(p.songId + "@" + k) || eventKeys.contains(p.songId + "@" + (k - 1)) || eventKeys.contains(p.songId + "@" + (k + 1))
            if (!dup) plays += PlayEvent(p.songId, p.ts, p.playedMs, p.durationMs, p.fromDiscover)
        }
        val signals = ArrayList<ExplicitSignal>()
        fun ts(c: android.database.Cursor, i: Int): Long? = if (c.isNull(i)) null else c.getLong(i)
        db.query("SELECT id, likedDate FROM song WHERE liked = 1").use { c -> while (c.moveToNext()) signals += ExplicitSignal(c.getString(0), SignalKind.LIKE, ts(c, 1)) }
        db.query("SELECT id, dateDownload FROM song WHERE dateDownload IS NOT NULL").use { c -> while (c.moveToNext()) signals += ExplicitSignal(c.getString(0), SignalKind.DOWNLOAD, ts(c, 1)) }
        db.query("SELECT id, inLibrary FROM song WHERE inLibrary IS NOT NULL").use { c -> while (c.moveToNext()) signals += ExplicitSignal(c.getString(0), SignalKind.LIBRARY_ADD, ts(c, 1)) }
        db.query("SELECT DISTINCT songId FROM playlist_song_map").use { c -> while (c.moveToNext()) signals += ExplicitSignal(c.getString(0), SignalKind.PLAYLIST_ADD, null) }
        val related = HashMap<String, MutableList<String>>()
        db.query("SELECT songId, relatedSongId FROM related_song_map ORDER BY id").use { c ->
            while (c.moveToNext()) related.getOrPut(c.getString(0)) { ArrayList() } += c.getString(1)
        }
        return History(tracks, plays, signals, related)
    }

    // ---------------------------------------------------------------- recommendations
    data class Result(val items: List<DiscoverItem>, val coldStart: Boolean)

    suspend fun refresh(online: Boolean = true): Result = withContext(Dispatchers.IO) {
        val history = loadHistory()
        val now = DiscoverHooks.nowTs()
        val state = tunerState()
        val engine = DiscoverEngine(config.copy(weights = state.weights))
        val profile = engine.profile(history, now)
        val th = profile.thresholds
        store.put(DiscoverStore.K_THRESHOLDS, "${th.skipFraction},${th.skipSeconds},${th.fullFraction}")
        awardLikes(history)
        if (profile.isColdStart) return@withContext Result(emptyList(), true)
        val candidates = CandidateBuilder.offline(history, profile) + (if (online) onlineCandidates(history, profile) else emptyList())
        val recs = engine.recommend(history, candidates, now, n = config.listSize, weights = state.weights,
            exploreRatio = tuner.exploreRatio(state), profile = profile)
        store.putRecs(recs.map { Triple(it.track.id, it.slot, it.features) }, now)
        val items = recs.map { DiscoverItem(it.track, it.reason, it.slot, it.seedLabel) }
        store.put(DiscoverStore.K_CACHE, encode(items)); store.put(DiscoverStore.K_CACHE_TS, now.toString())
        Result(items, false)
    }

    fun cached(): List<DiscoverItem> = runCatching { decode(store.get(DiscoverStore.K_CACHE)) }.getOrDefault(emptyList())
    fun cacheAgeMs(): Long = DiscoverHooks.nowTs() - store.getLong(DiscoverStore.K_CACHE_TS, 0L)

    /** YouTube Music radio of the strongest seeds + songs of related artists (Metrolist's existing innertube calls). */
    private suspend fun onlineCandidates(h: History, p: TasteProfile): List<Candidate> = coroutineScope {
        val seeds = p.topTracks(40).sortedByDescending { (id, a) -> a + (p.artistShort[h.tracks[id]?.mainArtist] ?: 0.0).coerceAtMost(1.0) }.take(4)
        val radio = seeds.map { (seed, aff) ->
            async {
                withTimeoutOrNull(15_000) {
                    YouTube.next(WatchEndpoint(videoId = seed, playlistId = "RDAMVM$seed")).getOrNull()?.items.orEmpty()
                        .filter { it.id != seed }.take(25)
                        .map { Candidate(it.toMeta(), setOf(CandidateSource.RADIO), h.tracks[seed]?.mainArtist, seed, aff) }
                }.orEmpty()
            }
        }
        val topArtists = p.topArtists(12).filter { it.first.startsWith("UC") }
        val artistJobs = topArtists.take(3).map { (artistId, aff) ->
            async {
                val page = withTimeoutOrNull(15_000) { YouTube.artist(artistId).getOrNull() } ?: return@async emptyList<Candidate>()
                val out = ArrayList<Candidate>()
                page.sections.flatMap { it.items }.filterIsInstance<SongItem>().take(10)
                    .forEach { out += Candidate(it.toMeta(), setOf(CandidateSource.ARTIST_TOP), artistId, null, aff) }
                val related = page.sections.flatMap { it.items }.filterIsInstance<ArtistItem>()
                    .filter { it.id.startsWith("UC") && p.longFamiliarity(it.id) < 0.5 }.take(3)
                for (r in related) {
                    val rp = withTimeoutOrNull(15_000) { YouTube.artist(r.id).getOrNull() } ?: continue
                    rp.sections.flatMap { it.items }.filterIsInstance<SongItem>().take(6)
                        .forEach { out += Candidate(it.toMeta(), setOf(CandidateSource.RELATED_ARTIST), artistId, null, aff) }
                }
                out
            }
        }
        // exploration: a related artist of a mid-ranked artist (not a top one)
        val exploreJob = async {
            val mid = topArtists.drop(5).shuffled().firstOrNull() ?: return@async emptyList<Candidate>()
            val page = withTimeoutOrNull(15_000) { YouTube.artist(mid.first).getOrNull() } ?: return@async emptyList<Candidate>()
            val rel = page.sections.flatMap { it.items }.filterIsInstance<ArtistItem>().filter { it.id.startsWith("UC") && !p.artistPlays.containsKey(it.id) }.shuffled().firstOrNull()
                ?: return@async emptyList<Candidate>()
            val rp = withTimeoutOrNull(15_000) { YouTube.artist(rel.id).getOrNull() } ?: return@async emptyList<Candidate>()
            rp.sections.flatMap { it.items }.filterIsInstance<SongItem>().take(5).map { Candidate(it.toMeta(), setOf(CandidateSource.EXPLORE), mid.first, null, 0.1) }
        }
        (radio + artistJobs + exploreJob).awaitAll().flatten()
    }

    private fun SongItem.toMeta() = TrackMeta(
        id, title, artists.map { it.id ?: ("name:" + it.name) }, artists.map { it.name }, album?.id, duration ?: -1, thumbnail,
    )

    // ---------------------------------------------------------------- feedback
    /** Likes that arrived after a Discover pick was played give the tuner an extra positive step. */
    private fun awardLikes(h: History) {
        val awarded = store.get(DiscoverStore.K_LIKE_AWARDED).orEmpty().split(",").filter { it.isNotBlank() }.toMutableSet()
        val liked = h.signals.filter { it.kind == SignalKind.LIKE }.map { it.trackId }.toSet()
        var state = tunerState(); var changed = false
        val now = DiscoverHooks.nowTs()
        for (p in store.plays(now - 30L * 86_400_000L)) {
            if (!p.fromDiscover || p.songId !in liked || p.songId in awarded) continue
            val rec = store.rec(p.songId, now, 30L * 86_400_000L) ?: continue
            state = tuner.update(state, rec.second, rec.first, 1.5); awarded += p.songId; changed = true
        }
        if (changed) { store.put(DiscoverStore.K_TUNER, state.encode()); store.put(DiscoverStore.K_LIKE_AWARDED, awarded.toList().takeLast(500).joinToString(",")) }
    }

    fun counters(): DiscoverCounters {
        val plays = store.plays(DiscoverHooks.nowTs() - 30L * 86_400_000L).filter { it.fromDiscover }
        if (plays.isEmpty()) return DiscoverCounters(0, 0.0, 0.0, 0.0)
        val liked = HashSet<String>()
        runCatching {
            database.openHelper.readableDatabase.query("SELECT id FROM song WHERE liked = 1").use { c -> while (c.moveToNext()) liked += c.getString(0) }
        }
        val n = plays.size.toDouble()
        val distinct = plays.map { it.songId }.toSet()
        return DiscoverCounters(
            plays.size,
            plays.count { it.outcome == Outcome.FULL.name } / n,
            distinct.count { it in liked } / distinct.size.toDouble(),
            plays.count { it.outcome == Outcome.SKIP.name } / n,
        )
    }

    // ---------------------------------------------------------------- mixes, facts, evaluation
    suspend fun mixes(force: Boolean = false): List<MixBuilder.Mix> = withContext(Dispatchers.IO) {
        val age = DiscoverHooks.nowTs() - store.getLong(DiscoverStore.K_MIXES_TS, 0L)
        if (!force && age < 6L * 3_600_000L) runCatching { decodeMixes(store.get(DiscoverStore.K_MIXES)) }.getOrNull()?.let { return@withContext it }
        val mixes = MixBuilder.build(loadHistory(), DiscoverHooks.nowTs(), DiscoverEngine(config.copy(weights = tunerState().weights)))
        store.put(DiscoverStore.K_MIXES, encodeMixes(mixes)); store.put(DiscoverStore.K_MIXES_TS, DiscoverHooks.nowTs().toString())
        mixes
    }

    suspend fun nextFact(): StatsFacts.Fact? = withContext(Dispatchers.IO) {
        if (!store.getBool(DiscoverStore.K_BUBBLE, true)) return@withContext null
        val facts = StatsFacts.compute(loadHistory(), DiscoverHooks.nowTs())
        val last = store.get(DiscoverStore.K_BUBBLE_LAST)?.let { n -> StatsFacts.Type.values().firstOrNull { it.name == n } }
        val counter = store.getLong(DiscoverStore.K_BUBBLE_COUNTER, 0L).toInt()
        val f = StatsFacts.pick(facts, last, counter) ?: return@withContext null
        store.put(DiscoverStore.K_BUBBLE_LAST, f.type.name); store.put(DiscoverStore.K_BUBBLE_COUNTER, (counter + 1).toString())
        f
    }

    suspend fun allFacts(): List<StatsFacts.Fact> = withContext(Dispatchers.IO) { StatsFacts.compute(loadHistory(), DiscoverHooks.nowTs()) }

    fun bubbleEnabled() = store.getBool(DiscoverStore.K_BUBBLE, true)
    fun setBubbleEnabled(on: Boolean) = store.put(DiscoverStore.K_BUBBLE, on.toString())

    /** The offline holdout evaluator on the user's real history, on the phone. */
    suspend fun evaluate(): List<Evaluator.Metric> = withContext(Dispatchers.Default) {
        val h = loadHistory()
        if (h.plays.size < 200) return@withContext emptyList()
        Evaluator.holdout(h, 14.0, listOf(50, 200), Evaluator.Mode.NEW_ONLY) + Evaluator.holdout(h, 14.0, listOf(50, 200), Evaluator.Mode.ALL)
    }

    // ---------------------------------------------------------------- (de)serialisation
    private fun TrackMeta.json() = JSONObject().put("id", id).put("t", title).put("a", JSONArray(artistIds)).put("n", JSONArray(artistNames))
        .put("al", albumId ?: "").put("d", durationSec).put("th", thumbnailUrl ?: "")
    private fun JSONObject.meta(): TrackMeta {
        val a = getJSONArray("a"); val n = getJSONArray("n")
        return TrackMeta(getString("id"), getString("t"), (0 until a.length()).map { a.getString(it) }, (0 until n.length()).map { n.getString(it) },
            optString("al").ifBlank { null }, optInt("d", -1), optString("th").ifBlank { null })
    }
    private fun encode(items: List<DiscoverItem>) = JSONArray(items.map { JSONObject().put("m", it.track.json()).put("r", it.reason.name).put("s", it.slot.name).put("seed", it.seed ?: "") }).toString()
    private fun decode(s: String?): List<DiscoverItem> {
        if (s.isNullOrBlank()) return emptyList()
        val a = JSONArray(s)
        return (0 until a.length()).map { val o = a.getJSONObject(it); DiscoverItem(o.getJSONObject("m").meta(), Reason.valueOf(o.getString("r")), Slot.valueOf(o.getString("s")), o.optString("seed").ifBlank { null }) }
    }
    private fun encodeMixes(m: List<MixBuilder.Mix>) = JSONArray(m.map { mx ->
        JSONObject().put("k", mx.kind.name).put("he", mx.titleHe).put("en", mx.titleEn).put("tr", JSONArray(mx.tracks.map { it.json() }))
    }).toString()
    private fun decodeMixes(s: String?): List<MixBuilder.Mix>? {
        if (s.isNullOrBlank()) return null
        val a = JSONArray(s)
        return (0 until a.length()).map { val o = a.getJSONObject(it); val tr = o.getJSONArray("tr")
            MixBuilder.Mix(MixBuilder.Kind.valueOf(o.getString("k")), o.getString("he"), o.getString("en"), (0 until tr.length()).map { i -> tr.getJSONObject(i).meta() }) }
    }

    companion object {
        /** (skipFraction, skipSeconds, fullFraction) as last computed from the user's data, or the defaults. */
        fun thresholds(store: DiscoverStore): Triple<Double, Double, Double> =
            store.get(DiscoverStore.K_THRESHOLDS)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }
                ?.let { Triple(it[0], it[1], it[2]) } ?: Triple(0.25, 30.0, 0.8)
    }
}
