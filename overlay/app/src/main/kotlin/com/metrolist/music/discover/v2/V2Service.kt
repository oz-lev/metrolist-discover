/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.engine.RuleEngine
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.engine.Violation
import com.metrolist.music.eq.data.FilterType
import com.metrolist.music.eq.data.ParametricEQBand
import com.metrolist.music.eq.data.SavedEQProfile
import com.metrolist.music.extensions.metadata
import com.metrolist.music.playback.MusicService
import java.lang.ref.WeakReference

/**
 * Service-side v2 features, attached once from MusicService.onCreate (hook 6):
 *  - queue guard: skips songs that break the weird settings (artist/day blocks, no-repeat lock, < 3 min,
 *    no remixes / live, turtle mode, max Mizrahi %) when they arrive automatically (autoplay, radio, next).
 *    A song the user picked by hand (new queue) is never skipped.
 *  - neighbours mode: after the chosen hour caps the app volume and cuts the bass through Metrolist's own
 *    in-pipeline equalizer (no audio-effect session, no new permission); restores both in the morning.
 * Every entry point is wrapped so a failure here can never stop playback.
 */
object V2Service {
    private var serviceRef: WeakReference<MusicService>? = null
    private var attachedTo: WeakReference<Player>? = null
    private val main = Handler(Looper.getMainLooper())
    private var autoSkips = 0
    private val recentMizrahi = ArrayDeque<Boolean>()
    private var neighboursActive = false
    private var savedVolume = -1f
    private var lastToast = 0L

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            runCatching { onTransition(mediaItem, reason) }
        }
    }

    @JvmStatic
    fun attach(service: MusicService) {
        runCatching {
            serviceRef = WeakReference(service)
            reattach()
            main.removeCallbacks(tick)
            main.postDelayed(tick, 5_000L)
            V2Offline.schedule(service.applicationContext)
        }
        runCatching { com.metrolist.music.discover.v21.V21Playback.attach(service) } // v2.1 playback features
    }

    private fun service(): MusicService? = serviceRef?.get()

    /** Crossfade swaps the ExoPlayer instance; follow it. */
    private fun reattach() {
        val s = service() ?: return
        val p = runCatching { s.player }.getOrNull() ?: return
        if (attachedTo?.get() !== p) {
            attachedTo?.get()?.removeListener(listener)
            p.addListener(listener)
            attachedTo = WeakReference(p)
        }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            runCatching { reattach(); neighboursTick() }
            if (service() != null) main.postDelayed(this, 30_000L)
        }
    }

    // ---------------------------------------------------------------- queue guard
    private fun onTransition(item: MediaItem?, reason: Int) {
        val s = service() ?: return
        val ctx = s.applicationContext
        val set = V2Settings.get(ctx)
        val md = item?.metadata ?: return
        val t = TrackMeta(md.id, md.title, md.artists.map { a -> a.id ?: ("name:" + a.name.lowercase()) }, md.artists.map { it.name },
            md.album?.id, md.duration, md.thumbnailUrl)
        val vibe = V2Engine.vibeOf(ctx, t)
        val userPicked = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        if (!set.guardEverywhere || userPicked) { remember(vibe.isMizrahi); autoSkips = 0; TodayLog.mark(ctx, t.id, V2Engine.now()); return }
        val now = V2Engine.now()
        val engine = V2Engine.ruleEngine(ctx, null)
        val lock = if (set.noRepeatToday) TodayLog.lock(ctx, now) else null
        var v: Violation? = RuleEngine(engine.rules, lock).violation(t, vibe, now)
        if (v == null && RuleEngine.mizrahiOverCap(recentMizrahi.toList(), vibe.isMizrahi, set.maxMizrahiPct)) v = Violation.BLOCKED
        val extra = if (v == null) runCatching { com.metrolist.music.discover.v21.V21Playback.extraViolation(ctx, t, md.explicit) }.getOrNull() else null
        val p = attachedTo?.get() ?: return
        if ((v != null || extra != null) && autoSkips < 12 && p.hasNextMediaItem()) {
            autoSkips++
            main.post { runCatching { p.seekToNextMediaItem() } }
            toast(ctx, "⏭️ דילגתי: " + when (v) {
                Violation.BLOCKED -> "חסום עכשיו (הגדרות מוזרות)"
                Violation.REPEAT_TODAY -> "כבר התנגן היום"
                Violation.TOO_LONG -> "ארוך מדי"
                Violation.REMIX -> "רמיקס"
                Violation.LIVE -> "הופעה חיה"
                Violation.TOO_ENERGETIC -> "מצב צב 🐢"
                null -> extra ?: ""
            }, set.guardToasts)
        } else {
            autoSkips = 0
            remember(vibe.isMizrahi)
            TodayLog.mark(ctx, t.id, now)
        }
    }

    private fun remember(miz: Boolean) {
        recentMizrahi.addLast(miz)
        while (recentMizrahi.size > 10) recentMizrahi.removeFirst()
    }

    private fun toast(ctx: android.content.Context, text: String, enabled: Boolean) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastToast < 2_500L) return
        lastToast = now
        main.post { runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() } }
    }

    // ---------------------------------------------------------------- neighbours mode
    private fun neighboursTick() {
        val s = service() ?: return
        val mode = V2Settings.get(s.applicationContext).neighbours()
        val active = mode.activeAt(DayClock.minuteOfDay(V2Engine.now()))
        if (active) {
            if (!neighboursActive) {
                savedVolume = s.playerVolume.value
                applyBassCut(s, mode.bassCutDb)
                neighboursActive = true
            }
            if (s.playerVolume.value > mode.volumeCap) s.playerVolume.value = mode.volumeCap
        } else if (neighboursActive) {
            neighboursActive = false
            if (savedVolume >= 0f) s.playerVolume.value = savedVolume
            restoreEq(s)
        }
    }

    /** The user's active EQ profile (if any) plus a low shelf cut, through Metrolist's own equalizer service. */
    private fun applyBassCut(s: MusicService, db: Double) {
        runCatching {
            val active = s.eqProfileRepository.activeProfile.value
            val bands = active?.bands.orEmpty().take(17) + listOf(
                ParametricEQBand(frequency = 120.0, gain = db, q = 0.7, filterType = FilterType.LSC),
                ParametricEQBand(frequency = 60.0, gain = db / 2, q = 0.7, filterType = FilterType.LSC),
            )
            s.equalizerService.applyProfile(SavedEQProfile(id = "discover_neighbours", name = "Neighbours mode", deviceModel = "-",
                bands = bands, preamp = active?.preamp ?: 0.0))
        }
    }

    private fun restoreEq(s: MusicService) {
        runCatching {
            val active = s.eqProfileRepository.activeProfile.value
            if (active != null) s.equalizerService.applyProfile(active) else s.equalizerService.disable()
        }
    }

    /** For the settings screen: is it in effect right now? */
    fun neighboursActiveNow(): Boolean = neighboursActive
}

/** Songs that actually started today (for the no-repeat lock), kept in memory and seeded from history. */
object TodayLog {
    private var day = -1L
    private val ids = HashSet<String>()
    private var seeded = false

    @Synchronized fun mark(ctx: android.content.Context, id: String, now: Long) {
        roll(now); ids += id
    }

    @Synchronized fun lock(ctx: android.content.Context, now: Long): com.metrolist.music.discover.engine.DayLock {
        roll(now)
        if (!seeded) {
            seeded = true
            runCatching {
                com.metrolist.music.discover.DiscoverStore.get(ctx).plays(now - DayClock.DAY_MS)
                    .filter { DayClock.dayKey(it.ts) == day && it.playedMs >= 30_000L }.forEach { ids += it.songId }
            }
        }
        return com.metrolist.music.discover.engine.DayLock(day, ids.toList())
    }

    private fun roll(now: Long) {
        val d = DayClock.dayKey(now)
        if (d != day) { day = d; ids.clear(); seeded = false }
    }
}
