package dev.surdy.hazri.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.ui.Destination
import dev.surdy.hazri.ui.HazriApp
import dev.surdy.hazri.ui.Navigator
import dev.surdy.hazri.vm.AppContainer
import kotlinx.coroutines.launch

/**
 * The only Activity.
 *
 * It no longer builds the object graph — [HazriApplication] does, because the survey
 * service reads the same one — so what is left here is the two runtime permissions, the
 * navigation stack and the engine's screen-on lifetime. The permission requests are
 * deliberately not on first launch for their own sake: in a debug build the app starts in
 * simulated mode, so a scan permission asked for then would be a dialog with nothing
 * behind it.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = hazri.container

    /**
     * The in-app back stack.
     *
     * Held here rather than remembered in the composable so that the notification's tap
     * target can select the Survey tab from `onNewIntent`, on an Activity that is already
     * composed and will not re-run its initial state.
     */
    private val navigator = Navigator()

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            container.scope.launch { container.switchSource(SourceKind.DIRECT) }
        } else {
            // Direct mode with no permission produces an empty node list, which looks
            // exactly like a house with no nodes in it. Say what happened instead.
            container.engine.reportError(
                "Bluetooth scanning was not permitted, so Direct mode cannot hear anything. " +
                    "Grant it in Settings, or switch source."
            )
        }
    }

    /**
     * The notification permission, asked for when the Survey tab opens.
     *
     * The result is not acted on. Refused, the foreground service still runs and still
     * keeps the scan alive — Android simply shows nothing for it, which is a worse deal for
     * the user than it is for the survey.
     */
    private val notificationRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both bars dark: the app has no light theme, and the default edge-to-edge scrim
        // would put a light three-button bar under a #0F1115 page.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(SYSTEM_BAR_SCRIM),
            navigationBarStyle = SystemBarStyle.dark(SYSTEM_BAR_SCRIM),
        )

        // The graph is built by the Application; started here, because this is the first
        // point at which something exists that will also stop it. Idempotent, so a second
        // Activity or a recreation does not restart the sources.
        container.start()
        openSurveyTabIfAsked(intent)

        if (container.repository.settings.value.sourceKind == SourceKind.DIRECT &&
            !BluetoothAvailability.hasScanPermission(this)
        ) {
            permissionRequest.launch(BluetoothAvailability.requiredPermissions())
        }

        setContent {
            HazriApp(
                container = container,
                actions = AndroidPlatformActions(
                    context = applicationContext,
                    requestNotifications = ::requestNotificationPermission,
                ),
                navigator = navigator,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSurveyTabIfAsked(intent)
    }

    override fun onStart() {
        super.onStart()
        container.scope.launch { container.engine.start() }
    }

    override fun onStop() {
        super.onStop()
        // A backgrounded app stops collecting — unless a survey is recording, in which case
        // SurveyForegroundService is holding the process up and stopping here would silence
        // the very thing it is keeping alive.
        if (!container.survey.uiState.value.isRecording) container.engine.stop()
    }

    private fun openSurveyTabIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SURVEY, false) == true) {
            navigator.selectTab(Destination.Survey)
        }
    }

    /** Asks for [Manifest.permission.POST_NOTIFICATIONS] once, on the versions that have it. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val held = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (held == PackageManager.PERMISSION_GRANTED) return
        notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** Set by the survey notification's tap target: open on the Survey tab. */
        const val EXTRA_OPEN_SURVEY = "dev.surdy.hazri.extra.OPEN_SURVEY"

        /** HazriColors.navBackground, as the platform wants it: an opaque ARGB int. */
        private const val SYSTEM_BAR_SCRIM = 0xFF12151A.toInt()
    }
}
