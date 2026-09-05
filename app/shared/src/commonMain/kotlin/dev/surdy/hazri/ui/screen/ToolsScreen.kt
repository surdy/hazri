package dev.surdy.hazri.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.source.MqttConnectionState
import dev.surdy.hazri.ui.Destination
import dev.surdy.hazri.ui.component.MonoText
import dev.surdy.hazri.ui.component.ScreenHeader
import dev.surdy.hazri.ui.component.StatusDot
import dev.surdy.hazri.ui.component.ToolRow
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.ui.theme.HazriIcons
import dev.surdy.hazri.vm.ToolsState
import kotlin.math.roundToInt

/** The Tools index: two status pills and the list of everything that is not a tab. */
@Composable
fun ToolsScreen(state: ToolsState, onOpen: (Destination) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Tools")

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        modifier = Modifier.weight(1f),
                        title = "MQTT",
                        detail = mqttDetail(state),
                        healthy = state.mqtt is MqttConnectionState.Connected,
                    )
                    StatusPill(
                        modifier = Modifier.weight(1f),
                        title = "Beacon",
                        detail = "not checked",
                        healthy = false,
                    )
                }
            }

            item {
                ToolRow(
                    icon = HazriIcons.Calibrate,
                    title = "Calibrate reference",
                    subtitle = "Stand 1 m from a node, capture the power",
                    onClick = { onOpen(Destination.Calibrate) },
                )
            }
            item {
                ToolRow(
                    icon = HazriIcons.Beacon,
                    title = "Beacon check",
                    subtitle = "Is this phone advertising? Interval, UUID",
                    onClick = { onOpen(Destination.BeaconCheck) },
                )
            }
            item {
                ToolRow(
                    icon = HazriIcons.Broker,
                    title = "MQTT inspector",
                    subtitle = "Live messages from espresense/…",
                    onClick = { onOpen(Destination.MqttInspector) },
                )
            }
            item {
                ToolRow(
                    icon = HazriIcons.Compare,
                    title = "Compare sources",
                    subtitle = "Direct scan vs what nodes report, per node",
                    onClick = { onOpen(Destination.CompareSources) },
                )
            }
            item {
                ToolRow(
                    icon = HazriIcons.Tag,
                    title = "Nodes & rooms",
                    subtitle = "Aliases, room assignment, hide nodes",
                    onClick = { onOpen(Destination.NodesAndRooms) },
                )
            }
            item {
                ToolRow(
                    icon = HazriIcons.Export,
                    title = "Export session",
                    subtitle = "CSV or JSON of every survey",
                    onClick = { onOpen(Destination.ExportSession) },
                )
            }
            item {
                ToolRow(
                    icon = HazriIcons.Settings,
                    title = "Settings",
                    subtitle = "Broker, phone ID, smoothing, thresholds",
                    onClick = { onOpen(Destination.Settings) },
                )
            }
        }
    }
}

@Composable
private fun StatusPill(modifier: Modifier, title: String, detail: String, healthy: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    val background = if (healthy) HazriColors.bannerBackground else HazriColors.surface
    val borderColor: Color = if (healthy) HazriColors.bannerBorder else HazriColors.border

    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (healthy) HazriColors.accent else HazriColors.muted)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
            MonoText(detail, size = 11, weight = FontWeight.Normal, color = HazriColors.textSecondary)
        }
    }
}

private fun mqttDetail(state: ToolsState): String = when (val connection = state.mqtt) {
    is MqttConnectionState.Connected ->
        "${connection.host} · ${state.mqttMessageRate.roundToInt()} msg/min"
    MqttConnectionState.Connecting -> "connecting"
    MqttConnectionState.Disconnected -> "not connected"
    is MqttConnectionState.Failed -> connection.reason.take(28)
}
