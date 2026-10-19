/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.RemoteViews
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.discover.engine.DailyPlan
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings

/** Opens the app and asks the Discover home to do something (play the daily mix, panic, car mode, roulette). */
object WidgetActions {
    const val EXTRA = "discover_action"
    fun open(ctx: Context, action: String, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).putExtra(EXTRA, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Reads (and clears) the widget action from the activity that hosts [ctx]. */
    fun consume(ctx: Context): String? {
        var c: Context? = ctx
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val a = c as? Activity ?: return null
        val v = a.intent?.getStringExtra(EXTRA) ?: return null
        a.intent?.removeExtra(EXTRA)
        return v
    }
}

/** Player widget: song + play/pause, next, and the panic button. Plain RemoteViews (Metrolist has no Glance). */
class DiscoverWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val p = V21Playback.service()?.player
        update(context, p?.currentMediaItem?.mediaMetadata?.title?.toString(), p?.currentMediaItem?.mediaMetadata?.artist?.toString(), p?.isPlaying == true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val p = V21Playback.service()?.player
        when (intent.action) {
            ACTION_TOGGLE -> if (p != null && p.mediaItemCount > 0) { if (p.isPlaying) p.pause() else p.play() } else open(context, "daily")
            ACTION_NEXT -> if (p != null && p.hasNextMediaItem()) p.seekToNextMediaItem() else open(context, "daily")
        }
    }

    private fun open(ctx: Context, action: String) = runCatching { WidgetActions.open(ctx, action, 90).send() }

    companion object {
        const val ACTION_TOGGLE = "com.metrolist.music.discover.WIDGET_TOGGLE"
        const val ACTION_NEXT = "com.metrolist.music.discover.WIDGET_NEXT"

        private fun broadcast(ctx: Context, action: String, req: Int): PendingIntent =
            PendingIntent.getBroadcast(ctx, req, Intent(ctx, DiscoverWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun update(ctx: Context, title: String?, artist: String?, playing: Boolean) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, DiscoverWidget::class.java))
                if (ids.isEmpty()) return
                val v = RemoteViews(ctx.packageName, R.layout.discover_widget_player)
                v.setTextViewText(R.id.dw_title, title ?: "מה נשמע? 🎧")
                v.setTextViewText(R.id.dw_artist, artist ?: "לחץ ▶ למיקס היומי")
                v.setTextViewText(R.id.dw_play, if (playing) "⏸" else "▶")
                v.setOnClickPendingIntent(R.id.dw_play, broadcast(ctx, ACTION_TOGGLE, 91))
                v.setOnClickPendingIntent(R.id.dw_next, broadcast(ctx, ACTION_NEXT, 92))
                v.setOnClickPendingIntent(R.id.dw_panic, WidgetActions.open(ctx, "panic", 93))
                v.setOnClickPendingIntent(R.id.dw_root, WidgetActions.open(ctx, "open", 94))
                mgr.updateAppWidget(ids, v)
            }
        }
    }
}

/** Daily-mix widget: today's daylist title + play daily mix / roulette / car mode. */
class DailyMixWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching {
            val s = V2Settings.get(context)
            val dt = DayClock.dateTime(V2Engine.now())
            val preset = DailyPlan.presetFor(dt.dayOfWeek.value, dt.hour, s.weekdayMoods, s.defaultMood)
            val v = RemoteViews(context.packageName, R.layout.discover_widget_daily)
            v.setTextViewText(R.id.dd_title, if (s.daylist) DailyPlan.daylistTitle(dt.dayOfWeek.value, dt.hour, preset) else "המיקס היומי שלך")
            v.setTextViewText(R.id.dd_sub, "${preset.emoji} ${preset.he} · לחץ לנגן")
            v.setOnClickPendingIntent(R.id.dd_root, WidgetActions.open(context, "daily", 95))
            v.setOnClickPendingIntent(R.id.dd_play, WidgetActions.open(context, "daily", 96))
            v.setOnClickPendingIntent(R.id.dd_roulette, WidgetActions.open(context, "roulette", 97))
            v.setOnClickPendingIntent(R.id.dd_car, WidgetActions.open(context, "car", 98))
            manager.updateAppWidget(ids, v)
        }
    }
}
