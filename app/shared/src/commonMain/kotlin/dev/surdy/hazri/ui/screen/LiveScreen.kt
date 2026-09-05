package dev.surdy.hazri.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.data.AppSettings
import dev.surdy.hazri.data.RateUnit
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.ui.component.AccentBanner
import dev.surdy.hazri.ui.component.Fmt
import dev.surdy.hazri.ui.component.HazriCard
import dev.surdy.hazri.ui.component.MonoText
import dev.surdy.hazri.ui.component.ScreenHeader
import dev.surdy.hazri.ui.component.SegmentedControl
import dev.surdy.hazri.ui.component.Sparkline
import dev.surdy.hazri.ui.component.StrengthBar
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.ui.theme.HazriIcons
import dev.surdy.hazri.vm.LiveState
import dev.surdy.hazri.vm.NodeLive
import kotlin.math.roundToInt

/**
 * The home screen: every node the phone can hear, strongest first, and the margin between
 * the top two.
 *
 * A node whose smoothed reading is below the verdict floor is drawn in grey rather than
 * teal, which is the mockup's way of saying "this one is not really hearing you".
 */
@Composable
fun LiveScreen(
    state: LiveState,
    sources: List<SourceKind>,
    onSelectSource: (SourceKind) -> Unit,
    onOpenNode: (NodeLive) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Live signals",
            subtitle = subtitleFor(state),
            trailing = {
                SegmentedControl(
                    options = sources.map(::sourceLabel),
                    selectedIndex = sources.indexOf(state.sourceKind).coerceAtLeast(0),
                    onSelect = { index -> onSelectSource(sources[index]) },
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp),
        ) {
            state.error?.let { message ->
                item { ErrorBanner(message) }
            }
            state.lead?.let { lead ->
                item {
                    AccentBanner {
                        Icon(
                            HazriIcons.Check,
                            contentDescription = null,
                            tint = HazriColors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        LeadText(
                            best = lead.best.displayName,
                            margin = lead.marginDb,
                            runnerUp = lead.runnerUp?.displayName,
                        )
                    }
                }
            }
            items(state.nodes, key = { it.node.id.value }) { node ->
                NodeCard(
                    node = node,
                    leading = node == state.nodes.firstOrNull(),
                    rate = AppSettings.rateUnit(state.sourceKind),
                ) { onOpenNode(node) }
            }
            if (state.nodes.isEmpty()) {
                item { EmptyLive(state) }
            }
        }
    }
}

@Composable
private fun LeadText(best: String, margin: Double?, runnerUp: String?) {
    if (margin == null || runnerUp == null) {
        Text(
            text = "$best is the only node in range",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = HazriColors.bannerText,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$best leads by ",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = HazriColors.bannerText,
        )
        MonoText("${margin.roundToInt()} dB", size = 13, color = HazriColors.accent)
        Text(
            text = " over $runnerUp",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = HazriColors.bannerText,
        )
    }
}

@Composable
private fun NodeCard(node: NodeLive, leading: Boolean, rate: RateUnit, onClick: () -> Unit) {
    val weak = node.smoothedRssi <= WEAK_DBM
    val accent = if (weak) HazriColors.inactive else HazriColors.accent

    HazriCard(highlighted = leading && !weak, onClick = onClick, padding = 12) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    node.node.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = HazriColors.text,
                )
                Text(
                    node.subtitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = HazriColors.textSecondary,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                MonoText(
                    text = Fmt.rssi(node.smoothedRssi),
                    size = 26,
                    color = accent,
                    letterSpacing = -0.5,
                )
                Text(
                    " dBm",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HazriColors.textSecondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StrengthBar(
                fraction = node.strengthFraction(),
                modifier = Modifier.weight(1f),
                color = accent,
            )
            Sparkline(
                values = node.history.map { it.rssi },
                modifier = Modifier.size(width = 88.dp, height = 22.dp),
                color = accent,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MetaText(Fmt.distance(node.distanceMetres))
            MetaText("${rate.convert(node.stats.packetRate).roundToInt()} ${rate.label}")
            MetaText("σ ${Fmt.one(node.stats.sigma)} dB")
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HazriColors.textSecondary)
}

@Composable
private fun ErrorBanner(message: String) {
    HazriCard(padding = 14) {
        Text("Source problem", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.red)
        Text(message, fontSize = 13.sp, color = HazriColors.textTertiary)
    }
}

@Composable
private fun EmptyLive(state: LiveState) {
    HazriCard(padding = 16) {
        Text(
            text = if (state.isRunning) "Listening" else "Not listening",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = HazriColors.text,
        )
        Text(
            text = when (state.sourceKind) {
                SourceKind.DIRECT ->
                    "No node advertisements yet. Check Bluetooth is on and the permission was granted."
                SourceKind.MQTT ->
                    "No reports for this phone id yet. Check the broker and the phone id in Settings."
                SourceKind.SIMULATED -> "The simulator is starting."
            },
            fontSize = 13.sp,
            color = HazriColors.textTertiary,
        )
    }
}

private fun subtitleFor(state: LiveState): String {
    val label = sourceLabel(state.sourceKind)
    val nodes = state.nodes.size
    val rate = AppSettings.rateUnit(state.sourceKind)
    val figure = rate.convert(state.packetsPerSecond).roundToInt()
    // The simulated walker's room belongs on the Survey screen, where it decides what a
    // recording means. Here it would wrap the line under the source picker for no gain.
    return "$label · $nodes ${if (nodes == 1) "node" else "nodes"} · $figure ${rate.label}"
}

/** The label the source picker and the Compare tool both use. */
fun sourceLabel(kind: SourceKind): String = when (kind) {
    SourceKind.DIRECT -> "Direct"
    SourceKind.MQTT -> "MQTT"
    SourceKind.SIMULATED -> "Sim"
}

/** Below this a node is drawn in grey: it is present but not usefully hearing the phone. */
private const val WEAK_DBM = -85.0
