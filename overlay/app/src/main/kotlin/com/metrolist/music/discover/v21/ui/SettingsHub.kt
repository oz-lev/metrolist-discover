/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.AudioNormalizationKey
import com.metrolist.music.constants.AutoLoadMoreKey
import com.metrolist.music.constants.AutoplayKey
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.DensityScaleKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.SkipSilenceKey
import com.metrolist.music.constants.SleepTimerFadeOutKey
import com.metrolist.music.constants.SwipeThumbnailKey
import com.metrolist.music.constants.SwipeToSongKey
import com.metrolist.music.discover.DiscoverStore
import com.metrolist.music.discover.engine.BlockKind
import com.metrolist.music.discover.engine.BlockRule
import com.metrolist.music.discover.engine.BubbleFreq
import com.metrolist.music.discover.engine.LangMode
import com.metrolist.music.discover.engine.MoodPreset
import com.metrolist.music.discover.engine.TastePreset
import com.metrolist.music.discover.engine.VibeCorrections
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Offline
import com.metrolist.music.discover.v2.V2Service
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.BusyOverlay
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.V2Card
import com.metrolist.music.discover.v2.ui.V2Colors
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.V2Routes
import com.metrolist.music.discover.v2.ui.V2Rtl
import com.metrolist.music.discover.v2.ui.playMix
import com.metrolist.music.discover.v2.ui.playTracks
import com.metrolist.music.discover.v2.ui.themeHero
import com.metrolist.music.discover.v2.ui.toast
import com.metrolist.music.discover.v21.V21Profiles
import com.metrolist.music.discover.v21.theme.DiscoverDesign
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Hebrew day letters in week order (Sunday first, as in Israel) with their ISO numbers. */
internal val DAYS = listOf("א" to 7, "ב" to 1, "ג" to 2, "ד" to 3, "ה" to 4, "ו" to 5, "ש" to 6)

internal fun hhmm(min: Int) = "%02d:%02d".format(min / 60 % 24, min % 60)

private fun ruleLabel(r: BlockRule): String {
    val what = when (r.kind) { BlockKind.ARTIST -> "🎤 ${r.value}"; BlockKind.SAD_SONGS -> "💔 שירי פרידה ועצב"; BlockKind.KEYWORD -> "🔤 \"${r.value}\"" }
    val days = if (r.days.size == 7) "כל יום" else DAYS.filter { it.second in r.days }.joinToString(" ") { it.first + "׳" }
    val time = if (r.fromMinute == r.toMinute) "כל היום" else "${hhmm(r.fromMinute)}–${hhmm(r.toMinute)}"
    return "$what · $days · $time"
}

/** One searchable setting (or function) of the hub. */
private class SItem(val group: String, val title: String, val desc: String, val content: @Composable () -> Unit)

private val GROUPS = listOf(
    "🎨" to "סטודיו עיצוב", "👥" to "פרופילים ופרטיות", "🧠" to "המוח של האלגוריתם", "🎵" to "חוקי התור",
    "📅" to "חסימות לפי ימים ושעות", "🌙" to "מצב שכנים", "🔊" to "סאונד ונגינה", "👤" to "טעם של מישהו אחר", "🎉" to "כיף ושטויות",
)

private val GROUP_BRUSH = listOf(
    listOf(Color(0xFF831843), Color(0xFFDB2777)), listOf(Color(0xFF1F2937), Color(0xFF475569)), listOf(Color(0xFF4C1D95), Color(0xFF6D28D9)),
    listOf(Color(0xFF0C4A6E), Color(0xFF0E7490)), listOf(Color(0xFF7F1D1D), Color(0xFFBE123C)), listOf(Color(0xFF1E1B4B), Color(0xFF3730A3)),
    listOf(Color(0xFF134E4A), Color(0xFF0F766E)), listOf(Color(0xFF064E3B), Color(0xFF15803D)), listOf(Color(0xFF78350F), Color(0xFFD97706)),
)

@Composable
fun SettingsHubScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val s = remember(version) { V2Settings.get(ctx) }
    val profile = remember(version) { V21Profiles.active(ctx) }
    var query by remember { mutableStateOf("") }
    var addRule by remember { mutableStateOf(false) }
    var weekdayDialog by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    // Metrolist's own settings, exposed here (they change Metrolist's behaviour directly)
    val (crossfade, setCrossfade) = rememberPreference(CrossfadeEnabledKey, false)
    val (crossfadeSec, setCrossfadeSec) = rememberPreference(CrossfadeDurationKey, 5f)
    val (skipSilence, setSkipSilence) = rememberPreference(SkipSilenceKey, false)
    val (normalize, setNormalize) = rememberPreference(AudioNormalizationKey, true)
    val (autoplay, setAutoplay) = rememberPreference(AutoplayKey, true)
    val (autoLoadMore, setAutoLoadMore) = rememberPreference(AutoLoadMoreKey, true)
    val (sleepFadeMetrolist, setSleepFadeMetrolist) = rememberPreference(SleepTimerFadeOutKey, false)
    val (hideExplicit, setHideExplicit) = rememberPreference(HideExplicitKey, false)
    val (density, setDensity) = rememberPreference(DensityScaleKey, 1f)
    val (darkMode, setDarkMode) = rememberEnumPreference(DarkModeKey, DarkMode.AUTO)
    val (swipeMini, setSwipeMini) = rememberPreference(SwipeThumbnailKey, true)
    val (swipeSong, setSwipeSong) = rememberPreference(SwipeToSongKey, false)

    val items = buildList {
        // ------------------------------------------------ 🎨 design studio
        val g0 = GROUPS[0].second
        add(SItem(g0, "ערכת עיצוב", "Neon Night, זהב מזרחי, פאנק אדום, אוקיינוס, AMOLED ועוד") {
            val t = DiscoverDesign.currentTheme(s)
            NavRow("${t.emoji} ${t.he}", "פתח את סטודיו העיצוב (צבע הדגשה, פינות, גופן)") { onNavigate(V2Routes.THEME) }
        })
        add(SItem(g0, "ערכה אקראית כל יום", "כל בוקר האפליקציה מתלבשת אחרת") { Toggle("🎲 ערכה אקראית כל יום", "כל יום ערכה אחרת (קבועה לכל היום)", s.randomDailyTheme) { s.randomDailyTheme = it } })
        add(SItem(g0, "תמיד כהה", "מצב כהה גם כשהטלפון בהיר") { Toggle("🌑 תמיד כהה", "הערכות שלנו נבנו לחושך", darkMode == DarkMode.ON) { setDarkMode(if (it) DarkMode.ON else DarkMode.AUTO) } })
        add(SItem(g0, "צפיפות מסך", "כמה דברים נכנסים במסך (של Metrolist)") {
            Choice("📐 צפיפות", listOf(0.85f to "צפוף", 1f to "רגיל", 1.1f to "מרווח", 1.2f to "ענק"), density) { setDensity(it) }
        })
        add(SItem(g0, "סרגל ניווט זוהר", "פינות מעוגלות וקו אור בצבעי הערכה") { Toggle("✨ סרגל ניווט זוהר", "הסרגל התחתון של כל האפליקציה", s.navGlow) { s.navGlow = it } })
        add(SItem(g0, "הילה לנגן המיני", "מסגרת גרדיאנט סביב הנגן הקטן") { Toggle("💫 הילה לנגן המיני", "מסגרת בצבעי הערכה", s.miniGlow) { s.miniGlow = it } })
        add(SItem(g0, "אנימציות קופצניות", "תנועה אקספרסיבית בכל הכפתורים והחלונות") { Toggle("🫧 אנימציות קופצניות", "כבוי = תנועה רגועה וסטנדרטית", s.motionExpressive) { s.motionExpressive = it } })
        add(SItem(g0, "רטט בלחיצות", "רטט קטן בכל לחיצה במסכי Discover") { Toggle("📳 רטט בלחיצות", "משוב מישוש עדין", s.haptics) { s.haptics = it } })
        add(SItem(g0, "הבית החדש", "כבוי = הבית הקלאסי של Discover") { Toggle("🏠 הבית החדש", "כבוי = הבית הקלאסי של Discover", s.homeV2) { s.homeV2 = it } })
        add(SItem(g0, "כפתורי וייב וכלים בנגן", "עוד כזה / שנה וייב / אנרגיה ↑ + כלים") { Toggle("🔁 כפתורי וייב וכלים בנגן", "עוד כזה / שנה וייב / אנרגיה ↑, פאניקה, קריוקי, לולאה…", s.vibeBar) { s.vibeBar = it } })
        add(SItem(g0, "פס צבעים חי בנגן", "אנימציה בצבעי העטיפה") { Toggle("🌈 פס צבעים חי בנגן", "בצבעי העטיפה, קופץ לפי האנרגיה", s.animatedArt) { s.animatedArt = it } })
        add(SItem(g0, "החלקה על הנגן להחלפת שיר", "כמו ב-YouTube Music: החלק את העטיפה ימינה/שמאלה") { Toggle("👆 החלקה להחלפת שיר", "על הנגן המיני והעטיפה הגדולה (של Metrolist)", swipeMini) { setSwipeMini(it) } })
        add(SItem(g0, "החלקה על שיר ברשימה", "החלק שיר ברשימה כדי להוסיף אותו לתור") { Toggle("↔️ החלקה על שיר ברשימה", "מוסיף לתור בהחלקה (של Metrolist)", swipeSong) { setSwipeSong(it) } })
        add(SItem(g0, "איך לקרוא לך", "השם בברכה בבית") {
            var name by remember(version) { mutableStateOf(s.greetingName) }
            OutlinedTextField(value = name, onValueChange = { name = it; s.greetingName = it }, singleLine = true,
                label = { Text("איך לקרוא לך בבית? (רשות)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        })

        // ------------------------------------------------ 👥 profiles & privacy
        val g1 = GROUPS[1].second
        add(SItem(g1, "פרופילים", "אני, חדר כושר, עבודה, לילה, רכב, מסיבה, אורח + שלך") {
            NavRow("${profile.emoji} פרופיל פעיל: ${profile.name}", "החלפה, יצירה, שכפול, מחיקה ומעבר אוטומטי לפי שעה") { onNavigate(V2Routes.PROFILES) }
        })
        add(SItem(g1, "מעבר אוטומטי בין פרופילים", "לפי יום ושעה (למשל עבודה א׳–ה׳ 9–17)") { Toggle("⏰ מעבר אוטומטי בין פרופילים", if (s.autoRules.isEmpty()) "הגדר לוח זמנים במסך הפרופילים" else "${s.autoRules.size} חוקים", s.autoSwitch) { s.autoSwitch = it } })
        add(SItem(g1, "מצב סודי", "שום השמעה לא נרשמת להיסטוריה וללמידה") {
            Toggle("🕵️ מצב סודי", "לא נרשם כלום: לא היסטוריה, לא למידה, לא סטטיסטיקות", s.secret) { s.secret = it; V21Profiles.applySecret(ctx, it) }
        })
        add(SItem(g1, "לייק אוטומטי", "אחרי כמה האזנות מלאות השיר מקבל לייק לבד") {
            Toggle("❤️ לייק אוטומטי", "שיר ששמעת עד הסוף ${s.autoLikeAfter} פעמים מקבל לייק", s.autoLike) { s.autoLike = it }
            if (s.autoLike) SettingSlider("אחרי כמה האזנות מלאות", s.autoLikeAfter.toFloat(), 2f..10f, 7, { "${it.roundToInt()}" }, "2", "10") { s.autoLikeAfter = it.roundToInt() }
        })

        // ------------------------------------------------ 🧠 algorithm
        val g2 = GROUPS[2].second
        add(SItem(g2, "כמה האלגוריתם מעז", "בטוח ↔ משוגע") {
            SettingSlider("כמה האלגוריתם מעז", s.boldness.toFloat(), 0f..1f, 0, { v -> when { v < 0.15f -> "בטוח לגמרי 🛡️"; v < 0.4f -> "רגיל"; v < 0.7f -> "הרפתקן 🧭"; else -> "משוגע 🤪" } }, "בטוח", "משוגע") { s.boldness = it.toDouble(); V2Engine.invalidate() }
        })
        add(SItem(g2, "כמה מזרחית מותר", "אחוז מקסימלי של מזרחית במיקסים ובתור") {
            SettingSlider("כמה מזרחית מותר", s.maxMizrahiPct.toFloat(), 0f..100f, 19, { "${it.roundToInt()}%" }, "0%", "100%") { s.maxMizrahiPct = it.roundToInt() }
        })
        add(SItem(g2, "עונש על דילוגים", "כמה המנוע נוטר לשירים שדילגת עליהם") {
            SettingSlider("עונש על שירים שדילגת עליהם", s.skipPenalty.toFloat(), 0f..2.5f, 0, { v -> when { v < 0.2f -> "סולח לגמרי 😇"; v < 1.2f -> "רגיל"; v < 2f -> "קשוח"; else -> "לא סולח 😤" } }, "סולח", "נוקם") { s.skipPenalty = it.toDouble(); V2Engine.invalidate() }
        })
        add(SItem(g2, "מצב רוח לפי יום בשבוע", "למשל: שישי = מזרחית, ראשון = רגוע") {
            val m = s.weekdayMoods
            NavRow("📆 מצב רוח לפי יום בשבוע", if (m.isEmpty()) "אוטומטי לפי השעה" else DAYS.filter { it.second in m }.joinToString(" · ") { "${it.first}׳ ${m[it.second]?.emoji}" }) { weekdayDialog = true }
        })
        add(SItem(g2, "מצב רוח ברירת מחדל לפרופיל", "המיקס היומי של הפרופיל הזה") {
            Choice("🎚️ מצב רוח ברירת מחדל", listOf<Pair<MoodPreset?, String>>(null to "אוטומטי") + MoodPreset.entries.map { it to "${it.emoji} ${it.he}" }, s.defaultMood) { s.defaultMood = it }
        })
        add(SItem(g2, "שם מיקס שמשתנה (Daylist)", "המיקס היומי מקבל שם לפי היום והשעה") { Toggle("🏷️ שם מיקס שמשתנה", "\"יום שני בערב · רגוע\" (רעיון מספוטיפיי)", s.daylist) { s.daylist = it } })
        add(SItem(g2, "רמפת בוקר", "בבוקר האנרגיה עולה בהדרגה ב-20 הדקות הראשונות") { Toggle("🌅 רמפת בוקר", "בין 5 ל-12: המיקס מתחיל רגוע ומתחמם", s.morningRamp) { s.morningRamp = it } })
        add(SItem(g2, "אמן אהוב כל כמה שירים", "ערבות: אחד האמנים הכי אהובים שלך כל N שירים") {
            SettingSlider("⭐ אמן אהוב כל…", s.favoriteEvery.toFloat(), 0f..10f, 9, { if (it < 0.5f) "כבוי" else "${it.roundToInt()} שירים" }, "כבוי", "10") { s.favoriteEvery = it.roundToInt() }
        })
        add(SItem(g2, "רק שירים שלא שמעתי", "מיקסים ותור רק עם שירים חדשים לגמרי") { Toggle("🆕 רק שירים שלא שמעתי אף פעם", "גם בתור (דילוג אוטומטי על מה ששמעת)", s.neverHeard) { s.neverHeard = it } })
        add(SItem(g2, "נעילת זרע אקראי", "אותו זרע = אותו מיקס כל היום") {
            Toggle("🔐 נעילת זרע אקראי", "מיקסים שחוזרים על עצמם בדיוק (זרע ${s.seed})", s.seedLock) { s.seedLock = it }
            if (s.seedLock) SettingSlider("הזרע", s.seed.toFloat(), 0f..99f, 98, { "${it.roundToInt()}" }, "0", "99") { s.seed = it.roundToInt().toLong() }
        })
        add(SItem(g2, "הפתעה כל 5 שירים", "כל שיר חמישי במיקס הוא משהו שלא שמעת") { Toggle("🎁 הפתעה כל 5 שירים", "במיקסים: כל שיר חמישי חדש לגמרי", s.surpriseEvery5) { s.surpriseEvery5 = it } })
        add(SItem(g2, "אפס למידת וייב", "המנוע שוכח את התיקונים שלמד בפרופיל הזה") {
            Pill("🧽 אפס למידת וייב") { s.corrections = null; V2Engine.estimator(ctx).corrections = VibeCorrections(); V2Engine.estimator(ctx).clearCache(); toast(ctx, "המנוע שכח את התיקונים שלמד") }
        })

        // ------------------------------------------------ 🎵 queue rules
        val g3 = GROUPS[3].second
        add(SItem(g3, "נעילת חזרות", "אף שיר לא מתנגן פעמיים באותו יום") { Toggle("🔒 נעילת חזרות", "אף שיר לא מתנגן פעמיים באותו יום", s.noRepeatToday) { s.noRepeatToday = it } })
        add(SItem(g3, "רק שירים מתחת ל-3 דקות", "ארוכים מזה מדולגים") { Toggle("⏱️ רק שירים מתחת ל-3 דקות", "ארוכים מזה מדולגים", s.shortOnly) { s.shortOnly = it } })
        add(SItem(g3, "בלי שירים ארוכים מ-X", "סליידר אורך מקסימלי") {
            SettingSlider("📏 בלי שירים ארוכים מ-", s.maxLengthMin.toFloat(), 0f..12f, 11, { if (it < 0.5f) "כבוי" else "${it.roundToInt()} דק׳" }, "כבוי", "12 דק׳") { s.maxLengthMin = it.roundToInt() }
        })
        add(SItem(g3, "מרחק בין שירים של אותו אמן", "אנטי-חזרה על אמן") {
            SettingSlider("🙅 אותו אמן לא חוזר לפני…", s.artistGap.toFloat(), 0f..10f, 9, { if (it < 0.5f) "כבוי" else "${it.roundToInt()} שירים" }, "כבוי", "10") { s.artistGap = it.roundToInt() }
        })
        add(SItem(g3, "עברית בלבד / לועזית בלבד", "סינון שפה לפי אותיות בשם השיר והאמן") {
            Choice("🔤 שפה", LangMode.entries.map { it to it.he }, s.lang) { s.lang = it }
        })
        add(SItem(g3, "סינון שירים בוטים", "בלי Explicit בתור ובחיפושים") {
            Toggle("🔞 סינון שירים בוטים", "דילוג בתור + הסתרה ברשימות של Metrolist", s.explicitFilter || hideExplicit) { s.explicitFilter = it; setHideExplicit(it) }
        })
        add(SItem(g3, "בלי רמיקסים", "גם slowed / sped up / nightcore") { Toggle("🚫 בלי רמיקסים", "גם slowed / sped up / nightcore", s.noRemix) { s.noRemix = it } })
        add(SItem(g3, "בלי הופעות חיות", "רק גרסאות אולפן") { Toggle("🎤 בלי הופעות חיות", "רק גרסאות אולפן", s.noLive) { s.noLive = it } })
        add(SItem(g3, "מצב צב", "רק שירים רגועים") { Toggle("🐢 מצב צב", "רק שירים רגועים (אנרגיה נמוכה)", s.turtle) { s.turtle = it } })
        add(SItem(g3, "החוקים בכל האפליקציה", "גם ברדיו ובהשמעה האוטומטית") { Toggle("🛡️ החוקים בכל האפליקציה", "גם ברדיו ובהשמעה אוטומטית של Metrolist (דילוג אוטומטי)", s.guardEverywhere) { s.guardEverywhere = it } })
        add(SItem(g3, "הודעה כשדילגתי", "\"דילגתי: כבר התנגן היום\"") { Toggle("💬 הודעה כשדילגתי", "\"דילגתי: כבר התנגן היום\"", s.guardToasts) { s.guardToasts = it } })

        // ------------------------------------------------ 📅 blocks
        val g4 = GROUPS[4].second
        add(SItem(g4, "חסימות לפי ימים ושעות", "בלי שירי פרידה ביום שישי 😂") {
            val rules = s.blocks
            if (rules.isEmpty()) Text("אין חסימות. דוגמה: בלי שירי פרידה ביום שישי 😂", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            rules.forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(ruleLabel(r), color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("✕", color = Color.White, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable { s.blocks = rules.filterIndexed { j, _ -> j != i } }.padding(8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("➕ חסימה חדשה", selected = true) { addRule = true }
                Pill("💔 בלי פרידות בשישי") { s.blocks = s.blocks + BlockRule(BlockKind.SAD_SONGS, "", setOf(5)) }
            }
        })

        // ------------------------------------------------ 🌙 neighbours
        val g5 = GROUPS[5].second
        add(SItem(g5, "מצב שכנים", "מגביל עוצמה ומוריד בס בלילה") {
            Toggle("🤫 מצב שכנים", if (V2Service.neighboursActiveNow()) "פעיל עכשיו ✅" else "מגביל עוצמה ומוריד בס בשעות הלילה", s.neighboursOn) { s.neighboursOn = it }
        })
        add(SItem(g5, "שעת התחלה של מצב שכנים", "מתי מתחיל השקט") { SettingSlider("מתחיל ב-", s.neighboursStart / 60f, 0f..23f, 22, { hhmm(it.roundToInt() * 60) }, "00:00", "23:00") { s.neighboursStart = it.roundToInt() * 60 } })
        add(SItem(g5, "שעת סיום של מצב שכנים", "מתי חוזרים לרעש") { SettingSlider("נגמר ב-", s.neighboursEnd / 60f, 0f..23f, 22, { hhmm(it.roundToInt() * 60) }, "00:00", "23:00") { s.neighboursEnd = it.roundToInt() * 60 } })
        add(SItem(g5, "עוצמה מקסימלית בלילה", "תקרת עוצמה במצב שכנים") { SettingSlider("עוצמה מקסימלית", s.neighboursVolume.toFloat(), 5f..100f, 18, { "${it.roundToInt()}%" }, "5%", "100%") { s.neighboursVolume = it.roundToInt() } })
        add(SItem(g5, "הורדת בס בלילה", "דרך האקולייזר של Metrolist") { SettingSlider("הורדת בס", -s.neighboursBassDb.toFloat(), 0f..18f, 17, { "-${it.roundToInt()} dB" }, "0", "-18 dB") { s.neighboursBassDb = -it.roundToInt() } })

        // ------------------------------------------------ 🔊 sound
        val g6 = GROUPS[6].second
        add(SItem(g6, "קרוספייד", "מעבר רך בין שירים (של Metrolist)") {
            Toggle("🌊 קרוספייד", "השיר הבא נכנס בזמן שהקודם דועך", crossfade) { setCrossfade(it) }
            if (crossfade) SettingSlider("אורך הקרוספייד", crossfadeSec, 1f..12f, 10, { "${it.roundToInt()} שנ׳" }, "1", "12") { setCrossfadeSec(it.roundToInt().toFloat()) }
        })
        add(SItem(g6, "קיצוץ שקט", "מדלג על שקט בתחילת ובסוף שירים") { Toggle("✂️ קיצוץ שקט", "מדלג על קטעים שקטים (של Metrolist)", skipSilence) { setSkipSilence(it) } })
        add(SItem(g6, "נרמול עוצמה", "כל השירים באותה עוצמה בערך") { Toggle("🔉 נרמול עוצמה", "בלי קפיצות עוצמה בין שירים", normalize) { setNormalize(it) } })
        add(SItem(g6, "אקוולייזר אוטומטי לפי וייב", "בס לפאנק, ווקאל למזרחית, חם לשקטים") {
            Toggle("🎛️ אקוולייזר אוטומטי לפי וייב", "האקולייזר של Metrolist מתחלף לפי השיר", s.autoEq) { s.autoEq = it }
            if (s.autoEq) SettingSlider("עוצמת האקוולייזר האוטומטי", s.autoEqIntensity, 0.2f..1.5f, 0, { "${(it * 100).roundToInt()}%" }, "עדין", "חזק") { s.autoEqIntensity = it }
        })
        add(SItem(g6, "השמעה אוטומטית אינסופית", "כשהתור נגמר ממשיך לשירים דומים (Autoplay)") {
            Toggle("♾️ השמעה אוטומטית אינסופית", "Metrolist ממשיך לבד כשהתור נגמר", autoplay && autoLoadMore) { setAutoplay(it); setAutoLoadMore(it) }
        })
        add(SItem(g6, "Flow אינסופי אישי", "התור אף פעם לא נגמר: המיקס היומי ממשיך לבד") { Toggle("🌀 Flow אישי", "כשנשארים 3 שירים מתווספים עוד מהטעם שלך (רעיון מדיזר)", s.flow) { s.flow = it } })
        add(SItem(g6, "ערבוב חכם", "כמה שירים מומלצים להכניס בערבוב חכם") {
            SettingSlider("🔀 ערבוב חכם: המלצה אחרי כל", s.smartShuffleEvery.toFloat(), 1f..10f, 8, { "${it.roundToInt()} שירים" }, "1", "10") { s.smartShuffleEvery = it.roundToInt() }
        })
        add(SItem(g6, "טיימר שינה עדין", "כמה דקות דעיכה בסוף טיימר השינה שלנו") {
            SettingSlider("😴 אורך הדעיכה בשינה עדינה", s.sleepFadeMin.toFloat(), 1f..20f, 18, { "${it.roundToInt()} דק׳" }, "1", "20") { s.sleepFadeMin = it.roundToInt() }
        })
        add(SItem(g6, "דעיכה בטיימר השינה של Metrolist", "גם הטיימר המקורי דועך בדקה האחרונה") { Toggle("🌙 דעיכה בטיימר של Metrolist", "הטיימר המקורי מנמיך לאט בסוף", sleepFadeMetrolist) { setSleepFadeMetrolist(it) } })

        // ------------------------------------------------ 👤 someone else's taste
        val g7 = GROUPS[7].second
        add(SItem(g7, "מצב טעם של מישהו אחר", "שעה של EDM בלבד / מזרחית בלבד / שנות ה-2000 / זר אקראי") {
            val active = s.activeTaste()
            if (active != null) {
                val left = ((s.tasteUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${active.emoji} פעיל: ${active.he} · עוד $left דק׳", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Pill("עצור") { s.tastePreset = null; s.tasteUntil = 0L }
                }
            }
            TastePreset.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    row.forEach { p ->
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.large)
                            .background(Color.White.copy(alpha = if (active == p) 0.35f else 0.12f))
                            .clickable(enabled = busy == null) {
                                s.tastePreset = p; s.tasteUntil = System.currentTimeMillis() + 3_600_000L
                                busy = "מחליף טעם…"
                                scope.launch {
                                    val mix = runCatching { V2Engine.tasteMix(ctx, db, p) }.getOrNull()
                                    busy = null
                                    if (mix != null) player?.playMix(mix, ctx) else toast(ctx, "לא הצלחתי למצוא שירים, נסה שוב")
                                }
                            }.padding(vertical = 14.dp)) { Text("${p.emoji} ${p.he}", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        })

        // ------------------------------------------------ 🎉 fun
        val g8 = GROUPS[8].second
        add(SItem(g8, "תדירות בועת הנתונים", "כמה פעמים קופצת העובדה המפתיעה") {
            Choice("🫧 בועת נתונים", BubbleFreq.entries.map { it to it.he }, s.bubbleFreq) { s.bubbleFreq = it; DiscoverStore.get(ctx).put(DiscoverStore.K_BUBBLE, (it != BubbleFreq.NEVER).toString()) }
        })
        add(SItem(g8, "אתגר יומי", "אמן חדש כל יום, עם רצף והישג") { Toggle("🎯 אתגר יומי בבית", "\"היום: תשמע אמן אחד שלא שמעת אף פעם\"", s.challengeOnHome) { s.challengeOnHome = it } })
        add(SItem(g8, "יעד האזנה יומי", "להבה ורצף בסגנון דואולינגו") {
            SettingSlider("🔥 יעד האזנה יומי", s.dailyGoalMin.toFloat(), 0f..120f, 11, { if (it < 5f) "כבוי" else "${it.roundToInt()} דק׳" }, "כבוי", "120 דק׳") { s.dailyGoalMin = it.roundToInt() }
        })
        add(SItem(g8, "קפוץ בחזרה", "מדף השירים האחרונים בבית (כמו בספוטיפיי)") { Toggle("⏪ מדף \"קפוץ בחזרה\"", "השירים האחרונים ששמעת, בלחיצה אחת", s.jumpBack) { s.jumpBack = it } })
        add(SItem(g8, "הערות DJ", "\"הנה משהו שלא שמעת מזמן\" כשהשיר מתחיל") { Toggle("🎧 הערות DJ", "הודעה קטנה כשמתחיל שיר מיוחד (רעיון מה-DJ של ספוטיפיי)", s.djIntros) { s.djIntros = it } })
        add(SItem(g8, "מיקס לילה אופליין", "בלילה, בטעינה וב-Wi-Fi המיקס היומי יורד לבד") { Toggle("📥 מיקס לילה אופליין", "25 שירים יורדים בלילה, בטעינה וב-Wi-Fi", s.offlineMix) { s.offlineMix = it; V2Offline.schedule(ctx) } })
        add(SItem(g8, "רולטה", "שיר אקראי לגמרי מכל YouTube Music") {
            Pill("🎲 רולטה עכשיו", selected = true) {
                if (busy == null) {
                    busy = "מגריל…"
                    scope.launch {
                        val (seed, t) = runCatching { V2Engine.roulette(ctx) }.getOrDefault("" to null)
                        busy = null
                        if (t != null) { player?.playTracks(listOf(t), "🎲 רולטה"); toast(ctx, "🎲 \"$seed\" ← ${t.title}") } else toast(ctx, "נסה שוב 🎲")
                    }
                }
            }
        })
    }

    val q = query.trim()
    val visible = if (q.isEmpty()) items else items.filter { it.title.contains(q, true) || it.desc.contains(q, true) || it.group.contains(q, true) }

    V2Page(title = "הגדרות Discover 🧪", subtitle = "${items.size} הגדרות שבאמת עובדות · פרופיל ${profile.emoji} ${profile.name}", onBack = onBack) {
        item(key = "search") {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("🔎 חיפוש בהגדרות… (למשל: בס, לילה, מזרחית)") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(50))
        }
        if (visible.isEmpty()) item(key = "none") { Text("לא מצאתי הגדרה כזו 🤷", modifier = Modifier.padding(24.dp)) }
        GROUPS.forEachIndexed { gi, (emoji, title) ->
            val list = visible.filter { it.group == title }
            if (list.isNotEmpty()) item(key = "g_$gi") {
                Group(emoji, title, Brush.linearGradient(GROUP_BRUSH[gi])) { list.forEach { it.content() } }
            }
        }
        item(key = "reset") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Pill("♻️ איפוס ההגדרות של הפרופיל הזה") { confirmReset = true } }
        }
    }

    if (addRule) AddRuleDialog(onDismiss = { addRule = false }, currentArtist = player?.mediaMetadata?.value?.artists?.firstOrNull()?.name) { r -> s.blocks = s.blocks + r; addRule = false }
    if (weekdayDialog) WeekdayDialog(s.weekdayMoods, onDismiss = { weekdayDialog = false }) { s.weekdayMoods = it; weekdayDialog = false }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("לאפס הכל?") },
        text = { Text("כל ההגדרות של הפרופיל ${profile.name} חוזרות לברירת מחדל. ההיסטוריה שלך לא נמחקת.") },
        confirmButton = { TextButton(onClick = { s.resetAll(); V2Offline.schedule(ctx); confirmReset = false; toast(ctx, "אופס! הכל אופס ♻️") }) { Text("אפס") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("ביטול") } },
    )
    busy?.let { BusyOverlay(it) }
}

// ====================================================================== building blocks (shared by the v2.1 screens)

@Composable
internal fun Group(emoji: String, title: String, brush: Brush, content: @Composable () -> Unit) {
    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), brush = brush) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 26.sp)
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(sub, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        }
        Text("‹", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            items(options.size) { i ->
                val (v, label) = options[i]
                val on = v == selected
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.14f)).clickable { onPick(v) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(label, color = if (on) Color(0xFF111111) else Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Slider that only writes the setting when the finger lifts (no database write per frame). */
@Composable
internal fun SettingSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: (Float) -> String,
                           startLabel: String, endLabel: String, onCommit: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(label(v), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Slider(value = v, onValueChange = { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onCommit(v) })
        Row(Modifier.fillMaxWidth()) {
            Text(startLabel, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(endLabel, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun WeekdayDialog(current: Map<Int, MoodPreset>, onDismiss: () -> Unit, onSave: (Map<Int, MoodPreset>) -> Unit) {
    var m by remember { mutableStateOf(current) }
    V2Rtl {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("מצב רוח לפי יום בשבוע") },
            text = {
                Column {
                    DAYS.forEach { (letter, iso) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("יום $letter׳", fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val opts = listOf<MoodPreset?>(null) + MoodPreset.entries
                                items(opts.size) { i ->
                                    val p = opts[i]
                                    val on = m[iso] == p
                                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable { m = if (p == null) m - iso else m + (iso to p) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text(p?.let { "${it.emoji} ${it.he}" } ?: "אוטו׳", color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(m) }) { Text("שמור") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
        )
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, currentArtist: String?, onAdd: (BlockRule) -> Unit) {
    var kind by remember { mutableStateOf(BlockKind.ARTIST) }
    var value by remember { mutableStateOf(currentArtist.orEmpty()) }
    var days by remember { mutableStateOf(setOf(5)) }
    var from by remember { mutableFloatStateOf(0f) }
    var to by remember { mutableFloatStateOf(0f) }
    V2Rtl {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("חסימה חדשה") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("🎤 אמן", kind == BlockKind.ARTIST) { kind = BlockKind.ARTIST }
                        Pill("💔 עצובים", kind == BlockKind.SAD_SONGS) { kind = BlockKind.SAD_SONGS }
                        Pill("🔤 מילה", kind == BlockKind.KEYWORD) { kind = BlockKind.KEYWORD }
                    }
                    if (kind != BlockKind.SAD_SONGS) {
                        OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            label = { Text(if (kind == BlockKind.ARTIST) "שם האמן (בדיוק כמו באפליקציה)" else "מילה בשם השיר") })
                    }
                    Text("באילו ימים", modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        DAYS.forEach { (l, iso) ->
                            val on = iso in days
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp).clip(CircleShape)
                                .background(if (on) V2Colors.purple else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { days = if (on) days - iso else days + iso }) { Text(l, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    Text("משעה ${hhmm(from.roundToInt() * 60)} עד ${hhmm(to.roundToInt() * 60)}" + if (from.roundToInt() == to.roundToInt()) " (כל היום)" else "",
                        modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
                    Slider(value = from, onValueChange = { from = it }, valueRange = 0f..23f, steps = 22)
                    Slider(value = to, onValueChange = { to = it }, valueRange = 0f..23f, steps = 22)
                }
            },
            confirmButton = {
                TextButton(enabled = days.isNotEmpty() && (kind == BlockKind.SAD_SONGS || value.isNotBlank()), onClick = {
                    onAdd(BlockRule(kind, value.trim(), days, from.roundToInt() * 60, to.roundToInt() * 60))
                }) { Text("הוסף") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
        )
    }
}
