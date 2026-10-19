/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.math.max
import kotlin.random.Random

/**
 * Deterministic synthetic listening histories for tests and the demo report.
 * A small catalogue of genres/artists/tracks with a related-songs graph (like YouTube's), and
 * personas whose taste drifts and who listen at genre-specific hours, skip what they dislike,
 * like/download favourites and discover new songs mostly through related artists.
 */
object SyntheticWorld {
    val GENRES = listOf("phonk", "israeli-pop", "mizrahi", "edm", "rock", "hiphop", "lofi", "classical")

    data class Persona(val name: String, val genrePref: Map<String, Double>, val genreHours: Map<String, IntRange>, val skipiness: Double)

    val PERSONAS = listOf(
        Persona("phonk-night", mapOf("phonk" to 1.0, "edm" to 0.6, "hiphop" to 0.4), mapOf("phonk" to 20..27, "edm" to 16..22, "hiphop" to 12..18), 0.25),
        Persona("israeli-pop", mapOf("israeli-pop" to 1.0, "mizrahi" to 0.8, "edm" to 0.3), mapOf("israeli-pop" to 7..12, "mizrahi" to 17..23, "edm" to 21..25), 0.2),
        Persona("oz-like-mix", mapOf("mizrahi" to 0.9, "edm" to 0.9, "israeli-pop" to 0.6, "phonk" to 0.5), mapOf("mizrahi" to 9..15, "edm" to 18..24, "israeli-pop" to 12..19, "phonk" to 22..27), 0.3),
    )

    class World(val tracks: Map<String, TrackMeta>, val genreOf: Map<String, String>, val related: Map<String, List<String>>, val artistsByGenre: Map<String, List<String>>, val tracksByArtist: Map<String, List<String>>)

    fun world(seed: Int = 42, artistsPerGenre: Int = 14, tracksPerArtist: Int = 14): World {
        val r = Random(seed)
        val tracks = LinkedHashMap<String, TrackMeta>()
        val genreOf = HashMap<String, String>()
        val byGenre = LinkedHashMap<String, MutableList<String>>()
        val byArtist = LinkedHashMap<String, MutableList<String>>()
        for (g in GENRES) for (a in 0 until artistsPerGenre) {
            val artist = "$g-artist-$a"
            byGenre.getOrPut(g) { ArrayList() } += artist
            genreOf[artist] = g
            for (t in 0 until tracksPerArtist) {
                val id = "$artist-t$t"
                val feat = if (r.nextDouble() < 0.1) listOf(artist, "$g-artist-${r.nextInt(artistsPerGenre)}").distinct() else listOf(artist)
                tracks[id] = TrackMeta(id, "$g song ${a}_$t", feat, feat.map { it.replace("-artist-", " #") }, "$artist-album-${t / 7}", 150 + r.nextInt(120))
                byArtist.getOrPut(artist) { ArrayList() } += id
            }
        }
        // artist neighbourhood: same genre mostly, sometimes a neighbouring genre
        val artistRelated = HashMap<String, List<String>>()
        for ((g, list) in byGenre) for ((i, a) in list.withIndex()) {
            val near = (1..4).map { list[(i + it) % list.size] } + (1..2).map { list[(i - it + list.size) % list.size] }
            val gi = GENRES.indexOf(g)
            val cross = byGenre[GENRES[(gi + 1) % GENRES.size]]!![i % artistsPerGenre]
            artistRelated[a] = near + cross
        }
        val related = HashMap<String, List<String>>()
        for ((id, t) in tracks) {
            val a = t.mainArtist
            val pool = byArtist[a]!!.filter { it != id }.shuffled(r).take(5) +
                artistRelated[a]!!.flatMap { byArtist[it]!!.shuffled(r).take(2) }
            related[id] = pool.shuffled(r).take(18)
        }
        return World(tracks, genreOf, related, byGenre, byArtist)
    }

    fun history(world: World, persona: Persona, days: Int = 120, seed: Int = 1, endMs: Long = 1_790_000_000_000L): History {
        val r = Random(seed * 7919 + persona.name.hashCode())
        val start = endMs - days * 86_400_000L
        val plays = ArrayList<PlayEvent>()
        val signals = ArrayList<ExplicitSignal>()
        // the persona's favourite artists, growing over time via related artists
        val favs = LinkedHashMap<String, Double>()
        for ((g, pref) in persona.genrePref) world.artistsByGenre[g]!!.shuffled(r).take(max(1, (pref * 3).toInt())).forEach { favs[it] = pref }
        val favTracks = LinkedHashMap<String, Double>()
        for (day in 0 until days) {
            // drift: occasionally adopt a related artist of a favourite in a liked genre
            if (r.nextDouble() < 0.12) {
                val base = favs.keys.random(r)
                val cand = world.related[world.tracksByArtist[base]!!.random(r)]!!.map { world.tracks[it]!!.mainArtist }.random(r)
                val pref = persona.genrePref[world.genreOf[cand]] ?: 0.0
                if (pref > 0.2) favs.putIfAbsent(cand, pref * 0.8)
            }
            val sessions = 1 + r.nextInt(3)
            repeat(sessions) {
                val genre = weighted(persona.genrePref, r)
                val hrs = persona.genreHours[genre] ?: 8..22
                val hour = hrs.first + r.nextInt(hrs.last - hrs.first + 1)
                var ts = start + day * 86_400_000L + hour * 3_600_000L + r.nextInt(3_600_000)
                var current: String? = null
                repeat(6 + r.nextInt(10)) {
                    val roll = r.nextDouble()
                    val id = when {
                        roll < 0.45 && favTracks.isNotEmpty() -> weighted(favTracks, r)
                        roll < 0.75 && current != null -> world.related[current]!!.random(r)
                        else -> {
                            val inGenre = favs.keys.filter { world.genreOf[it] == genre }.ifEmpty { favs.keys.toList() }
                            world.tracksByArtist[inGenre.random(r)]!!.random(r)
                        }
                    }
                    val t = world.tracks[id]!!
                    val pref = (persona.genrePref[world.genreOf[t.mainArtist]] ?: 0.0) * (if (t.mainArtist in favs) 1.2 else 0.8)
                    val pSkip = (persona.skipiness + 0.6 * (1.0 - pref)).coerceIn(0.03, 0.95)
                    val dur = t.durationSec * 1000L
                    val played = if (r.nextDouble() < pSkip) (5_000 + r.nextInt(25_000)).toLong().coerceAtMost(dur)
                    else if (r.nextDouble() < 0.8) dur else (dur * (0.4 + 0.5 * r.nextDouble())).toLong()
                    plays += PlayEvent(id, ts, played, dur)
                    if (played >= dur * 0.8) {
                        favTracks.merge(id, pref * 0.5, Double::plus)
                        if (r.nextDouble() < 0.04 * pref) signals += ExplicitSignal(id, SignalKind.LIKE, ts)
                        if (r.nextDouble() < 0.03 * pref) signals += ExplicitSignal(id, SignalKind.DOWNLOAD, ts)
                        if (r.nextDouble() < 0.02 * pref) signals += ExplicitSignal(id, SignalKind.PLAYLIST_ADD, ts)
                        if (r.nextDouble() < 0.10) { // repeat
                            ts += played + 2_000; plays += PlayEvent(id, ts, dur, dur)
                        }
                    }
                    ts += played + 1_000
                    current = id
                }
            }
        }
        return History(world.tracks, plays, signals, world.related)
    }

    private fun <K> weighted(m: Map<K, Double>, r: Random): K {
        val total = m.values.sum()
        var x = r.nextDouble() * total
        for ((k, v) in m) { x -= v; if (x <= 0) return k }
        return m.keys.last()
    }
}
