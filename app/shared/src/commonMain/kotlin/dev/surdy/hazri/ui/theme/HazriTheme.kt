package dev.surdy.hazri.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.generated.resources.Res
import dev.surdy.hazri.generated.resources.jetbrains_mono_medium
import dev.surdy.hazri.generated.resources.jetbrains_mono_regular
import dev.surdy.hazri.generated.resources.jetbrains_mono_semibold
import dev.surdy.hazri.generated.resources.manrope_variable
import org.jetbrains.compose.resources.Font

/**
 * The palette, straight from the mockups in `design/`.
 *
 * A flat object rather than a Material colour scheme because most of these have no Material
 * role: there is no Material slot for "the tint a -70 dBm cell gets" or "the border on the
 * card of the node that is currently winning". [HazriTheme] still installs a Material dark
 * scheme underneath so that Material 3 components that do appear are not white on white.
 */
object HazriColors {
    val background = Color(0xFF0F1115)
    val surface = Color(0xFF171A20)
    val surfaceRaised = Color(0xFF22262F)
    val surfaceSunken = Color(0xFF12151A)
    val border = Color(0xFF242933)
    val borderStrong = Color(0xFF2C313C)

    val text = Color(0xFFECEEF2)
    val textSecondary = Color(0xFF8B92A0)
    val textTertiary = Color(0xFFAEB4C0)
    val textQuiet = Color(0xFFC5CAD4)
    val muted = Color(0xFF646B79)
    val inactive = Color(0xFF7A8190)
    val dim = Color(0xFF4D5462)
    val gridLabel = Color(0xFF6B7280)

    val accent = Color(0xFF45D3C2)
    val accentDim = Color(0xFF2A6F68)
    val amber = Color(0xFFE5B85A)
    val red = Color(0xFFE0766C)

    val bannerBackground = Color(0xFF12211F)
    val bannerBorder = Color(0xFF1F4A45)
    val bannerText = Color(0xFFCFE9E5)

    val navBackground = Color(0xFF12151A)
    val navBorder = Color(0xFF1F232C)

    /** Track behind every strength bar. */
    val barTrack = Color(0xFF22262F)

    /** Fill for a bar that is not the leader, on the Survey screen. */
    val barSecondary = Color(0xFF3A5F5B)

    /**
     * The heat ramp, weakest to strongest, spanning -95 to -50 dBm.
     *
     * Sequential rather than diverging: there is no meaningful midpoint in an RSSI, only
     * "quieter" and "louder", and the legend on the Coverage screen shows exactly these
     * five swatches.
     */
    val heat = listOf(
        Color(0xFF171A20),
        Color(0xFF1B343A),
        Color(0xFF1E4F4C),
        Color(0xFF23716A),
        Color(0xFF2A9D90),
    )
}

/** The two families the mockups use. */
data class HazriFonts(
    val ui: FontFamily,
    val mono: FontFamily,
)

/** Fonts, so a screen can ask for the mono family without importing the resource ids. */
val LocalHazriFonts = staticCompositionLocalOf<HazriFonts> {
    error("HazriFonts requested outside HazriTheme")
}

/**
 * Manrope for the UI, JetBrains Mono for every number.
 *
 * Manrope ships from Google Fonts only as a variable font, and the family here registers it
 * once at [FontWeight.Normal]. Registering the same file again at 700 would tell Compose the
 * file *is* bold and suppress the synthetic weight it would otherwise apply, so headings
 * would come out light. One entry plus Compose's own synthesis is the shape that renders
 * the mockup's 700 and 800 headings correctly on every API level.
 *
 * JetBrains Mono ships as static instances, so its three weights are three files and no
 * synthesis is involved — which matters, because the numbers are the thing the eye lands on.
 */
@Composable
private fun rememberFonts(): HazriFonts = HazriFonts(
    ui = FontFamily(Font(Res.font.manrope_variable, FontWeight.Normal)),
    mono = FontFamily(
        Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    ),
)

/** The dark theme. There is no light theme: this is a tool used while walking a house. */
@Composable
fun HazriTheme(content: @Composable () -> Unit) {
    val fonts = rememberFonts()
    val scheme = darkColorScheme(
        primary = HazriColors.accent,
        onPrimary = HazriColors.background,
        secondary = HazriColors.accentDim,
        background = HazriColors.background,
        onBackground = HazriColors.text,
        surface = HazriColors.surface,
        onSurface = HazriColors.text,
        surfaceVariant = HazriColors.surfaceRaised,
        onSurfaceVariant = HazriColors.textSecondary,
        outline = HazriColors.border,
        error = HazriColors.red,
    )

    CompositionLocalProvider(LocalHazriFonts provides fonts) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyFor(fonts),
            content = content,
        )
    }
}

private fun typographyFor(fonts: HazriFonts): Typography {
    val base = TextStyle(fontFamily = fonts.ui, color = HazriColors.text)
    return Typography(
        headlineLarge = base.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
        titleMedium = base.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        titleSmall = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
        bodyMedium = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        bodySmall = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
    )
}

/** A text style in JetBrains Mono. Every number in the app goes through this. */
@Composable
fun monoStyle(
    size: Int,
    weight: FontWeight = FontWeight.SemiBold,
    color: Color = HazriColors.text,
    letterSpacing: Double = 0.0,
): TextStyle = TextStyle(
    fontFamily = LocalHazriFonts.current.mono,
    fontSize = size.sp,
    fontWeight = weight,
    color = color,
    letterSpacing = letterSpacing.sp,
)
