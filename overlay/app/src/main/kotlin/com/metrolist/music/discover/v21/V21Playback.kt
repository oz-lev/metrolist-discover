/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.metrolist.music.discover.DiscoverStore
import com.metrolist.music.discover.engine.AbLoop
import com.metrolist.music.discover.engine.AbLoops
import com.metrolist.music.discover.engine.ArtistGap
import com.metrolist.music.discover.engine.AutoEq
import com.metrolist.music.discover.engine.AutoLike
import com.metrolist.music.discover.engine.DjIntro
import com.metrolist.music.discover.engine.Lang
import com.metrolist.music.discover.engine.ProfileScope
import com.metrolist.music.discover.engine.QueueSlots
import com.metrolist.music.discover.engine.SavedQueue
import com.metrolist.music.discover.engine.SleepFade
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Service
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.mediaItem
import com.metrolist.music.eq.data.FilterType
import com.metrolist.music.eq.data.ParametricEQBand
import com.metrolist.music.eq.data.SavedEQProfile
import com.metrolist.music.extensions.metadata
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Service-side v2.1 features, attached from V2Service.attach (no new upstream hook):
 * A-B loop, gentle sleep fade, playback speed, auto-EQ by vibe, auto-like after N full listens, DJ intro lines,
 * Flow (endless queue), queue history, profile auto-switch, the Discover widget, and the extra queue-guard rules
 * (language, explicit, never-heard, anti-repeat artist). Every entry point is wrapped: a failure never stops playback.
 */
object V21Playback {
    private var serviceRef: WeakReference<MusicService>? = null
    private var attachedTo: WeakReference<Player>? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastToast = 0L

    val loop = MutableStateFlow<AbLoop?>(null)
    val aMark = MutableStateFlow<Long?>(null)
    val sleepEndAt = MutableStateFlow(0L)
    private var sleepFadeMs = 0L
    private var sleepSavedVolume = -1f
    private var autoEqApplied = false
    private var lastEqCurve: AutoEq.Curve? = null
    private var autoLikeCheckedFor: String? = null
    private var flowLoading = false
    private val recentArtists = ArrayDeque<Pair<String, String>>()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            runCatching { onTransition(mediaItem, reason) }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runCatching { service()?.let { s -> DiscoverWidget.update(s.applicationContext, s.player.currentMediaItem?.metadata?.title, s.player.currentMediaItem?.metadata?.artists?.joinToString { it.name }, isPlaying) } }
        }
    }

    fun service(): MusicService? = serviceRef?.get()

    fun attach(s: MusicService) {
        serviceRef = WeakReference(s)
        reattach()
        main.removeCallbacks(fast); main.post(fast)
        main.removeCallbacks(slow); main.postDelayed(slow, 3_000L)
        // build the taste snapshot in the background so the guard, DJ intros and auto-like have facts
        scope.launch(Dispatchers.IO) { runCatching { V2Engine.snapshot(s.applicationContext, s.database) } }
    }

    private fun reattach() {
        val s = service() ?: return
        val p = runCatching { s.player }.getOrNull() ?: return
        if (attachedTo?.get() !== p) {
            attachedTo?.get()?.removeListener(listener)
            p.addListener(listener)
            attachedTo = WeakReference(p)
        }
    }

    private fun player(): Player? = attachedTo?.get()

    /** 4x per second: A-B loop, sleep fade, auto-like at 85 %. */
    private val fast: Runnable = object : Runnable {
        override fun run() {
            runCatching { fastTick() }
            if (service() != null) main.postDelayed(this, 250L)
        }
    }

    /** Every 30 s: player swap (crossfade), profile auto-switch, auto-EQ off -> restore. */
    private val slow: Runnable = object : Runnable {
        override fun run() {
            runCatching {
                reattach()
                service()?.let { s ->
                    V21Profiles.autoTick(s.applicationContext)
                    if (autoEqApplied && !V2Settings.get(s.applicationContext).autoEq) restoreEq(s)
                }
            }
            if (service() != null) main.postDelayed(this, 30_000L)
        }
    }

    private fun fastTick() {
        val s = service() ?: return
        val p = player() ?: return
        // A-B loop
        val l = loop.value
        if (l != null && AbLoops.shouldJump(l, p.currentPosition)) p.seekTo(l.a)
        // gentle sleep
        val end = sleepEndAt.value
        if (end > 0L) {
            val remaining = end - System.currentTimeMillis()
            if (sleepSavedVolume < 0f && remaining < sleepFadeMs) sleepSavedVolume = s.playerVolume.value
            if (sleepSavedVolume >= 0f) s.playerVolume.value = (sleepSavedVolume * SleepFade.volumeAt(remaining, sleepFadeMs)).coerceIn(0f, 1f)
            if (remaining <= 0L) {
                p.pause()
                if (sleepSavedVolume >= 0f) s.playerVolume.value = sleepSavedVolume
                sleepSavedVolume = -1f
                sleepEndAt.value = 0L
                toast(s.applicationContext, "😴 לילה טוב")
            }
        }
        // auto-like after N full listens: decided at 85 % of the song, through Metrolist's own like
        val ctx = s.applicationContext
        val set = V2Settings.get(ctx)
        val item = p.currentMediaItem ?: return
        if (set.autoLike && !ProfileScope.guest && !set.secret && autoLikeCheckedFor != item.mediaId) {
            val dur = p.duration
            if (dur > 30_000L && p.currentPosition >= dur * 0.85) {
                autoLikeCheckedFor = item.mediaId
                val id = item.mediaId
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val before = DiscoverStore.get(ctx).fullListens(id)
                            val liked = s.database.getSongById(id)?.song?.liked ?: false
                            AutoLike.shouldLike(before, thisWasFull = true, alreadyLiked = liked, threshold = set.autoLikeAfter)
                        }.getOrDefault(false)
                    }
                    if (ok && player()?.currentMediaItem?.mediaId == id) { s.toggleLike(); toast(ctx, "❤️ לייק אוטומטי: שמעת עד הסוף ${set.autoLikeAfter} פעמים") }
                }
            }
        }
    }

    private fun onTransition(item: MediaItem?, reason: Int) {
        val s = service() ?: return
        val ctx = s.applicationContext
        val set = V2Settings.get(ctx)
        loop.value = null; aMark.value = null
        val md = item?.metadata ?: return
        DiscoverWidget.update(ctx, md.title, md.artists.joinToString { it.name }, s.player.isPlaying)
        val t = TrackMeta(md.id, md.title, md.artists.map { a -> a.id ?: ("name:" + a.name.lowercase()) }, md.artists.map { it.name }, md.album?.id, md.duration, md.thumbnailUrl)
        recentArtists.addLast(t.id to t.mainArtist); while (recentArtists.size > 12) recentArtists.removeFirst()
        // auto-EQ (neighbours mode owns the EQ while it is active)
        if (set.autoEq && !V2Service.neighboursActiveNow()) applyAutoEq(s, t, set.autoEqIntensity.toDouble())
        // DJ intro line
        if (set.djIntros) V2Engine.playInfo(t.id)?.let { info -> DjIntro.line(t.title, info.plays, info.lastTs, V2Engine.now())?.let { toast(ctx, it) } }
        // queue history: a new queue started
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && !ProfileScope.guest && !set.secret) {
            main.postDelayed({ runCatching { recordQueue(ctx) } }, 1_500L)
        }
        // Flow: keep the queue endless
        if (set.flow) refillFlow(s)
    }

    /** Extra queue-guard rules (v2.1). Returns a Hebrew reason to skip, or null. [recent] excludes the song itself. */
    fun extraViolation(ctx: Context, t: TrackMeta, explicit: Boolean): String? {
        val set = V2Settings.get(ctx)
        if (set.explicitFilter && explicit) return "שיר בוטה 🔞"
        if (!Lang.allows(set.lang, t)) return set.lang.he
        if (set.neverHeard) { val info = V2Engine.playInfo(t.id); if (info != null && info.plays > 0) return "כבר שמעת אותו (רק חדשים)" }
        val recent = recentArtists.toList().filter { it.first != t.id }.map { it.second }
        if (ArtistGap.violates(recent, t.mainArtist, set.artistGap)) return "אותו אמן מוקדם מדי"
        return null
    }

    private fun applyAutoEq(s: MusicService, t: TrackMeta, k: Double) {
        runCatching {
            val curve = AutoEq.curveFor(V2Engine.vibeOf(s.applicationContext, t))
            if (curve == lastEqCurve && autoEqApplied) return
            lastEqCurve = curve
            val bands = AutoEq.bands(curve, k)
            if (bands.isEmpty()) { restoreEq(s); lastEqCurve = curve; return }
            val user = s.eqProfileRepository.activeProfile.value
            s.equalizerService.applyProfile(SavedEQProfile(id = "discover_auto_eq", name = "Auto-EQ " + curve.name, deviceModel = "-",
                bands = user?.bands.orEmpty().take(14) + bands.map { b ->
                    ParametricEQBand(frequency = b.freq, gain = b.gainDb, q = b.q, filterType = when (b.type) { "LSC" -> FilterType.LSC; "HSC" -> FilterType.HSC; else -> FilterType.PK })
                }, preamp = (user?.preamp ?: 0.0) + AutoEq.preamp(bands)))
            autoEqApplied = true
        }
    }

    private fun restoreEq(s: MusicService) {
        runCatching {
            val active = s.eqProfileRepository.activeProfile.value
            if (active != null) s.equalizerService.applyProfile(active) else s.equalizerService.disable()
        }
        autoEqApplied = false; lastEqCurve = null
    }

    fun currentCurve(): AutoEq.Curve? = if (autoEqApplied) lastEqCurve else null

    private fun refillFlow(s: MusicService) {
        val p = player() ?: return
        if (flowLoading || p.mediaItemCount - p.currentMediaItemIndex - 1 >= 3) return
        flowLoading = true
        val queued = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }.toSet()
        scope.launch {
            val more = runCatching { V2Engine.flowMore(s.applicationContext, s.database, queued) }.getOrDefault(emptyList())
            if (more.isNotEmpty()) runCatching { player()?.addMediaItems(more.map { it.mediaItem() }) }
            flowLoading = false
        }
    }

    // ------------------------------------------------------------------ queues
    fun currentQueue(): SavedQueue? {
        val p = player() ?: return null
        val n = p.mediaItemCount
        if (n == 0) return null
        val tracks = (0 until minOf(n, 300)).mapNotNull { i ->
            p.getMediaItemAt(i).metadata?.let { md -> TrackMeta(md.id, md.title, md.artists.map { a -> a.id ?: ("name:" + a.name.lowercase()) }, md.artists.map { it.name }, md.album?.id, md.duration, md.thumbnailUrl) }
        }
        if (tracks.isEmpty()) return null
        val name = tracks.first().title + if (tracks.size > 1) " +${tracks.size - 1}" else ""
        return SavedQueue(name, System.currentTimeMillis(), tracks, p.currentMediaItemIndex.coerceIn(0, tracks.size - 1))
    }

    private fun recordQueue(ctx: Context) {
        val q = currentQueue() ?: return
        if (q.tracks.size < 2) return
        val store = DiscoverStore.get(ctx)
        store.put("v21.qhist", queueHistory(ctx).let { QueueSlots.pushHistory(it, q) }.joinToString("\u001D") { QueueSlots.encode(it) })
    }

    fun queueHistory(ctx: Context): List<SavedQueue> =
        DiscoverStore.get(ctx).get("v21.qhist").orEmpty().split("\u001D").mapNotNull { QueueSlots.decode(it) }

    fun slots(ctx: Context): List<SavedQueue?> = (0 until QueueSlots.MAX).map { QueueSlots.decode(DiscoverStore.get(ctx).get("v21.qslot.$it")) }
    fun saveSlot(ctx: Context, index: Int, q: SavedQueue?) { QueueSlots.put(slots(ctx), index, q)[index].let { DiscoverStore.get(ctx).put("v21.qslot.$index", QueueSlots.encode(it)) } }

    // ------------------------------------------------------------------ controls for the UI
    fun startSleep(ctx: Context, minutes: Int) {
        val fade = V2Settings.get(ctx).sleepFadeMin.coerceAtMost(minutes)
        sleepFadeMs = fade * 60_000L
        sleepSavedVolume = -1f
        sleepEndAt.value = System.currentTimeMillis() + minutes * 60_000L
    }

    fun cancelSleep() {
        val s = service()
        if (s != null && sleepSavedVolume >= 0f) s.playerVolume.value = sleepSavedVolume
        sleepSavedVolume = -1f
        sleepEndAt.value = 0L
    }

    fun markA() { aMark.value = player()?.currentPosition; loop.value = null }
    fun markB(): Boolean {
        val p = player() ?: return false
        val a = aMark.value ?: return false
        loop.value = AbLoops.normalize(a, p.currentPosition, p.duration)
        return loop.value != null
    }
    fun clearLoop() { loop.value = null; aMark.value = null }

    fun setSpeed(speed: Float) { runCatching { player()?.playbackParameters = PlaybackParameters(speed, 1f) } }
    fun speed(): Float = player()?.playbackParameters?.speed ?: 1f

    private fun toast(ctx: Context, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastToast < 2_000L) return
        lastToast = now
        main.post { runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() } }
    }
}
