/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 * Tests for the v2.1 settings logic.
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

class V21Test {
    private fun ts(day: Int, hour: Int, min: Int = 0) = LocalDateTime.of(2026, 10, day, hour, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun track(i: Int, artist: Int = i % 7, sec: Int = 200, he: Boolean = false) =
        TrackMeta("t$i", if (he) "שיר $i" else "Song $i", listOf("a$artist"), listOf(if (he) "אמן $artist" else "Artist $artist"), durationSec = sec)

    private fun pt(i: Int, energy: Double = 0.5, artist: Int = i % 7, sec: Int = 200, he: Boolean = false) =
        PoolTrack(track(i, artist, sec, he), Vibe(energy, 0.5, if (he) 0.8 else 0.1, 0.1), 0.5, 0.5)

    // ---------------------------------------------------------------- morning ramp
    @Test fun morningRampEnergyOnlyRisesInTheFirst20Minutes() {
        val r = Random(4)
        val list = (0 until 30).map { pt(it, r.nextDouble(), sec = 180) }
        val out = MixShaping.morningRamp(list, 20)
        assertEquals(list.size, out.size)
        assertEquals(list.map { it.track.id }.toSet(), out.map { it.track.id }.toSet())
        var acc = 0; var i = 0
        while (acc < 20 * 60) { acc += out[i].track.durationSec; i++ }
        val head = out.take(i)
        for (k in 1 until head.size) assertTrue(head[k].vibe.energy >= head[k - 1].vibe.energy)
        assertEquals(list.drop(i), out.drop(i)) // the rest keeps its order
    }

    @Test fun morningRampOnlyInTheMorning() {
        val list = listOf(pt(1, 0.9), pt(2, 0.1), pt(3, 0.5))
        val o = ShapeOptions(morningRamp = true)
        assertEquals(listOf("t2", "t3", "t1"), MixShaping.apply(list, o, emptySet(), emptyList(), emptySet(), 8).map { it.track.id })
        assertEquals(listOf("t1", "t2", "t3"), MixShaping.apply(list, o, emptySet(), emptyList(), emptySet(), 20).map { it.track.id })
    }

    // ---------------------------------------------------------------- favourite artist guarantee
    @Test fun favouriteArtistAtLeastOnceInEveryWindow() {
        val list = (0 until 40).map { pt(it, artist = 1 + it % 5) }          // artists a1..a5, never a0
        val favs = (100 until 120).map { pt(it, artist = 0) }                 // the favourite: a0
        for (n in listOf(3, 4, 6)) {
            val out = MixShaping.favoriteEvery(list, favs, setOf("a0"), n)
            for (s in 0..out.size - n) assertTrue("n=$n window $s", out.subList(s, s + n).any { it.track.mainArtist == "a0" })
            assertEquals(out.size, out.map { it.track.id }.toSet().size)
            assertTrue(out.map { it.track.id }.containsAll(list.map { it.track.id }))
        }
        assertEquals(list, MixShaping.favoriteEvery(list, favs, setOf("a0"), 0))
    }

    // ---------------------------------------------------------------- never heard / language / length / artist gap
    @Test fun neverHeardModeDropsEverythingAlreadyPlayed() {
        val list = (0 until 10).map { pt(it) }
        val out = MixShaping.filter(list, ShapeOptions(neverHeard = true), setOf("t1", "t2", "t5"))
        assertEquals(7, out.size)
        assertTrue(out.none { it.track.id in setOf("t1", "t2", "t5") })
    }

    @Test fun languageAndLengthFilters() {
        val list = listOf(pt(1, he = true), pt(2), pt(3, sec = 400), pt(4, he = true, sec = 500))
        assertEquals(listOf("t1", "t4"), MixShaping.filter(list, ShapeOptions(lang = LangMode.HEBREW), emptySet()).map { it.track.id })
        assertEquals(listOf("t2", "t3"), MixShaping.filter(list, ShapeOptions(lang = LangMode.ENGLISH), emptySet()).map { it.track.id })
        assertEquals(listOf("t1", "t2"), MixShaping.filter(list, ShapeOptions(maxDurationSec = 300), emptySet()).map { it.track.id })
    }

    @Test fun artistGapKeepsTheSameArtistApart() {
        val list = (0 until 24).map { pt(it, artist = it / 4) } // a0 a0 a0 a0 a1 a1 ...
        val out = MixShaping.artistGap(list, 3)
        assertEquals(list.size, out.size)
        for (i in 1 until out.size) for (k in 1..3) if (i - k >= 0) assertTrue(out[i].track.mainArtist != out[i - k].track.mainArtist)
        assertTrue(ArtistGap.violates(listOf("a1", "a2", "a3"), "a2", 2))
        assertFalse(ArtistGap.violates(listOf("a1", "a2", "a3"), "a1", 2))
    }

    // ---------------------------------------------------------------- secret mode / guest
    @Test fun secretModeAndGuestNeverRecord() {
        assertTrue(Recording.shouldRecord(secretMode = false, guestProfile = false, historyPaused = false))
        assertFalse(Recording.shouldRecord(secretMode = true, guestProfile = false, historyPaused = false))
        assertFalse(Recording.shouldRecord(secretMode = false, guestProfile = true, historyPaused = false))
        assertFalse(Recording.shouldRecord(secretMode = false, guestProfile = false, historyPaused = true))
    }

    // ---------------------------------------------------------------- profiles
    @Test fun profilesIsolateSettingsAndLearning() {
        val kv = HashMap<String, String>()
        fun put(k: String, v: String) { kv[ProfileScope.key(k)] = v }
        fun get(k: String) = kv[ProfileScope.key(k)]
        try {
            ProfileScope.active = "me"; put("v2.boldness", "0.9"); put("tuner.state", "ME"); put("v2.colors", "shared")
            ProfileScope.active = "gym"
            assertNull(get("v2.boldness")); assertNull(get("tuner.state"))
            assertEquals("shared", get("v2.colors"))                 // caches stay global
            put("v2.boldness", "0.1"); put("v21.theme", "PHONK_RED")
            ProfileScope.active = "me"
            assertEquals("0.9", get("v2.boldness")); assertEquals("ME", get("tuner.state")); assertNull(get("v21.theme"))
            assertEquals("0.9", kv["v2.boldness"])                 // the default profile keeps the pre-2.1 keys
            assertEquals("0.1", kv["p.gym.v2.boldness"])
            assertEquals("g.active", ProfileScope.key("g.active", "gym"))
        } finally { ProfileScope.active = "me" }
    }

    @Test fun profileListEncodeDecodeAndEdits() {
        val l = Profiles.PRESETS
        assertEquals(7, l.size)
        assertTrue(l.single { it.id == "guest" }.guest)
        val back = Profiles.decode(Profiles.encode(l))
        assertEquals(l, back)
        assertEquals(l, Profiles.decode(null))
        assertEquals(l, Profiles.delete(l, "me"))                  // the default can never be deleted
        assertEquals(6, Profiles.delete(l, "gym").size)
        assertEquals("ספורט", Profiles.rename(l, "gym", "ספורט", "🏋️").single { it.id == "gym" }.name)
        val id = Profiles.newId(l.map { it.id }, Random(1))
        assertTrue(id !in l.map { it.id })
    }

    @Test fun autoSwitchSchedule() {
        val rules = listOf(AutoRule("work", setOf(7, 1, 2, 3, 4), 9 * 60, 17 * 60), AutoRule("night", (1..7).toSet(), 23 * 60, 6 * 60))
        val ids = setOf("me", "work", "night")
        assertEquals("work", AutoSwitch.pick(rules, ids, 2, 10 * 60))
        assertNull(AutoSwitch.pick(rules, ids, 5, 10 * 60))                // Friday: no work profile
        assertEquals("night", AutoSwitch.pick(rules, ids, 5, 23 * 60 + 30))
        assertEquals("night", AutoSwitch.pick(rules, ids, 6, 2 * 60))      // wraps past midnight
        assertNull(AutoSwitch.pick(rules, setOf("me"), 2, 10 * 60))        // deleted profile is ignored
        // edge triggered
        assertEquals("work", AutoSwitch.decide(null, "work", "me"))
        assertNull(AutoSwitch.decide("work", "work", "party"))             // manual switch inside the window is respected
        assertEquals("me", AutoSwitch.decide("work", null, "work"))        // window over -> back to default
        assertNull(AutoSwitch.decide(null, "me", "me"))
    }

    // ---------------------------------------------------------------- blend
    @Test fun blendMergeSharedFirstNoDuplicates() {
        val mine = TasteFile("עוז", listOf("Artist 1" to 1.0, "Artist 2" to 0.5), (0 until 10).map { track(it, artist = 1 + it % 3) })
        val friend = TasteFile("דני", listOf("artist 1" to 1.0, "Artist 9" to 0.8), (20 until 30).map { track(it, artist = if (it % 2 == 0) 1 else 9) } + track(3, artist = 1))
        val out = Blend.merge(mine, friend, 15)
        assertEquals(15, out.size)
        assertEquals(out.size, out.map { it.id }.toSet().size)
        val sharedCount = out.indexOfFirst { it.artistNames.none { n -> n.equals("Artist 1", true) } }
        assertTrue(sharedCount > 0)
        assertTrue(out.take(sharedCount).all { it.artistNames.first().equals("Artist 1", true) })
        assertTrue(out.any { it.id.removePrefix("t").toInt() >= 20 } && out.any { it.id.removePrefix("t").toInt() < 10 })
        val m = Blend.matchPct(mine, friend)
        assertTrue(m in 1..99)
    }

    @Test fun blendFileRoundTrip() {
        val plays = (0 until 60).map { PlayEvent("t${it % 6}", ts(10, 12) + it * 60_000L, 180_000) }
        val tracks = (0 until 6).associate { "t$it" to track(it, artist = it % 2, he = it % 2 == 0) }
        val f = Blend.export(History(tracks, plays), "עוז", ts(19, 12))
        assertEquals(6, f.tracks.size)
        val back = Blend.decode(Blend.encode(f))
        assertNotNull(back)
        assertEquals(f.name, back!!.name)
        assertEquals(f.tracks, back.tracks)
        assertEquals(f.artists.map { it.first }, back.artists.map { it.first })
        assertNull(Blend.decode("hello"))
    }

    // ---------------------------------------------------------------- auto-EQ
    @Test fun autoEqMapping() {
        assertEquals(AutoEq.Curve.BASS, AutoEq.curveFor(Vibe(0.85, 0.5, 0.0, 0.0, setOf(VibeTag.PHONK))))
        assertEquals(AutoEq.Curve.VOCAL, AutoEq.curveFor(Vibe(0.6, 0.6, 0.9, 0.0, setOf(VibeTag.MIZRAHI))))
        assertEquals(AutoEq.Curve.WARM, AutoEq.curveFor(Vibe(0.4, 0.6, 0.1, 0.0, setOf(VibeTag.ACOUSTIC))))
        assertEquals(AutoEq.Curve.SOFT, AutoEq.curveFor(Vibe(0.2, 0.2, 0.1, 0.0, setOf(VibeTag.SAD))))
        assertEquals(AutoEq.Curve.BRIGHT, AutoEq.curveFor(Vibe(0.5, 0.5, 0.1, 0.8)))
        assertEquals(AutoEq.Curve.FLAT, AutoEq.curveFor(Vibe(0.5, 0.5, 0.1, 0.1)))
        val full = AutoEq.bands(AutoEq.Curve.BASS, 1.0); val half = AutoEq.bands(AutoEq.Curve.BASS, 0.5)
        assertEquals(full.size, half.size)
        assertEquals(full[0].gainDb / 2, half[0].gainDb, 1e-9)
        assertTrue(AutoEq.preamp(full) < 0)
        assertTrue(AutoEq.bands(AutoEq.Curve.FLAT, 1.0).isEmpty())
    }

    // ---------------------------------------------------------------- smart shuffle / queues / A-B / ask music
    @Test fun smartShuffleInsertionRatio() {
        val q = (0 until 30).map { "q$it" }; val recs = (0 until 30).map { "r$it" }
        val out = SmartShuffle.mix(q, recs, 3, Random(1))
        assertEquals(40, out.size)
        assertEquals(10, out.count { it.startsWith("r") })
        assertTrue(out.filter { it.startsWith("q") }.toSet() == q.toSet())
        out.chunked(4).forEach { c -> if (c.size == 4) { assertEquals(1, c.count { it.startsWith("r") }); assertTrue(c.last().startsWith("r")) } }
        assertEquals(30, SmartShuffle.mix(q, recs, 0, Random(1)).size)
    }

    @Test fun multipleQueuesAndHistory() {
        val q = SavedQueue("ערב", 5L, (0 until 3).map { track(it, he = true) }, 1)
        var slots = QueueSlots.put(emptyList(), 2, q)
        assertEquals(QueueSlots.MAX, slots.size)
        assertEquals(q, QueueSlots.decode(QueueSlots.encode(slots[2])))
        assertNull(slots[0])
        slots = QueueSlots.put(slots, 7, q)                       // out of range: ignored
        assertEquals(1, slots.count { it != null })
        var h = emptyList<SavedQueue>()
        repeat(25) { i -> h = QueueSlots.pushHistory(h, SavedQueue("q$i", i.toLong(), listOf(track(i)))) }
        assertEquals(QueueSlots.HISTORY, h.size)
        assertEquals("q24", h.first().name)
        h = QueueSlots.pushHistory(h, SavedQueue("again", 99L, listOf(track(20))))
        assertEquals("again", h.first().name); assertEquals(QueueSlots.HISTORY, h.size)
        assertEquals(1, h.count { it.tracks.first().id == "t20" })
    }

    @Test fun abLoopBounds() {
        assertEquals(AbLoop(10_000, 20_000), AbLoops.normalize(20_000, 10_000, 180_000))
        assertEquals(AbLoop(170_000, 180_000), AbLoops.normalize(170_000, 250_000, 180_000))
        assertNull(AbLoops.normalize(5_000, 5_400, 180_000))
        assertNull(AbLoops.normalize(-5_000, 0, 180_000))
        val l = AbLoop(10_000, 20_000)
        assertTrue(AbLoops.shouldJump(l, 20_000)); assertFalse(AbLoops.shouldJump(l, 19_999)); assertFalse(AbLoops.shouldJump(null, 99_999))
    }

    @Test fun askMusicTextToVibe() {
        val a = AskMusic.parse("שירים עצובים לנסיעה בלילה")
        assertTrue(a.target.valence!! < 0.3)
        assertTrue(a.target.energy!! < 0.55)
        assertTrue("עצוב" in a.matched && "נסיעה" in a.matched && "לילה" in a.matched)
        val b = AskMusic.parse("מזרחית למסיבה")
        assertTrue(b.target.mizrahi!! > 0.8 && b.target.energy!! > 0.8)
        assertEquals(MoodPreset.PARTY, b.preset)
        val c = AskMusic.parse("phonk for the gym 40 min")
        assertEquals(40, c.minutes)
        assertTrue(VibeTag.PHONK in c.target.tags && c.target.energy!! > 0.85)
        assertTrue(AskMusic.parse("xyz").matched.isEmpty())
    }

    // ---------------------------------------------------------------- small rules
    @Test fun seedLockIsReproducible() {
        val pool = (0 until 200).map { pt(it, Random(it).nextDouble()) }
        val a = VibeMixer(SeedLock.random(true, 42, 100)).build(pool, MoodPreset.GYM.target, 20)
        val b = VibeMixer(SeedLock.random(true, 42, 100)).build(pool, MoodPreset.GYM.target, 20)
        val c = VibeMixer(SeedLock.random(true, 42, 101)).build(pool, MoodPreset.GYM.target, 20)
        assertEquals(a.map { it.track.id }, b.map { it.track.id })
        assertTrue(a.map { it.track.id } != c.map { it.track.id })
    }

    @Test fun weekdayMoodAndDaylist() {
        val m = mapOf(5 to MoodPreset.MIZRAHI)
        assertEquals(MoodPreset.MIZRAHI, DailyPlan.presetFor(5, 9, m, null))
        assertEquals(MoodPreset.GYM, DailyPlan.presetFor(2, 9, m, MoodPreset.GYM))
        assertEquals(MoodPreset.WAKE_UP, DailyPlan.presetFor(2, 9, m, null))
        assertEquals(m, DailyPlan.decodeWeekday(DailyPlan.encodeWeekday(m)))
        assertTrue(DailyPlan.daylistTitle(1, 20, MoodPreset.CHILL).startsWith("יום שני בערב"))
        assertTrue(DailyPlan.daylistTitle(6, 9, MoodPreset.CHILL).startsWith("שבת בוקר"))
    }

    @Test fun sleepFadeBubbleAutoLike() {
        assertEquals(1f, SleepFade.volumeAt(300_000, 120_000)); assertEquals(0f, SleepFade.volumeAt(0, 120_000))
        assertTrue(SleepFade.volumeAt(60_000, 120_000) in 0.2f..0.5f)
        assertTrue(SleepFade.volumeAt(30_000, 120_000) < SleepFade.volumeAt(90_000, 120_000))
        assertTrue(BubbleGate.shouldShow(BubbleFreq.EVERY_3RD, 6, 0, 0)); assertFalse(BubbleGate.shouldShow(BubbleFreq.EVERY_3RD, 7, 0, 0))
        assertFalse(BubbleGate.shouldShow(BubbleFreq.DAILY, 1, 10, 10)); assertTrue(BubbleGate.shouldShow(BubbleFreq.DAILY, 1, 9, 10))
        assertFalse(BubbleGate.shouldShow(BubbleFreq.NEVER, 0, 0, 1))
        assertTrue(AutoLike.shouldLike(2, thisWasFull = true, alreadyLiked = false))
        assertFalse(AutoLike.shouldLike(1, thisWasFull = true, alreadyLiked = false))
        assertFalse(AutoLike.shouldLike(5, thisWasFull = false, alreadyLiked = false))
        assertFalse(AutoLike.shouldLike(5, thisWasFull = true, alreadyLiked = true))
    }

    @Test fun dailyChallengeReplayAndStats() {
        val tracks = (0 until 10).associate { "t$it" to track(it, artist = it) }
        val plays = listOf(PlayEvent("t0", ts(10, 12), 180_000), PlayEvent("t1", ts(10, 13), 180_000), PlayEvent("t0", ts(11, 12), 180_000),
            PlayEvent("t2", ts(17, 12), 180_000), PlayEvent("t3", ts(18, 12), 180_000), PlayEvent("t4", ts(19, 9), 180_000), PlayEvent("t5", ts(19, 10), 5_000))
        val h = History(tracks, plays)
        val st = DailyChallenge.compute(h, ts(19, 20))
        assertTrue(st.doneToday); assertEquals("Artist 4", st.newArtistToday); assertEquals(3, st.streak); assertEquals(3, st.totalDone)
        assertFalse(DailyChallenge.compute(h, ts(20, 20)).doneToday)
        val top = Replay.top(h, ts(1, 0), ts(31, 0))
        assertEquals("t0", top.first().track.id); assertEquals(2, top.first().plays)
        assertEquals(6, top.sumOf { it.plays })   // the 5 s skip does not count
        val clock = TasteStats.clock(h, ts(19, 20), 30)
        assertEquals(4, clock[12]); assertEquals(6, clock.sum())
        assertTrue(TasteStats.obscurity(h) in 0..100)
        assertEquals("🎧 משהו חדש בשבילך: X", DjIntro.line("X", 0, null, 0))
        assertNull(DjIntro.line("X", 3, ts(18, 0), ts(19, 0)))
        assertTrue(DailyPick.index(12345, 7) in 0..6)
    }
}
