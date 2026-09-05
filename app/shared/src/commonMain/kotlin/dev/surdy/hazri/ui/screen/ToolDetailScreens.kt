package dev.surdy.hazri.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.data.NodeRecord
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.source.MqttConnectionState
import dev.surdy.hazri.ui.component.Fmt
import dev.surdy.hazri.ui.component.HazriCard
import dev.surdy.hazri.ui.component.LabelledField
import dev.surdy.hazri.ui.component.MonoText
import dev.surdy.hazri.ui.component.PrimaryButton
import dev.surdy.hazri.ui.component.ScreenHeader
import dev.surdy.hazri.ui.component.SecondaryButton
import dev.surdy.hazri.ui.component.SectionLabel
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.vm.SourceDelta
import dev.surdy.hazri.vm.ToolsState

/** A screen body with a header and a scrolling column. Every tool sub-screen is one. */
@Composable
private fun ToolScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title, subtitle, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            content = content,
        )
    }
}

/**
 * Calibrate reference: pick a node, then the capture happens on that node's detail screen.
 *
 * Deliberately a chooser rather than a second calibration implementation. The capture needs
 * the live chart beside it to be trustworthy, and that already exists on Node detail.
 */
@Composable
fun CalibrateToolScreen(
    nodes: List<NodeRecord>,
    onBack: () -> Unit,
    onOpenNode: (NodeId) -> Unit,
) {
    ToolScaffold("Calibrate reference", "Pick the node to stand 1 m from", onBack) {
        item {
            HazriCard(padding = 16) {
                Text(
                    "What this measures",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = HazriColors.text,
                )
                Text(
                    "The mean RSSI between this phone and one node at one metre. Because the " +
                        "phone advertises as an iBeacon, ESPresense ranges it from the power the " +
                        "phone advertises — so the number belongs in the Home Assistant " +
                        "Companion app's BLE transmitter settings, not in the node's ref_rssi.",
                    fontSize = 13.sp,
                    color = HazriColors.textTertiary,
                    lineHeight = 19.sp,
                )
            }
        }
        item { SectionLabel("Nodes") }
        items(nodes.size) { index ->
            val node = nodes[index]
            HazriCard(padding = 14, onClick = { onOpenNode(node.nodeId) }) {
                Text(node.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
                Text(
                    "${node.id} · room ${node.espresenseRoom}",
                    fontSize = 12.sp,
                    color = HazriColors.textSecondary,
                )
            }
        }
    }
}

/**
 * Beacon check — a placeholder, and honest about it.
 *
 * The phone cannot hear its own advertisement, so there is no way for Hazri to confirm it
 * is advertising from inside the app. Confirming it needs either a second device or a node
 * on the network reporting this phone's fingerprint back, which is exactly what MQTT mode
 * already shows. This screen explains that rather than inventing a status light.
 */
@Composable
fun BeaconCheckScreen(phoneId: String, onBack: () -> Unit) {
    ToolScaffold("Beacon check", "Pending hardware", onBack) {
        item {
            HazriCard(padding = 16) {
                Text(
                    "Why this is empty",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = HazriColors.text,
                )
                Text(
                    "A phone cannot scan for its own advertisement, so nothing in this app can " +
                        "confirm the beacon is transmitting. The check that works is indirect: " +
                        "a node has to hear the phone and publish it. Connect MQTT mode, set the " +
                        "phone id below, and any reading at all on the Live screen is proof the " +
                        "beacon is up.",
                    fontSize = 13.sp,
                    color = HazriColors.textTertiary,
                    lineHeight = 19.sp,
                )
            }
        }
        item {
            HazriCard(padding = 16) {
                Text("Phone id", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HazriColors.textSecondary)
                MonoText(phoneId.ifBlank { "not set" }, size = 13)
                Text(
                    "Set in the Home Assistant Companion app under BLE Transmitter. The " +
                        "fingerprint ESPresense uses is iBeacon:<uuid>-<major>-<minor>, matching " +
                        "the UUID, major and minor configured there.",
                    fontSize = 12.sp,
                    color = HazriColors.muted,
                    lineHeight = 17.sp,
                )
            }
        }
        item {
            HazriCard(padding = 16) {
                Text(
                    "Waiting on hardware",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = HazriColors.amber,
                )
                Text(
                    "Advertising interval and transmit power are readable from the platform on " +
                        "Android and are not surfaced yet. That work is worth doing once there " +
                        "is a node to verify the readings against.",
                    fontSize = 12.sp,
                    color = HazriColors.textTertiary,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

/**
 * The raw MQTT feed, newest first, with each node's health above it.
 *
 * The first place to look when MQTT mode shows nothing: the telemetry block says which
 * nodes are talking to the broker at all, and the message list says what they are saying
 * about this phone. A node present in the first and absent from the second is a phone-id
 * problem, not a network one.
 */
@Composable
fun MqttInspectorScreen(state: ToolsState, onBack: () -> Unit) {
    ToolScaffold("MQTT inspector", subscriptionSummary(state), onBack) {
        if (state.telemetry.isNotEmpty()) {
            item { SectionLabel("Nodes") }
            item {
                HazriCard(padding = 14) {
                    state.telemetry.entries.sortedBy { it.key }.forEach { (room, health) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MonoText(room, size = 12)
                            MonoText(
                                text = listOfNotNull(
                                    health.uptime?.let { "up ${Fmt.duration(it * 1000)}" },
                                    health.freeHeap?.let { "${it / 1024} KB free" },
                                    health.ver,
                                ).joinToString(" · ").ifEmpty { "—" },
                                size = 11,
                                weight = FontWeight.Normal,
                                color = HazriColors.textSecondary,
                            )
                        }
                    }
                }
            }
            item { SectionLabel("Messages") }
        }
        if (state.inspector.isEmpty()) {
            item {
                HazriCard(padding = 16) {
                    Text("No messages", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
                    Text(
                        "Nothing has arrived on the subscribed topics. Check the broker in " +
                            "Settings, then check the phone is advertising.",
                        fontSize = 13.sp,
                        color = HazriColors.textTertiary,
                    )
                }
            }
        }
        items(state.inspector.size) { index ->
            val message = state.inspector[index]
            HazriCard(padding = 12) {
                MonoText(message.topic, size = 11, weight = FontWeight.Medium, color = HazriColors.accent)
                MonoText(message.payload, size = 11, weight = FontWeight.Normal, color = HazriColors.textTertiary)
            }
        }
    }
}

/**
 * Compare sources: the same nodes read two ways, differenced.
 *
 * The number that matters is the delta column. Once it is stable and small, direct-scan
 * mode can be trusted for placement work and the broker is not needed in the field.
 */
@Composable
fun CompareSourcesScreen(
    state: ToolsState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    ToolScaffold("Compare sources", "Per-node delta between two streams", onBack) {
        item {
            HazriCard(padding = 16) {
                Text(
                    if (state.comparing) {
                        "${state.comparisonPrimary} vs ${state.comparisonSecondary}"
                    } else {
                        "Not comparing"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = HazriColors.text,
                )
                Text(
                    "Runs a second source alongside the one the app is on and differences the " +
                        "two per node. With no hardware present both are simulated walks with " +
                        "different seeds, which exercises the screen without pretending to be " +
                        "a measurement.",
                    fontSize = 12.sp,
                    color = HazriColors.textTertiary,
                    lineHeight = 17.sp,
                )
                if (state.comparing) {
                    SecondaryButton("Stop", modifier = Modifier.fillMaxWidth(), onClick = onStop)
                } else {
                    PrimaryButton("Start comparison", onClick = onStart)
                }
            }
        }
        if (state.deltas.isNotEmpty()) {
            item { SectionLabel("Per node") }
            items(state.deltas.size) { index -> DeltaRow(state, state.deltas[index]) }
        }
    }
}

@Composable
private fun DeltaRow(state: ToolsState, delta: SourceDelta) {
    HazriCard(padding = 12) {
        Text(delta.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(state.comparisonPrimary, fontSize = 11.sp, color = HazriColors.textSecondary)
                MonoText(delta.primaryRssi?.let { Fmt.one(it) } ?: "—", size = 15)
            }
            Column {
                Text(state.comparisonSecondary, fontSize = 11.sp, color = HazriColors.textSecondary)
                MonoText(delta.secondaryRssi?.let { Fmt.one(it) } ?: "—", size = 15)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("delta", fontSize = 11.sp, color = HazriColors.textSecondary)
                MonoText(
                    text = delta.deltaDb?.let { Fmt.one(it) } ?: "—",
                    size = 15,
                    color = HazriColors.accent,
                )
            }
        }
    }
}

/**
 * Nodes and rooms.
 *
 * The screen that makes direct-scan mode usable without a broker, and the reason it has two
 * fields rather than one.
 *
 *  - **Name** is the user's, shown everywhere and published nowhere. "Under the stairs" is
 *    fine. Setting it marks the record so a retained config cannot rename it back.
 *  - **ESPresense room** is the firmware's, and it is the segment in
 *    `espresense/rooms/<room>/<setting>/set`. Deriving it from the name is how settings end
 *    up published to a room that does not exist, so it is entered separately and is
 *    slugified on the way in, exactly as the firmware does.
 *
 * A node discovered by scanning arrives as `node-<major>-<minor>` with no room at all —
 * nothing in a BLE advertisement carries one. This is where it gets one, unless the broker
 * announces it first.
 */
@Composable
fun NodesAndRoomsScreen(
    state: ToolsState,
    onBack: () -> Unit,
    onRename: (NodeId, String) -> Unit,
    onSetRoom: (NodeId, String) -> Unit,
    onSetHidden: (NodeId, Boolean) -> Unit,
    onClearUnidentified: () -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }

    ToolScaffold("Nodes & rooms", "${state.nodes.size} known", onBack) {
        items(state.nodes.size) { index ->
            val node = state.nodes[index]
            HazriCard(padding = 14) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        node.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (node.hidden) HazriColors.muted else HazriColors.text,
                    )
                    MonoText(
                        text = node.beaconFingerprint ?: node.id,
                        size = 11,
                        weight = FontWeight.Normal,
                        color = HazriColors.textSecondary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText(
                            text = "room ${node.espresenseRoom}",
                            size = 11,
                            weight = FontWeight.Normal,
                            color = HazriColors.textTertiary,
                        )
                        Text(
                            text = if (node.roomIsConfirmed) "confirmed" else "unconfirmed",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (node.roomIsConfirmed) HazriColors.accent else HazriColors.amber,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        label = "Edit",
                        modifier = Modifier.weight(1f),
                        onClick = { editing = if (editing == node.id) null else node.id },
                    )
                    SecondaryButton(
                        label = if (node.hidden) "Show" else "Hide",
                        modifier = Modifier.weight(1f),
                        onClick = { onSetHidden(node.nodeId, !node.hidden) },
                    )
                }
                if (editing == node.id) {
                    NodeEditor(
                        node = node,
                        onCancel = { editing = null },
                        onSave = { name, room ->
                            onRename(node.nodeId, name)
                            onSetRoom(node.nodeId, room)
                            editing = null
                        },
                    )
                }
            }
        }
        if (state.nodes.isEmpty()) {
            item {
                HazriCard(padding = 16) {
                    Text("No nodes yet", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
                    Text(
                        "Nodes appear here as soon as any source hears one.",
                        fontSize = 13.sp,
                        color = HazriColors.textTertiary,
                    )
                }
            }
        }
        if (state.unidentified.isNotEmpty()) {
            item { SectionLabel("Heard, not a node") }
            item {
                HazriCard(padding = 14) {
                    Text(
                        "Advertisers the scan could not tie to ESPresense. Shown rather than " +
                            "dropped, so the identity mapping can be checked against real " +
                            "hardware.",
                        fontSize = 12.sp,
                        color = HazriColors.textTertiary,
                        lineHeight = 17.sp,
                    )
                    SecondaryButton(
                        label = "Clear",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onClearUnidentified,
                    )
                    state.unidentified.take(UNIDENTIFIED_SHOWN).forEach { advertiser ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MonoText(
                                text = advertiser.localName ?: advertiser.address,
                                size = 11,
                                weight = FontWeight.Normal,
                                color = HazriColors.textSecondary,
                            )
                            MonoText(
                                text = "${Fmt.signed(advertiser.lastRssi)} dBm",
                                size = 11,
                                weight = FontWeight.Normal,
                                color = HazriColors.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Enough of the debug list to be useful without turning the screen into a scanner. */
private const val UNIDENTIFIED_SHOWN = 12

@Composable
private fun NodeEditor(
    node: NodeRecord,
    onCancel: () -> Unit,
    onSave: (name: String, room: String) -> Unit,
) {
    var name by remember(node.id) { mutableStateOf(node.displayName) }
    var room by remember(node.id) { mutableStateOf(node.espresenseRoom) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        LabelledField(
            label = "Name",
            value = name,
            onValueChange = { name = it },
            placeholder = "Kitchen",
            helper = "Shown in the app. Never published.",
        )
        LabelledField(
            label = "ESPresense room",
            value = room,
            onValueChange = { room = it },
            placeholder = "kitchen",
            mono = true,
            helper = "The topic segment: espresense/rooms/<room>/…/set. Must match the node's " +
                "own room name, lower case and one word.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Cancel", modifier = Modifier.weight(1f), onClick = onCancel)
            PrimaryButton(
                label = "Save",
                enabled = name.isNotBlank() && room.isNotBlank(),
                modifier = Modifier.weight(1f),
                onClick = { onSave(name.trim(), room.trim()) },
            )
        }
    }
}

/** Export: two buttons and a preview of what will be shared. */
@Composable
fun ExportScreen(
    csv: String,
    json: String,
    onBack: () -> Unit,
    onShare: (String, String) -> Unit,
) {
    ToolScaffold("Export session", "Every survey, as a file", onBack) {
        item {
            HazriCard(padding = 16) {
                Text("CSV", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
                Text(
                    "One row per room per node, with the room's verdict repeated on each row.",
                    fontSize = 12.sp,
                    color = HazriColors.textTertiary,
                )
                MonoText(
                    text = csv.lineSequence().take(4).joinToString("\n"),
                    size = 10,
                    weight = FontWeight.Normal,
                    color = HazriColors.muted,
                )
                PrimaryButton("Share CSV", onClick = { onShare("hazri-session.csv", csv) })
            }
        }
        item {
            HazriCard(padding = 16) {
                Text("JSON", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
                Text(
                    "The whole session: settings, nodes, rooms and every survey.",
                    fontSize = 12.sp,
                    color = HazriColors.textTertiary,
                )
                MonoText(
                    text = "${json.length} characters",
                    size = 10,
                    weight = FontWeight.Normal,
                    color = HazriColors.muted,
                )
                PrimaryButton("Share JSON", onClick = { onShare("hazri-session.json", json) })
            }
        }
    }
}

private fun subscriptionSummary(state: ToolsState): String = when (val connection = state.mqtt) {
    is MqttConnectionState.Connected -> "${connection.host}:${connection.port}"
    MqttConnectionState.Connecting -> "connecting"
    MqttConnectionState.Disconnected -> "not connected"
    is MqttConnectionState.Failed -> connection.reason
}
