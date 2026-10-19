/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 * Fonts: Heebo and Rubik, SIL Open Font License 1.1 (license texts ship in assets/discover-licenses/).
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.music.discover.v21.theme

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import com.metrolist.music.R
import com.metrolist.music.discover.engine.DailyPick
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings

/** The v2.1 design system themes (סטודיו עיצוב). Colours are ARGB. */
enum class DTheme(val he: String, val emoji: String, val accent: Long, val accent2: Long, val accent3: Long, val bg: Long, val surface: Long) {
    NEON("Neon Night", "🌃", 0xFF8B5CF6, 0xFFEC4899, 0xFF22D3EE, 0xFF0B0716, 0xFF15102A),
    MIZRAHI_GOLD("זהב מזרחי", "👑", 0xFFFACC15, 0xFFF97316, 0xFFE11D48, 0xFF110B02, 0xFF1F1707),
    PHONK_RED("פאנק אדום", "🩸", 0xFFEF4444, 0xFFF97316, 0xFFA855F7, 0xFF0D0505, 0xFF1C0B0B),
    OCEAN("אוקיינוס", "🌊", 0xFF0EA5E9, 0xFF22D3EE, 0xFF6366F1, 0xFF03101B, 0xFF0A1D2D),
    AMOLED("שחור AMOLED", "🖤", 0xFFA78BFA, 0xFFF472B6, 0xFF34D399, 0xFF000000, 0xFF000000),
    SUNSET("שקיעה", "🌅", 0xFFFB7185, 0xFFF59E0B, 0xFF8B5CF6, 0xFF150A12, 0xFF241322),
    FOREST("יער", "🌲", 0xFF22C55E, 0xFFA3E635, 0xFF14B8A6, 0xFF03110A, 0xFF0B1F14),
    DYNAMIC("דינמי מהעטיפה", "🎨", 0xFF8B5CF6, 0xFFEC4899, 0xFF22D3EE, 0xFF0A0A0F, 0xFF16161E),
    CLASSIC("Metrolist קלאסי", "📻", 0xFFED5564, 0xFFED5564, 0xFFED5564, 0xFF000000, 0xFF000000);

    companion object {
        val dailyPool = listOf(NEON, MIZRAHI_GOLD, PHONK_RED, OCEAN, AMOLED, SUNSET, FOREST)
        fun of(id: String?) = entries.firstOrNull { it.name == id } ?: NEON
    }
}

/** Accent swatches for the accent picker (0 = the theme's own). */
val ACCENTS: List<Long> = listOf(0xFF8B5CF6, 0xFFEC4899, 0xFFEF4444, 0xFFF97316, 0xFFFACC15, 0xFFA3E635, 0xFF22C55E, 0xFF14B8A6, 0xFF22D3EE, 0xFF3B82F6, 0xFF6366F1, 0xFFFFFFFF)

object DiscoverDesign {
    fun currentTheme(s: V2Settings): DTheme =
        if (s.randomDailyTheme) DTheme.dailyPool[DailyPick.index(DayClock.dayKey(V2Engine.now()), DTheme.dailyPool.size)] else DTheme.of(s.themeId)

    private val heebo by lazy {
        FontFamily(listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black)
            .map { Font(R.font.discover_heebo, it) })
    }
    private val rubik by lazy {
        FontFamily(listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black)
            .map { Font(R.font.discover_rubik, it) })
    }

    fun family(id: String): FontFamily? = when (id) { "HEEBO" -> heebo; "RUBIK" -> rubik; else -> null }

    fun typography(s: V2Settings): Typography {
        val base = Typography()
        val fam = family(s.font)
        val k = s.fontScale
        fun TextStyle.t(weight: FontWeight? = null) = copy(
            fontFamily = fam ?: fontFamily,
            fontSize = fontSize * k,
            lineHeight = lineHeight * k,
            fontWeight = weight ?: fontWeight,
        )
        val heavy = if (s.themeId == DTheme.CLASSIC.name) null else FontWeight.ExtraBold
        return base.copy(
            displayLarge = base.displayLarge.t(heavy), displayMedium = base.displayMedium.t(heavy), displaySmall = base.displaySmall.t(heavy),
            headlineLarge = base.headlineLarge.t(heavy), headlineMedium = base.headlineMedium.t(heavy), headlineSmall = base.headlineSmall.t(heavy),
            titleLarge = base.titleLarge.t(if (heavy != null) FontWeight.Bold else null), titleMedium = base.titleMedium.t(), titleSmall = base.titleSmall.t(),
            bodyLarge = base.bodyLarge.t(), bodyMedium = base.bodyMedium.t(), bodySmall = base.bodySmall.t(),
            labelLarge = base.labelLarge.t(), labelMedium = base.labelMedium.t(), labelSmall = base.labelSmall.t(),
        )
    }

    fun shapes(s: V2Settings): Shapes {
        val k = s.roundness
        return Shapes(
            extraSmall = RoundedCornerShape((6 * k).dp), small = RoundedCornerShape((10 * k).dp), medium = RoundedCornerShape((16 * k).dp),
            large = RoundedCornerShape((22 * k).dp), extraLarge = RoundedCornerShape((30 * k).dp),
        )
    }

    /** A curated dark scheme on top of the generated tonal palette: deep themed backgrounds, vivid accents. */
    fun curate(base: ColorScheme, t: DTheme, accent: Color, dark: Boolean): ColorScheme {
        if (!dark) return base
        val bg = if (t == DTheme.DYNAMIC) lerp(Color(t.bg), accent, 0.10f) else Color(t.bg)
        val surf = if (t == DTheme.DYNAMIC) lerp(Color(t.surface), accent, 0.14f) else Color(t.surface)
        val a2 = if (t == DTheme.DYNAMIC) base.secondary else Color(t.accent2)
        val a3 = if (t == DTheme.DYNAMIC) base.tertiary else Color(t.accent3)
        fun on(c: Color) = if (c.luminance() > 0.45f) Color(0xFF0B0B0F) else Color.White
        val lift = if (t == DTheme.AMOLED) Color(0xFF121212) else surf
        return base.copy(
            primary = accent, onPrimary = on(accent),
            primaryContainer = lerp(surf, accent, 0.45f), onPrimaryContainer = Color.White,
            secondary = a2, onSecondary = on(a2),
            secondaryContainer = lerp(surf, accent, 0.32f), onSecondaryContainer = Color.White,
            tertiary = a3, onTertiary = on(a3),
            tertiaryContainer = lerp(surf, a3, 0.35f), onTertiaryContainer = Color.White,
            background = bg, onBackground = Color(0xFFF4F1FA),
            surface = bg, onSurface = Color(0xFFF4F1FA),
            surfaceVariant = lerp(lift, accent, 0.10f), onSurfaceVariant = Color(0xFFBDB6CC),
            surfaceContainerLowest = bg, surfaceContainerLow = lerp(bg, lift, 0.5f), surfaceContainer = lift,
            surfaceContainerHigh = lerp(lift, accent, 0.07f), surfaceContainerHighest = lerp(lift, accent, 0.13f),
            surfaceBright = lerp(lift, Color.White, 0.08f), surfaceDim = bg,
            outline = lerp(Color(0xFF6B6480), accent, 0.25f), outlineVariant = lerp(lift, accent, 0.25f),
            inverseSurface = Color(0xFFF4F1FA), inversePrimary = lerp(accent, Color.Black, 0.3f),
        )
    }
}

private fun settings(ctx: Context) = V2Settings.get(ctx)

/**
 * HOOK TARGET (hook "theme", in Metrolist's MetrolistTheme): replaces the final MaterialTheme(...) call, so the whole app
 * (every upstream screen included) gets the Discover colours, Hebrew font, shapes and motion. "Metrolist קלאסי" keeps
 * Metrolist's own colours but still gets the font, roundness and text-size settings.
 */
@Composable
fun DiscoverTheme(colorScheme: ColorScheme, darkTheme: Boolean, themeColor: Color, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val s = remember(version) { settings(ctx) }
    val t = remember(version) { DiscoverDesign.currentTheme(s) }
    val accent = when {
        s.accent != 0 -> Color(s.accent)
        t == DTheme.DYNAMIC -> themeColor
        else -> Color(t.accent)
    }
    val generated = rememberDynamicColorScheme(
        seedColor = accent,
        isDark = darkTheme,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
        style = PaletteStyle.Vibrant,
    )
    val scheme = remember(generated, t, accent, darkTheme, colorScheme) {
        if (t == DTheme.CLASSIC) colorScheme else DiscoverDesign.curate(generated, t, accent, darkTheme)
    }
    val typography = remember(version) { DiscoverDesign.typography(s) }
    val shapes = remember(version) { DiscoverDesign.shapes(s) }
    if (s.motionExpressive) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), shapes = shapes, typography = typography, content = content)
    } else {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
    }
}

/** Small looks for upstream chrome (hooks "navbar" and "miniplayer"). */
object DiscoverChrome {
    /** Bottom navigation: rounded top corners + a thin gradient light line along the top edge. */
    @Composable
    fun navBar(modifier: Modifier): Modifier {
        val ctx = LocalContext.current
        val version by V2Settings.version.collectAsStateWithLifecycle()
        val s = remember(version) { settings(ctx) }
        if (!s.navGlow || DiscoverDesign.currentTheme(s) == DTheme.CLASSIC) return modifier
        val c = MaterialTheme.colorScheme
        val r = (26 * s.roundness).dp
        val brush = Brush.horizontalGradient(listOf(c.primary.copy(alpha = 0f), c.primary, c.secondary, c.tertiary, c.tertiary.copy(alpha = 0f)))
        return modifier
            .clip(RoundedCornerShape(topStart = r, topEnd = r))
            .drawWithContent {
                drawContent()
                drawLine(brush, Offset(0f, 1.5f), Offset(size.width, 1.5f), strokeWidth = 3f)
            }
    }

    /** Mini player: gradient glow border instead of the plain outline. */
    @Composable
    fun miniBorder(outlineColor: Color): Modifier {
        val ctx = LocalContext.current
        val version by V2Settings.version.collectAsStateWithLifecycle()
        val s = remember(version) { settings(ctx) }
        val shape = RoundedCornerShape(32.dp)
        if (!s.miniGlow || DiscoverDesign.currentTheme(s) == DTheme.CLASSIC) return Modifier.border(1.dp, outlineColor.copy(alpha = 0.3f), shape)
        val c = MaterialTheme.colorScheme
        return Modifier.border(1.5.dp, Brush.linearGradient(listOf(c.primary, c.secondary, c.tertiary)), shape)
    }
}
