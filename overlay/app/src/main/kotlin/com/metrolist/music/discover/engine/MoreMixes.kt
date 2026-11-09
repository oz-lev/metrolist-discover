/**
 * Metrolist Discover 13.7.0.14 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 *
 * More mixes for the home row and the mixes hub, and the pieces the mix builder / radio use:
 * "על ריפיט עכשיו", "עומק" (deep cuts of artists you love), the time-of-day mix, "פלאשבק" (a month from the past),
 * "רק עברית" / "בלי עברית" (Lang.isHebrew of V21.kt), song radio, artist radio and a blend of two artists.
 * Every builder returns null (or an empty list) instead of throwing on sparse data.
 */
package com.metrolist.music.discover.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A part of the day for the time-of-day mix. Hours are local; NIGHT wraps past midnight. */
enum class DayPart(val he: String, val emoji: String, val hours: Set<Int>) {
    MORNING("הבוקר", "🌅", (5..10).toSet()),
    NOON("הצהריים", "☀️", (11..15).toSet()),
    EVENING("הערב", "🌆", (16..20).toSet()),
    NIGHT("הלילה", "🌙", setOf(21, 22, 23, 0, 1, 2, 3, 4));

    companion object { fun of(hour: Int): DayPart = entries.first { hour in it.hours } }
}

object MoreMixes {
    private const val DAY = 86_400_000L
    const val SIZE = PersonalMixes.SIZE
    private val MONTHS_HE = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")

    /** Real listens: >= 30 s and not an early skip. */
    private fun real(h: History): List<PlayEvent> = h.plays.filter { it.playedMs >= 30_000L && !SkipRule.isEarlySkip(it.playedMs, h.duration(it)) }

    private fun order(list: List<PoolTrack>) = EnergyArc.order(list, wave = 10, minArtistGap = 1)

    private fun fallback(t: TrackMeta) = PoolTrack(t, Vibe(0.5, 0.5, 0.5, 0.1), 0.5, 0.5)

    /** All the new mixes that have enough songs (unordered). [hour] = the local hour now. */
    fun all(h: History, pool: List<PoolTrack>, now: Long, hour: Int = DayClock.dateTime(now).hour,
            mixer: VibeMixer = VibeMixer(kotlin.random.Random(now / DAY)), minSize: Int = PersonalMixes.MIN_SIZE): List<PersonalMix> {
        val out = ArrayList<PersonalMix>()
        val uniq = pool.distinctBy { it.track.id }
        if (uniq.isEmpty()) return out
        fun safe(f: () -> PersonalMix?) { runCatching(f).getOrNull()?.let { if (it.tracks.size >= minSize) out += it } }
        safe { onRepeat(h, uniq, now) }
        safe { deepCuts(h, uniq, now, mixer) }
        safe { dayPartMix(h, uniq, DayPart.of(hour), mixer) }
        safe { throwback(h, uniq, now) }
        safe { languageMix(h, uniq, now, true, mixer) }
        safe { languageMix(h, uniq, now, false, mixer) }
        return out
    }

    /** "על ריפיט עכשיו": what you keep coming back to in the last 14 days (>= 3 real listens), most recent-weighted first. */
    fun onRepeat(h: History, pool: List<PoolTrack>, now: Long, days: Int = 14, minPlays: Int = 3): PersonalMix? {
        val byId = pool.associateBy { it.track.id }
        val since = now - days * DAY
        val score = HashMap<String, Double>(); val n = HashMap<String, Int>()
        for (e in real(h)) if (e.timestampMs in since..now) {
            n[e.trackId] = (n[e.trackId] ?: 0) + 1
            score[e.trackId] = (score[e.trackId] ?: 0.0) + exp(-(now - e.timestampMs).toDouble() / (5 * DAY))
        }
        val ids = score.entries.filter { (n[it.key] ?: 0) >= minPlays }.sortedByDescending { it.value }.map { it.key }
        val list = ids.mapNotNull { byId[it] ?: h.tracks[it]?.let(::fallback) }
        if (list.isEmpty()) return null
        val picked = VibeMixer().diversify(list, SIZE, maxPerArtist = 4)
        return PersonalMix("on_repeat", PersonalKind.ON_REPEAT, "🔁", "על ריפיט עכשיו", "מה שאתה לא מפסיק לשמוע ב-$days הימים האחרונים", picked)
    }

    /** Top artists by real listens in the last [days] (all time when that is empty). */
    fun lovedArtists(h: History, now: Long, n: Int = 8, days: Int = 90): List<String> {
        val r = real(h)
        val recent = r.filter { it.timestampMs >= now - days * DAY }.ifEmpty { r }
        val per = HashMap<String, Int>()
        for (e in recent) h.tracks[e.trackId]?.mainArtist?.let { if (it != "?") per[it] = (per[it] ?: 0) + 1 }
        return per.entries.filter { it.value >= 5 }.sortedByDescending { it.value }.take(n).map { it.key }
    }

    /** "עומק": songs of the artists you love that you played at most once, closest to that artist's own sound. */
    fun deepCuts(h: History, pool: List<PoolTrack>, now: Long, mixer: VibeMixer, maxPerArtist: Int = 4): PersonalMix? {
        val loved = lovedArtists(h, now)
        if (loved.isEmpty()) return null
        val n = HashMap<String, Int>()
        for (e in real(h)) n[e.trackId] = (n[e.trackId] ?: 0) + 1
        val lovedSet = loved.toHashSet()
        val cand = pool.filter { it.track.mainArtist in lovedSet && (n[it.track.id] ?: 0) <= 1 && it.track.title.isNotBlank() }
        if (cand.isEmpty()) return null
        // round-robin over the loved artists (best affinity first inside each), so one artist cannot fill the mix
        val per = cand.groupBy { it.track.mainArtist }.mapValues { (_, l) -> l.sortedByDescending { it.affinity + it.context }.take(maxPerArtist) }
        val rr = ArrayList<PoolTrack>()
        for (round in 0 until maxPerArtist) for (a in loved) per[a]?.getOrNull(round)?.let { rr += it }
        val picked = mixer.diversify(rr, SIZE, maxPerArtist)
        return PersonalMix("deep_cuts", PersonalKind.DEEP_CUTS, "💎", "עומק", "שירים שכמעט לא שמעת של האמנים שאתה הכי אוהב", order(picked))
    }

    /**
     * Time-of-day mix: songs you play MORE in this part of the day than in general (lift >= 1.3, >= 2 listens in it),
     * plus ~25% new songs close to their sound.
     */
    fun dayPartMix(h: History, pool: List<PoolTrack>, part: DayPart, mixer: VibeMixer, newShare: Double = 0.25): PersonalMix? {
        val byId = pool.associateBy { it.track.id }
        val all = HashMap<String, Int>(); val inPart = HashMap<String, Int>(); var total = 0; var totalIn = 0
        for (e in real(h)) {
            all[e.trackId] = (all[e.trackId] ?: 0) + 1; total++
            if (DayClock.dateTime(e.timestampMs).hour in part.hours) { inPart[e.trackId] = (inPart[e.trackId] ?: 0) + 1; totalIn++ }
        }
        if (totalIn < 20 || total == 0) return null
        val partShare = totalIn.toDouble() / total
        val fav = inPart.entries.filter { (id, c) -> c >= 2 && c.toDouble() / (all[id] ?: 1) >= 1.3 * partShare }
            .sortedByDescending { (id, c) -> c + c.toDouble() / (all[id] ?: 1) }.mapNotNull { byId[it.key] }
        if (fav.size < 5) return null
        val famPart = mixer.diversify(fav, (SIZE * (1 - newShare)).toInt(), 2)
        val v = famPart.map { it.vibe }
        val t = VibeTarget(v.map { it.energy }.average(), v.map { it.valence }.average(), v.map { it.mizrahi }.average())
        val used = famPart.map { it.track.id }.toHashSet()
        val newPart = mixer.build(pool.filter { it.familiarity == 0.0 && it.track.id !in used }, t, SIZE - famPart.size, 0.0)
        val list = mixer.interleave(famPart, newPart)
        return PersonalMix("daypart_${part.name}", PersonalKind.DAY_PART, part.emoji, "מיקס ${part.he}", "מה שאתה שומע דווקא בשעות האלה, ועוד קצת חדש", list)
    }

    /**
     * "פלאשבק": one calendar month at least 3 months back (rotates by day among the months with >= 15 songs);
     * its most played songs that you did not play in the last 30 days.
     */
    fun throwback(h: History, pool: List<PoolTrack>, now: Long, dayKey: Long = DayClock.dayKey(now)): PersonalMix? {
        val byId = pool.associateBy { it.track.id }
        val cur = DayClock.date(now).withDayOfMonth(1)
        val byMonth = HashMap<java.time.YearMonth, HashMap<String, Int>>()
        val lately = HashSet<String>()
        for (e in real(h)) {
            if (e.timestampMs >= now - 30 * DAY) lately += e.trackId
            val d = DayClock.date(e.timestampMs)
            val ym = java.time.YearMonth.of(d.year, d.monthValue)
            if (!d.withDayOfMonth(1).isAfter(cur.minusMonths(3))) byMonth.getOrPut(ym) { HashMap() }.let { it[e.trackId] = (it[e.trackId] ?: 0) + 1 }
        }
        val months = byMonth.entries.map { (ym, c) -> ym to c.filter { it.value >= 2 && it.key !in lately } }.filter { it.second.size >= 15 }.sortedBy { it.first }
        if (months.isEmpty()) return null
        val (ym, counts) = months[Math.floorMod(dayKey, months.size.toLong()).toInt()]
        val list = counts.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] ?: h.tracks[it.key]?.let(::fallback) }
        val picked = VibeMixer().diversify(list, SIZE, maxPerArtist = 3)
        val label = "${MONTHS_HE[ym.monthValue - 1]} ${ym.year}"
        return PersonalMix("throwback_$ym", PersonalKind.THROWBACK, "📼", "פלאשבק: $label", "מה שהיה לך על ריפיט ב$label ונעלם", order(picked))
    }

    /** "רק עברית" / "בלי עברית": your favourites in that language (70%) + close new songs in it (30%). */
    fun languageMix(h: History, pool: List<PoolTrack>, now: Long, hebrew: Boolean, mixer: VibeMixer, newShare: Double = 0.3): PersonalMix? {
        val inLang = pool.filter { Lang.isHebrew(it.track) == hebrew }
        if (inLang.size < PersonalMixes.MIN_SIZE) return null
        val ids = inLang.map { it.track.id }.toHashSet()
        val c = PersonalMixes.tasteCentroid(h.copy(plays = h.plays.filter { it.trackId in ids }), inLang, now) ?: return null
        val list = mixer.buildBalanced(inLang, VibeTarget(c.energy, c.valence, c.mizrahi), SIZE, newShare, maxPerArtist = 3)
        val (key, emoji, title, sub) = if (hebrew) listOf("lang_he", "🇮🇱", "רק עברית", "האהובים שלך בעברית ועוד קצת חדש")
            else listOf("lang_foreign", "🌍", "בלי עברית", "האהובים שלך מחו\"ל ועוד קצת חדש")
        return PersonalMix(key, PersonalKind.LANGUAGE, emoji, title, sub, order(list))
    }

    // ------------------------------------------------------------------ radio and blend
    /** Related songs of [seedId] (YouTube Music "related" map, 2 hops; the second hop counts half). */
    fun relatedScores(h: History, seedId: String): Map<String, Double> {
        val s = HashMap<String, Double>()
        val first = h.related[seedId].orEmpty()
        for ((i, r) in first.withIndex()) s[r] = max(s[r] ?: 0.0, 1.0 - 0.02 * i)
        for (r in first.take(10)) for ((i, r2) in h.related[r].orEmpty().withIndex()) if (r2 != seedId) s[r2] = max(s[r2] ?: 0.0, 0.5 - 0.01 * i)
        return s
    }

    /**
     * Song radio: songs that sound like [seed] — its related songs first, then its vibe neighbours; a little of the same
     * artist (at most 3); [extraRelated] = online related ids (radio of the song) that count like first-hop related.
     */
    fun songRadio(h: History, pool: List<PoolTrack>, seed: PoolTrack, mixer: VibeMixer, size: Int = 30, newShare: Double = 0.3,
                  extraRelated: Set<String> = emptySet()): List<PoolTrack> {
        val rel = relatedScores(h, seed.track.id).toMutableMap()
        for (id in extraRelated) rel[id] = max(rel[id] ?: 0.0, 0.9)
        val sv = seed.vibe
        val t = VibeTarget(sv.energy, sv.valence, sv.mizrahi, sv.cinematic)
        val boosted = pool.filter { it.track.id != seed.track.id }.map { p ->
            val r = rel[p.track.id] ?: 0.0
            val same = if (p.track.mainArtist == seed.track.mainArtist) 0.12 else 0.0
            p.copy(context = p.context + 0.6 * r + same)
        }
        val list = mixer.buildBalanced(boosted, t, size, newShare)
        val capped = mixer.diversify(list, size, maxPerArtist = 3)
        return listOf(seed) + EnergyArc.order(capped, wave = 10, startEnergy = sv.energy, minArtistGap = 1)
    }

    /** Artists that the related graph links to [artistId] (most links first). */
    fun relatedArtists(h: History, pool: List<PoolTrack>, artistId: String, n: Int = 6): List<String> {
        val byId = pool.associateBy { it.track.id }
        val c = HashMap<String, Int>()
        for (p in pool) if (p.track.mainArtist == artistId) for (r in h.related[p.track.id].orEmpty()) {
            val a = byId[r]?.track?.mainArtist ?: h.tracks[r]?.mainArtist ?: continue
            if (a != artistId && a != "?") c[a] = (c[a] ?: 0) + 1
        }
        return c.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    /** Average vibe of an artist's songs in the pool, or null. */
    fun artistVibe(pool: List<PoolTrack>, artistId: String): Vibe? {
        val l = pool.filter { it.track.mainArtist == artistId }
        if (l.isEmpty()) return null
        return Vibe(l.map { it.vibe.energy }.average(), l.map { it.vibe.valence }.average(), l.map { it.vibe.mizrahi }.average(), l.map { it.vibe.cinematic }.average())
    }

    /** Artist radio for ANY artist with songs in the pool: their best songs + songs of related / similar-sounding artists. */
    fun artistRadio(h: History, pool: List<PoolTrack>, artistId: String, mixer: VibeMixer, size: Int = 30): List<PoolTrack> {
        val own = pool.filter { it.track.mainArtist == artistId }.sortedByDescending { it.affinity + it.familiarity + it.context }
        if (own.isEmpty()) return emptyList()
        val v = artistVibe(pool, artistId)!!
        val t = VibeTarget(v.energy, v.valence, v.mizrahi, v.cinematic)
        val rel = relatedArtists(h, pool, artistId).toHashSet()
        val others = pool.filter { it.track.mainArtist != artistId && it.track.mainArtist != "?" }
            .map { p -> if (p.track.mainArtist in rel) p.copy(context = p.context + 0.35) else p }
        val sim = mixer.buildBalanced(others, t, size - min(size / 3, own.size), 0.3)
        return order(mixer.interleave(sim, own.take(size / 3)).take(size))
    }

    /**
     * Blend of two artists: a third A, a third B, and a third "bridge" songs whose sound sits between them
     * (vibe midpoint), with artists the related graph links to both first. Interleaved A, bridge, B.
     */
    fun blendArtists(h: History, pool: List<PoolTrack>, a: String, b: String, mixer: VibeMixer, size: Int = 30): List<PoolTrack> {
        if (a == b) return artistRadio(h, pool, a, mixer, size)
        val va = artistVibe(pool, a) ?: return emptyList()
        val vb = artistVibe(pool, b) ?: return emptyList()
        val third = max(3, size / 3)
        fun best(id: String) = pool.filter { it.track.mainArtist == id }.sortedByDescending { it.affinity + it.familiarity + it.context }.take(third)
        val sa = best(a); val sb = best(b)
        val ra = relatedArtists(h, pool, a, 12).toHashSet(); val rb = relatedArtists(h, pool, b, 12).toHashSet()
        val mid = VibeTarget((va.energy + vb.energy) / 2, (va.valence + vb.valence) / 2, (va.mizrahi + vb.mizrahi) / 2, (va.cinematic + vb.cinematic) / 2)
        val bridgePool = pool.filter { it.track.mainArtist != a && it.track.mainArtist != b && it.track.mainArtist != "?" }.map { p ->
            val m = p.track.mainArtist
            val bonus = (if (m in ra) 0.2 else 0.0) + (if (m in rb) 0.2 else 0.0)
            if (bonus == 0.0) p else p.copy(context = p.context + bonus)
        }
        val bridge = mixer.build(bridgePool, mid, third, 0.5)
        val out = ArrayList<PoolTrack>()
        for (i in 0 until max(sa.size, max(sb.size, bridge.size))) {
            sa.getOrNull(i)?.let { out += it }; bridge.getOrNull(i)?.let { out += it }; sb.getOrNull(i)?.let { out += it }
        }
        return out.distinctBy { it.track.id }.take(size)
    }
}
