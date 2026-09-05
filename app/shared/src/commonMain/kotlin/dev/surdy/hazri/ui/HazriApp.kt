package dev.surdy.hazri.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.data.SessionExport
import dev.surdy.hazri.ui.screen.BeaconCheckScreen
import dev.surdy.hazri.ui.screen.CalibrateToolScreen
import dev.surdy.hazri.ui.screen.CompareSourcesScreen
import dev.surdy.hazri.ui.screen.CoverageScreen
import dev.surdy.hazri.ui.screen.ExportScreen
import dev.surdy.hazri.ui.screen.LiveScreen
import dev.surdy.hazri.ui.screen.MqttInspectorScreen
import dev.surdy.hazri.ui.screen.NodeDetailScreen
import dev.surdy.hazri.ui.screen.NodesAndRoomsScreen
import dev.surdy.hazri.ui.screen.SettingsScreen
import dev.surdy.hazri.ui.screen.SurveyScreen
import dev.surdy.hazri.ui.screen.ToolsScreen
import dev.surdy.hazri.ui.screen.sourceLabel
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.ui.theme.HazriIcons
import dev.surdy.hazri.ui.theme.HazriTheme
import dev.surdy.hazri.source.MqttConnectionState
import dev.surdy.hazri.vm.AppContainer
import kotlinx.coroutines.launch

/**
 * Platform capabilities the UI needs and cannot do itself.
 *
 * Two of them, both Android intents in practice. Passing them in as an interface rather
 * than an `expect` keeps the whole UI in `commonMain` and makes both trivially fakeable.
 */
interface PlatformActions {
    /** Puts [text] on the clipboard. */
    fun copyToClipboard(label: String, text: String)

    /** Offers [content] to be saved or sent as [fileName]. */
    fun shareText(fileName: String, content: String)

    /**
     * Asks for whatever the platform needs before a recording can show its ongoing
     * notification. Called when the Survey tab opens, so the dialog is in context rather
     * than on first launch.
     *
     * Defaulted to nothing: a platform with no such permission, and every fake, wants no
     * implementation at all. Refusal is not an error — the recording still runs, with
     * nothing on screen to say so.
     */
    fun requestSurveyNotificationPermission() {}
}

/**
 * The whole app: theme, navigation and the four tabs.
 *
 * One composable rather than a navigation graph. See [Navigator] for why.
 *
 * `BackHandler` is both experimental and deprecated in Compose Multiplatform 1.11, which
 * points at `NavigationEventHandler` — a class the `ui-backhandler` artifact of this version
 * does not ship (its only classes are `BackHandler_androidKt` and `BackEventCompat`). So the
 * replacement is not available yet and this is the API that exists. The alternative is
 * registering an `OnBackPressedCallback` from the Activity, which would move the navigation
 * state out of common code for the sake of two annotations.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
fun HazriApp(
    container: AppContainer,
    actions: PlatformActions,
    /**
     * The back stack. Passed in where the platform needs to drive it from outside the
     * composition — on Android, the survey notification opening the Survey tab.
     */
    navigator: Navigator = remember { Navigator() },
) {
    HazriTheme {
        val scope = rememberCoroutineScope()
        val sources = remember(container) { container.availableSources() }

        // The platform back gesture pops the in-app stack; only a back press on a bare tab
        // leaves the app. Enabled only when there is something to pop, so the system keeps
        // its default behaviour on the four tabs.
        BackHandler(enabled = navigator.canGoBack) { navigator.back() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(HazriColors.background)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when (val destination = navigator.current) {
                    Destination.Live -> {
                        val state by container.engine.live.collectAsState()
                        LiveScreen(
                            state = state,
                            sources = sources,
                            onSelectSource = { kind -> scope.launch { container.switchSource(kind) } },
                            onOpenNode = { node -> navigator.push(Destination.NodeDetail(node.node.id)) },
                        )
                    }

                    Destination.Survey -> {
                        val state by container.survey.uiState.collectAsState()
                        // Asked for here rather than at the Record tap: the dialog and the
                        // first notification would otherwise land in the same frame.
                        LaunchedEffect(Unit) { actions.requestSurveyNotificationPermission() }
                        SurveyScreen(
                            state = state,
                            onSelectRoom = container.survey::selectRoom,
                            onAddRoom = container.survey::addRoom,
                            onStart = container.survey::start,
                            onStop = container.survey::stop,
                        )
                    }

                    Destination.Coverage -> {
                        val state by container.coverage.uiState.collectAsState()
                        CoverageScreen(state)
                    }

                    Destination.Tools -> {
                        val state by container.tools.uiState.collectAsState()
                        ToolsScreen(state) { navigator.push(it) }
                    }

                    is Destination.NodeDetail -> {
                        val viewModel = remember(destination.nodeId) {
                            container.nodeDetail(destination.nodeId)
                        }
                        val state by viewModel.uiState.collectAsState()
                        val connected =
                            container.mqtt?.connection?.value is MqttConnectionState.Connected
                        NodeDetailScreen(
                            state = state,
                            onBack = { navigator.back() },
                            onEditConfig = viewModel::editConfig,
                            onStartCalibration = { viewModel.startCalibration() },
                            onStopCalibration = viewModel::stopCalibration,
                            onApplyCalibrationAsRefRssi = viewModel::applyCalibrationAsRefRssi,
                            onCopyConfig = { text -> actions.copyToClipboard("Hazri config", text) },
                            configText = viewModel.configAsCommands(),
                            onPush = viewModel::pushConfig,
                            canPush = connected,
                        )
                    }

                    Destination.Calibrate -> {
                        val state by container.tools.uiState.collectAsState()
                        CalibrateToolScreen(
                            nodes = state.nodes,
                            onBack = { navigator.back() },
                            onOpenNode = { nodeId -> navigator.push(Destination.NodeDetail(nodeId)) },
                        )
                    }

                    Destination.BeaconCheck -> {
                        val settings by container.repository.settings.collectAsState()
                        BeaconCheckScreen(settings.phoneId) { navigator.back() }
                    }

                    Destination.MqttInspector -> {
                        val state by container.tools.uiState.collectAsState()
                        MqttInspectorScreen(state) { navigator.back() }
                    }

                    Destination.CompareSources -> {
                        val state by container.tools.uiState.collectAsState()
                        val live by container.engine.live.collectAsState()
                        CompareSourcesScreen(
                            state = state,
                            onBack = { navigator.back() },
                            onStart = {
                                container.tools.startComparison(
                                    primaryLabel = sourceLabel(live.sourceKind),
                                    secondaryLabel = "Sim B",
                                    other = container.comparisonSource(),
                                )
                            },
                            onStop = container.tools::stopComparison,
                        )
                    }

                    Destination.NodesAndRooms -> {
                        val state by container.tools.uiState.collectAsState()
                        NodesAndRoomsScreen(
                            state = state,
                            onBack = { navigator.back() },
                            onRename = container.tools::renameNode,
                            onSetRoom = container.tools::setEspresenseRoom,
                            onSetHidden = container.tools::setHidden,
                            onClearUnidentified = container.tools::clearUnidentified,
                        )
                    }

                    Destination.ExportSession -> {
                        val exportedAt = container.engine.live.collectAsState().value.updatedAt
                        ExportScreen(
                            csv = SessionExport.toCsv(container.repository),
                            json = SessionExport.toJson(container.repository, exportedAt),
                            onBack = { navigator.back() },
                            onShare = actions::shareText,
                        )
                    }

                    Destination.Settings -> {
                        val settings by container.repository.settings.collectAsState()
                        SettingsScreen(
                            settings = settings,
                            sources = sources,
                            onBack = { navigator.back() },
                            onSelectSource = { kind ->
                                scope.launch { container.settings.setSource(kind) }
                            },
                            onBrokerChange = container.settings::setBroker,
                            onPhoneIdChange = container.settings::setPhoneId,
                            onAlphaChange = container.settings::setSmoothingAlpha,
                            onMedianChange = container.settings::setMedianWindow,
                            onMarginChange = container.settings::setMarginDb,
                            onFloorChange = container.settings::setFloorDbm,
                        )
                    }
                }
            }

            BottomBar(
                selected = navigator.tab,
                onSelect = navigator::selectTab,
            )
        }
    }
}

@Composable
private fun BottomBar(selected: Destination.Tab, onSelect: (Destination.Tab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HazriColors.navBackground)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HazriColors.navBorder))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TabItem(Destination.Live, HazriIcons.Live, selected, onSelect, Modifier.weight(1f))
            TabItem(Destination.Survey, HazriIcons.Survey, selected, onSelect, Modifier.weight(1f))
            TabItem(Destination.Coverage, HazriIcons.Coverage, selected, onSelect, Modifier.weight(1f))
            TabItem(Destination.Tools, HazriIcons.Tools, selected, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabItem(
    tab: Destination.Tab,
    icon: ImageVector,
    selected: Destination.Tab,
    onSelect: (Destination.Tab) -> Unit,
    modifier: Modifier,
) {
    val active = tab == selected
    val tint = if (active) HazriColors.accent else HazriColors.inactive
    Column(
        modifier = modifier.height(56.dp).clickable { onSelect(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = tab.title, tint = tint, modifier = Modifier.size(22.dp))
        Box(modifier = Modifier.height(4.dp))
        Text(
            text = tab.title,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            color = tint,
        )
    }
}
