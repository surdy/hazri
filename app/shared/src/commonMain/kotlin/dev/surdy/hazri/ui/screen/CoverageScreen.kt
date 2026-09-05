package dev.surdy.hazri.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.domain.CoverageCell
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.RoomVerdict
import dev.surdy.hazri.domain.Suggestion
import dev.surdy.hazri.ui.component.Fmt
import dev.surdy.hazri.ui.component.HairlineDivider
import dev.surdy.hazri.ui.component.HazriCard
import dev.surdy.hazri.ui.component.MonoText
import dev.surdy.hazri.ui.component.ScreenHeader
import dev.surdy.hazri.ui.component.SectionLabel
import dev.surdy.hazri.ui.component.heatColor
import dev.surdy.hazri.ui.component.heatTextColor
import dev.surdy.hazri.ui.component.verdictColor
import dev.surdy.hazri.ui.component.verdictLabel
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.vm.CoverageState
import kotlin.math.roundToInt

/**
 * Rooms by nodes, with each room's verdict on the right.
 *
 * The grid is built out of rows rather than a real grid layout: the column widths are all
 * equal except the first and last, and a `Row` of weighted cells gets that in a dozen lines
 * where a lazy grid would need a span calculator.
 */
@Composable
fun CoverageScreen(state: CoverageState) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Coverage", "Mean RSSI per room · last survey")

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            if (state.matrix.rooms.isEmpty()) {
                item { EmptyCoverage() }
                return@LazyColumn
            }

            item { Matrix(state) }
            item { Legend() }

            if (state.suggestions.isNotEmpty()) {
                item { SectionLabel("Suggestions") }
                item { SuggestionCard(state.suggestions) }
            }
        }
    }
}

@Composable
private fun Matrix(state: CoverageState) {
    HazriCard(padding = 12) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(ROOM_COLUMN.dp))
            state.matrix.nodes.forEach { nodeId ->
                Text(
                    text = abbreviate(state.displayNames[nodeId] ?: nodeId.value),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = HazriColors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(modifier = Modifier.width(VERDICT_COLUMN.dp))
        }

        state.matrix.rooms.forEach { room ->
            MatrixRow(
                room = room,
                nodes = state.matrix.nodes,
                cells = state.matrix.cells[room].orEmpty(),
                verdict = state.matrix.verdicts[room],
            )
        }
    }
}

@Composable
private fun MatrixRow(
    room: String,
    nodes: List<NodeId>,
    cells: Map<NodeId, CoverageCell>,
    verdict: RoomVerdict?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = room,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = HazriColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(ROOM_COLUMN.dp),
        )
        nodes.forEach { nodeId ->
            HeatCell(cells[nodeId], modifier = Modifier.weight(1f))
        }
        Column(
            modifier = Modifier.width(VERDICT_COLUMN.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (verdict != null) {
                Text(
                    text = verdictLabel(verdict.verdict),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = verdictColor(verdict.verdict),
                )
                MonoText(
                    text = verdict.margin?.let { "${it.roundToInt()} dB" } ?: "—",
                    size = 10,
                    weight = FontWeight.Normal,
                    color = HazriColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun HeatCell(cell: CoverageCell?, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val mean = cell?.mean

    if (mean == null) {
        Box(
            modifier = modifier.height(40.dp).clip(shape).border(1.dp, HazriColors.borderStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text("—", fontSize = 12.sp, color = HazriColors.dim)
        }
        return
    }

    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(heatColor(mean))
            .then(
                if (cell.isStrongestInRoom) Modifier.border(2.dp, HazriColors.accent, shape)
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        MonoText(Fmt.rssi(mean), size = 12, color = heatTextColor(mean))
    }
}

@Composable
private fun Legend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            HazriColors.heat.forEach { swatch ->
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(swatch)
                        .then(
                            if (swatch == HazriColors.heat.first()) {
                                Modifier.border(1.dp, HazriColors.border, RoundedCornerShape(2.dp))
                            } else {
                                Modifier
                            }
                        ),
                )
            }
        }
        Text("−95 → −50 dBm", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HazriColors.textSecondary)
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(2.dp, HazriColors.accent, RoundedCornerShape(3.dp)),
        )
        Text("strongest in room", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HazriColors.textSecondary)
    }
}

@Composable
private fun SuggestionCard(suggestions: List<Suggestion>) {
    HazriCard(padding = 16) {
        suggestions.forEachIndexed { index, suggestion ->
            if (index > 0) HairlineDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        suggestion.room,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = HazriColors.text,
                    )
                    Text(
                        verdictLabel(suggestion.verdict).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = verdictColor(suggestion.verdict),
                    )
                }
                Text(
                    suggestion.text,
                    fontSize = 13.sp,
                    color = HazriColors.textTertiary,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyCoverage() {
    HazriCard(padding = 16) {
        Text("Nothing surveyed yet", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
        Text(
            "Record a room on the Survey tab and it will appear here with its verdict.",
            fontSize = 13.sp,
            color = HazriColors.textTertiary,
        )
    }
}

/** Column headings are three or four characters, as in the mockup: Kit, Liv, Hall, Bed, Off. */
private fun abbreviate(name: String): String =
    name.trim().take(if (name.length > 4) 3 else name.length)

private const val ROOM_COLUMN = 76
private const val VERDICT_COLUMN = 44
