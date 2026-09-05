package dev.surdy.hazri.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.domain.Verdict
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.ui.theme.HazriIcons

/** The title block every screen opens with. [onBack] adds the chevron from the mockup. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 0.dp)
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(HazriIcons.Back, contentDescription = "Back", tint = HazriColors.text, modifier = Modifier.size(22.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.4).sp,
                    color = HazriColors.text,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = HazriColors.textSecondary,
                    )
                }
            }
        }
        if (trailing != null) trailing()
    }
}

/** The small uppercase label above a list section. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = HazriColors.textSecondary,
        modifier = modifier.padding(start = 2.dp, end = 2.dp, top = 4.dp),
    )
}

/**
 * The card every panel in the app is made of.
 *
 * [highlighted] swaps the border for the teal one the mockups give to the node that is
 * currently winning and to the recording panel.
 */
@Composable
fun HazriCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    corner: Int = 14,
    padding: Int = 14,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HazriColors.surface)
            .border(1.dp, if (highlighted) HazriColors.accentDim else HazriColors.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The teal banner that carries the lead line and the running verdict. */
@Composable
fun AccentBanner(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HazriColors.bannerBackground)
            .border(1.dp, HazriColors.bannerBorder, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The Direct / MQTT / Simulated picker in the Live header. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(HazriColors.surface)
            .border(1.dp, HazriColors.border, shape)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) HazriColors.surfaceRaised else Color.Transparent)
                    .clickable { onSelect(index) }
                    .defaultMinSize(minHeight = 38.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) HazriColors.text else HazriColors.inactive,
                )
            }
        }
    }
}

/** A room chip. [dashed] is the "+ Room" affordance. */
@Composable
fun RoomChip(
    label: String,
    selected: Boolean = false,
    dashed: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) HazriColors.accent else if (dashed) Color.Transparent else HazriColors.surface)
            .border(1.dp, if (selected) HazriColors.accent else if (dashed) HazriColors.borderStrong else HazriColors.border, shape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                selected -> HazriColors.background
                dashed -> HazriColors.textSecondary
                else -> HazriColors.textQuiet
            },
        )
    }
}

/** The thin proportional bar under every RSSI reading. */
@Composable
fun StrengthBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = HazriColors.accent,
) {
    val shape = RoundedCornerShape(3.dp)
    Box(modifier = modifier.height(6.dp).clip(shape).background(HazriColors.barTrack)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(shape)
                .background(color),
        )
    }
}

/** One of the four tiles under the Node detail chart. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HazriColors.surfaceSunken)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HazriColors.textSecondary)
        MonoText(value, size = 14)
    }
}

/** A row in the Tools list: icon tile, title, subtitle, chevron. */
@Composable
fun ToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HazriColors.surface)
            .border(1.dp, HazriColors.border, shape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 62.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(HazriColors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = HazriColors.accent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = HazriColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(HazriIcons.Forward, contentDescription = null, tint = HazriColors.dim, modifier = Modifier.size(18.dp))
    }
}

/** The full-width teal action. Height 46 dp, comfortably over the 44 dp floor. */
@Composable
fun PrimaryButton(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) HazriColors.accent else HazriColors.surfaceRaised)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (enabled) HazriColors.background else HazriColors.muted
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = content)
    }
}

/** The outlined action. Used in pairs on Node detail. */
@Composable
fun SecondaryButton(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .border(1.dp, HazriColors.borderStrong, shape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (enabled) HazriColors.text else HazriColors.muted
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = HazriColors.textTertiary, modifier = Modifier.size(18.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = content)
    }
}

/** A small coloured dot. Recording, MQTT connected, beacon advertising. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
}

/** The colour a verdict is drawn in, everywhere in the app. */
fun verdictColor(verdict: Verdict): Color = when (verdict) {
    Verdict.CLEAR -> HazriColors.accent
    Verdict.TIGHT -> HazriColors.amber
    Verdict.BLIND -> HazriColors.red
}

/** "Clear", "Tight", "Blind". */
fun verdictLabel(verdict: Verdict): String = when (verdict) {
    Verdict.CLEAR -> "Clear"
    Verdict.TIGHT -> "Tight"
    Verdict.BLIND -> "Blind"
}

/** A fixed-width divider used between suggestion entries. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(HazriColors.border))
}
