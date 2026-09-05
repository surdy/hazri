package dev.surdy.hazri.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.surdy.hazri.data.AndroidFileStore
import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.ui.HazriApp
import dev.surdy.hazri.vm.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * The only Activity.
 *
 * It builds the object graph, asks for the scan permissions if direct mode is what the
 * settings say, and hands everything to [HazriApp]. The permission request is deliberately
 * not on first launch for its own sake: in a debug build the app starts in simulated mode
 * and there is nothing to scan, so asking would be a dialog with no purpose behind it.
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    /**
     * The scope everything in the app runs on.
     *
     * Not `lifecycleScope`: a survey outlives a configuration change, and the sources are
     * long-lived collectors. The manifest already keeps the Activity through rotation, and
     * this scope is cancelled in [onDestroy].
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            appScope.launch { container.switchSource(SourceKind.DIRECT) }
        } else {
            // Direct mode with no permission produces an empty node list, which looks
            // exactly like a house with no nodes in it. Say what happened instead.
            container.engine.reportError(
                "Bluetooth scanning was not permitted, so Direct mode cannot hear anything. " +
                    "Grant it in Settings, or switch source."
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both bars dark: the app has no light theme, and the default edge-to-edge scrim
        // would put a light three-button bar under a #0F1115 page.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(SYSTEM_BAR_SCRIM),
            navigationBarStyle = SystemBarStyle.dark(SYSTEM_BAR_SCRIM),
        )

        val repository = HazriRepository(
            store = AndroidFileStore(File(filesDir, STORE_DIRECTORY)),
            // Persistence is four small JSON documents, but noteNode is called from the
            // sample pipeline dozens of times a second, so the writes belong off the main
            // thread. The StateFlow update stays synchronous; only the file lands here.
            //
            // limitedParallelism(1) rather than plain IO: two saves of one document must
            // reach the disk in the order they were made. The repository tickets them so it
            // is correct either way, but a pool that resumes them out of order would make
            // every write a supersede-and-drop for no reason.
            writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
        )
        container = AppContainer(
            repository = repository,
            sources = AndroidSourceFactory(
                context = applicationContext,
                repository = repository,
                onScanFailed = { message -> container.engine.reportError(message) },
            ),
            scope = appScope,
            simulationAvailable = BuildConfig.SIMULATION_AVAILABLE,
        )
        container.start()

        if (container.repository.settings.value.sourceKind == SourceKind.DIRECT &&
            !BluetoothAvailability.hasScanPermission(this)
        ) {
            permissionRequest.launch(BluetoothAvailability.requiredPermissions())
        }

        setContent {
            HazriApp(container = container, actions = AndroidPlatformActions(applicationContext))
        }
    }

    override fun onStop() {
        super.onStop()
        // No foreground service in this pass, so a backgrounded app stops scanning. See the
        // TODO on DirectScanSource.
        container.engine.stop()
    }

    override fun onStart() {
        super.onStart()
        if (this::container.isInitialized) appScope.launch { container.engine.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()
    }

    private companion object {
        const val STORE_DIRECTORY = "hazri"

        /** HazriColors.navBackground, as the platform wants it: an opaque ARGB int. */
        const val SYSTEM_BAR_SCRIM = 0xFF12151A.toInt()
    }
}
