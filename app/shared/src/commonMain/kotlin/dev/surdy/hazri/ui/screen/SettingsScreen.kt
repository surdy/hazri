package dev.surdy.hazri.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.data.AppSettings
import dev.surdy.hazri.data.BrokerSettings
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.ui.component.Fmt
import dev.surdy.hazri.ui.component.HazriCard
import dev.surdy.hazri.ui.component.LabelledField
import dev.surdy.hazri.ui.component.ScreenHeader
import dev.surdy.hazri.ui.component.SectionLabel
import dev.surdy.hazri.ui.component.SegmentedControl
import dev.surdy.hazri.ui.component.Stepper
import dev.surdy.hazri.ui.theme.HazriColors

/** Everything that changes how the app behaves, in one list. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    sources: List<SourceKind>,
    onBack: () -> Unit,
    onSelectSource: (SourceKind) -> Unit,
    onBrokerChange: ((BrokerSettings) -> BrokerSettings) -> Unit,
    onPhoneIdChange: (String) -> Unit,
    onAlphaChange: (Double) -> Unit,
    onMedianChange: (Int) -> Unit,
    onMarginChange: (Double) -> Unit,
    onFloorChange: (Double) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Settings", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            item { SectionLabel("Source") }
            item {
                HazriCard(padding = 16) {
                    SegmentedControl(
                        options = sources.map(::sourceLabel),
                        selectedIndex = sources.indexOf(settings.sourceKind).coerceAtLeast(0),
                        onSelect = { index -> onSelectSource(sources[index]) },
                    )
                    Text(
                        text = when (settings.sourceKind) {
                            SourceKind.DIRECT ->
                                "The phone scans for the nodes' own advertisements. No broker needed."
                            SourceKind.MQTT ->
                                "What each node reports about this phone. Ground truth, needs the broker."
                            SourceKind.SIMULATED ->
                                "A scripted walk through a virtual six-room house. Debug builds only."
                        },
                        fontSize = 12.sp,
                        color = HazriColors.textTertiary,
                        lineHeight = 17.sp,
                    )
                }
            }

            item { SectionLabel("Broker") }
            item {
                HazriCard(padding = 16) {
                    LabelledField(
                        label = "Host",
                        value = settings.broker.host,
                        onValueChange = { host -> onBrokerChange { it.copy(host = host) } },
                        placeholder = "10.0.0.12",
                        mono = true,
                    )
                    LabelledField(
                        label = "Port",
                        value = settings.broker.port.toString(),
                        onValueChange = { port ->
                            onBrokerChange { it.copy(port = port.toIntOrNull() ?: it.port) }
                        },
                        mono = true,
                        keyboardType = KeyboardType.Number,
                    )
                    LabelledField(
                        label = "Username",
                        value = settings.broker.username,
                        onValueChange = { user -> onBrokerChange { it.copy(username = user) } },
                        placeholder = "optional",
                    )
                    LabelledField(
                        label = "Password",
                        value = settings.broker.password,
                        onValueChange = { pass -> onBrokerChange { it.copy(password = pass) } },
                        placeholder = "optional",
                    )
                }
            }

            item { SectionLabel("Phone") }
            item {
                HazriCard(padding = 16) {
                    LabelledField(
                        label = "Phone id",
                        value = settings.phoneId,
                        onValueChange = onPhoneIdChange,
                        placeholder = "iBeacon:<uuid>-<major>-<minor>",
                        mono = true,
                        helper = "The fingerprint the Companion app advertises under. It is the " +
                            "third segment of espresense/devices/<id>/<room>, and the app cannot " +
                            "discover it: a phone cannot hear its own advertisement.",
                    )
                }
            }

            item { SectionLabel("Smoothing") }
            item {
                HazriCard(padding = 16) {
                    Stepper(
                        label = "EMA alpha",
                        value = Fmt.one(settings.smoothingAlpha),
                        helper = "Weight on the newest reading. Higher reacts faster and jitters more.",
                        onDecrement = { onAlphaChange(settings.smoothingAlpha - 0.05) },
                        onIncrement = { onAlphaChange(settings.smoothingAlpha + 0.05) },
                    )
                    Stepper(
                        label = "Median window",
                        value = settings.medianWindow.toString(),
                        helper = "Raw samples the median runs over before the EMA. Always odd.",
                        onDecrement = { onMedianChange(settings.medianWindow - 2) },
                        onIncrement = { onMedianChange(settings.medianWindow + 2) },
                    )
                    Text(
                        text = "MQTT samples bypass both: ESPresense already sends a filtered " +
                            "value, so only a light EMA of ${Fmt.one(settings.mqttAlpha)} is applied.",
                        fontSize = 11.sp,
                        color = HazriColors.muted,
                        lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { SectionLabel("Verdict thresholds") }
            item {
                HazriCard(padding = 16) {
                    Stepper(
                        label = "Clear margin",
                        value = "${Fmt.one(settings.marginDb)} dB",
                        helper = "Best minus runner-up at or above which a room counts as Clear.",
                        onDecrement = { onMarginChange(settings.marginDb - 1.0) },
                        onIncrement = { onMarginChange(settings.marginDb + 1.0) },
                    )
                    Stepper(
                        label = "Blind floor",
                        value = "${Fmt.one(settings.floorDbm)} dBm",
                        helper = "A room whose best node is below this is Blind.",
                        onDecrement = { onFloorChange(settings.floorDbm - 1.0) },
                        onIncrement = { onFloorChange(settings.floorDbm + 1.0) },
                    )
                }
            }

            item {
                HazriCard(padding = 16) {
                    Text(
                        "Fonts",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HazriColors.textSecondary,
                    )
                    Text(
                        "Manrope and JetBrains Mono, both under the SIL Open Font License. The " +
                            "licence texts ship with the app.",
                        fontSize = 12.sp,
                        color = HazriColors.textTertiary,
                    )
                }
            }
        }
    }
}
