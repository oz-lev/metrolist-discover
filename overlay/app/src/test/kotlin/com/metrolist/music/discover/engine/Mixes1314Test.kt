package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 13.7.0.14: listening context, feedback, new mixes, radio, blend, the mix builder and "why this song?". */
class Mixes1314Test {
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val now = 200 * day + 18 * hour
    private fun t(id: String, artist: String, title: String = "song $id", name: String = artist) = TrackMeta(id, title, listOf(artist), listOf(name), null, 200)
    private fun play(id: String, ts: Long, ms: Long = 190_000) = PlayEvent(id, ts, ms, 200_000)
    private fun pt(tm: TrackMeta, e: Double = 0.5, fam: Double = 0.5, aff: Double = 0.5, v: Double = 0.5) = PoolTrack(tm, Vibe(e, v, 0.3, 0.1), fam, aff)

    /** 8 artists x 8 songs heard over 90 days at 8:00-17:00, 10 never-heard artists, 2 Hebrew artists. */
    private fun world(): Pair<History, List<PoolTrack>> {
        val tracks = HashMap<String, TrackMeta>(); val plays = ArrayList<PlayEvent>()
        for (a in 0 until 8) for (k in 0 until 8) tracks["h${a}_$k"] = t("h${a}_$k", "art$a")
        for (a in 0 until 10) for (k in 0 until 4) tracks["n${a}_$k"] = t("n${a}_$k", "new$a")
        for (a in 0 until 2) for (k in 0 until 10) tracks["he${a}_$k"] = t("he${a}_$k", "heb$a", "שיר $k", "זמר $a")
        val r = Random(5)
        for (d in 0 until 90) for (j in 0 until 10) plays += play("h${r.nextInt(8)}_${r.nextInt(8)}", now - (90 - d) * day + (8 + j - 18) * hour)
        for (d in 0 until 40) plays += play("he${d % 2}_${d % 10}", now - (60 - d) * day)
        val related = mapOf("h0_0" to listOf("n0_0", "n1_0", "h1_0"), "n0_0" to listOf("n0_1"))
        val h = History(tracks, plays, emptyList(), related)
        val played = h.plays.map { it.trackId }.toSet()
        val pl = tracks.values.map { tm -> PoolTrack(tm, Vibe(r.nextDouble(), r.nextDouble(), 0.4, 0.1), if (tm.id in played) 0.6 else 0.0, 0.5) }
        return h to pl
    }

    // ---------------- A: listening context
    @Test fun contextOffChangesNothing() {
        val (h, pl) = world()
        val b = MixContext.boosts(h, now, ContextConfig.OFF)
        assertTrue(b.values.all { it == 0.0 })
        assertEquals(pl, MixContext.apply(pl, b, Feedback(), ContextConfig.OFF))
    }

    @Test fun momentumLiftsWhatYouAreIntoNowAndCooldownHoldsWhatYouJustHeard() {
        val tr = mapOf("hot" to t("hot", "a"), "old" to t("old", "b"), "just" to t("just", "c"))
        val plays = (1..6).map { play("hot", now - it * day) } + (1..6).map { play("old", now - (100 + it) * day) } + listOf(play("just", now - hour))
        val b = MixContext.boosts(History(tr, plays), now, ContextConfig())
        assertTrue((b["hot"] ?: 0.0) > 0.15)
        assertTrue((b["hot"] ?: 0.0) > (b["old"] ?: 0.0))
        assertTrue((b["old"] ?: 0.0) < 0.0) // looped long ago: old taste fades a little
        // heard an hour ago: the cooldown outweighs one listen's momentum
        assertTrue((b["just"] ?: 0.0) < 0.0)
    }

    @Test fun earlySkipsAreNotMomentum() {
        val tr = mapOf("s" to t("s", "a"))
        val plays = (1..8).map { play("s", now - it * day, ms = 10_000) }
        assertTrue((MixContext.boosts(History(tr, plays), now, ContextConfig())["s"] ?: 0.0) <= 0.0)
    }

    @Test fun hourFitPrefersSongsOfThisTimeOfDay() {
        val tr = mapOf("eve" to t("eve", "a"), "morn" to t("morn", "b"))
        val plays = (1..6).map { play("eve", 200 * day - it * day + 18 * hour) } + (1..6).map { play("morn", 200 * day - it * day + 7 * hour) }
        val cfg = ContextConfig.OFF.copy(hourWeight = 0.2)
        val b = MixContext.boosts(History(tr, plays), now, cfg)
        assertTrue((b["eve"] ?: 0.0) > 0.0)
        assertTrue((b["morn"] ?: 0.0) < 0.0)
    }

    @Test fun artistCooldownWhenOneArtistFilledTheLastTwoDays() {
        val tr = (0 until 12).associate { "x$it" to t("x$it", if (it < 9) "loud" else "q$it") }
        val plays = (0 until 12).map { play("x$it", now - (it + 1) * hour * 3) }
        val b = MixContext.boosts(History(tr, plays), now, ContextConfig.OFF.copy(artistCooldownSlope = 1.0))
        assertTrue((b["x0"] ?: 0.0) < 0.0)
        assertEquals(0.0, b["x10"] ?: 0.0, 1e-9)
    }

    @Test fun feedbackRemovesBlockedAndPushesDownLessLikeThis() {
        val (h, pl) = world()
        val fb = Feedback(hiddenSongs = setOf("h0_0"), hiddenArtists = setOf("art1"), lessArtists = mapOf("art2" to 2))
        val out = MixContext.apply(pl, MixContext.boosts(h, now, ContextConfig.OFF.copy(lessArtistWeight = 0.25), fb), fb, ContextConfig.OFF.copy(lessArtistWeight = 0.25))
        assertTrue(out.none { it.track.id == "h0_0" || it.track.mainArtist == "art1" })
        assertTrue(out.filter { it.track.mainArtist == "art2" }.all { it.context <= -0.5 + 1e-9 })
        // an online song (not in the history) of a "less" artist is pushed down too
        val online = MixContext.apply(listOf(pt(t("web1", "art2"))), emptyMap(), fb)
        assertTrue(online.single().context < 0.0)
        // a mix built from the result never plays the blocked artist
        val mix = VibeMixer(Random(1)).buildBalanced(out, VibeTarget(energy = 0.5), 40, 0.3)
        assertTrue(mix.none { it.track.mainArtist == "art1" })
    }

    @Test fun lessLikeThisIsStoredAndCapped() {
        var f = Feedback()
        repeat(5) { f = f.withLess("a=b") }
        assertEquals(3, f.lessArtists["a=b"])
        assertEquals(mapOf("a=b" to 3, "z" to 1), Feedback.decodeLess(Feedback.encodeLess(f.lessArtists + ("z" to 1)) + "\nbroken\n=4"))
    }

    @Test fun contextReachesTheMixerScore() {
        val m = VibeMixer(Random(1))
        val a = pt(t("a", "x")); val b = a.copy(context = 0.5)
        // same track, same target: the context difference shows up in the score (noise <= 0.06)
        assertTrue(m.score(b, VibeTarget(energy = 0.5), 0.5) - m.score(a, VibeTarget(energy = 0.5), 0.5) > 0.4)
    }

    // ---------------- B: new mixes
    @Test fun onRepeatIsWhatYouLoopNow() {
        val tr = (0 until 12).associate { "s$it" to t("s$it", "a${it % 6}") }
        val plays = (0 until 10).flatMap { i -> (0 until 3 + i % 3).map { k -> play("s$i", now - (k + 1) * day - i * hour) } } + (1..5).map { play("s11", now - (40 + it) * day) }
        val h = History(tr, plays)
        val m = MoreMixes.onRepeat(h, tr.values.map { pt(it) }, now)
        assertNotNull(m)
        assertTrue(m!!.tracks.size >= 8)
        assertTrue(m.tracks.none { it.track.id == "s11" }) // looped 40+ days ago is not "now"
        assertEquals(PersonalKind.ON_REPEAT, m.kind)
    }

    @Test fun deepCutsAreBarelyPlayedSongsOfLovedArtists() {
        val (h, _) = world()
        val tracks = HashMap(h.tracks)
        for (a in 0 until 4) for (k in 0 until 4) tracks["deep${a}_$k"] = t("deep${a}_$k", "art$a")
        val h2 = h.copy(tracks = tracks)
        val pool = tracks.values.map { pt(it, fam = 0.0) }
        val m = MoreMixes.deepCuts(h2, pool, now, VibeMixer(Random(2)))
        assertNotNull(m)
        val counts = h2.plays.filter { it.playedMs >= 30_000 }.groupingBy { it.trackId }.eachCount()
        assertTrue(m!!.tracks.all { (counts[it.track.id] ?: 0) <= 1 && it.track.mainArtist.startsWith("art") })
        assertTrue(m.tracks.map { it.track.mainArtist }.distinct().size >= 3)
    }

    @Test fun dayPartMixUsesSongsOfThatPartOfTheDay() {
        val tr = (0 until 30).associate { "s$it" to t("s$it", "a$it") }
        val plays = ArrayList<PlayEvent>()
        for (d in 1..20) for (i in 0 until 10) plays += play("s$i", 200 * day - d * day + 7 * hour) // morning songs
        for (d in 1..20) for (i in 10 until 20) plays += play("s$i", 200 * day - d * day + 19 * hour) // evening songs
        val h = History(tr, plays)
        val pool = tr.values.map { pt(it, fam = if (it.id.removePrefix("s").toInt() < 20) 0.6 else 0.0) }
        val m = MoreMixes.dayPartMix(h, pool, DayPart.MORNING, VibeMixer(Random(3)))!!
        val ids = m.tracks.map { it.track.id.removePrefix("s").toInt() }
        assertTrue(ids.none { it in 10 until 20 })
        assertTrue(ids.count { it < 10 } >= 5)
        assertEquals(DayPart.NIGHT, DayPart.of(2)); assertEquals(DayPart.EVENING, DayPart.of(18)); assertEquals(DayPart.MORNING, DayPart.of(5))
    }

    @Test fun throwbackPicksAMonthAtLeastThreeMonthsBackAndSkipsWhatYouStillPlay() {
        val tr = (0 until 40).associate { "s$it" to t("s$it", "a${it % 10}") }
        val plays = ArrayList<PlayEvent>()
        val june = DayClock.tsOf(java.time.LocalDate.of(2026, 6, 10))
        for (i in 0 until 20) repeat(3) { plays += play("s$i", june + it * day + i * hour) }
        plays += play("s0", DayClock.tsOf(java.time.LocalDate.of(2026, 11, 1)))
        val nowTs = DayClock.tsOf(java.time.LocalDate.of(2026, 11, 10)) + 18 * hour
        val m = MoreMixes.throwback(History(tr, plays), tr.values.map { pt(it) }, nowTs)
        assertNotNull(m)
        assertTrue(m!!.titleHe.contains("יוני 2026"))
        assertTrue(m.tracks.none { it.track.id == "s0" })
        // a month only 1 month back is not a throwback
        assertNull(MoreMixes.throwback(History(tr, plays), tr.values.map { pt(it) }, june + 40 * day))
    }

    @Test fun languageMixesSplitHebrewFromTheRest() {
        val (h, pl) = world()
        val he = MoreMixes.languageMix(h, pl, now, true, VibeMixer(Random(1)))!!
        val fo = MoreMixes.languageMix(h, pl, now, false, VibeMixer(Random(1)))!!
        assertTrue(he.tracks.all { Lang.isHebrew(it.track) })
        assertTrue(fo.tracks.none { Lang.isHebrew(it.track) })
        assertTrue(Lang.isHebrew(t("x", "a", "Love", "עומר אדם")))
        assertFalse(Lang.isHebrew(t("x", "a", "Love (Remix) 2024", "Alan Walker")))
    }

    @Test fun todayLeadsWithOnRepeatAndThisPartOfTheDay() {
        val (h, pl) = world()
        val all = PersonalMixes.all(h, pl, now, VibeMixer(Random(1))) + MoreMixes.all(h, pl, now, 10, VibeMixer(Random(1)))
        val kinds = all.map { it.kind }.toSet()
        assertTrue(PersonalKind.ON_REPEAT in kinds)
        assertTrue(PersonalKind.LANGUAGE in kinds)
        val today = PersonalMixes.today(all, 300, 10, show = 9)
        assertTrue(today.size <= 9); assertEquals(today.size, today.map { it.key }.toSet().size)
        assertTrue(today.take(3).any { it.kind == PersonalKind.ON_REPEAT })
        all.forEach { assertTrue(it.tracks.size >= PersonalMixes.MIN_SIZE) }
        // sparse data: nothing throws, nothing too small
        assertTrue(MoreMixes.all(History(emptyMap(), emptyList()), emptyList(), now).isEmpty())
        val few = History(pl.take(3).associate { it.track.id to it.track }, listOf(play(pl[0].track.id, now - day)))
        MoreMixes.all(few, pl.take(3), now).forEach { assertTrue(it.tracks.size >= PersonalMixes.MIN_SIZE) }
    }

    // ---------------- radio, blend
    @Test fun songRadioStartsWithTheSeedAndFollowsItsRelatedSongs() {
        val (h, pl) = world()
        val seed = pl.first { it.track.id == "h0_0" }
        val r = MoreMixes.songRadio(h, pl, seed, VibeMixer(Random(1)), size = 25)
        assertEquals("h0_0", r.first().track.id)
        assertEquals(r.size, r.map { it.track.id }.toSet().size)
        assertTrue(r.any { it.track.id == "n0_0" || it.track.id == "n1_0" })
        assertTrue(r.groupingBy { it.track.mainArtist }.eachCount().values.max() <= 4)
    }

    @Test fun artistRadioAndBlend() {
        val (h, pl) = world()
        val ar = MoreMixes.artistRadio(h, pl, "art3", VibeMixer(Random(2)))
        assertTrue(ar.size >= 15)
        assertTrue(ar.count { it.track.mainArtist == "art3" } in 3..10)
        assertTrue(MoreMixes.artistRadio(h, pl, "nobody", VibeMixer(Random(2))).isEmpty())
        val bl = MoreMixes.blendArtists(h, pl, "art2", "heb0", VibeMixer(Random(3)), size = 24)
        assertTrue(bl.any { it.track.mainArtist == "art2" } && bl.any { it.track.mainArtist == "heb0" })
        assertTrue(bl.count { it.track.mainArtist != "art2" && it.track.mainArtist != "heb0" } >= 4)
        assertEquals(bl.size, bl.map { it.track.id }.toSet().size)
    }

    // ---------------- C: mix builder, why
    @Test fun mixBuilderFollowsSeedsLanguageLengthAndNewShare() {
        val (h, pl) = world()
        val r = MixRecipe(seedArtists = listOf("art4"), size = 20, newShare = 0.0)
        val m = MixLab.build(r, h, pl, VibeMixer(Random(1)), now)
        assertEquals(20, m.tracks.size)
        assertTrue(m.tracks.count { it.track.mainArtist == "art4" } >= 3)
        assertTrue(m.why.values.any { it.contains("בחרת") })
        assertTrue(m.title.startsWith("🧪"))
        val he = MixLab.build(MixRecipe(lang = LangMode.HEBREW, size = 15), h, pl, VibeMixer(Random(1)), now)
        assertTrue(he.tracks.isNotEmpty() && he.tracks.all { Lang.isHebrew(it.track) })
        val allNew = MixLab.build(MixRecipe(newShare = 1.0, size = 20), h, pl, VibeMixer(Random(1)), now)
        assertTrue(allNew.tracks.count { it.familiarity == 0.0 } >= 15)
        val seedSong = MixLab.build(MixRecipe(seedTracks = listOf("h0_0"), size = 12), h, pl, VibeMixer(Random(1)), now)
        assertEquals("h0_0", seedSong.tracks.first().track.id)
        val calm = MixLab.build(MixRecipe(energy = 0.05, size = 15), h, pl, VibeMixer(Random(1)), now)
        val loud = MixLab.build(MixRecipe(energy = 0.95, size = 15), h, pl, VibeMixer(Random(1)), now)
        assertTrue(calm.tracks.map { it.vibe.energy }.average() < loud.tracks.map { it.vibe.energy }.average())
        assertTrue(MixLab.build(MixRecipe(), History(emptyMap(), emptyList()), emptyList(), VibeMixer(), now).tracks.isEmpty())
    }

    @Test fun whyThisSongGivesAConcreteReason() {
        val tr = mapOf("rep" to t("rep", "a", name = "Alan Walker"), "newsong" to t("newsong", "a", name = "Alan Walker"),
            "stranger" to t("stranger", "zz", name = "Someone"), "gone" to t("gone", "b", name = "B"))
        val plays = (1..5).map { play("rep", now - it * day) } + (1..6).map { play("gone", now - (80 + it) * day) }
        val w = WhyIndex(History(tr, plays), now, tr.values.map { pt(it) })
        assertTrue(w.reason(tr["rep"]!!).contains("5 פעמים"))
        assertTrue(w.reason(tr["newsong"]!!).contains("Alan Walker"))
        assertTrue(w.reason(tr["gone"]!!).contains("לא כבר"))
        assertTrue(w.reason(tr["stranger"]!!, Vibe(0.5, 0.5, 0.3, 0.1)).startsWith("אמן חדש לך"))
    }
}
