/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeTest {
    private val est = VibeEstimator()
    private fun t(id: String, title: String, artist: String, dur: Int = 200) = TrackMeta(id, title, listOf("a_$artist"), listOf(artist), durationSec = dur)

    @Test fun phonkIsMoreEnergeticThanAcoustic() {
        val phonk = est.own(t("1", "Murder In My Mind (Phonk)", "Kordhell"))
        val acoustic = est.own(t("2", "Someone Like You (Acoustic)", "Some Singer"))
        assertTrue(phonk.energy > acoustic.energy + 0.3)
        assertTrue(VibeTag.PHONK in phonk.tags)
        assertTrue(VibeTag.ACOUSTIC in acoustic.tags)
    }

    @Test fun hebrewScriptAndLexiconMeanMizrahi() {
        val osher = est.own(t("3", "שיר כלשהו", "אושר כהן"))
        val walker = est.own(t("4", "Faded", "Alan Walker"))
        assertTrue(osher.isMizrahi)
        assertFalse(walker.isMizrahi)
        assertTrue(VibeTag.HEBREW in osher.tags)
        assertTrue(VibeTag.EDM in walker.tags)
    }

    @Test fun slowedAndSadLowerTheMood() {
        val base = est.own(t("5", "Song", "X"))
        val sad = est.own(t("6", "פרידה (Slowed + Reverb)", "X"))
        assertTrue(sad.energy < base.energy)
        assertTrue(sad.valence < base.valence)
        assertTrue(VibeTag.SAD in sad.tags && VibeTag.SLOWED in sad.tags)
    }

    @Test fun relatedGraphSmoothsTheEstimate() {
        val unknown = t("7", "Untitled", "Nobody")
        val loud = (1..5).map { t("r$it", "Festival Anthem (Remix) EDM", "Martin Garrix") }
        val tracks = (loud + unknown).associateBy { it.id }
        val smoothed = est.estimate(unknown, tracks, mapOf("7" to loud.map { it.id }))
        assertTrue(smoothed.energy > est.own(unknown).energy)
    }

    @Test fun skipsInsideAnEnergyMixSelfCorrect() {
        val e = VibeEstimator()
        val song = t("8", "Mid tempo song", "Y")
        val before = e.own(song).energy
        // user keeps skipping it inside a high-energy (gym) mix -> the estimate must drift down, away from the target
        repeat(5) { e.feedback(song, VibeTarget(energy = 0.95), skipped = true, fullListen = false) }
        val after = e.own(song).energy
        assertTrue("energy should drop: $before -> $after", after < before - 0.1)
        // corrections survive encode/decode
        val e2 = VibeEstimator(corrections = VibeCorrections.decode(e.corrections.encode()))
        assertEquals(after, e2.own(song).energy, 0.01)
    }

    @Test fun fullListensPullTowardTheTarget() {
        val e = VibeEstimator()
        val song = t("9", "Calm thing (acoustic)", "Z")
        val before = e.own(song).energy
        repeat(6) { e.feedback(song, VibeTarget(energy = 0.9), skipped = false, fullListen = true) }
        assertTrue(e.own(song).energy > before + 0.1)
    }

    @Test fun correctionsAreBounded() {
        val e = VibeEstimator()
        val song = t("10", "x", "W")
        repeat(200) { e.feedback(song, VibeTarget(energy = 1.0), skipped = true, fullListen = false) }
        val v = e.own(song)
        assertTrue(v.energy in 0.0..1.0)
        assertTrue((e.corrections.trackEnergy["10"] ?: 0.0) >= -e.config.maxCorrection - 1e-9)
    }
}
