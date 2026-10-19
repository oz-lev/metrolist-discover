/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.random.Random

/**
 * Candidate sources that need no network: they read Metrolist's own tables
 * (related_song_map, songs/artists already in the database, play history).
 * The app adds the online sources (YouTube Music radio + related artists) on top: see DiscoverRepository.
 */
object CandidateBuilder {
    data class Limits(
        val seedTracks: Int = 60,
        val seedArtists: Int = 50,
        val perArtist: Int = 40,
        val explore: Int = 60,
    )

    fun offline(history: History, p: TasteProfile, limits: Limits = Limits(), random: Random = Random(7)): List<Candidate> {
        val out = HashMap<String, Candidate>()
        fun put(c: Candidate) { out.merge(c.track.id, c) { a, b -> a.merge(b) } }
        val tracks = history.tracks

        // 1) forgotten favourites: loved before (top fifth of this user's track affinities), silent for long
        val pos = p.trackAff.values.filter { it > 0 }.sorted()
        val loveCut = TasteProfile.quantile(pos, 0.8)
        for ((id, v) in p.trackAff) {
            if (v < loveCut || v <= 0) continue
            val since = p.daysSince(id) ?: continue
            if (since >= p.thresholds.forgottenAfterDays) tracks[id]?.let {
                put(Candidate(it, setOf(CandidateSource.FORGOTTEN), it.mainArtist, id, p.trackAffinity(id)))
            }
        }

        // 2) related songs of the strongest seeds (short-term first, then long-term)
        val seeds = p.topTracks(limits.seedTracks * 2)
            .sortedByDescending { (id, a) -> a + 0.5 * (p.artistShort[tracks[id]?.mainArtist] ?: 0.0).coerceAtMost(1.0) }
            .take(limits.seedTracks)
        for ((seed, aff) in seeds) {
            history.related[seed]?.forEach { rid ->
                tracks[rid]?.let { put(Candidate(it, setOf(CandidateSource.RELATED_SONG), tracks[seed]?.mainArtist, seed, aff)) }
            }
        }

        // 3) other songs of top artists that are already known to the database
        val byArtist = HashMap<String, MutableList<TrackMeta>>()
        for (t in tracks.values) t.artistIds.forEach { byArtist.getOrPut(it) { ArrayList() }.add(t) }
        val topArtists = p.topArtists(limits.seedArtists)
        for ((a, aff) in topArtists) {
            byArtist[a].orEmpty().asSequence()
                .sortedBy { p.trackPlayCount[it.id] ?: 0 }
                .take(limits.perArtist)
                .forEach { put(Candidate(it, setOf(CandidateSource.ARTIST_TOP), a, null, aff)) }
        }

        // 4) exploration: artists two hops away on the related graph, plus a few random unknowns
        val known = p.artistLong.keys
        val twoHop = LinkedHashSet<String>()
        for ((seed, _) in seeds.take(10)) {
            history.related[seed].orEmpty().forEach { r -> history.related[r].orEmpty().forEach { twoHop += it } }
        }
        twoHop.mapNotNull { tracks[it] }.filter { it.artistIds.isNotEmpty() && it.mainArtist !in known }.shuffled(random)
            .take(limits.explore * 2 / 3)
            .forEach { put(Candidate(it, setOf(CandidateSource.EXPLORE), null, null, 0.1)) }
        val unknownPool = tracks.values.filter { it.artistIds.isNotEmpty() && it.mainArtist !in known }
        if (unknownPool.isNotEmpty()) {
            repeat(limits.explore / 3) { put(Candidate(unknownPool.random(random), setOf(CandidateSource.EXPLORE))) }
        }

        // 5) library / playlist / downloads that were barely played
        val explicit = history.signals.map { it.trackId }.toHashSet()
        explicit.forEach { id ->
            if ((p.trackPlayCount[id] ?: 0) <= 1) tracks[id]?.let {
                put(Candidate(it, setOf(CandidateSource.LIBRARY), it.mainArtist, id, 0.3))
            }
        }
        return out.values.toList()
    }
}
