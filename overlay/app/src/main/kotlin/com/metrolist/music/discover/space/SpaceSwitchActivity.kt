/**
 * Metrolist Discover 13.7.0.12 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.space

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The profile switch splash ("עובר לפרופיל…"). Runs in its own tiny process (":space", declared in the release
 * manifest overlay), ProcessPhoenix style: it ends the main process (so every database, cache and the media session of
 * the old profile is closed), then starts the app again, which comes up in the new profile, and finally ends itself.
 */
class SpaceSwitchActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(ProfileSpace.EXTRA_NAME).orEmpty()
        val emoji = intent.getStringExtra(ProfileSpace.EXTRA_EMOJI).orEmpty()
        val pid = intent.getIntExtra(ProfileSpace.EXTRA_PID, -1)
        @Suppress("DEPRECATION")
        val launch: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(ProfileSpace.EXTRA_LAUNCH, Intent::class.java)
            else intent.getParcelableExtra(ProfileSpace.EXTRA_LAUNCH)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#0B0B14"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        box.addView(TextView(this).apply { text = emoji.ifBlank { "🎵" }; textSize = 56f; gravity = Gravity.CENTER })
        box.addView(TextView(this).apply {
            text = if (name.isBlank()) "עובר לפרופיל…" else "עובר לפרופיל $name…"
            textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(48, 24, 48, 8)
        })
        box.addView(TextView(this).apply {
            text = "כל פרופיל הוא אפליקציה נפרדת: היסטוריה, ספרייה, הגדרות וחשבון משלו"
            textSize = 13f; setTextColor(Color.parseColor("#B3FFFFFF")); gravity = Gravity.CENTER; setPadding(48, 0, 48, 0)
        })
        setContentView(box)

        // 1) end the old profile's process (its databases are WAL: committed data is safe)
        main.postDelayed({
            if (pid > 0 && pid != Process.myPid()) runCatching { Process.killProcess(pid) }
        }, 250L)
        // 2) start the app again: it reads the registry and opens the new profile's files
        main.postDelayed({
            runCatching {
                val i = (launch ?: packageManager.getLaunchIntentForPackage(packageName))
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                if (i != null) startActivity(i)
            }
            finish()
            overridePendingTransition(0, 0)
        }, 700L)
        // 3) this helper process goes too
        main.postDelayed({ Runtime.getRuntime().exit(0) }, 1500L)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* the switch is already committed; let it finish */ }
}
