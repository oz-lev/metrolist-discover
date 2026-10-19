/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Coarse labels the heuristic can attach to a track. */
enum class VibeTag { HEBREW, MIZRAHI, EDM, PHONK, REMIX, SLOWED, SPED_UP, LIVE, ACOUSTIC, SAD, PARTY, CHILL, CINEMATIC, LOFI, RAP, ROCK, MEDLEY }

/**
 * Estimated feel of one track. All values 0..1.
 * energy: calm -> intense. valence: sad -> happy. mizrahi: international/EDM (0) -> Israeli Mizrahi (1).
 * cinematic: how much it sounds like a film score / epic soundtrack.
 */
data class Vibe(
    val energy: Double,
    val valence: Double,
    val mizrahi: Double,
    val cinematic: Double,
    val tags: Set<VibeTag> = emptySet(),
) {
    /** One number for "mood" ordering: low = sad/calm, high = happy/party. */
    val moodScore: Double get() = 0.6 * valence + 0.4 * energy
    val isMizrahi: Boolean get() = mizrahi >= 0.5
    fun distanceTo(t: VibeTarget): Double {
        var d = 0.0; var n = 0.0
        t.energy?.let { d += 1.4 * abs(energy - it); n += 1.4 }
        t.valence?.let { d += 1.0 * abs(valence - it); n += 1.0 }
        t.mizrahi?.let { d += 1.2 * abs(mizrahi - it); n += 1.2 }
        t.cinematic?.let { d += 1.0 * abs(cinematic - it); n += 1.0 }
        val base = if (n == 0.0) 0.0 else d / n
        val tagBonus = if (t.tags.isEmpty()) 0.0 else 0.18 * t.tags.count { it in tags } / t.tags.size
        return max(0.0, base - tagBonus)
    }
}

/** What a mix is aiming for. null = "don't care". */
data class VibeTarget(
    val energy: Double? = null,
    val valence: Double? = null,
    val mizrahi: Double? = null,
    val cinematic: Double? = null,
    val tags: Set<VibeTag> = emptySet(),
)

/** All the knobs of the heuristic in one place (tests and the settings screen read the same object). */
data class VibeConfig(
    val baseEnergy: Double = 0.55,
    val baseValence: Double = 0.55,
    val hebrewMizrahi: Double = 0.72,
    val arabicMizrahi: Double = 0.85,
    val latinMizrahi: Double = 0.12,
    /** weight of the track's own estimate vs. its related-songs neighbourhood */
    val ownWeight: Double = 0.7,
    /** learning rate for skip / full-listen feedback */
    val learnRate: Double = 0.08,
    val maxCorrection: Double = 0.4,
    /** keyword -> (energy delta, valence delta, mizrahi delta, cinematic delta, tag) */
    val keywords: List<KeywordRule> = DEFAULT_KEYWORDS,
    val artists: Map<String, ArtistVibe> = DEFAULT_ARTISTS,
) {
    companion object {
        val DEFAULT_KEYWORDS: List<KeywordRule> = listOf(
            KeywordRule(listOf("phonk", "פונק"), 0.30, -0.10, -0.30, 0.0, VibeTag.PHONK),
            KeywordRule(listOf("remix", "רמיקס", "rmx", " mix)", "edit)", "bootleg", "vip mix"), 0.15, 0.08, -0.05, 0.0, VibeTag.REMIX),
            KeywordRule(listOf("slowed", "reverb", "מואט"), -0.30, -0.15, 0.0, 0.05, VibeTag.SLOWED),
            KeywordRule(listOf("sped up", "speed up", "nightcore", "מואץ"), 0.20, 0.08, 0.0, 0.0, VibeTag.SPED_UP),
            KeywordRule(listOf("live", "לייב", "הופעה", "בהופעה", "concert"), -0.05, 0.02, 0.0, 0.0, VibeTag.LIVE),
            KeywordRule(listOf("acoustic", "אקוסטי", "unplugged", "piano version", "פסנתר"), -0.28, -0.05, 0.0, 0.05, VibeTag.ACOUSTIC),
            KeywordRule(listOf("lofi", "lo-fi", "lo fi"), -0.30, 0.0, -0.10, 0.0, VibeTag.LOFI),
            KeywordRule(listOf("מחרוזת", "medley", "mashup", "מאשאפ"), 0.18, 0.20, 0.10, 0.0, VibeTag.MEDLEY),
            KeywordRule(listOf("sad", "עצוב", "פרידה", "דמעות", "שבור", "געגוע", "כואב", "לבד", "נפרדים", "בוכה", "lonely", "tears", "broken", "goodbye", "miss you"), -0.12, -0.32, 0.0, 0.05, VibeTag.SAD),
            KeywordRule(listOf("party", "מסיבה", "חפלה", "רוקדים", "לרקוד", "dance", "שמח", "שמחה", "חתונה", "קיץ", "summer", "fiesta"), 0.18, 0.28, 0.0, 0.0, VibeTag.PARTY),
            KeywordRule(listOf("chill", "relax", "רגוע", "שקט", "sleep", "לילה טוב", "calm"), -0.25, 0.05, 0.0, 0.0, VibeTag.CHILL),
            KeywordRule(listOf("soundtrack", "ost", "theme", "score", "epic", "פסקול", "cinematic", "trailer", "interstellar", "main title"), 0.0, -0.05, -0.20, 0.55, VibeTag.CINEMATIC),
            KeywordRule(listOf("drill", "trap", "rap", "ראפ", "היפ הופ", "hip hop"), 0.15, -0.05, -0.15, 0.0, VibeTag.RAP),
            KeywordRule(listOf("rock", "metal", "רוק"), 0.20, 0.0, -0.20, 0.0, VibeTag.ROCK),
            KeywordRule(listOf("edm", "house", "techno", "trance", "dubstep", "drop", "festival", "bass boosted"), 0.25, 0.10, -0.30, 0.0, VibeTag.EDM),
        )

        private fun a(e: Double, v: Double, m: Double, c: Double = 0.1, vararg t: VibeTag) = ArtistVibe(e, v, m, c, t.toSet())

        /** A small seed lexicon of public artist names (lower-case); feedback corrects it over time. */
        val DEFAULT_ARTISTS: Map<String, ArtistVibe> = mapOf(
            // Israeli Mizrahi / pop
            "אושר כהן" to a(0.60, 0.55, 0.95, 0.1, VibeTag.MIZRAHI), "osher cohen" to a(0.60, 0.55, 0.95, 0.1, VibeTag.MIZRAHI),
            "אגם בוחבוט" to a(0.68, 0.70, 0.85, 0.1, VibeTag.MIZRAHI), "agam buhbut" to a(0.68, 0.70, 0.85, 0.1, VibeTag.MIZRAHI),
            "עדן בן זקן" to a(0.72, 0.70, 0.95, 0.1, VibeTag.MIZRAHI), "eden ben zaken" to a(0.72, 0.70, 0.95, 0.1, VibeTag.MIZRAHI),
            "עומר אדם" to a(0.72, 0.72, 0.90, 0.1, VibeTag.MIZRAHI), "omer adam" to a(0.72, 0.72, 0.90, 0.1, VibeTag.MIZRAHI),
            "אייל גולן" to a(0.55, 0.50, 1.0, 0.1, VibeTag.MIZRAHI), "eyal golan" to a(0.55, 0.50, 1.0, 0.1, VibeTag.MIZRAHI),
            "משה פרץ" to a(0.62, 0.62, 0.95, 0.1, VibeTag.MIZRAHI), "moshe peretz" to a(0.62, 0.62, 0.95, 0.1, VibeTag.MIZRAHI),
            "שרית חדד" to a(0.62, 0.60, 1.0, 0.1, VibeTag.MIZRAHI), "sarit hadad" to a(0.62, 0.60, 1.0, 0.1, VibeTag.MIZRAHI),
            "ליאור נרקיס" to a(0.70, 0.68, 1.0, 0.1, VibeTag.MIZRAHI), "lior narkis" to a(0.70, 0.68, 1.0, 0.1, VibeTag.MIZRAHI),
            "איתי לוי" to a(0.58, 0.48, 1.0, 0.1, VibeTag.MIZRAHI), "itay levi" to a(0.58, 0.48, 1.0, 0.1, VibeTag.MIZRAHI),
            "פאר טסי" to a(0.66, 0.66, 0.85, 0.1, VibeTag.MIZRAHI), "peer tasi" to a(0.66, 0.66, 0.85, 0.1, VibeTag.MIZRAHI),
            "דודו אהרון" to a(0.70, 0.70, 1.0, 0.1, VibeTag.MIZRAHI), "dudu aharon" to a(0.70, 0.70, 1.0, 0.1, VibeTag.MIZRAHI),
            "סטטיק ובן אל" to a(0.80, 0.85, 0.55, 0.05, VibeTag.PARTY), "static & ben el" to a(0.80, 0.85, 0.55, 0.05, VibeTag.PARTY),
            "נועה קירל" to a(0.80, 0.78, 0.45, 0.05, VibeTag.PARTY), "noa kirel" to a(0.80, 0.78, 0.45, 0.05, VibeTag.PARTY),
            "אנה זק" to a(0.75, 0.75, 0.50, 0.05, VibeTag.PARTY), "anna zak" to a(0.75, 0.75, 0.50, 0.05, VibeTag.PARTY),
            "מור" to a(0.62, 0.55, 0.80, 0.1, VibeTag.MIZRAHI), "mor" to a(0.62, 0.55, 0.80, 0.1, VibeTag.MIZRAHI),
            "רביד פלוטניק" to a(0.55, 0.40, 0.40, 0.1, VibeTag.RAP), "ravid plotnik" to a(0.55, 0.40, 0.40, 0.1, VibeTag.RAP),
            "נס וסטילה" to a(0.75, 0.55, 0.55, 0.05, VibeTag.RAP), "ness & stilla" to a(0.75, 0.55, 0.55, 0.05, VibeTag.RAP),
            "עידן רייכל" to a(0.40, 0.45, 0.45, 0.3), "idan raichel" to a(0.40, 0.45, 0.45, 0.3),
            "שלמה ארצי" to a(0.42, 0.45, 0.35, 0.2), "shlomo artzi" to a(0.42, 0.45, 0.35, 0.2),
            // EDM / phonk / electronic
            "alan walker" to a(0.82, 0.55, 0.0, 0.35, VibeTag.EDM), "marshmello" to a(0.85, 0.75, 0.0, 0.1, VibeTag.EDM),
            "avicii" to a(0.80, 0.78, 0.0, 0.15, VibeTag.EDM), "david guetta" to a(0.86, 0.78, 0.0, 0.05, VibeTag.EDM),
            "martin garrix" to a(0.88, 0.72, 0.0, 0.15, VibeTag.EDM), "calvin harris" to a(0.80, 0.82, 0.0, 0.05, VibeTag.EDM),
            "kygo" to a(0.62, 0.72, 0.0, 0.1, VibeTag.EDM), "the chainsmokers" to a(0.70, 0.65, 0.0, 0.05, VibeTag.EDM),
            "skrillex" to a(0.95, 0.55, 0.0, 0.1, VibeTag.EDM), "tiësto" to a(0.88, 0.75, 0.0, 0.1, VibeTag.EDM), "tiesto" to a(0.88, 0.75, 0.0, 0.1, VibeTag.EDM),
            "infected mushroom" to a(0.90, 0.55, 0.05, 0.2, VibeTag.EDM), "vini vici" to a(0.92, 0.60, 0.05, 0.15, VibeTag.EDM),
            "kordhell" to a(0.95, 0.35, 0.0, 0.1, VibeTag.PHONK), "kslv noh" to a(0.92, 0.35, 0.0, 0.1, VibeTag.PHONK),
            "dvrst" to a(0.90, 0.35, 0.0, 0.1, VibeTag.PHONK), "interworld" to a(0.88, 0.30, 0.0, 0.1, VibeTag.PHONK),
            "playaphonk" to a(0.92, 0.35, 0.0, 0.1, VibeTag.PHONK), "hensonn" to a(0.88, 0.35, 0.0, 0.1, VibeTag.PHONK),
            "kavinsky" to a(0.72, 0.45, 0.0, 0.45, VibeTag.EDM),
            // soundtrack / calm
            "hans zimmer" to a(0.50, 0.35, 0.0, 1.0, VibeTag.CINEMATIC), "ludovico einaudi" to a(0.25, 0.40, 0.0, 0.8, VibeTag.CINEMATIC),
            "two steps from hell" to a(0.80, 0.45, 0.0, 1.0, VibeTag.CINEMATIC), "ramin djawadi" to a(0.50, 0.35, 0.0, 0.95, VibeTag.CINEMATIC),
            "john williams" to a(0.55, 0.55, 0.0, 1.0, VibeTag.CINEMATIC), "max richter" to a(0.25, 0.30, 0.0, 0.85, VibeTag.CINEMATIC),
        )
    }
}

data class KeywordRule(val words: List<String>, val dEnergy: Double, val dValence: Double, val dMizrahi: Double, val dCinematic: Double, val tag: VibeTag)
data class ArtistVibe(val energy: Double, val valence: Double, val mizrahi: Double, val cinematic: Double, val tags: Set<VibeTag> = emptySet())

/** Learned corrections from the user's own skips / full listens, per artist and per track. */
data class VibeCorrections(
    val artistEnergy: Map<String, Double> = emptyMap(),
    val artistValence: Map<String, Double> = emptyMap(),
    val trackEnergy: Map<String, Double> = emptyMap(),
) {
    fun encode(): String = listOf(artistEnergy, artistValence, trackEnergy).joinToString("|") { m ->
        m.entries.joinToString(";") { "${it.key.replace(";", "").replace("|", "").replace("=", "")}=${"%.3f".format(java.util.Locale.ROOT, it.value)}" }
    }

    companion object {
        fun decode(s: String?): VibeCorrections {
            if (s.isNullOrBlank()) return VibeCorrections()
            return runCatching {
                val parts = s.split("|")
                fun map(i: Int): Map<String, Double> = parts.getOrNull(i).orEmpty().split(";").filter { "=" in it }
                    .associate { val k = it.substringBeforeLast("="); k to it.substringAfterLast("=").toDouble() }
                VibeCorrections(map(0), map(1), map(2))
            }.getOrDefault(VibeCorrections())
        }
    }
}

/**
 * The single place where energy / mood are estimated (YouTube Music exposes no BPM or mood data).
 * Signals: artist lexicon + script (Hebrew / Arabic / Latin) + title keywords + duration +
 * Metrolist's related-songs graph (neighbourhood smoothing) + the user's own feedback (corrections).
 */
class VibeEstimator(
    val config: VibeConfig = VibeConfig(),
    var corrections: VibeCorrections = VibeCorrections(),
) {
    private val cache = HashMap<String, Vibe>()

    fun clearCache() = cache.clear()

    /** Estimate from the track alone (no graph). */
    fun own(t: TrackMeta): Vibe {
        cache[t.id]?.let { return it }
        val text = (t.title + " " + t.artistNames.joinToString(" ")).lowercase()
        val tags = HashSet<VibeTag>()
        var e = config.baseEnergy; var v = config.baseValence; var c = 0.1
        val hebrew = text.any { it in '\u0590'..'\u05FF' }
        val arabic = text.any { it in '\u0600'..'\u06FF' }
        var m = when { arabic -> config.arabicMizrahi; hebrew -> config.hebrewMizrahi; else -> config.latinMizrahi }
        if (hebrew) tags += VibeTag.HEBREW
        // artist lexicon: average over credited artists we know
        val known = t.artistNames.mapNotNull { config.artists[it.trim().lowercase()] }
        if (known.isNotEmpty()) {
            e = 0.25 * e + 0.75 * known.map { it.energy }.average()
            v = 0.25 * v + 0.75 * known.map { it.valence }.average()
            m = 0.2 * m + 0.8 * known.map { it.mizrahi }.average()
            c = known.map { it.cinematic }.average()
            known.forEach { tags += it.tags }
        }
        for (k in config.keywords) {
            if (k.words.any { w -> text.contains(w) }) {
                e += k.dEnergy; v += k.dValence; m += k.dMizrahi; c += k.dCinematic; tags += k.tag
            }
        }
        if (t.durationSec in 1..149) e += 0.05
        if (t.durationSec > 420) { c += 0.15; e -= 0.05 }
        if (m >= 0.5 && VibeTag.HEBREW in tags) tags += VibeTag.MIZRAHI
        // feedback
        val a = t.mainArtist
        e += (corrections.artistEnergy[a] ?: 0.0) + (corrections.trackEnergy[t.id] ?: 0.0)
        v += corrections.artistValence[a] ?: 0.0
        val out = Vibe(clamp(e), clamp(v), clamp(m), clamp(c), tags)
        cache[t.id] = out
        return out
    }

    /** Estimate with one hop of related-songs smoothing. */
    fun estimate(t: TrackMeta, tracks: Map<String, TrackMeta>, related: Map<String, List<String>>): Vibe {
        val mine = own(t)
        val nb = related[t.id].orEmpty().take(12).mapNotNull { tracks[it] }.map { own(it) }
        if (nb.isEmpty()) return mine
        val w = config.ownWeight
        return mine.copy(
            energy = clamp(w * mine.energy + (1 - w) * nb.map { it.energy }.average()),
            valence = clamp(w * mine.valence + (1 - w) * nb.map { it.valence }.average()),
            mizrahi = clamp(0.85 * mine.mizrahi + 0.15 * nb.map { it.mizrahi }.average()),
        )
    }

    /**
     * Self-correction. The track was played inside a mix that aimed at [target];
     * skipped = it did not fit, so push its artist's estimate away from the target;
     * fullListen = it fit, so pull it toward the target.
     */
    fun feedback(t: TrackMeta, target: VibeTarget, skipped: Boolean, fullListen: Boolean) {
        val cur = own(t)
        val lr = config.learnRate
        val ae = corrections.artistEnergy.toMutableMap()
        val av = corrections.artistValence.toMutableMap()
        val te = corrections.trackEnergy.toMutableMap()
        val a = t.mainArtist
        target.energy?.let { te0 ->
            val diff = cur.energy - te0
            val delta = when {
                skipped -> lr * (if (diff >= 0) 1.0 else -1.0) * (1.0 - min(1.0, abs(diff)))
                fullListen -> -lr * diff
                else -> 0.0
            }
            ae[a] = clampC((ae[a] ?: 0.0) + delta * 0.5)
            te[t.id] = clampC((te[t.id] ?: 0.0) + delta)
        }
        target.valence?.let { tv ->
            val diff = cur.valence - tv
            val delta = when {
                skipped -> lr * (if (diff >= 0) 1.0 else -1.0) * (1.0 - min(1.0, abs(diff)))
                fullListen -> -lr * diff
                else -> 0.0
            }
            av[a] = clampC((av[a] ?: 0.0) + delta * 0.5)
        }
        corrections = VibeCorrections(ae, av, if (te.size > 4000) te.entries.toList().takeLast(3000).associate { it.key to it.value } else te)
        cache.clear() // an artist correction changes every track of that artist
    }

    private fun clampC(x: Double) = x.coerceIn(-config.maxCorrection, config.maxCorrection)
    private fun clamp(x: Double) = x.coerceIn(0.0, 1.0)
}
