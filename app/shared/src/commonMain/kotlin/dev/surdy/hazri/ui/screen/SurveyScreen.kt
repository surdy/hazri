package dev.surdy.hazri.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.domain.NodeSurveyStat
import dev.surdy.hazri.domain.RoomVerdict
import dev.surdy.hazri.ui.component.Fmt
import dev.surdy.hazri.ui.component.HazriCard
import dev.surdy.hazri.ui.component.MonoText
import dev.surdy.hazri.ui.component.RoomChip
import dev.surdy.hazri.ui.component.ScreenHeader
import dev.surdy.hazri.ui.component.SectionLabel
import dev.surdy.hazri.ui.component.StatusDot
import dev.surdy.hazri.ui.component.StrengthBar
import dev.surdy.hazri.ui.component.TextPrompt
import dev.surdy.hazri.ui.component.verdictColor
import dev.surdy.hazri.ui.component.verdictLabel
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.ui.theme.HazriIcons
import dev.surdy.hazri.vm.SurveyState
import dev.surdy.hazri.vm.SurveyedRoom
import kotlin.math.roundToInt

/**
 * Pick a room, record, walk it.
 *
 * The running verdict under the bars is the point of the screen: it means the user can stop
 * walking as soon as the answer is obvious, rather than completing a fixed-length ritual.
 */
@Composable
fun SurveyScreen(
    state: SurveyState,
    onSelectRoom: (String) -> Unit,
    onAddRoom: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var addingRoom by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Room survey",
            subtitle = if (state.isSimulated && state.simulatedRoom != null) {
                "Simulated walker is in ${state.simulatedRoom}"
            } else {
                "Walk each room slowly, corners included"
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rooms, key = { it }) { room ->
                        RoomChip(
                            label = room,
                            selected = room == state.selectedRoom,
                            onClick = { onSelectRoom(room) },
                        )
                    }
                    item {
                        RoomChip(label = "+ Room", dashed = true, onClick = { addingRoom = true })
                    }
                }
            }

            if (addingRoom) {
                item {
                    TextPrompt(
                        label = "New room",
                        placeholder = "Landing",
                        onDismiss = { addingRoom = false },
                        onConfirm = { name ->
                            onAddRoom(name)
                            addingRoom = false
                        },
                    )
                }
            }

            item { RecordCard(state, onStart, onStop) }

            if (state.surveyed.isNotEmpty()) {
                item { SectionLabel("Surveyed") }
                items(state.surveyed, key = { it.survey.room }) { entry -> SurveyedRow(entry) }
            }
        }
    }
}

@Composable
private fun RecordCard(state: SurveyState, onStart: () -> Unit, onStop: () -> Unit) {
    HazriCard(highlighted = state.isRecording, corner = 16, padding = 16) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(if (state.isRecording) HazriColors.red else HazriColors.muted)
                Text(
                    // Once a walk has finished, its numbers stay on screen — so the label
                    // has to say they are a result, not a live reading.
                    text = when {
                        state.isRecording -> "Recording · ${state.selectedRoom}"
                        state.liveStats.isNotEmpty() -> "Recorded · ${state.selectedRoom}"
                        state.selectedRoom != null -> "Ready · ${state.selectedRoom}"
                        else -> "Pick a room"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = HazriColors.text,
                )
            }
            MonoText(
                "${state.sampleCount} samples",
                size = 13,
                weight = FontWeight.Medium,
                color = HazriColors.textSecondary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText(Fmt.duration(state.elapsedMillis), size = 44, letterSpacing = -1.0)
            RecordButton(state.isRecording, state.selectedRoom != null, onStart, onStop)
        }

        if (state.isSimulated && state.isRecording) {
            Text(
                // The simulated walker is held in the selected room for the recording, and
                // saying so is the difference between a demo and a lie.
                text = "Simulated walk pinned to ${state.selectedRoom}",
                fontSize = 11.sp,
                color = HazriColors.muted,
            )
        }

        if (state.liveStats.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HazriColors.border))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val best = state.liveStats.firstOrNull()?.mean
                state.liveStats.forEach { stat ->
                    LiveMeanRow(stat, state.displayNames[stat.nodeId] ?: stat.nodeId.value, stat.mean == best)
                }
            }
        }

        state.runningVerdict?.let { verdict -> RunningVerdict(verdict, state) }
    }
}

@Composable
private fun RecordButton(
    recording: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                when {
                    recording -> HazriColors.red
                    enabled -> HazriColors.accent
                    else -> HazriColors.surfaceRaised
                }
            )
            .clickable(enabled = enabled || recording) { if (recording) onStop() else onStart() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (recording) HazriIcons.Stop else HazriIcons.Record,
            contentDescription = if (recording) "Stop recording" else "Start recording",
            tint = if (enabled || recording) HazriColors.background else HazriColors.muted,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun LiveMeanRow(stat: NodeSurveyStat, name: String, leading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (leading) HazriColors.text else HazriColors.textTertiary,
            modifier = Modifier.width(92.dp),
        )
        StrengthBar(
            fraction = fractionOf(stat.mean),
            modifier = Modifier.weight(1f),
            color = if (leading) HazriColors.accent else HazriColors.barSecondary,
        )
        MonoText(
            text = Fmt.rssi(stat.mean),
            size = 13,
            color = if (leading) HazriColors.accent else HazriColors.textTertiary,
            modifier = Modifier.width(40.dp),
            align = TextAlign.End,
        )
    }
}

@Composable
private fun RunningVerdict(verdict: RoomVerdict, state: SurveyState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HazriIcons.Check,
            contentDescription = null,
            tint = verdictColor(verdict.verdict),
            modifier = Modifier.size(16.dp),
        )
        val best = verdict.best?.let { state.displayNames[it] ?: it.value }
        val margin = verdict.margin
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (best == null) "So far: nothing in range" else "So far: $best wins by ",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = HazriColors.bannerText,
            )
            if (best != null && margin != null) {
                MonoText("${margin.roundToInt()} dB", size = 13, color = verdictColor(verdict.verdict))
            }
        }
    }
}

@Composable
private fun SurveyedRow(entry: SurveyedRoom) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(HazriColors.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.survey.room, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
            Text(
                Fmt.age(entry.ageMillis),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = HazriColors.textSecondary,
            )
        }
        Text(
            text = buildString {
                append(verdictLabel(entry.verdict.verdict))
                entry.verdict.margin?.let { append(" · ${it.roundToInt()} dB") }
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = verdictColor(entry.verdict.verdict),
        )
    }
}

private fun fractionOf(meanDbm: Double): Float =
    (((meanDbm + 95.0) / 45.0).coerceIn(0.0, 1.0)).toFloat()
