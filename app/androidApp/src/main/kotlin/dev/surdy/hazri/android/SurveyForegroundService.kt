package dev.surdy.hazri.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.surdy.hazri.vm.SurveyProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Holds the process in the foreground for the length of a recording.
 *
 * A survey is a walk with the phone in a pocket, which is exactly the state Android stops
 * an app's BLE scan and its socket reads in. This service is the platform's answer: while
 * its notification is up the process keeps its foreground importance and the scan in
 * [dev.surdy.hazri.source.DirectScanSource] — and the MQTT collector beside it — keep
 * delivering with the screen off.
 *
 * It owns nothing. The engine, the repository and the survey it stops all live on
 * [HazriApplication], so the service is a notification and a lifetime, and every path
 * through it ends in the same `SurveyViewModel.stop` the in-app Stop button calls.
 */
class SurveyForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var foregrounded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Through the view model, not the service: stopping a survey files it, releases
            // the simulated walk and drops the scan back to its resting rate, and the Stop
            // button in the notification has to do all of that too.
            hazri.container.survey.stop()
            // That stop normally ends this service through the keep-alive. A Stop tapped on
            // a notification the shade had not yet dropped — the recording already over —
            // finds nothing to stop, and without this the tap would have started the
            // service back up and left it sitting there.
            stopSelf()
            return START_NOT_STICKY
        }

        // A null intent is a sticky restart after the process was killed, and there is no
        // recording left to keep alive — the accumulator died with the process. START_NOT_STICKY
        // asks not to be restarted at all; this is the belt to that brace.
        //
        // Stopping here without going foreground first is safe: the five-second deadline a
        // startForegroundService imposes is enforced by a delayed message that is cancelled
        // when the service is brought down, so a service that stops immediately never
        // reaches it. The alternative — posting a notification for a recording that has
        // already ended, then removing it — is a flash of a lie.
        val progress = hazri.keepAlive.progress.value
        if (progress == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        goForeground(progress)
        if (watchJob == null) {
            watchJob = scope.launch {
                hazri.keepAlive.progress.filterNotNull().collect { show(it) }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * The platform's foreground service time limit ran out (API 34).
     *
     * `dataSync` — the type a survey recorded over MQTT or the simulator takes — is capped
     * at six hours a day, and the platform kills a service that ignores this. Ending the
     * recording files what has been walked so far and takes the service down cleanly, which
     * is a survey that stops rather than an app that disappears.
     */
    override fun onTimeout(startId: Int) {
        hazri.container.survey.stop()
        stopSelf()
    }

    /** The same, with the type that ran out named (API 35). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    /**
     * The user swiped the app out of Recents while a survey was running.
     *
     * Ending the recording rather than carrying on: a survey the user cannot see the
     * screen of is a survey they have stopped thinking about, and the samples taken so far
     * are still filed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        hazri.container.survey.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        watchJob = null
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun goForeground(progress: SurveyProgress) {
        if (foregrounded) return
        foregrounded = true
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(progress), serviceType())
    }

    private fun show(progress: SurveyProgress) {
        // The platform manager rather than NotificationManagerCompat: on API 33+ this call
        // needs POST_NOTIFICATIONS, and when it has been refused the right behaviour is the
        // service running with nothing on screen, not a permission check at every tick.
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(progress))
    }

    /**
     * Which foreground service type this recording is entitled to.
     *
     * `connectedDevice` is the honest one — the survey is reading a BLE radio — but from
     * API 34 the platform refuses that type unless the app actually holds one of the
     * Bluetooth runtime permissions. A survey run in MQTT or simulated mode may never have
     * asked for one, and throwing there would be a crash in place of a notification, so
     * those fall back to `dataSync`, which is what they are doing.
     */
    private fun serviceType(): Int = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !BluetoothAvailability.hasScanPermission(this) ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    }

    private fun notification(progress: SurveyProgress): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_survey)
            .setContentTitle("Surveying ${progress.room}")
            .setContentText(progress.summary)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openSurveyTab())
            .addAction(R.drawable.ic_stat_survey, "Stop", stopRecording())
            .build()

    /** Tapping the notification lands on the Survey tab, not wherever the app was left. */
    private fun openSurveyTab(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SURVEY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopRecording(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, SurveyForegroundService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The one channel. Creating it again with the same id is how it is kept up to date. */
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Survey",
            // Low, not default: this notification is a progress readout the user chose to
            // start. It should be legible in the shade and silent everywhere else.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a room survey is recording."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "survey"
        const val NOTIFICATION_ID = 1

        /** Ends the recording. The notification's Stop action. */
        const val ACTION_STOP = "dev.surdy.hazri.action.STOP_SURVEY"

        const val REQUEST_OPEN = 1
        const val REQUEST_STOP = 2
    }
}
