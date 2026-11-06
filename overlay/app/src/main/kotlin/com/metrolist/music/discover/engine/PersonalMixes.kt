/**
 * Metrolist Discover 13.7.0.9 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 *
 * Varied personal mixes for the home screen and the mixes hub, rotating every day:
 * "הגילויים שלך", "עונה שעברה", a mix per top artist, session mixes from the hours you really listen,
 * and "שינוי כיוון". Every builder returns null instead of a too-small mix; nothing here throws on sparse data.
 */
package com.metrolist.music.discover.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class PersonalKind { DISCOVERIES, LAST_SEASON, ARTIST, SESSION, CHANGE_DIRECTION }

data class PersonalMix(val key: String, val kind: PersonalKind, val emoji: String, val titleHe: String, val subtitleHe: String, val tracks: List<PoolTrack>)

/** A session type: its mood target and the hours it usually happens in (used when the history says nothing). */
enum class SessionType(val he: String, val emoji: String, val target: VibeTarget, val defaultHours: Set<Int>) {
    DRIVE("נסיעה", "🚗", VibeTarget(energy = 0.68, valence = 0.66), setOf(7, 8, 9, 16, 17, 18, 19)),
    SPORT("ספורט", "💪", VibeTarget(energy = 0.92, valence = 0.6, tags = setOf(VibeTag.EDM, VibeTag.PHONK, VibeTag.RAP)), setOf(6, 7, 17, 18, 19, 20, 21)),
    FOCUS("ריכוז", "🎯", VibeTarget(energy = 0.35, valence = 0.5, cinematic = 0.5, tags = setOf(VibeTag.CHILL, VibeTag.LOFI, VibeTag.CINEMATIC)), setOf(9, 10, 11, 12, 13, 14, 15, 16)),
    NIGHT("לילה", "🌙", VibeTarget(energy = 0.28, valence = 0.45, tags = setOf(VibeTag.CHILL)), setOf(22, 23, 0, 1, 2, 3));
}

object PersonalMixes {
    const val MIN_SIZE = 8
    const val SIZE = 25
    private const val DAY = 86_400_000L

    /** Played (>= 30 s) events only. */
    private fun played(h: History) = h.plays.filter { it.playedMs >= 30_000L }

    /** Every mix that has enough songs today (unordered, unrotated). */
    fun all(h: History, pool: List<PoolTrack>, now: Long, mixer: VibeMixer = VibeMixer(Random(now / DAY)), minSize: Int = MIN_SIZE, topArtists: Int = 6): List<PersonalMix> {
        val out = ArrayList<PersonalMix>()
        val uniq = pool.distinctBy { it.track.id }
        if (uniq.isEmpty()) return out
        fun safe(f: () -> PersonalMix?) { runCatching(f).getOrNull()?.let { if (it.tracks.size >= minSize) out += it } }
        safe { discoveries(h, uniq, now, mixer) }
        safe { lastSeason(h, uniq, now) }
        for (a in topArtistIds(h, now, topArtists)) safe { artistMix(h, uniq, a, mixer) }
        for (st in SessionType.entries) safe { sessionMix(h, uniq, st, mixer) }
        safe { changeDirection(h, uniq, now, mixer) }
        return out
    }

    /**
     * Today's selection: rotates by day so the screen changes. Always at most one of each kind except artists (2) and
     * sessions (2: the one that fits the current hour first). [show] caps the total.
     */
    fun today(all: List<PersonalMix>, dayKey: Long, hour: Int, show: Int = 6): List<PersonalMix> {
        if (all.isEmpty()) return all
        val rnd = Random(dayKey * 31 + 7)
        val byKind = all.groupBy { it.kind }
        val pick = ArrayList<PersonalMix>()
        byKind[PersonalKind.DISCOVERIES]?.firstOrNull()?.let { pick += it }
        val sessions = byKind[PersonalKind.SESSION].orEmpty()
        val now = sessions.firstOrNull { m -> SessionType.entries.firstOrNull { it.name == m.key.removePrefix("session_") }?.defaultHours?.contains(hour) == true }
        now?.let { pick += it }
        val artists = byKind[PersonalKind.ARTIST].orEmpty().shuffled(rnd).take(2)
        pick += artists
        byKind[PersonalKind.LAST_SEASON]?.firstOrNull()?.let { pick += it }
        byKind[PersonalKind.CHANGE_DIRECTION]?.firstOrNull()?.let { pick += it }
        sessions.filter { it !== now }.shuffled(rnd).firstOrNull()?.let { pick += it }
        // the order rotates too (except the first card)
        val head = pick.take(1); val tail = pick.drop(1).shuffled(rnd)
        return (head + tail).distinctBy { it.key }.take(show)
    }

    // ------------------------------------------------------------------ helpers
    private fun heardArtists(h: History): Set<String> {
        val s = HashSet<String>()
        for (e in played(h)) h.tracks[e.trackId]?.let { s += it.artistIds }
        return s
    }

    /** Affinity-weighted average vibe of what was played the most in the last 60 days (all time when that is empty). */
    fun tasteCentroid(h: History, pool: List<PoolTrack>, now: Long): Vibe? {
        val byId = pool.associateBy { it.track.id }
        val recent = played(h).filter { it.timestampMs >= now - 60 * DAY }.ifEmpty { played(h) }
        val counts = recent.groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.take(60)
        var w = 0.0; var e = 0.0; var v = 0.0; var m = 0.0; var c = 0.0
        for ((id, n) in counts) { val p = byId[id] ?: continue; val k = n.toDouble(); w += k; e += k * p.vibe.energy; v += k * p.vibe.valence; m += k * p.vibe.mizrahi; c += k * p.vibe.cinematic }
        return if (w == 0.0) null else Vibe(e / w, v / w, m / w, c / w)
    }

    fun topArtistIds(h: History, now: Long, n: Int): List<String> {
        val recent = played(h).filter { it.timestampMs >= now - 30 * DAY }.ifEmpty { played(h) }
        val per = HashMap<String, Int>()
        for (e in recent) h.tracks[e.trackId]?.mainArtist?.let { if (it != "?") per[it] = (per[it] ?: 0) + 1 }
        return per.entries.filter { it.value >= 3 }.sortedByDescending { it.value }.take(n).map { it.key }
    }

    private fun artistName(h: History, pool: List<PoolTrack>, artistId: String): String {
        val t = pool.firstOrNull { it.track.mainArtist == artistId }?.track ?: h.tracks.values.firstOrNull { it.mainArtist == artistId }
        return t?.artistNames?.firstOrNull()?.takeIf { it.isNotBlank() } ?: t?.artistLabel ?: "האמן"
    }

    private fun order(list: List<PoolTrack>) = EnergyArc.order(list, wave = 10, minArtistGap = 1)

    // ------------------------------------------------------------------ the mixes
    /** "הגילויים שלך": only artists you never played, closest to your taste. */
    fun discoveries(h: History, pool: List<PoolTrack>, now: Long, mixer: VibeMixer): PersonalMix? {
        val heard = heardArtists(h)
        val c = tasteCentroid(h, pool, now) ?: return null
        val t = VibeTarget(c.energy, c.valence, c.mizrahi, c.cinematic)
        val cand = pool.filter { p -> p.familiarity == 0.0 && p.track.artistIds.isNotEmpty() && p.track.artistIds.none { it in heard } }
        if (cand.isEmpty()) return null
        val picked = mixer.build(cand, t, SIZE, 0.0)
        return PersonalMix("discoveries", PersonalKind.DISCOVERIES, "🧭", "הגילויים שלך", "רק אמנים שעוד לא שמעת, בטעם שלך", order(picked))
    }

    /** "עונה שעברה": songs you looped 1-2 months ago and dropped. */
    fun lastSeason(h: History, pool: List<PoolTrack>, now: Long, minLoops: Int = 3): PersonalMix? {
        val byId = pool.associateBy { it.track.id }
        val from = now - 62 * DAY; val to = now - 28 * DAY; val recent = now - 21 * DAY
        val then = HashMap<String, Int>(); val lately = HashMap<String, Int>()
        for (e in played(h)) {
            if (e.timestampMs in from until to) then[e.trackId] = (then[e.trackId] ?: 0) + 1
            if (e.timestampMs >= recent) lately[e.trackId] = (lately[e.trackId] ?: 0) + 1
        }
        val ids = then.entries.filter { it.value >= minLoops && (lately[it.key] ?: 0) <= 1 }.sortedByDescending { it.value }.map { it.key }
        val list = ids.mapNotNull { byId[it] ?: h.tracks[it]?.let { t -> PoolTrack(t, Vibe(0.5, 0.5, 0.5, 0.1), 0.5, 0.5) } }
        if (list.isEmpty()) return null
        val picked = VibeMixer().diversify(list, SIZE, maxPerArtist = 3)
        return PersonalMix("last_season", PersonalKind.LAST_SEASON, "🍂", "עונה שעברה", "מה ששמעת בלופ לפני חודש-חודשיים וזנחת", order(picked))
    }

    /** One mix per top artist: the artist's best songs + songs of ~5 similar artists (related graph first, vibe second). */
    fun artistMix(h: History, pool: List<PoolTrack>, artistId: String, mixer: VibeMixer, similar: Int = 5): PersonalMix? {
        val own = pool.filter { it.track.mainArtist == artistId }.sortedByDescending { it.affinity + it.familiarity }.take(10)
        if (own.size < 3) return null
        val ownIds = own.map { it.track.id }.toHashSet()
        val byId = pool.associateBy { it.track.id }
        val relCount = HashMap<String, Int>()
        for (p in pool) if (p.track.mainArtist == artistId) for (r in h.related[p.track.id].orEmpty()) {
            val a = byId[r]?.track?.mainArtist ?: h.tracks[r]?.mainArtist ?: continue
            if (a != artistId && a != "?") relCount[a] = (relCount[a] ?: 0) + 1
        }
        var sims = relCount.entries.sortedByDescending { it.value }.take(similar).map { it.key }
        if (sims.size < similar) {
            val mv = own.map { it.vibe }
            val t = VibeTarget(mv.map { it.energy }.average(), mv.map { it.valence }.average(), mv.map { it.mizrahi }.average())
            val per = pool.filter { it.track.mainArtist != artistId && it.track.mainArtist !in sims && it.track.mainArtist != "?" }
                .groupBy { it.track.mainArtist }.mapValues { (_, l) -> l.minOf { it.vibe.distanceTo(t) } - 0.05 * min(5, l.size) }
            sims = sims + per.entries.sortedBy { it.value }.take(similar - sims.size).map { it.key }
        }
        val simTracks = sims.flatMap { a -> pool.filter { it.track.mainArtist == a && it.track.id !in ownIds }.sortedByDescending { it.affinity }.take(3) }
        val mixed = mixer.interleave(own.take(10), simTracks)
        if (mixed.size < MIN_SIZE) return null
        val name = artistName(h, pool, artistId)
        return PersonalMix("artist_$artistId", PersonalKind.ARTIST, "🎤", "מיקס $name", "$name + ${sims.size} אמנים דומים", order(mixed.take(SIZE)))
    }

    /** The hours (0..23) this listener actually plays music, by play count (top first). */
    fun listeningHours(h: History): List<Pair<Int, Int>> {
        val c = IntArray(24)
        for (e in played(h)) c[DayClock.dateTime(e.timestampMs).hour]++
        return (0 until 24).map { it to c[it] }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    /**
     * Session mix (נסיעה / ספורט / ריכוז / לילה): songs you actually played in the hours this session happens for you,
     * that fit its mood, plus ~30% close new songs. Hours: your busiest hours inside the session's usual window
     * (or your busiest hours overall when the window is empty).
     */
    fun sessionMix(h: History, pool: List<PoolTrack>, st: SessionType, mixer: VibeMixer): PersonalMix? {
        val hrs = listeningHours(h)
        if (hrs.isEmpty()) return null
        val inWindow = hrs.filter { it.first in st.defaultHours }.take(3).map { it.first }
        val hours = inWindow.ifEmpty { hrs.take(3).map { it.first } }.toSet()
        val byId = pool.associateBy { it.track.id }
        val mine = played(h).filter { DayClock.dateTime(it.timestampMs).hour in hours }.map { it.trackId }.distinct().mapNotNull { byId[it] }
        val fit = mine.filter { it.vibe.distanceTo(st.target) <= 0.32 }
        val familiarPart = mixer.build(fit.ifEmpty { mine }, st.target, (SIZE * 0.7).toInt(), 0.7)
        val used = familiarPart.map { it.track.id }.toHashSet()
        val newPart = mixer.build(pool.filter { it.familiarity == 0.0 && it.track.id !in used }, st.target, SIZE - familiarPart.size, 0.0)
        val list = mixer.interleave(familiarPart, newPart)
        if (list.size < MIN_SIZE) return null
        val hourLabel = hours.sorted().joinToString(", ") { "%02d:00".format(it) }
        return PersonalMix("session_${st.name}", PersonalKind.SESSION, st.emoji, "מיקס ${st.he}", "מהשעות שאתה באמת שומע: $hourLabel",
            if (st == SessionType.FOCUS || st == SessionType.NIGHT) list else order(list))
    }

    /** "שינוי כיוון": ~70% new songs from a neighbouring style of yours. */
    fun changeDirection(h: History, pool: List<PoolTrack>, now: Long, mixer: VibeMixer, newShare: Double = 0.7): PersonalMix? {
        val c = tasteCentroid(h, pool, now) ?: return null
        val (t, label) = when {
            c.mizrahi >= 0.5 -> VibeTarget(energy = min(1.0, c.energy + 0.05), valence = c.valence, mizrahi = max(0.0, c.mizrahi - 0.45)) to "פופ וראפ ישראלי, פחות מזרחית"
            c.energy >= 0.72 -> VibeTarget(energy = c.energy - 0.25, valence = min(1.0, c.valence + 0.1), mizrahi = c.mizrahi) to "אותו סגנון, יותר רגוע"
            c.cinematic >= 0.4 -> VibeTarget(energy = min(1.0, c.energy + 0.2), valence = c.valence, cinematic = 0.2) to "פחות פסקול, יותר קצב"
            else -> VibeTarget(energy = min(1.0, c.energy + 0.22), valence = c.valence, mizrahi = min(1.0, c.mizrahi + 0.3)) to "יותר אנרגיה ויותר מזרחית"
        }
        if (abs((t.energy ?: c.energy) - c.energy) + abs((t.mizrahi ?: c.mizrahi) - c.mizrahi) + abs((t.cinematic ?: c.cinematic) - c.cinematic) < 0.1) return null
        val list = mixer.buildBalanced(pool, t, SIZE, newShare)
        return PersonalMix("change_direction", PersonalKind.CHANGE_DIRECTION, "🔀", "שינוי כיוון", "$label · 70% חדש", order(list))
    }
}
