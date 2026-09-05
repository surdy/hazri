package dev.surdy.hazri.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.data.AppSettings
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.NodeConfig
import dev.surdy.hazri.ui.component.Fmt
import dev.surdy.hazri.ui.component.HazriCard
import dev.surdy.hazri.ui.component.CHART_BOTTOM_DBM
import dev.surdy.hazri.ui.component.CHART_TOP_DBM
import dev.surdy.hazri.ui.component.HistoryChart
import dev.surdy.hazri.ui.component.MonoText
import dev.surdy.hazri.ui.component.PrimaryButton
import dev.surdy.hazri.ui.component.ScreenHeader
import dev.surdy.hazri.ui.component.SecondaryButton
import dev.surdy.hazri.ui.component.StatTile
import dev.surdy.hazri.ui.component.Stepper
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.ui.theme.HazriIcons
import dev.surdy.hazri.vm.NodeDetailState
import dev.surdy.hazri.vm.PushState

/**
 * One node in detail: the sixty-second chart, the four statistics, its ESPresense config
 * and the two things that can be done with it.
 *
 * The calibration block is the part that most needed care. See [CalibrationBlock].
 */
@Composable
fun NodeDetailScreen(
    state: NodeDetailState,
    onBack: () -> Unit,
    onEditConfig: ((NodeConfig) -> NodeConfig) -> Unit,
    onStartCalibration: () -> Unit,
    onStopCalibration: () -> Unit,
    onApplyCalibrationAsRefRssi: () -> Unit,
    onCopyConfig: (String) -> Unit,
    configText: String,
    onPush: () -> Unit,
    canPush: Boolean,
) {
    val live = state.live

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = state.record?.displayName ?: state.nodeId.value,
            subtitle = subtitle(state),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        MonoText(
                            text = live?.let { Fmt.rssi(it.smoothedRssi) } ?: "—",
                            size = 48,
                            color = HazriColors.accent,
                            letterSpacing = -1.5,
                        )
                        Text(
                            " dBm",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = HazriColors.textSecondary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MonoText(live?.let { Fmt.distance(it.distanceMetres) } ?: "—", size = 20)
                        Text(
                            "est. distance",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = HazriColors.textSecondary,
                        )
                    }
                }
            }

            item { ChartCard(state) }
            item { ConfigCard(state, onEditConfig, onCopyConfig, configText, onPush, canPush) }
            item {
                CalibrationBlock(
                    state = state,
                    canPush = canPush,
                    onStart = onStartCalibration,
                    onStop = onStopCalibration,
                    onApplyAsRefRssi = onApplyCalibrationAsRefRssi,
                    onCopy = onCopyConfig,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(state: NodeDetailState) {
    HazriCard(padding = 16) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Last 60 s", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HazriColors.textSecondary)
            Text("raw samples", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HazriColors.textSecondary)
        }

        // The labels sit on the grid lines rather than at the top, middle and bottom of the
        // box — the axis is fixed at -40 to -100, so -50 belongs a sixth of the way down —
        // and in a gutter of their own, so the series cannot be drawn underneath them.
        Box(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT.dp)) {
            HistoryChart(
                samples = state.live?.history.orEmpty(),
                modifier = Modifier.fillMaxSize().padding(end = GRID_GUTTER.dp),
            )
            GRID_LEVELS.forEach { level ->
                val fraction = (CHART_TOP - level) / (CHART_TOP - CHART_BOTTOM)
                GridLabel(
                    text = Fmt.signed(level.toInt()),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = (CHART_HEIGHT * fraction - GRID_LABEL_HALF).dp),
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AxisLabel("60 s ago")
            AxisLabel("30 s")
            AxisLabel("now")
        }

        val stats = state.windowStats
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile("Mean", stats?.let { Fmt.one(it.mean) } ?: "—", Modifier.weight(1f))
            StatTile("σ", stats?.let { Fmt.one(it.sigma) } ?: "—", Modifier.weight(1f))
            StatTile(
                "Min / max",
                stats?.let { "${Fmt.signed(it.min)}/${Fmt.signed(it.max)}" } ?: "—",
                Modifier.weight(1f),
            )
            val rate = AppSettings.rateUnit(state.sourceKind)
            StatTile(
                "Rate",
                stats?.let { "${Fmt.one(rate.convert(it.packetRate))} ${rate.label}" } ?: "—",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GridLabel(text: String, modifier: Modifier = Modifier) {
    MonoText(text, size = 10, weight = FontWeight.Normal, color = HazriColors.gridLabel, modifier = modifier)
}

@Composable
private fun AxisLabel(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = HazriColors.gridLabel)
}

@Composable
private fun ConfigCard(
    state: NodeDetailState,
    onEditConfig: ((NodeConfig) -> NodeConfig) -> Unit,
    onCopyConfig: (String) -> Unit,
    configText: String,
    onPush: () -> Unit,
    canPush: Boolean,
) {
    HazriCard(padding = 16) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "NODE CONFIG",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = HazriColors.textSecondary,
            )
            Text(
                text = when (state.pushState) {
                    PushState.NOT_PUSHED -> "not pushed"
                    PushState.PUSHING -> "pushing"
                    PushState.PUSHED -> "pushed"
                    PushState.FAILED -> "push failed"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = when (state.pushState) {
                    PushState.PUSHED -> HazriColors.accent
                    PushState.FAILED -> HazriColors.red
                    else -> HazriColors.amber
                },
            )
        }

        ConfigRow(
            key = "room",
            value = state.config.room,
            note = if (state.roomIsConfirmed) null else "unconfirmed",
        )
        ConfigRow(
            key = "ref_rssi",
            value = "${Fmt.signed(state.config.refRssi)} dBm",
            note = if (state.refRssiIsKnown) null else "assumed",
        )
        ConfigRow("absorption", Fmt.one(state.config.absorption))
        ConfigRow("max_distance", "${Fmt.one(state.config.maxDistance)} m")

        Text(
            text = "ESPresense never publishes ref_rssi back, so its value here is whatever " +
                "Hazri last pushed. Until then it is the firmware default, shown as assumed.",
            fontSize = 11.sp,
            color = HazriColors.muted,
            lineHeight = 16.sp,
        )
        if (!state.roomIsConfirmed) {
            Text(
                text = "The room is the MQTT topic segment and no node has confirmed it. " +
                    "Set it under Tools, Nodes & rooms before pushing, or the settings go to " +
                    "a room that does not exist.",
                fontSize = 11.sp,
                color = HazriColors.amber,
                lineHeight = 16.sp,
            )
        }

        Stepper(
            label = "absorption",
            value = Fmt.one(state.config.absorption),
            onDecrement = { onEditConfig { it.copy(absorption = (it.absorption - 0.1).coerceAtLeast(1.0)) } },
            onIncrement = { onEditConfig { it.copy(absorption = (it.absorption + 0.1).coerceAtMost(5.0)) } },
        )
        Stepper(
            label = "max_distance",
            value = "${Fmt.one(state.config.maxDistance)} m",
            onDecrement = { onEditConfig { it.copy(maxDistance = (it.maxDistance - 1.0).coerceAtLeast(1.0)) } },
            onIncrement = { onEditConfig { it.copy(maxDistance = (it.maxDistance + 1.0).coerceAtMost(50.0)) } },
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                label = "Copy config",
                icon = HazriIcons.Copy,
                modifier = Modifier.weight(1f),
                onClick = { onCopyConfig(configText) },
            )
            SecondaryButton(
                label = "Push via MQTT",
                icon = HazriIcons.Push,
                enabled = canPush && state.roomIsConfirmed,
                modifier = Modifier.weight(1f),
                onClick = onPush,
            )
        }

        state.message?.let { message ->
            Text(message, fontSize = 12.sp, color = HazriColors.red)
        }
        if (!canPush) {
            Text(
                "Push needs MQTT mode and a connected broker.",
                fontSize = 11.sp,
                color = HazriColors.muted,
            )
        }
    }
}

@Composable
private fun ConfigRow(key: String, value: String, note: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(key, size = 13, weight = FontWeight.Normal, color = HazriColors.textTertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (note != null) {
                Text(note, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HazriColors.amber)
            }
            MonoText(value, size = 13)
        }
    }
}

/**
 * Calibration, and the thing it is easy to get wrong.
 *
 * The phone advertises as an iBeacon, and ESPresense ranges an iBeacon from the power the
 * beacon itself advertises — not from the node's `ref_rssi`. So the primary output of this
 * capture is a number to type into the Home Assistant Companion app, and pushing it to the
 * node is the secondary, clearly-labelled action for non-beacon devices. The copy here says
 * so, because a "Calibrate" button that silently wrote `ref_rssi` would look like it worked
 * and change nothing.
 */
@Composable
private fun CalibrationBlock(
    state: NodeDetailState,
    canPush: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onApplyAsRefRssi: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val progress = state.calibration

    HazriCard(padding = 16) {
        val phoneBeacon = state.sourceKind == SourceKind.MQTT

        Text("Calibrate at 1 m", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
        Text(
            text = if (phoneBeacon) {
                "Stand one metre from this node and hold still. The node is measuring this " +
                    "phone, so the mean is the phone beacon's measured power at 1 m — set it " +
                    "in the Home Assistant Companion app's BLE transmitter."
            } else {
                "Stand one metre from this node and hold still. In this mode the phone is " +
                    "measuring the node's own iBeacon, so the mean calibrates the node's " +
                    "tx_ref_rssi, not the phone. Switch to MQTT to calibrate the phone."
            },
            fontSize = 12.sp,
            color = HazriColors.textTertiary,
            lineHeight = 17.sp,
        )

        if (progress == null) {
            PrimaryButton("Calibrate at 1 m", icon = HazriIcons.Calibrate, onClick = onStart)
            return@HazriCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText(
                text = "${progress.sampleCount} / ${progress.minSamples} samples",
                size = 13,
                weight = FontWeight.Medium,
                color = HazriColors.textSecondary,
            )
            MonoText(
                text = progress.meanRssi?.let { Fmt.one(it) } ?: "—",
                size = 20,
                color = HazriColors.accent,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(HazriColors.barTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(
                        (progress.sampleCount.toFloat() / progress.minSamples).coerceIn(0f, 1f)
                    )
                    .height(6.dp)
                    .background(HazriColors.accent),
            )
        }

        progress.result?.let { result ->
            Text(
                text = buildString {
                    append(if (phoneBeacon) "Phone measured power at 1 m: " else "Node power at 1 m: ")
                    append("${Fmt.signed(result.measuredPowerAtOneMetre)} dBm ")
                    append("(σ ${Fmt.one(result.sigma)} over ${result.sampleCount} samples)")
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = HazriColors.text,
            )
            SecondaryButton(
                label = "Copy value",
                icon = HazriIcons.Copy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onCopy(result.measuredPowerAtOneMetre.toString()) },
            )
            // Only offered in MQTT mode. Outside it the capture is of the node's own
            // transmitter, and writing that number to ref_rssi — the node's *receive*
            // reference — would be a plausible-looking way to make every distance wrong.
            if (phoneBeacon) {
                Text(
                    "Secondary: push this as the node's ref_rssi. That only affects devices " +
                        "advertising no calibrated power of their own — not this phone.",
                    fontSize = 11.sp,
                    color = HazriColors.muted,
                    lineHeight = 16.sp,
                )
                SecondaryButton(
                    label = "Push as ref_rssi",
                    icon = HazriIcons.Push,
                    enabled = canPush && state.roomIsConfirmed,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onApplyAsRefRssi,
                )
            }
        }

        SecondaryButton(label = "Stop", modifier = Modifier.fillMaxWidth(), onClick = onStop)
    }
}

/** The chart's fixed axis, shared by [HistoryChart] and the labels drawn beside it. */
private const val CHART_TOP = CHART_TOP_DBM
private const val CHART_BOTTOM = CHART_BOTTOM_DBM
private const val CHART_HEIGHT = 130f
private const val GRID_LABEL_HALF = 7f

/** Width reserved on the right for the axis labels, so the series never runs under them. */
private const val GRID_GUTTER = 32f
private val GRID_LEVELS = listOf(-50f, -70f, -90f)

private fun subtitle(state: NodeDetailState): String {
    val id = state.nodeId.value
    val room = state.config.room
    // For an MQTT-discovered node the id *is* the room, and printing both spelled the same
    // word twice. Only say the room when it adds something.
    val roomNote = when {
        room == id -> null
        state.roomIsConfirmed -> "room $room"
        else -> "room $room?"
    }
    val seen = when (val since = state.sinceLastSeenMillis) {
        null -> "not heard"
        in 0 until 1_000 -> "seen just now"
        else -> "seen ${Fmt.one(since / 1000.0)} s ago"
    }
    return listOfNotNull(id, roomNote, seen).joinToString(" · ")
}
