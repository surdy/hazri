package dev.surdy.hazri.vm

import dev.surdy.hazri.data.AppSettings
import dev.surdy.hazri.data.BrokerSettings
import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.SourceKind
import kotlinx.coroutines.flow.StateFlow

/** The Settings screen. A thin editor over [AppSettings]; every change persists at once. */
class SettingsViewModel(
    private val repository: HazriRepository,
    private val onSourceChanged: suspend (SourceKind) -> Unit,
) {
    val uiState: StateFlow<AppSettings> = repository.settings

    /** Switches source, which also restarts the engine. */
    suspend fun setSource(kind: SourceKind) {
        onSourceChanged(kind)
    }

    /** Edits the broker coordinates. Takes effect the next time MQTT mode is selected. */
    fun setBroker(transform: (BrokerSettings) -> BrokerSettings) {
        repository.updateSettings { it.copy(broker = transform(it.broker)) }
    }

    /** Sets the fingerprint the phone advertises under. See [AppSettings.phoneId]. */
    fun setPhoneId(phoneId: String) {
        repository.updateSettings { it.copy(phoneId = phoneId.trim()) }
    }

    /** Sets the EMA weight for raw sources. Clamped to the range the smoother accepts. */
    fun setSmoothingAlpha(alpha: Double) {
        repository.updateSettings { it.copy(smoothingAlpha = alpha.coerceIn(ALPHA_MIN, 1.0)) }
    }

    /** Sets the median window. Forced odd, because a median of an even count is not one. */
    fun setMedianWindow(window: Int) {
        val odd = window.coerceIn(1, MEDIAN_MAX).let { if (it % 2 == 0) it + 1 else it }
        repository.updateSettings { it.copy(medianWindow = odd) }
    }

    /** Sets the dB gap at or above which a room counts as Clear. */
    fun setMarginDb(marginDb: Double) {
        repository.updateSettings { it.copy(marginDb = marginDb.coerceIn(0.0, MARGIN_MAX)) }
    }

    /** Sets the mean RSSI a room's best node must beat to be heard at all. */
    fun setFloorDbm(floorDbm: Double) {
        repository.updateSettings { it.copy(floorDbm = floorDbm.coerceIn(FLOOR_MIN, FLOOR_MAX)) }
    }

    private companion object {
        const val ALPHA_MIN = 0.01
        const val MEDIAN_MAX = 15
        const val MARGIN_MAX = 30.0
        const val FLOOR_MIN = -110.0
        const val FLOOR_MAX = -40.0
    }
}
