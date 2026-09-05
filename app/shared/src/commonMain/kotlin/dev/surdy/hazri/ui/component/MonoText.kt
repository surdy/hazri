package dev.surdy.hazri.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.ui.theme.monoStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/** Every number in the app. JetBrains Mono, so digits do not jitter as they change. */
@Composable
fun MonoText(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.SemiBold,
    color: Color = HazriColors.text,
    letterSpacing: Double = 0.0,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        style = monoStyle(size, weight, color, letterSpacing),
        textAlign = align,
        modifier = modifier,
    )
}

/**
 * Number formatting, in one place.
 *
 * Kotlin common has no `String.format`, and every screen needs the same three shapes: an
 * RSSI to no decimals, a statistic to one, and a distance that becomes "> 10 m" once the
 * estimate stops meaning anything.
 */
object Fmt {
    /** A signed integer with the typographic minus the mockups use. */
    fun rssi(value: Double): String = signed(value.roundToInt())

    /** A signed integer, typographic minus. */
    fun signed(value: Int): String = if (value < 0) "−${abs(value)}" else value.toString()

    /** One decimal place, typographic minus. */
    fun one(value: Double): String {
        val scaled = (value * 10).roundToInt()
        val whole = abs(scaled) / 10
        val tenth = abs(scaled) % 10
        val sign = if (scaled < 0) "−" else ""
        return "$sign$whole.$tenth"
    }

    /** Metres, or the cut-off marker once the model has stopped being informative. */
    fun distance(metres: Double, limit: Double = 10.0): String =
        if (metres > limit) "> ${limit.roundToInt()} m" else "${one(metres)} m"

    /** `mm:ss`, for the survey timer. */
    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${pad(minutes)}:${pad(seconds)}"
    }

    /** "2 min ago", "yesterday". Coarse on purpose: precision here would be noise. */
    fun age(millis: Long): String = when {
        millis < 60_000L -> "just now"
        millis < 3_600_000L -> "${millis / 60_000L} min ago"
        millis < 86_400_000L -> "${millis / 3_600_000L} h ago"
        millis < 172_800_000L -> "yesterday"
        else -> "${millis / 86_400_000L} days ago"
    }

    private fun pad(value: Long): String = if (value < 10) "0$value" else value.toString()
}
