package dev.surdy.hazri.vm

import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.NodeSurveyStat
import dev.surdy.hazri.domain.RoomSurvey
import dev.surdy.hazri.domain.RoomVerdict
import dev.surdy.hazri.domain.Source
import dev.surdy.hazri.domain.SurveyAccumulator
import dev.surdy.hazri.source.MillisClock
import dev.surdy.hazri.source.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A finished survey as the "Surveyed" list shows it. */
data class SurveyedRoom(
    val survey: RoomSurvey,
    val verdict: RoomVerdict,
    val ageMillis: Long,
)

/** Everything the Survey screen renders. */
data class SurveyState(
    val rooms: List<String> = emptyList(),
    val selectedRoom: String? = null,
    val isRecording: Boolean = false,
    val elapsedMillis: Long = 0L,
    val sampleCount: Int = 0,
    val liveStats: List<NodeSurveyStat> = emptyList(),
    val runningVerdict: RoomVerdict? = null,
    val displayNames: Map<NodeId, String> = emptyMap(),
    val surveyed: List<SurveyedRoom> = emptyList(),
    /**
     * Where the simulated walker is standing, or `null` outside simulated mode.
     *
     * Shown because in simulated mode the walk is the thing being measured, and a recording
     * whose room does not match the walker's is measuring the wrong room.
     */
    val simulatedRoom: String? = null,
    val isSimulated: Boolean = false,
)

/**
 * The Survey screen: pick a room, record, walk it, stop.
 *
 * A plain class with a [StateFlow], constructed by the app container and held across
 * recomposition by the caller. No `androidx.lifecycle` — the shared module has no Android
 * dependencies, and a survey's lifetime is the app's, not a screen's.
 */
class SurveyViewModel(
    private val repository: HazriRepository,
    private val engine: SignalEngine,
    private val scope: CoroutineScope,
    private val clock: MillisClock = SystemClock,
) {
    private val state = MutableStateFlow(SurveyState())
    val uiState: StateFlow<SurveyState> = state.asStateFlow()

    private var accumulator: SurveyAccumulator? = null
    private var collectJob: Job? = null
    private var tickJob: Job? = null

    init {
        scope.launch {
            repository.rooms.collect { rooms -> state.value = state.value.copy(rooms = rooms) }
        }
        scope.launch {
            repository.surveys.collect { refreshSurveyed() }
        }
        scope.launch {
            engine.live.collect { live ->
                state.value = state.value.copy(
                    simulatedRoom = live.simulatedRoom,
                    isSimulated = live.sourceKind == SourceKind.SIMULATED,
                )
            }
        }
        // A recording measures one room through one source. Switching source mid-walk would
        // otherwise leave the accumulator averaging two different measurements and, in
        // simulated mode, leave the walker pinned to a source that is no longer running.
        engine.onSourceChange(::stop)
        refreshSurveyed()
    }

    /** Selects the room the next recording will be filed under. */
    fun selectRoom(room: String) {
        if (state.value.isRecording) return
        state.value = state.value.copy(selectedRoom = room)
    }

    /** Adds a room to the chip row and selects it. */
    fun addRoom(room: String) {
        val trimmed = room.trim()
        if (trimmed.isEmpty()) return
        repository.addRoom(trimmed)
        state.value = state.value.copy(selectedRoom = trimmed)
    }

    /** Starts recording into the selected room. No-op if no room is selected. */
    fun start() {
        val room = state.value.selectedRoom ?: return
        if (state.value.isRecording) return

        val startedAt = clock.now()
        val fresh = SurveyAccumulator(room, startedAt, sourceOf(engine.live.value.sourceKind))
        accumulator = fresh
        state.value = state.value.copy(
            isRecording = true,
            elapsedMillis = 0L,
            sampleCount = 0,
            liveStats = emptyList(),
            runningVerdict = null,
        )

        // The simulated walker does not know which room was tapped, so a Kitchen recording
        // taken while the loop happens to be in the hallway would be won by the Hall node.
        // Holding the walk in the selected room for the recording is what makes the
        // simulator answer the question the user asked. No-op for the real sources.
        engine.pinSimulatedRoom(room)

        collectJob = scope.launch { engine.samples.collect { fresh.add(it) } }
        tickJob = scope.launch {
            while (isActive) {
                publish(fresh, startedAt)
                delay(TICK_MILLIS)
            }
        }
    }

    /** Stops recording and files the survey. No-op if not recording. */
    fun stop() {
        val finished = accumulator ?: return
        engine.pinSimulatedRoom(null)
        collectJob?.cancel()
        collectJob = null
        tickJob?.cancel()
        tickJob = null
        accumulator = null

        if (finished.sampleCount > 0) repository.addSurvey(finished.finish(clock.now()))
        state.value = state.value.copy(isRecording = false)
        refreshSurveyed()
    }

    /** Forgets every survey of [room]. */
    fun discard(room: String) {
        repository.deleteSurveys(room)
        refreshSurveyed()
    }

    private fun publish(accumulator: SurveyAccumulator, startedAt: Long) {
        state.value = state.value.copy(
            elapsedMillis = clock.now() - startedAt,
            sampleCount = accumulator.sampleCount,
            liveStats = accumulator.statsNow(),
            runningVerdict = accumulator.verdictNow(repository.settings.value.thresholds()),
            displayNames = repository.displayNames(),
        )
    }

    private fun refreshSurveyed() {
        val now = clock.now()
        val thresholds = repository.settings.value.thresholds()
        state.value = state.value.copy(
            displayNames = repository.displayNames(),
            surveyed = repository.latestSurveys().map { survey ->
                SurveyedRoom(
                    survey = survey,
                    verdict = survey.verdict(thresholds),
                    ageMillis = now - survey.endedAt,
                )
            },
        )
    }

    private fun sourceOf(kind: SourceKind): Source = when (kind) {
        SourceKind.DIRECT -> Source.DIRECT
        SourceKind.MQTT -> Source.MQTT
        SourceKind.SIMULATED -> Source.SIMULATED
    }

    private companion object {
        const val TICK_MILLIS = 400L
    }
}
