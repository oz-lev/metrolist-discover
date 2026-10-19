/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

/** One track as the engine sees it. Times are epoch millis. */
data class TrackMeta(
    val id: String,
    val title: String,
    val artistIds: List<String>,
    val artistNames: List<String> = emptyList(),
    val albumId: String? = null,
    val durationSec: Int = -1,
    val thumbnailUrl: String? = null,
) {
    val mainArtist: String get() = artistIds.firstOrNull() ?: "?"
    val artistLabel: String get() = artistNames.joinToString(", ").ifBlank { mainArtist }
}

/**
 * A listening session for one track. timestampMs is the local wall-clock time encoded as UTC
 * millis (that is how Metrolist stores Event.timestamp), so hour-of-day reads straight off it.
 */
data class PlayEvent(
    val trackId: String,
    val timestampMs: Long,
    val playedMs: Long,
    val durationMs: Long = -1,
    val fromDiscover: Boolean = false,
)

enum class SignalKind { LIKE, PLAYLIST_ADD, DOWNLOAD, LIBRARY_ADD }

/** An explicit action on a track. timestampMs may be null when the source table keeps no date. */
data class ExplicitSignal(val trackId: String, val kind: SignalKind, val timestampMs: Long?)

/** Everything the engine learns from. Built from Metrolist's own Room tables (+ discover.db). */
data class History(
    val tracks: Map<String, TrackMeta>,
    val plays: List<PlayEvent>,
    val signals: List<ExplicitSignal> = emptyList(),
    /** Metrolist's related_song_map: song -> related songs (YouTube Music "related"). */
    val related: Map<String, List<String>> = emptyMap(),
) {
    val sortedPlays: List<PlayEvent> by lazy { plays.sortedBy { it.timestampMs } }

    fun before(cutMs: Long): History = copy(
        plays = plays.filter { it.timestampMs < cutMs },
        signals = signals.filter { (it.timestampMs ?: Long.MIN_VALUE) < cutMs },
    )

    fun duration(e: PlayEvent): Long =
        if (e.durationMs > 0) e.durationMs else (tracks[e.trackId]?.durationSec ?: -1).toLong() * 1000L
}

enum class CandidateSource(val prior: Double) {
    RADIO(0.6), RELATED_SONG(0.55), RELATED_ARTIST(0.5), ARTIST_TOP(0.45),
    FORGOTTEN(0.7), EXPLORE(0.2), LIBRARY(0.35),
}

/** A track proposed by some source. seedTrack/seedArtist say why it was proposed. */
data class Candidate(
    val track: TrackMeta,
    val sources: Set<CandidateSource>,
    val seedArtistId: String? = null,
    val seedTrackId: String? = null,
    /** Affinity (0..1) of the seed that produced this candidate. */
    val seedAffinity: Double = 0.0,
) {
    fun merge(o: Candidate): Candidate = Candidate(
        track = if (track.artistIds.isEmpty()) o.track else track,
        sources = sources + o.sources,
        seedArtistId = if (o.seedAffinity > seedAffinity) o.seedArtistId else seedArtistId,
        seedTrackId = if (o.seedAffinity > seedAffinity) o.seedTrackId else seedTrackId,
        seedAffinity = maxOf(seedAffinity, o.seedAffinity),
    )
}

enum class Slot { EXPLOIT, EXPLORE }

enum class Reason { BECAUSE_YOU_PLAY, FORGOTTEN_FAVORITE, NEW_FROM_ARTIST, SIMILAR_ARTIST, EXPLORE, FITS_THIS_HOUR }

/** The feature values a score was built from; also what the self-tuner learns on. */
data class Features(
    val similarity: Double,
    val timeOfDay: Double,
    val novelty: Double,
    val skipPenalty: Double,
    val overRepresentation: Double,
    val sourcePrior: Double,
) {
    fun toArray() = doubleArrayOf(similarity, timeOfDay, novelty, -skipPenalty, -overRepresentation, sourcePrior)
    fun encode(): String = toArray().joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) }

    companion object {
        fun decode(s: String): Features? = runCatching {
            val a = s.split(",").map { it.toDouble() }
            Features(a[0], a[1], a[2], -a[3], -a[4], a[5])
        }.getOrNull()
    }
}

data class Recommendation(
    val track: TrackMeta,
    val score: Double,
    val features: Features,
    val slot: Slot,
    val reason: Reason,
    val seedLabel: String? = null,
)
