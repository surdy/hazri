package dev.surdy.hazri.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.surdy.hazri.data.AndroidFileStore
import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.vm.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The process, and the one object graph in it.
 *
 * The graph used to be built in the Activity, which was fine while the Activity was the
 * only thing that read it. It is not: [SurveyForegroundService] stops the same survey the
 * Stop button in the app stops, and a second engine with a second scanner behind it would
 * make the notification's Stop action a no-op on the recording the user could see. Owning
 * it here also means a rotation or a back-out-and-return re-attaches to the running
 * sources instead of building new ones.
 *
 * Nothing here is lazy: the graph is four objects and a file read, and the alternative is
 * a lock on a field that both the Activity and a service touch on the main thread. It is
 * built here but *started* by the Activity — a process the system brings up for a
 * content-provider read or a service restart has no screen, and a scan begun there would
 * have nothing to stop it.
 */
class HazriApplication : Application() {

    /**
     * The scope every source, engine tick and view model runs on.
     *
     * The process's, not an Activity's. A survey outlives the screen it was started from —
     * that is the whole point of the service — and cancelling this on `onDestroy` would
     * have cancelled the collector the notification was counting.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Starts and stops [SurveyForegroundService], and carries what it shows. */
    lateinit var keepAlive: AndroidSurveyKeepAlive
        private set

    /** The object graph. Started by [MainActivity], not here. */
    lateinit var container: AppContainer
        private set

    /**
     * How many Activities are between `onStart` and `onStop`.
     *
     * The engine's lifetime is "a screen, or a recording". The Activity can only see the
     * first half of that, so when a recording ends from the notification or from a swipe
     * out of Recents, this is what says whether there is still a screen to keep collecting
     * for. Without it the last `onStop` had already declined to stop the engine, and
     * nothing came back to it.
     */
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()

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
        keepAlive = AndroidSurveyKeepAlive(this, onRecordingEnded = ::stopEngineIfBackgrounded)
        container = AppContainer(
            repository = repository,
            sources = AndroidSourceFactory(
                context = this,
                repository = repository,
                onScanFailed = { message -> container.engine.reportError(message) },
            ),
            scope = appScope,
            simulationAvailable = BuildConfig.SIMULATION_AVAILABLE,
            keepAlive = keepAlive,
        )

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities -= 1
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Stops collecting if a recording just ended with nothing on screen.
     *
     * `MainActivity.onStop` deliberately leaves the engine running while a survey records.
     * This is the other end of that: the recording is over, so the reason to keep scanning
     * a backgrounded app is too.
     */
    private fun stopEngineIfBackgrounded() {
        if (startedActivities > 0) return
        appScope.launch { container.engine.stop() }
    }

    private companion object {
        const val STORE_DIRECTORY = "hazri"
    }
}

/** The application, from anywhere that has a [Context]. */
val Context.hazri: HazriApplication get() = applicationContext as HazriApplication
