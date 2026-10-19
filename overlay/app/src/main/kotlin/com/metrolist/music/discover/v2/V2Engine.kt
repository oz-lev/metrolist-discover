/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2

import android.content.Context
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.discover.DiscoverHooks
import com.metrolist.music.discover.DiscoverRepository
import com.metrolist.music.discover.DiscoverStore
import com.metrolist.music.discover.engine.Achievements
import com.metrolist.music.discover.engine.AskMusic
import com.metrolist.music.discover.engine.Blend
import com.metrolist.music.discover.engine.DailyPlan
import com.metrolist.music.discover.engine.MixShaping
import com.metrolist.music.discover.engine.SeedLock
import com.metrolist.music.discover.engine.ShapeOptions
import com.metrolist.music.discover.engine.TasteFile
import com.metrolist.music.discover.engine.VibeTag
import com.metrolist.music.discover.engine.ColorBuckets
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.engine.DayLock
import com.metrolist.music.discover.engine.History
import com.metrolist.music.discover.engine.MoodPreset
import com.metrolist.music.discover.engine.PoolTrack
import com.metrolist.music.discover.engine.ProfileBuilder
import com.metrolist.music.discover.engine.QueryRewriter
import com.metrolist.music.discover.engine.Roulette
import com.metrolist.music.discover.engine.RuleEngine
import com.metrolist.music.discover.engine.Sliders
import com.metrolist.music.discover.engine.TastePreset
import com.metrolist.music.discover.engine.TasteProfile
import com.metrolist.music.discover.engine.TimeMachine
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.engine.Vibe
import com.metrolist.music.discover.engine.VibeAction
import com.metrolist.music.discover.engine.VibeCorrections
import com.metrolist.music.discover.engine.VibeEstimator
import com.metrolist.music.discover.engine.VibeMixer
import com.metrolist.music.discover.engine.VibeTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/** A ready-to-play mix. */
data class BuiltMix(val title: String, val tracks: List<TrackMeta>, val target: VibeTarget? = null, val note: String? = null)

/**
 * Glue between Metrolist (database, innertube search) and the pure v2 engine (VibeEstimator, VibeMixer, RuleEngine).
 * Everything heavy runs on Dispatchers.IO; nothing here writes to Metrolist's own tables.
 */
object V2Engine {
    private val random = Random(System.nanoTime())
    private val defaultMixer = VibeMixer(random)
    /** v2.1 "random seed lock": a seeded mixer gives the same mix all day for the same seed. */
    private fun mixer(ctx: Context): VibeMixer {
        val s = V2Settings.get(ctx)
        return if (s.seedLock) VibeMixer(SeedLock.random(true, s.seed, DayClock.dayKey(now()))) else defaultMixer
    }
    @Volatile private var est: VibeEstimator? = null
    @Volatile private var snap: Snapshot? = null
    private val onlineCache = HashMap<String, Pair<Long, List<TrackMeta>>>()
    @Volatile private var activeMix: Pair<VibeTarget, Set<String>>? = null
    private var feedbackCount = 0

    data class Snapshot(val history: History, val profile: TasteProfile, val pool: List<PoolTrack>, val byId: Map<String, PoolTrack>, val builtAt: Long)

    fun estimator(ctx: Context): VibeEstimator = est ?: synchronized(this) {
        est ?: VibeEstimator(corrections = VibeCorrections.decode(V2Settings.get(ctx).corrections)).also { est = it }
    }

    fun now(): Long = DiscoverHooks.nowTs()

    // ------------------------------------------------------------------ pool
    suspend fun snapshot(ctx: Context, db: MusicDatabase, force: Boolean = false): Snapshot = withContext(Dispatchers.IO) {
        snap?.let { if (!force && System.currentTimeMillis() - it.builtAt < 10 * 60_000L) return@withContext it }
        val app = ctx.applicationContext
        val repo = DiscoverRepository(app, db)
        val history = repo.loadHistory()
        val nowTs = now()
        val profile = ProfileBuilder.build(history, nowTs)
        val e = estimator(app)
        val s = V2Settings.get(app)
        val counts = HashMap<String, Int>()
        for (p in history.plays) if (p.playedMs >= 30_000L) counts[p.trackId] = (counts[p.trackId] ?: 0) + 1
        val pool = ArrayList<PoolTrack>()
        fun add(t: TrackMeta, fam: Double?) {
            if (t.title.isBlank()) return
            val c = counts[t.id] ?: 0
            val familiarity = fam ?: min(1.0, ln(1.0 + c) / ln(31.0))
            val art = profile.artistAffinity(t.mainArtist)
            var aff = (0.55 * profile.trackAffinity(t.id).coerceIn(0.0, 1.0) + 0.45 * ((art + 1) / 2)).coerceIn(0.0, 1.0)
            aff = (aff - 0.5 * s.skipPenalty * profile.trackSkipExcess(t.id)).coerceIn(0.0, 1.0)
            pool += PoolTrack(t, e.estimate(t, history.tracks, history.related), familiarity, aff)
        }
        for (t in history.tracks.values) add(t, null)
        val known = history.tracks.keys
        for (item in repo.cached()) if (item.track.id !in known) add(item.track, 0.0)
        val byId = pool.associateBy { it.track.id }
        Snapshot(history, profile, pool, byId, System.currentTimeMillis()).also { snap = it }
    }

    fun invalidate() { snap = null }

    private fun poolTrack(ctx: Context, t: TrackMeta, s: Snapshot?): PoolTrack {
        s?.byId?.get(t.id)?.let { return it }
        val e = estimator(ctx)
        val aff = s?.profile?.artistAffinity(t.mainArtist)?.let { (it + 1) / 2 } ?: 0.4
        return PoolTrack(t, e.own(t), 0.0, aff.coerceIn(0.0, 1.0))
    }

    fun vibeOf(ctx: Context, t: TrackMeta): Vibe = snap?.byId?.get(t.id)?.vibe ?: estimator(ctx).own(t)

    // ------------------------------------------------------------------ online (innertube only)
    fun SongItem.meta(): TrackMeta = TrackMeta(
        id = id, title = title,
        artistIds = artists.map { a -> a.id ?: ("name:" + a.name.lowercase()) },
        artistNames = artists.map { it.name },
        albumId = album?.id, durationSec = duration ?: -1, thumbnailUrl = thumbnail,
    )

    suspend fun search(query: String, limit: Int = 20): List<TrackMeta> = withContext(Dispatchers.IO) {
        synchronized(onlineCache) { onlineCache[query]?.let { if (System.currentTimeMillis() - it.first < 6 * 3_600_000L) return@withContext it.second } }
        val r = withTimeoutOrNull(12_000) { YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull() }
        val list = r?.items.orEmpty().filterIsInstance<SongItem>().map { it.meta() }.take(limit)
        if (list.isNotEmpty()) synchronized(onlineCache) { onlineCache[query] = System.currentTimeMillis() to list }
        list
    }

    suspend fun searchMany(queries: List<String>, limit: Int = 20): List<TrackMeta> = coroutineScope {
        queries.map { q -> async { runCatching { search(q, limit) }.getOrDefault(emptyList()) } }.awaitAll().flatten().distinctBy { it.id }
    }

    suspend fun related(songId: String): List<TrackMeta> = withContext(Dispatchers.IO) {
        val r = withTimeoutOrNull(12_000) { YouTube.next(WatchEndpoint(videoId = songId, playlistId = "RDAMVM$songId")).getOrNull() }
        r?.items.orEmpty().map { it.meta() }.filter { it.id != songId }
    }

    private val presetSeeds: Map<MoodPreset, List<String>> = mapOf(
        MoodPreset.WAKE_UP to listOf("שירים שמחים לבוקר", "upbeat morning songs"),
        MoodPreset.DRIVE to listOf("שירים לנסיעה", "driving songs"),
        MoodPreset.GYM to listOf("gym phonk", "workout edm"),
        MoodPreset.NIGHT to listOf("שירי לילה רגועים", "late night chill"),
        MoodPreset.MIZRAHI to listOf("מזרחית חדש", "להיטים מזרחית"),
        MoodPreset.HEAVY_PHONK to listOf("phonk", "brazilian phonk"),
        MoodPreset.SAD to listOf("שירים עצובים", "sad songs"),
        MoodPreset.PARTY to listOf("מסיבה מזרחית", "party hits"),
        MoodPreset.CHILL to listOf("שירים רגועים", "chill vibes"),
        MoodPreset.HYPE to listOf("festival edm hype", "hype songs"),
        MoodPreset.DRAMA to listOf("epic cinematic soundtrack", "movie soundtrack epic"),
    )

    private suspend fun withOnline(ctx: Context, s: Snapshot, preset: MoodPreset?, familiarNew: Double): List<PoolTrack> {
        if (preset == null || familiarNew > 0.8) return s.pool
        val extra = runCatching { searchMany(presetSeeds[preset].orEmpty()) }.getOrDefault(emptyList())
        return s.pool + extra.filter { it.id !in s.byId }.map { poolTrack(ctx, it, s) }
    }

    // ------------------------------------------------------------------ rules
    fun ruleEngine(ctx: Context, s: Snapshot?): RuleEngine {
        val set = V2Settings.get(ctx)
        val lock = s?.let { DayLock.from(it.history.plays, now()) }
        return RuleEngine(set.rules(), lock)
    }

    private fun finish(ctx: Context, s: Snapshot, list: List<PoolTrack>, title: String, target: VibeTarget?, note: String? = null, keepOrder: Boolean = false): BuiltMix {
        val set = V2Settings.get(ctx)
        var out = ruleEngine(ctx, s).filter(list, now())
        if (set.surpriseEvery5 && out.size >= 5) {
            val ids = out.map { it.track.id }.toHashSet()
            val wild = s.pool.filter { it.familiarity < 0.15 && it.track.id !in ids }.shuffled(random)
            val m = out.toMutableList()
            var w = 0
            var i = 4
            while (i < m.size && w < wild.size) { m.add(i, wild[w++]); i += 5 }
            out = ruleEngine(ctx, s).filter(m, now())
        }
        out = shape(ctx, s, out, keepOrder)
        val tracks = out.map { it.track }
        if (target != null) activeMix = target to tracks.map { it.id }.toSet()
        return BuiltMix(title, tracks, target, note)
    }

    // ------------------------------------------------------------------ v2.1 shaping (language, length, never-heard, favourite artist, artist gap, morning ramp)
    fun heardIds(s: Snapshot): Set<String> = s.history.plays.asSequence().filter { it.playedMs >= 30_000L }.map { it.trackId }.toHashSet()

    fun favoriteArtists(s: Snapshot, n: Int = 5): Set<String> {
        val per = HashMap<String, Int>()
        for (p in s.history.plays) if (p.playedMs >= 30_000L) s.history.tracks[p.trackId]?.let { per[it.mainArtist] = (per[it.mainArtist] ?: 0) + 1 }
        return per.entries.sortedByDescending { it.value }.take(n).map { it.key }.toSet()
    }

    fun shape(ctx: Context, s: Snapshot, list: List<PoolTrack>, keepOrder: Boolean = false): List<PoolTrack> {
        val set = V2Settings.get(ctx)
        val o = set.shape().let { if (keepOrder) it.copy(artistGap = 0, morningRamp = false, favoriteEvery = 0) else it }
        if (o == ShapeOptions()) return list
        val fav = if (o.favoriteEvery > 0) favoriteArtists(s) else emptySet()
        val favTracks = if (fav.isEmpty()) emptyList() else s.pool.filter { it.track.mainArtist in fav }.sortedByDescending { it.affinity }.take(60).shuffled(random)
        val heard = if (o.neverHeard) heardIds(s) else emptySet()
        return MixShaping.apply(list, o, heard, favTracks, fav, DayClock.dateTime(now()).hour)
    }

    fun resetLearning(ctx: Context) { est = null; snap = null; activeMix = null }

    // ------------------------------------------------------------------ mixes
    suspend fun mood(ctx: Context, db: MusicDatabase, preset: MoodPreset?, sliders: Sliders = Sliders(), size: Int = 30): BuiltMix = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val pool = withOnline(ctx, s, preset, sliders.familiarNew)
        val t = mixer(ctx).target(preset, sliders)
        val title = preset?.let { "${it.emoji} ${it.he}" } ?: "🎛️ המיקס שלך"
        finish(ctx, s, mixer(ctx).build(pool, t, size + 10, sliders.familiarNew), title, t)
    }

    suspend fun moodFlip(ctx: Context, db: MusicDatabase): BuiltMix = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val pool = withOnline(ctx, s, MoodPreset.SAD, 0.5) + withOnline(ctx, s, MoodPreset.PARTY, 0.5).filter { it.familiarity == 0.0 }
        val mix = mixer(ctx).moodFlip(pool.distinctBy { it.track.id }, 22)
        // keep the flip order: rules may drop songs but never reorder
        finish(ctx, s, mix, "🔄 רגש להיפך", null, "מתחיל בעצוב ונגמר במסיבה", keepOrder = true)
    }

    suspend fun trip(ctx: Context, db: MusicDatabase, minutes: Int): BuiltMix = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val pool = withOnline(ctx, s, MoodPreset.DRIVE, 0.6)
        val rules = ruleEngine(ctx, s)
        val allowed = pool.filter { rules.violation(it.track, it.vibe, now()) == null }
        val mix = mixer(ctx).tripLength(RuleEngine.capMizrahi(allowed, V2Settings.get(ctx).maxMizrahiPct), minutes)
        val total = mix.sumOf { it.track.durationSec }
        activeMix = MoodPreset.DRIVE.target to mix.map { it.track.id }.toSet()
        BuiltMix("🚗 נסיעה של $minutes דק׳", mix.map { it.track }, MoodPreset.DRIVE.target,
            "בדיוק ${total / 60}:${"%02d".format(total % 60)} דקות")
    }

    suspend fun drama(ctx: Context, db: MusicDatabase): BuiltMix = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val pool = withOnline(ctx, s, MoodPreset.DRAMA, 0.4)
        finish(ctx, s, mixer(ctx).drama(pool, 20), "🎬 מצב דרמה", MoodPreset.DRAMA.target, "פסקול לחיים שלך עכשיו")
    }

    suspend fun daily(ctx: Context, db: MusicDatabase): BuiltMix = withContext(Dispatchers.Default) {
        val set = V2Settings.get(ctx)
        val taste = set.activeTaste()
        if (taste != null) return@withContext tasteMix(ctx, db, taste, minutes = 60)
        val dt = DayClock.dateTime(now())
        val preset = DailyPlan.presetFor(dt.dayOfWeek.value, dt.hour, set.weekdayMoods, set.defaultMood)
        val s = snapshot(ctx, db)
        val t = mixer(ctx).target(preset, Sliders(familiarNew = 0.65)).copy(tags = emptySet())
        val title = if (set.daylist) DailyPlan.daylistTitle(dt.dayOfWeek.value, dt.hour, preset) else "המיקס היומי שלך"
        finish(ctx, s, mixer(ctx).build(s.pool, t, 35, 0.65), title, t)
    }

    suspend fun reshape(ctx: Context, db: MusicDatabase, current: TrackMeta, action: VibeAction): BuiltMix = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val rel = runCatching { related(current.id) }.getOrDefault(emptyList())
        val relIds = rel.map { it.id }.toSet()
        val pool = s.pool + rel.filter { it.id !in s.byId }.map { poolTrack(ctx, it, s) }
        val cur = poolTrack(ctx, current, s)
        val out = mixer(ctx).reshape(cur, pool, action, 20, relIds)
        val title = when (action) { VibeAction.MORE_LIKE_THIS -> "עוד כזה"; VibeAction.CHANGE_VIBE -> "וייב חדש"; VibeAction.ENERGY_UP -> "אנרגיה ↑" }
        val target = when (action) {
            VibeAction.MORE_LIKE_THIS -> VibeTarget(cur.vibe.energy, cur.vibe.valence, cur.vibe.mizrahi)
            VibeAction.ENERGY_UP -> VibeTarget(energy = min(1.0, cur.vibe.energy + 0.22))
            VibeAction.CHANGE_VIBE -> null
        }
        finish(ctx, s, out, title, target)
    }

    // ------------------------------------------------------------------ someone else's taste, roulette, searches
    suspend fun tasteMix(ctx: Context, db: MusicDatabase, preset: TastePreset, minutes: Int = 60): BuiltMix = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val seeds = preset.seeds.shuffled(random).take(3)
        val found = searchMany(seeds, 25).map { poolTrack(ctx, it, s).copy(familiarity = 0.0) }
        val rules = ruleEngine(ctx, s)
        val allowed = found.filter { rules.violation(it.track, it.vibe, now()) == null }
        val ordered = mixer(ctx).tripLength(allowed, minutes, preset.target).ifEmpty { mixer(ctx).build(allowed, preset.target, 20) }
        activeMix = null
        BuiltMix("${preset.emoji} הטעם של: ${preset.he}", ordered.map { it.track }, null, "שעה של טעם של מישהו אחר")
    }

    suspend fun roulette(ctx: Context): Pair<String, TrackMeta?> = withContext(Dispatchers.Default) {
        repeat(4) {
            val seed = Roulette.seed(random)
            val res = search(seed, 30)
            val rules = ruleEngine(ctx, snap)
            val ok = res.filter { rules.violation(it, vibeOf(ctx, it), now()) == null }
            if (ok.isNotEmpty()) return@withContext seed to ok.random(random)
        }
        "" to null
    }

    suspend fun likeXBut(ctx: Context, db: MusicDatabase, text: String): BuiltMix = withContext(Dispatchers.Default) {
        val rw = QueryRewriter.parse(text)
        val s = snapshot(ctx, db)
        val seedTrack = search(rw.base, 3).firstOrNull()
        val seedVibe = seedTrack?.let { vibeOf(ctx, it) }
        val found = searchMany(QueryRewriter.queries(rw), 20) +
            (seedTrack?.let { st -> runCatching { related(st.id) }.getOrDefault(emptyList()) } ?: emptyList())
        val rules = ruleEngine(ctx, s)
        val fit = found.distinctBy { it.id }.filter { it.id != seedTrack?.id }.filter { t ->
            QueryRewriter.accepts(rw, vibeOf(ctx, t), t.title, seedVibe) && rules.violation(t, vibeOf(ctx, t), now()) == null
        }
        val label = rw.modifier?.let { "כמו ${rw.base} אבל ${it.he}" } ?: rw.base
        BuiltMix(label, fit.take(30), null, if (rw.modifier == null) "טיפ: כתוב \"כמו X אבל במזרחית\"" else null)
    }

    suspend fun lyric(text: String): List<TrackMeta> = withContext(Dispatchers.Default) {
        val q = text.trim()
        if (q.isEmpty()) return@withContext emptyList()
        searchMany(listOf(q, "$q lyrics", "\"$q\""), 15).take(30)
    }

    suspend fun timeMachine(ctx: Context, db: MusicDatabase, back: TimeMachine.Back): TimeMachine.Result = withContext(Dispatchers.Default) {
        TimeMachine.lookup(snapshot(ctx, db).history, now(), back)
    }

    suspend fun profile(ctx: Context, db: MusicDatabase): Achievements.Profile = withContext(Dispatchers.Default) {
        Achievements.compute(snapshot(ctx, db).history, now(), estimator(ctx))
    }

    /** "% match" for the Discover row: from the features the engine ranked each song on. */
    suspend fun matchPercents(ctx: Context, ids: List<String>): Map<String, Int> = withContext(Dispatchers.IO) {
        val store = DiscoverStore.get(ctx)
        val now = now()
        ids.mapIndexed { i, id ->
            val f = store.rec(id, now, 7L * DayClock.DAY_MS)?.second
            val score = if (f == null) 0.75 - i * 0.01
            else 0.55 * f.similarity.coerceIn(0.0, 1.0) + 0.15 * f.timeOfDay.coerceIn(0.0, 1.0) + 0.15 * f.sourcePrior.coerceIn(0.0, 1.0) +
                0.15 * (1.0 - f.skipPenalty.coerceIn(0.0, 1.0))
            id to (62 + (score * 37).toInt()).coerceIn(60, 99)
        }.toMap()
    }

    // ------------------------------------------------------------------ v2.1 functions
    /** Panic button: instantly calm music, built only from the local pool (no network wait). */
    suspend fun panic(ctx: Context, db: MusicDatabase): BuiltMix = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val t = VibeTarget(energy = 0.2, valence = 0.55, tags = setOf(VibeTag.CHILL))
        val calm = s.pool.filter { it.vibe.energy <= 0.45 }
        finish(ctx, s, mixer(ctx).build(calm.ifEmpty { s.pool }, t, 25, 0.8), "🧯 כפתור פאניקה", t, "רגוע. נושמים.")
    }

    /** Decade dial: the decade's hits from YouTube Music search, shaped by your taste. */
    suspend fun decade(ctx: Context, db: MusicDatabase, decade: Int): BuiltMix = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val yy = decade % 100
        val heb = if (decade >= 2000) "שנות ה-${decade}" else "שנות ה-${"%02d".format(yy)}"
        val seeds = listOf("${decade}s hits", "להיטים ישראליים $heb", "best of ${decade}s", "מזרחית $heb")
        val found = searchMany(seeds, 25).map { poolTrack(ctx, it, s) }
        finish(ctx, s, mixer(ctx).diversify(found.shuffled(random), 30), "📻 עשור: $heb", null, "חוגת העשורים")
    }

    /** "Ask music": free text -> vibe target (+ trip length), from the local pool plus a search for the words themselves. */
    suspend fun ask(ctx: Context, db: MusicDatabase, text: String): BuiltMix = withContext(Dispatchers.Default) {
        val q = AskMusic.parse(text)
        val s = snapshot(ctx, db)
        val online = runCatching { searchMany(listOfNotNull(text.trim().takeIf { it.isNotEmpty() }) + (q.preset?.let { presetSeeds[it].orEmpty() } ?: emptyList()), 20) }.getOrDefault(emptyList())
        val pool = s.pool + online.filter { it.id !in s.byId }.map { poolTrack(ctx, it, s) }
        val label = if (q.matched.isEmpty()) "🗣️ \"${text.take(24)}\"" else "🗣️ " + q.matched.joinToString(" · ")
        if (q.minutes != null) {
            val rules = ruleEngine(ctx, s)
            val ok = pool.filter { rules.violation(it.track, it.vibe, now()) == null }
            val mix = mixer(ctx).tripLength(ok, q.minutes, q.target)
            activeMix = q.target to mix.map { it.track.id }.toSet()
            return@withContext BuiltMix(label, mix.map { it.track }, q.target, "${q.minutes} דקות")
        }
        finish(ctx, s, mixer(ctx).build(pool, q.target, 30, 0.5), label, q.target, if (q.matched.isEmpty()) "לא זיהיתי מילים, חיפשתי את הטקסט" else null)
    }

    /** Recommendations for Smart Shuffle / Flow / samples: unknown-to-you songs that fit your taste. */
    suspend fun recs(ctx: Context, db: MusicDatabase, n: Int, exclude: Set<String> = emptySet()): List<TrackMeta> = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val fromDiscover = runCatching { DiscoverRepository(ctx.applicationContext, db).cached().map { it.track } }.getOrDefault(emptyList())
        val pool = (fromDiscover.map { poolTrack(ctx, it, s) } + s.pool.filter { it.familiarity < 0.2 })
            .filter { it.track.id !in exclude }.distinctBy { it.track.id }
        val rules = ruleEngine(ctx, s)
        shape(ctx, s, pool.filter { rules.violation(it.track, it.vibe, now()) == null }.sortedByDescending { it.affinity }.take(n * 3).shuffled(random))
            .take(n).map { it.track }
    }

    /** Flow: the next chunk of an endless personal stream (daily-mix logic, never repeating what is queued). */
    suspend fun flowMore(ctx: Context, db: MusicDatabase, queued: Set<String>): List<TrackMeta> = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val dt = DayClock.dateTime(now())
        val set = V2Settings.get(ctx)
        val preset = DailyPlan.presetFor(dt.dayOfWeek.value, dt.hour, set.weekdayMoods, set.defaultMood)
        val t = mixer(ctx).target(preset, Sliders(familiarNew = 0.55))
        finish(ctx, s, mixer(ctx).build(s.pool.filter { it.track.id !in queued }, t, 15, 0.55), "Flow", t).tracks
    }

    suspend fun history(ctx: Context, db: MusicDatabase): History = snapshot(ctx, db).history

    data class PlayInfo(val plays: Int, val lastTs: Long?, val fullListens: Int)

    /** Cheap per-song facts for the service (DJ intros, never-heard guard, auto-like); null until the pool is built. */
    fun playInfo(songId: String): PlayInfo? {
        val s = snap ?: return null
        var plays = 0; var last: Long? = null; var full = 0
        for (p in s.history.plays) if (p.trackId == songId && p.playedMs >= 30_000L) {
            plays++; if (last == null || p.timestampMs > last) last = p.timestampMs
            val d = s.history.duration(p); if (d > 0 && p.playedMs >= d * 0.85) full++
        }
        return PlayInfo(plays, last, full)
    }

    suspend fun blendMine(ctx: Context, db: MusicDatabase, name: String): TasteFile = withContext(Dispatchers.Default) {
        Blend.export(snapshot(ctx, db).history, name, now())
    }

    suspend fun blendMix(ctx: Context, db: MusicDatabase, friend: TasteFile, myName: String): BuiltMix = withContext(Dispatchers.Default) {
        val mine = blendMine(ctx, db, myName)
        val merged = Blend.merge(mine, friend, 40)
        val s = snapshot(ctx, db)
        val rules = ruleEngine(ctx, s)
        val ok = merged.map { poolTrack(ctx, it, s) }.filter { rules.violation(it.track, it.vibe, now()) == null }
        BuiltMix("🤝 Blend: ${mine.name} + ${friend.name}", ok.map { it.track }, null, "התאמה של ${Blend.matchPct(mine, friend)}%")
    }

    // ------------------------------------------------------------------ colour search
    fun colorMap(ctx: Context): Map<String, Int> {
        val raw = DiscoverStore.get(ctx).get("v2.colors").orEmpty()
        return raw.split(";").filter { "=" in it }.mapNotNull { e -> e.substringAfterLast("=").toIntOrNull()?.let { e.substringBeforeLast("=") to it } }.toMap()
    }

    /** Reads the dominant colour of up to [limit] album covers (most played first) and caches it. */
    suspend fun scanColors(ctx: Context, db: MusicDatabase, limit: Int = 300, onProgress: (Int, Int) -> Unit): Map<String, Int> = withContext(Dispatchers.IO) {
        val s = snapshot(ctx, db)
        val have = colorMap(ctx).toMutableMap()
        val todo = s.pool.filter { it.familiarity > 0 && !it.track.thumbnailUrl.isNullOrBlank() && it.track.id !in have }
            .sortedByDescending { it.familiarity }.take(limit)
        todo.forEachIndexed { i, p ->
            val url = p.track.thumbnailUrl!!.replace(Regex("=w\\d+-h\\d+"), "=w60-h60")
            val req = ImageRequest.Builder(ctx).data(url).size(64, 64).allowHardware(false).build()
            val bmp = withTimeoutOrNull(8_000) { runCatching { ctx.imageLoader.execute(req).image?.toBitmap() }.getOrNull() }
            if (bmp != null) {
                val pal = Palette.from(bmp).maximumColorCount(12).generate()
                val c = pal.getVibrantColor(pal.getDominantColor(0)).let { v -> if (v == 0) pal.getDominantColor(0) else v }
                have[p.track.id] = c and 0xFFFFFF
            }
            if (i % 10 == 0) withContext(Dispatchers.Main) { onProgress(i + 1, todo.size) }
        }
        DiscoverStore.get(ctx).put("v2.colors", have.entries.joinToString(";") { "${it.key}=${it.value}" })
        have
    }

    suspend fun byColor(ctx: Context, db: MusicDatabase, bucket: ColorBuckets.Bucket): List<TrackMeta> = withContext(Dispatchers.Default) {
        val s = snapshot(ctx, db)
        val colors = colorMap(ctx)
        s.pool.filter { p -> colors[p.track.id]?.let { ColorBuckets.bucketOf(it) == bucket } == true }
            .sortedByDescending { it.familiarity }.map { it.track }
    }

    // ------------------------------------------------------------------ feedback (called from DiscoverHooks on the io thread)
    fun onPlayed(ctx: Context, songId: String, playedMs: Long, durationMs: Long) {
        val (target, ids) = activeMix ?: return
        if (songId !in ids) return
        val t = snap?.byId?.get(songId)?.track ?: return
        val skipped = playedMs < 30_000L || (durationMs > 0 && playedMs < durationMs * 0.35)
        val full = durationMs > 0 && playedMs >= durationMs * 0.85
        if (!skipped && !full) return
        val e = estimator(ctx)
        synchronized(e) { e.feedback(t, target, skipped, full) }
        if (++feedbackCount % 3 == 0) V2Settings.get(ctx).corrections = e.corrections.encode()
    }
}
