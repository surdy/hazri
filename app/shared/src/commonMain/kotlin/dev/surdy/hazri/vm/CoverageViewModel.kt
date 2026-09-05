package dev.surdy.hazri.vm

import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.domain.CoverageMatrix
import dev.surdy.hazri.domain.DistanceModel
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.Suggestion
import dev.surdy.hazri.domain.Suggestions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the Coverage screen renders. */
data class CoverageState(
    val matrix: CoverageMatrix = CoverageMatrix(emptyList(), emptyList(), emptyMap(), emptyMap()),
    val displayNames: Map<NodeId, String> = emptyMap(),
    val suggestions: List<Suggestion> = emptyList(),
)

/** The Coverage screen: the rooms-by-nodes grid, its verdicts, and the advice under it. */
class CoverageViewModel(
    private val repository: HazriRepository,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow(CoverageState())
    val uiState: StateFlow<CoverageState> = state.asStateFlow()

    init {
        scope.launch { repository.surveys.collect { refresh() } }
        scope.launch { repository.nodes.collect { refresh() } }
        refresh()
    }

    /** Recomputes the grid. Cheap: the whole dataset is a handful of rooms by nodes. */
    fun refresh() {
        val matrix = repository.coverage()
        val names = repository.displayNames()
        val models: Map<NodeId, DistanceModel> = repository.nodes.value
            .associate { it.nodeId to it.config().distanceModel() }
        state.value = CoverageState(
            matrix = matrix,
            displayNames = names,
            suggestions = Suggestions.forMatrix(matrix, names, models),
        )
    }
}
