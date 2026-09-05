# Hazri app

Kotlin Multiplatform phone app for placing and tuning ESPresense BLE presence nodes.
Android is the only target that builds today; the shared module holds everything that is
not a platform capability, so iOS is a build-file change plus three small files.

No hardware exists yet, so the app is fully exercisable against a simulated signal source.
Debug builds start in **Simulated** mode with every screen populated.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd app
./gradlew :shared:allTests :androidApp:assembleDebug
```

There is no `java` on this machine's `PATH`, hence the `JAVA_HOME` export. JDK 17 is what
AGP 9.3.1 and Kotlin 2.4.10 want; a newer JDK is not required and has not been tested.

`app/local.properties` (gitignored) must contain:

```
sdk.dir=/Users/surdy/Library/Android/sdk
```

Install and run:

```bash
./gradlew :androidApp:installDebug
adb shell am start -n dev.surdy.hazri/dev.surdy.hazri.android.MainActivity
```

## Two names per node, and why

`NodeRecord` carries both, and keeping them apart is the difference between a push that
lands and one that vanishes:

- **`displayName`** is the user's. It is shown everywhere and published nowhere. "Under the
  stairs" is a legitimate value. Setting it marks the record so a retained config from the
  broker cannot rename it back.
- **`espresenseRoom`** is the firmware's, and it is the segment in
  `espresense/rooms/<room>/<setting>/set`. It is only ever set by the node — the last
  segment of a device topic, or the `node:<room>` in a retained settings config — or
  explicitly by the user in Tools, Nodes & rooms. Deriving it from the display name is how
  settings end up published to a room that does not exist.

`roomIsConfirmed` says which of those happened. An unconfirmed room is badged on Node
detail and disables Push, because a push to a guessed room is worse than no push.

## Naming a node with no broker

Nothing in a BLE advertisement carries a room — the mapping lives only in the retained
`espresense/settings/iBeacon:<uuid>-<major>-<minor>/config` a node publishes about itself.
So direct-scan mode resolves an unmapped node beacon to `node-<major>-<minor>` and shows it
like any other node; Tools, Nodes & rooms is where it gets a name and a firmware room.

When the broker does announce a room, `HazriRepository.learnBrokerRoom` applies it to the
record that already carries the fingerprint, or attaches the fingerprint to the record the
room already has, or creates one. The scanner's fingerprint lookup is a function reading the
repository per advertisement, not a snapshot taken when Direct mode was first selected.

The other half of "one board, one record" is on the MQTT side. A device topic names a
*room*, not a node, and on a live broker the retained settings config normally arrives
first — so the node is already on record under its beacon id by the time its first report
lands. `MqttSignalSource` therefore resolves the topic's room through
`HazriRepository.nodeIdForRoom` (a confirmed room only) and falls back to the room itself
only on first contact. Keying on the room regardless is what used to give one board two
records: direct samples on one, MQTT samples on the other, a column each in Coverage, and
nothing for Compare sources to pair. Both orderings are covered by tests.

## Surveying with the screen off

A survey is a walk with the phone in a pocket, which is exactly the state Android stops an
app's BLE scan and its socket reads in. `SurveyForegroundService` is the answer: it starts
with a recording and stops with it, and while its notification is up the process keeps its
foreground importance and the samples keep arriving.

The seam is `vm/SurveyKeepAlive.kt` — three methods and a no-op default, so nothing outside
Android needs an implementation and the tests use a fake. `SurveyViewModel` calls `start`
when a recording begins, `update` on its own tick (throttled to once a second, because the
notification shows whole seconds), and `stop` on every path that ends a recording: the Stop
button, a source switch, the notification's own Stop action, and the app being swiped out
of Recents. The Android side writes progress to a `StateFlow` the service collects rather
than sending an intent per second, since the two are in one process.

The notification shows the room, the elapsed time and the sample count. Tapping it opens
the app on the Survey tab. Its **Stop** action ends the recording through the same
`SurveyViewModel.stop` the in-app button calls — so the walk is filed, the simulated pin is
released and the scan drops back to its resting rate. The service is never the thing that
decides a survey is over.

The object graph moved with it. `HazriApplication` owns the `AppContainer`, not the
Activity: the service stops the same survey the user can see, and a second engine with a
second scanner behind it would have made the notification's Stop action a no-op on the
recording on screen. A rotation or a back-out-and-return now re-attaches to the running
sources instead of building new ones. It is *built* there and *started* by
`MainActivity.onCreate` — a process the system brings up for a content-provider read or a
service restart has no screen, and a scan begun there would have had nothing to stop it.
`AppContainer.start` is idempotent, so a recreation does not restart the sources.

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | Any foreground service. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | What a BLE survey is. |
| `FOREGROUND_SERVICE_DATA_SYNC` | What a survey recorded over MQTT or the simulator is. |
| `POST_NOTIFICATIONS` | Asked for when the Survey tab opens, on API 33+. |

Three things the platform forces:

- **The type is chosen at start, not in the manifest alone.** From API 34 the platform
  refuses `connectedDevice` unless the app actually holds a Bluetooth runtime permission,
  and a survey recorded in MQTT or simulated mode may never have asked for one. Those fall
  back to `dataSync`, which is what they are doing. Both branches were checked on the
  emulator: `types=00000010` with the scan permission granted, `types=00000001` without.
- **A refused notification permission is not an error.** The service still runs and the
  survey still records; Android simply shows nothing for it. The request is fired when the
  Survey tab opens and its result is not read.
- **`START_NOT_STICKY`,** and a start command that finds no recording in progress stops the
  service instead of going foreground. A process the system restarted has no accumulator
  left, so there is nothing to keep alive. Stopping before `startForeground` is safe there:
  the five-second deadline is a delayed message that is cancelled when the service is
  brought down. A Stop tapped on a notification whose recording has already ended stops the
  service too, rather than leaving the tap to have started it.
- **`onTimeout` is handled on both signatures** — API 34's one argument and API 35's two.
  `dataSync` is capped at six hours a day, and a service that ignores the callback is
  killed; ending the recording files what has been walked and takes the service down.

**The scan changes shape for a recording, twice over.** Android stops delivering an
*unfiltered* scan's results once the screen is off, from 8.1 onwards and whether or not a
foreground service is holding the process up — so a survey walked with the phone in a
pocket would otherwise hear nothing at all. A recording therefore scans with one filter,
`source/NodeIdentifier.kt`'s `NodeBeaconFilter`: Apple's company id, the `02 15` proximity
header, and the fixed eight bytes of `Espresense.NODE_BEACON_UUID`, masked `FF`. Major and
minor are left free, so one filter matches every node in the house. The bytes are derived
from the UUID constant rather than typed out, and their order is unit-tested against a
frame the iBeacon parser agrees is a node.

The cost is that a filtered scan reports nothing else, so Tools -> Nodes & rooms sees no
new unidentified advertisers while a survey runs. The resting scan stays unfiltered, which
is when that list is read anyway.

**Battery.** Nothing changes when no survey is recording: the Activity stops the engine in
`onStop`, and the scan stops with it. A recording is the exception, and when one ends with
nothing on screen — the notification's Stop, a swipe out of Recents — the engine is stopped
then instead, because the `onStop` that would have done it has already been and gone.
During a recording the scan asks for `SCAN_MODE_LOW_LATENCY`; outside one it asks for
`SCAN_MODE_BALANCED`, which is itself a step up from the platform default of
`SCAN_MODE_LOW_POWER` this app used to take by omission.

## Smoke tested

Installed on the `tt_phone` AVD (API 35, 1080x2400) and driven through every screen:

- Live renders five nodes sorted by strength with the lead banner, sparklines and the
  source picker.
- Survey records: 914 samples over 23 s, live per-node means, running verdict, and the
  finished walk lands in the Surveyed list with its verdict and age.
- Coverage shows the seeded six-room grid with a Clear, a Tight and a Blind row, the
  legend, and both suggestion sentences.
- Node detail shows the 60 s chart with its axis labels in a gutter of their own, the four
  stat tiles on one line each, and `ref_rssi` marked **assumed**.
- Nodes & rooms edits the display name and the firmware room as two separate fields, with
  the room badged confirmed or unconfirmed.
- Tools and Settings render and navigate. The platform back gesture pops the in-app stack.

The foreground service was driven on the same AVD, on API 34, where the type entitlement is
enforced: a simulated Kitchen survey started, Home pressed, and after fifteen seconds
`dumpsys notification` showed `Surveying Kitchen` / `0:35 · 1218 samples` on channel
`survey` with one Stop action, and `dumpsys activity services` showed
`isForeground=true types=00000001 stopIfKilled=true`. Left backgrounded the count kept
climbing at the simulator's full rate — 3125 samples at 01:28 when the notification was
tapped, which landed on the Survey tab from the Live tab it had been left on. The
notification's **Stop** filed the walk (Kitchen, Clear · 18 dB, "just now"), released the
simulated pin and left no service and no notification behind. Repeating it in Direct mode
with the scan permission granted gave `types=00000010`, the `connectedDevice` branch. With
nothing recording, a backgrounded app has no service and sits at `LAST` importance, as
before. No exceptions in logcat across the run.

Re-run after review, on the same AVD:

- The survey filter reaches the platform. With a recording running,
  `dumpsys bluetooth_manager` shows the ongoing scan as
  `ScanMode=LOW_LATENCY` with
  `BluetoothLeScanFilter [ ManufacturerId=76 ManufacturerData=[2, 21, -27, -54, 26, -34, -16, 7, -70, 17] ManufacturerDataMask=[-1 × 10] ]`
  — `02 15 E5 CA 1A DE F0 07 BA 11` as signed bytes. The resting scans in the same dump are
  `ScanMode=BALANCED` with no filter.
- Ending a recording from the background now stops the engine. Process CPU
  (`/proc/<pid>/stat`) while recording and backgrounded: **145 jiffies per 10 s**. After the
  notification's Stop, still backgrounded: **0 per 10 s**. Reopening resumed it (27 per 8 s)
  and the walk was filed — Kitchen, 892 samples over 00:25, "just now".

A reviewer also ran it against `tools/espresense-sim` over a real broker: MQTT mode works
and the HiveMQ client re-subscribes on reconnect.

No crashes in logcat across the run.

## Toolchain

Mirrors the author's `smart-display` project, and for the same reasons:

| | |
|---|---|
| Gradle | 9.6.1, wrapper copied from `smart-display` |
| AGP | 9.3.1 — the first line that carries KMP through `com.android.kotlin.multiplatform.library` |
| Kotlin | 2.4.10 |
| Compose Multiplatform | 1.11.1 (Material 3 at `1.11.0-alpha07`, which is the line published alongside it; there is no 1.11.x stable of that artifact) |
| minSdk / compileSdk / targetSdk | 26 / 36 / 35 |

## Module layout

```
app/
  shared/       KMP library: domain, protocol, sources, data, view models, the whole UI
    commonMain/ everything except two platform seams
    androidMain/ DirectScanSource actual (Kable), HiveMqGateway, AndroidFileStore
    commonTest/ 227 tests: domain, protocol, sources, repository and every view model
  androidApp/   Application and object graph, Activity, survey foreground service,
                permission flow, clipboard and share intents
```

`shared/src/commonMain/kotlin/dev/surdy/hazri/`:

| Package | What is in it |
|---|---|
| `domain` | `NodeId`, `Node`, `SignalSample`, `RssiSmoother`, `DistanceModel`, `RoomSurvey`, `Verdict`, `Suggestions`, `CoverageMatrix`, `NodeConfig`, `CalibrationSession` |
| `protocol` | ESPresense topics, setting keys, device-report and telemetry parsing |
| `source` | `SignalSource` and its three implementations, `NodeIdentifier`, `MqttGateway` |
| `data` | `FileStore`, `HazriRepository`, `AppSettings`, `SessionExport`, `DemoSeed`, `NodeRecord` |
| `vm` | `SignalEngine` and one view model per screen, plus `AppContainer` and `SurveyKeepAlive` |
| `ui` | theme, icons, components, screens, `Navigator` |

## What is real and what is stubbed

**Real, and exercised by tests**

- The whole domain: smoothing, distance model, verdicts, surveys, coverage, suggestions,
  calibration arithmetic.
- The ESPresense protocol: topic construction and parsing, setting keys, lenient JSON
  parsing of device reports, retained node-identity configs and telemetry.
- `SimulatedSignalSource`: a six-room, five-node virtual house with a scripted walk,
  per-node distance models, Gaussian noise and dropped packets, deterministic for a seed.
  Its geometry is chosen so that Coverage shows all three verdicts (Kitchen Clear, Hallway
  Tight, Garage Blind), which is what makes every screen usable with no hardware.
- Persistence: four JSON documents through a `FileStore`.
- Every screen, wired to live state.

**Real, but never run against hardware**

- `DirectScanSource` (Kable). Compiles and resolves; the scan callback path has not seen a
  real advertisement.
- `HiveMqGateway` (HiveMQ MQTT 3.1.1). Connects, subscribes, publishes; not yet run against
  a real Mosquitto. The `MqttSignalSource` above it is fully covered by tests with a fake
  gateway.

**Stubbed**

- **Beacon check** is a placeholder screen that explains why: a phone cannot scan for its
  own advertisement, so nothing inside this app can confirm the beacon is transmitting.
  The working check is indirect — connect MQTT mode and see whether any node reports this
  phone. Advertising interval and transmit power are readable from the Android platform and
  are not surfaced yet. It is the only stub left: a recording now survives the screen going
  off, and the mechanism is in *Surveying with the screen off*.

## Running against the MQTT simulator

`tools/espresense-sim` publishes under
`espresense/devices/iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1/<room>`, and those
are the app's shipped defaults for phone id and broker, so MQTT mode works with nothing
typed in.

The broker host defaults to `localhost`. **On the Android emulator that must be changed to
`10.0.2.2`** — inside the emulator, `localhost` is the emulated device, not the machine
running the simulator. Settings → Broker → Host. On a physical phone, use the LAN address
of the machine running the simulator.

The network security config permits cleartext to `localhost` and `10.0.2.2` only. MQTT on
1883 is raw TCP and is not governed by that config at all; the entries are there for any
HTTP the development loop grows.

## Verified against ESPresense, and what it changed

Checked 2026-09-04 against `ESPresense/ESPresense` `master` (v4.0.6),
<https://espresense.com/configuration/settings>, and `docs/espresense-topics.md`.

- **`ref_rssi` is -65 and `absorption` is 2.7**, from `include/defaults.h`
  (`DEFAULT_RX_REF_RSSI`, `DEFAULT_ABSORPTION`). The `-59` that circulates — and that this
  app's brief carried — is `DEFAULT_TX_REF_RSSI`, the power a *node* advertises in its own
  iBeacon. It is kept as `DistanceModel.TX_REF_RSSI` so the number has a correct home. The
  design mockups show `-59 / 3.5` in the node config block; the app shows the firmware's
  real defaults instead.
- **There is no `room` setting.** `name/set` is the rename, it rewrites `/room` on the node
  and re-slugifies every topic the node uses, and it only takes effect after a restart.
- **`ref_rssi` is write-only.** The retained snapshot a node republishes on connect carries
  `name`, `max_distance`, `absorption`, `tx_ref_rssi`, `rx_adj_rssi` and the filter lists —
  but never `ref_rssi`. So the value Node detail shows is either what Hazri pushed (tracked
  per node in `NodeRecord.refRssiPushedAt`) or the firmware default, and the screen labels
  the second case **assumed**.
- **An empty `set` payload resets a setting to its default**; a room of `*` addresses every
  node. Both are supported (`MqttSignalSource.resetSetting`, `Espresense.ALL_ROOMS`).
- **The device payload has no `raw` key in 4.x.** `rssi` is the reading, a float with two
  decimals. `raw` is still parsed, because a fleet is rarely all on one firmware.
- **`rssi` is already smoothed by the node** — a Tukey-fenced mean over 15 s. Re-running
  Hazri's median-of-five and alpha-0.2 EMA over it would lag reality by most of a minute,
  so MQTT samples get a median window of 1 and an EMA weight of 0.6
  (`AppSettings.newSmoother(kind)`). Direct scan and the simulator get the full treatment,
  because those samples are raw.
- **Node identity is known.** Every node advertises a non-connectable iBeacon under UUID
  `e5ca1ade-f007-ba11-0000-000000000000` with major/minor from its eFuse MAC, and publishes
  itself retained on `espresense/settings/iBeacon:<uuid>-<major>-<minor>/config` as
  `{"id":"node:<room>"}`. `DefaultNodeIdentifier` matches the UUID and looks the major/minor
  pair up in that mapping.
- **Calibration does not write `ref_rssi`.** ESPresense ranges an iBeacon from the power the
  beacon advertises, and the phone *is* an iBeacon. So the capture's output is a "measured
  power at 1 m" to type into the Home Assistant Companion app's BLE transmitter settings,
  copyable from the screen. Pushing it as the node's `ref_rssi` is offered as a clearly
  labelled secondary action, for devices that advertise no calibrated power of their own.

## Deliberate deviations from the brief

- **No SQLDelight.** The whole dataset is a handful of rooms times a handful of nodes, and a
  survey is already reduced to a mean and a sigma before it is stored. Four JSON documents
  through a `FileStore` interface cost no Gradle plugin, no schema, no migration story, and
  are testable in `commonTest` with a map. If the app ever stores raw samples, this is the
  seam to replace.

  Writes are ticketed in caller order and applied under a lock, so a writer that reaches the
  lock behind a newer edit of the same document does nothing. A mutex alone would serialise
  the writers without ordering them, and "rename, then hide" could persist the pre-hide
  document — `RepositoryWriteOrderTest` drives the repository through a deliberately
  reversing dispatcher to hold that down.
- **No `multiplatform-settings`.** Preferences are one of those four documents. Adding a
  library to store six fields would have been more moving parts than the thing it replaced.
- **No `androidx.navigation`.** Four tabs, seven leaf screens, one argument, no deep links.
  `Navigator` is a list and an index, in `ui/Navigation.kt`.
- **Hand-wired DI, no Koin.** Seven objects; the wiring is in `vm/AppContainer.kt` and reads
  top to bottom.
- **Icons are drawn, not imported.** Every glyph in `design/` is a hand-drawn 24x24 stroke
  with no Material equivalent, so `HazriIcons` pastes the SVG path data verbatim and parses
  it with Compose's own `PathParser`.
- **Manrope is registered at one weight.** Google Fonts publishes it only as a variable
  font. Registering the same file again at 700 would tell Compose the file *is* bold and
  suppress the synthetic weight it would otherwise apply, so headings would come out light.
  One entry plus Compose's own synthesis renders the mockup's 700 and 800 headings on every
  API level. JetBrains Mono ships static instances, so its three weights are three files.
  Both fonts are OFL; the licence texts ship in `composeResources/files/`.
- **No iOS targets.** Every platform-dependent declaration is already an `expect`/`actual`
  pair or an interface, so the iOS work is `iosArm64()`/`iosSimulatorArm64()` in
  `shared/build.gradle.kts` plus three files: a `DirectScanSource` actual (Kable publishes
  the same API for Darwin), an `MqttGateway` implementation, and a `FileStore`. They are out
  of this pass because `:shared:allTests` would then want to run the simulator test
  binaries, which is a longer loop than this build is asked to keep green. The comment in
  `shared/build.gradle.kts` marks the spot.

## TODOs waiting on hardware

| Where | What |
|---|---|
| `source/NodeIdentifier.kt` | The node beacon UUID, the major/minor derivation and the retained-config shape are read from firmware source, not measured. Capture one node's advertisement and confirm the UUID byte for byte, that major/minor are stable across reboots, and that the retained config appears under exactly that fingerprint. Unidentified advertisers are surfaced in a debug list rather than dropped, which is the capture this needs. |
| `source/NodeIdentifier.kt` | Current firmware calls `NimBLEDevice::init("ESPresense")`, so the advertised local name is a fleet-wide constant and identifies the firmware, not the room. If a build is found that suffixes it, the suffix path already handles it. |
| `source/DirectScanSource.android.kt` | A recording scans filtered, at `SCAN_MODE_LOW_LATENCY`, behind the foreground service. The filter bytes come from `Espresense.NODE_BEACON_UUID` and are read from firmware source, not measured — the same capture that confirms the UUID confirms the filter, since a wrong one produces a walk that hears nothing and looks like a house out of range. |
| `ui/screen/ToolDetailScreens.kt` | Beacon check is a placeholder. Advertising interval and transmit power are readable on Android; worth surfacing once there is a node to verify the readings against. |
| `androidApp/proguard-rules.pro` | Release is not minified. HiveMQ's Netty transport and its reflective event dispatch will need keeps before it can be. |
| `ui/HazriApp.kt` | `BackHandler` is both experimental and deprecated in CMP 1.11, which points at `NavigationEventHandler` — a class the `ui-backhandler` artifact of this version does not ship. Revisit when it does. |

## Open questions

- The design mockups show `ref_rssi −59` and `absorption 3.5` in the node config block. The
  app shows the firmware's actual defaults, `-65` and `2.7`. Worth a mockup update if the
  design is regenerated.
- `max_distance` defaults to 16 m here, matching the firmware. The mockup shows 6 m, which
  is a tuned value rather than a default.
- The Coverage column headings abbreviate node names to three characters, as the mockup
  does. With more than about six nodes the grid will need to scroll horizontally instead.
