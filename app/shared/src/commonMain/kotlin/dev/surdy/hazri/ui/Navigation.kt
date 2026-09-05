package dev.surdy.hazri.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.surdy.hazri.domain.NodeId

/** Every place the app can be. */
sealed interface Destination {
    /** One of the four bottom-bar tabs. */
    sealed interface Tab : Destination {
        val title: String
    }

    data object Live : Tab {
        override val title = "Live"
    }

    data object Survey : Tab {
        override val title = "Survey"
    }

    data object Coverage : Tab {
        override val title = "Coverage"
    }

    data object Tools : Tab {
        override val title = "Tools"
    }

    data class NodeDetail(val nodeId: NodeId) : Destination
    data object Calibrate : Destination
    data object BeaconCheck : Destination
    data object MqttInspector : Destination
    data object CompareSources : Destination
    data object NodesAndRooms : Destination
    data object ExportSession : Destination
    data object Settings : Destination
}

/**
 * An in-memory back stack.
 *
 * Not the Compose Multiplatform navigation library. This app has four tabs and seven leaf
 * screens with no deep links, no arguments beyond a node id, and no process death to
 * restore across — a list and an index is the whole requirement, and it is a dozen lines
 * that can be read in full rather than a beta dependency that cannot.
 *
 * The tab is remembered separately from the stack, so returning from a leaf screen lands
 * back on the tab that opened it.
 */
class Navigator(initialTab: Destination.Tab = Destination.Live) {
    var tab: Destination.Tab by mutableStateOf(initialTab)
        private set

    private val stack = mutableStateListOf<Destination>()

    /** What to render: the top of the stack, or the current tab when the stack is empty. */
    val current: Destination get() = stack.lastOrNull() ?: tab

    /** Whether [back] would do anything. */
    val canGoBack: Boolean get() = stack.isNotEmpty()

    /** Switches tab. Clears any leaf screen above it. */
    fun selectTab(next: Destination.Tab) {
        stack.clear()
        tab = next
    }

    /** Pushes a leaf screen. */
    fun push(destination: Destination) {
        stack.add(destination)
    }

    /** Pops one screen. Returns whether anything was popped. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}
