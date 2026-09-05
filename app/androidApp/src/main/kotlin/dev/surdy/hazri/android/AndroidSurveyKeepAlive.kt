package dev.surdy.hazri.android

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.surdy.hazri.vm.SurveyKeepAlive
import dev.surdy.hazri.vm.SurveyProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Android [SurveyKeepAlive]: one foreground service, started with the recording.
 *
 * Progress reaches the service through [progress] rather than through intent extras. The
 * two are in the same process, so an update is a value written to a flow the service is
 * already collecting — where a `startForegroundService` per second would be asking the
 * platform to start a service that is already running, once a second, from a process that
 * by then is in the background and subject to Android 12's start rules.
 */
class AndroidSurveyKeepAlive(
    private val context: Context,
    /**
     * Called after a recording ends, however it ended.
     *
     * The engine's lifetime is "a screen, or a recording", and a recording can now end with
     * no screen there — from the notification's Stop, or from a swipe out of Recents. The
     * Activity's last `onStop` had already declined to stop the engine because a survey was
     * running, so this is the only thing left that can.
     */
    private val onRecordingEnded: () -> Unit = {},
) : SurveyKeepAlive {

    private val current = MutableStateFlow<SurveyProgress?>(null)

    /** What the notification shows, or `null` when nothing is recording. */
    val progress: StateFlow<SurveyProgress?> = current.asStateFlow()

    override fun start(room: String) {
        current.value = SurveyProgress(room = room, elapsedMillis = 0L, sampleCount = 0)
        // Always from the foreground: a recording begins on a tap, so the five-second
        // window to call startForeground opens with the app on screen.
        ContextCompat.startForegroundService(context, serviceIntent())
    }

    override fun update(progress: SurveyProgress) {
        // Cancelling the survey's tick job does not un-dispatch a tick already on its way,
        // so an update can arrive just after the stop that ended the recording. Writing it
        // would put the notification back after the service had been told to go.
        if (current.value == null) return
        current.value = progress
    }

    override fun stop() {
        if (current.value == null) return
        current.value = null
        context.stopService(serviceIntent())
        onRecordingEnded()
    }

    private fun serviceIntent(): Intent = Intent(context, SurveyForegroundService::class.java)
}
