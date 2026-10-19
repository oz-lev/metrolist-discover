/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.random.Random

/**
 * Auto mixes built from the taste profile, offline (songs already in the database), refreshed when
 * stale. Taste "worlds" are communities of artists found by label propagation on a graph of
 * co-listening (back-to-back plays) and Metrolist's related songs; each is named after its top artist.
 */
object MixBuilder {
    enum class Kind { DAILY, FORGOTTEN, TIME_OF_DAY, WORLD }

    data class Mix(val kind: Kind, val titleHe: String, val titleEn: String, val tracks: List<TrackMeta>)

    fun build(h: History, nowMs: Long, engine: DiscoverEngine = DiscoverEngine(), size: Int = 25): List<Mix> {
        val p = engine.profile(h, nowMs)
        if (p.isColdStart) return emptyList()
        val cands = CandidateBuilder.offline(h, p)
        val favs = p.topTracks(400).mapNotNull { h.tracks[it.first] }
            .map { Candidate(it, setOf(CandidateSource.LIBRARY), it.mainArtist, it.id, p.trackAffinity(it.id)) }
        val out = ArrayList<Mix>()

        // daily: 60 % favourites you have not heard lately + 40 % discoveries, diversified
        val daily = engine.recommend(h, favs.take(150) + cands, nowMs, n = size, exploreRatio = 0.4, random = Random(nowMs / 86_400_000L), profile = p)
        if (daily.size >= 8) out += Mix(Kind.DAILY, "המיקס היומי שלך", "Your daily mix", daily.map { it.track })

        val forgotten = cands.filter { CandidateSource.FORGOTTEN in it.sources }
            .sortedByDescending { p.trackAffinity(it.track.id) }.map { it.track }
        if (forgotten.size >= 8) out += Mix(Kind.FORGOTTEN, "שכחת שאהבת", "Forgotten favorites", diversify(forgotten).take(size))

        val slot = TasteProfile.slotOf(nowMs)
        val hour = slot % 24
        val (he, en) = when (hour) {
            in 5..10 -> "מיקס בוקר" to "Morning mix"
            in 11..16 -> "מיקס צהריים" to "Afternoon mix"
            in 17..21 -> "מיקס ערב" to "Evening mix"
            else -> "מיקס לילה" to "Night mix"
        }
        val tod = favs.map { it to p.hourFit(it.track.mainArtist, slot) + 0.5 * p.trackAffinity(it.track.id) }
            .filter { it.second > 0.2 }.sortedByDescending { it.second }.map { it.first.track }
        if (tod.size >= 8) out += Mix(Kind.TIME_OF_DAY, he, en, diversify(tod).take(size))

        for ((top, artists) in worlds(h, p).take(3)) {
            val name = h.tracks.values.firstOrNull { it.mainArtist == top }?.artistNames?.firstOrNull() ?: continue
            val tr = (favs.map { it.track } + cands.map { it.track })
                .filter { it.mainArtist in artists }.distinctBy { it.id }
                .sortedByDescending { p.trackAffinity(it.id) + 0.5 * p.artistAffinity(it.mainArtist) }
            if (tr.size >= 8) out += Mix(Kind.WORLD, "בסגנון $name", "$name & similar", diversify(tr).take(size))
        }
        return out
    }

    /** Artist communities, biggest (by affinity) first: top artist -> member artists. */
    fun worlds(h: History, p: TasteProfile, maxArtists: Int = 150): List<Pair<String, Set<String>>> {
        val top = p.topArtists(maxArtists).map { it.first }
        val inTop = top.toHashSet()
        val g = HashMap<String, HashMap<String, Double>>()
        fun edge(a: String, b: String, w: Double) {
            if (a == b || a !in inTop || b !in inTop) return
            g.getOrPut(a) { HashMap() }.merge(b, w, Double::plus)
            g.getOrPut(b) { HashMap() }.merge(a, w, Double::plus)
        }
        var prev: PlayEvent? = null
        for (e in h.sortedPlays) {
            val pv = prev
            if (pv != null && e.timestampMs - pv.timestampMs < 20 * 60_000L) {
                val a = h.tracks[pv.trackId]?.mainArtist; val b = h.tracks[e.trackId]?.mainArtist
                if (a != null && b != null) edge(a, b, 1.0)
            }
            prev = e
        }
        for ((s, rel) in h.related) {
            val a = h.tracks[s]?.mainArtist ?: continue
            rel.forEach { r -> h.tracks[r]?.mainArtist?.let { edge(a, it, 0.5) } }
        }
        val label = HashMap<String, String>(); top.forEach { label[it] = it }
        val r = Random(11)
        repeat(15) {
            for (a in top.shuffled(r)) {
                val nb = g[a] ?: continue
                val score = HashMap<String, Double>()
                nb.forEach { (b, w) -> score.merge(label[b]!!, w, Double::plus) }
                score.maxByOrNull { it.value }?.let { label[a] = it.key }
            }
        }
        return top.groupBy { label[it]!! }.values
            .filter { it.size >= 3 }
            .map { members -> members.maxBy { p.artistAffinity(it) } to members.toSet() }
            .sortedByDescending { (_, m) -> m.sumOf { p.artistAffinity(it) } }
    }

    private fun diversify(list: List<TrackMeta>): List<TrackMeta> {
        val out = ArrayList<TrackMeta>(); val rest = list.toMutableList()
        while (rest.isNotEmpty()) {
            val last = out.lastOrNull()?.mainArtist
            val i = rest.indexOfFirst { it.mainArtist != last }.let { if (it < 0) 0 else it }
            out += rest.removeAt(i)
        }
        return out
    }
}
