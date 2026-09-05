# Hazri — BLE placement & tuning companion for home presence sensing

Name: **Hazri** (ਹਾਜ਼ਰੀ, attendance or roll call).

## Problem

A handful of M5Stack AtomS3 Lite boards will run BLE presence-sensing firmware
and report to Home Assistant. Placement and per-node tuning (reference RSSI,
absorption, max distance) is trial and error. Hazri is a phone app you carry
while walking the house that shows, per room, how strongly each node is heard
and whether the room is unambiguously "owned" by one node.

## Key insight: two data sources, and both matter

With ESPresense / ESPHome-Bermuda style setups the **nodes listen, the phone is
the beacon**. Hazri therefore supports two sources and lets you flip between them:

| Source | What it measures | Needs | Use it for |
|---|---|---|---|
| **Direct scan** | Phone scans the nodes' own BLE advertisements, RSSI per node | Bluetooth only while walking; the advert-to-room mapping is learned once from the broker, or entered by hand under Nodes & rooms (see Decisions) | Fast placement work, no broker on the walk |
| **MQTT** | What each node reports *about the phone* (`rssi`, `distance`) | Wi-Fi + broker reachable | Ground truth: what HA will actually see |

RSSI is roughly symmetric, so direct scan is a good proxy. A "Compare sources"
tool shows the per-node delta so you can trust direct mode once calibrated.

The single most useful number is the **margin**: best node RSSI minus
second-best, per room. Under ~5 dB the room is ambiguous and HA will flap.

## Decisions

| | Choice | Why |
|---|---|---|
| Board | **M5Stack AtomS3 Lite** (ESP32-S3FN8, 8 MB flash, native USB CDC, 3D antenna) | One model for every node so RSSI is comparable across nodes. [Decision 0002](decisions/0002-board-atoms3-lite.md) |
| Firmware | **ESPresense** (v4.0.6 stable, Feb 2026) | Nodes advertise out of the box, every setting is live over MQTT, per-node RSSI streams as JSON. [Decision 0001](decisions/0001-firmware-espresense.md); Bermuda was the runner-up |
| Broker | Mosquitto add-on in HA | Only extra infrastructure ESPresense needs |
| Companion | Skip for now | Hazri covers placement; Companion can be added later for floor-plan trilateration, it reads the same topics |

### Flashing an AtomS3 Lite with ESPresense

Step by step in [docs/guides/flash-atoms3-lite.md](guides/flash-atoms3-lite.md).

1. Use the **`esp32s3-cdc`** build. The AtomS3 Lite has no USB-serial chip;
   the S3's native USB is the console, which is what the `-cdc` variant
   targets. The plain `esp32s3` build runs but logs go nowhere.
2. Flash from Chrome/Edge at espresense.com/firmware. If no serial port
   appears, hold the side reset button about 2 s until the internal green LED
   lights, then release, and try again.
3. esptool only re-flashes a board that already carries ESPresense's
   bootloader; see the guide.
4. Join the `ESPresense-xxxx` access point, set Wi-Fi, MQTT broker, and the
   room name. Set the status LED to the WS2812 on GPIO 35 if you want it.
5. Node name convention: room name only, lower-case, one word
   (`kitchen`, `hall`). ESPresense uses it in topics and in Companion.

### ESPresense topics Hazri uses

- `espresense/devices/<phone-id>/<room>` — JSON `{id, rssi, rssi@1m,
  distance, var, int, …}` per node, the ground-truth source for MQTT mode.
- `espresense/rooms/<room>/<setting>/set` — write a setting live
  (`ref_rssi`, `absorption`, `max_distance`, …); Hazri's "Push via MQTT".
- `espresense/rooms/<room>/telemetry` — node health, uptime, free heap.

Full reference, with payloads: [docs/espresense-topics.md](espresense-topics.md).

Hazri keys nodes on what they **advertise**, never on MAC, because iOS hides
MACs. ESPresense nodes advertise a non-connectable iBeacon under UUID
`e5ca1ade-f007-ba11-0000-000000000000`, major/minor derived from the chip id,
and publish a retained `espresense/settings/iBeacon:<uuid>-<major>-<minor>/config`
naming themselves `node:<room>` — that pair is the mapping from advert to room.
First task of the scan spike: confirm that on real hardware.

## App structure (5 screens + tools)

1. **Live** (home): nodes sorted by strength, big RSSI, smoothed bar,
   10 s sparkline, packet rate, distance estimate; source toggle; margin line.
2. **Survey**: pick a room, tap Record, walk the room; Hazri averages every
   node's RSSI for that room and shows the verdict as you go. Surveyed rooms
   are listed with age so you know what's stale after moving a node.
3. **Coverage**: rooms × nodes matrix of mean RSSI with a per-room verdict
   (Clear / Tight / Blind) and concrete suggestions ("Hall and Living nodes
   within 3 dB in Hallway — move Hall node toward the stairs or lower its
   `max_distance`").
4. **Node detail**: 60 s RSSI history, mean / σ / min–max / pkt/s, and the
   node's config block (room, `ref_rssi`, `absorption`, `max_distance`) with
   Calibrate-at-1 m, Copy config, Push via MQTT. Calibrate-at-1 m measures the
   phone's RSSI at 1 m and reports the value to set as *Measured power at 1 m*
   in the Companion app — the node uses a beacon's own measured power, so
   `ref_rssi` only affects non-beacon devices.
5. **Tools**: Calibrate reference · Beacon check (is the phone advertising, at
   what interval, iBeacon UUID) · MQTT inspector · Compare sources · Session
   export (CSV/JSON) · Settings (broker, phone ID, smoothing window, node
   aliases).

Later (not in v1): floor-plan image with node pins; multi-phone comparison;
push node config directly to ESPHome via its API.

## Tech plan

**Kotlin Multiplatform + Compose Multiplatform** ([decision 0003](decisions/0003-app-stack.md)), Android
first. You already work in Compose; iOS becomes a build target rather than a
rewrite.

| Concern | Choice | Notes |
|---|---|---|
| UI | Compose Multiplatform, Material 3 | Custom dark theme from the mockups |
| BLE scanning | [Kable](https://github.com/JuulLabs/kable) | Android + iOS from common code; scan by advertised name |
| MQTT | KMQTT client (multiplatform) or HiveMQ client on Android only for v1 | Subscribe `espresense/devices/<phone-id>/#` |
| Storage | SQLDelight (or Room KMP) | Sessions, room surveys, node aliases/config |
| Charts | Compose `Canvas` | Sparkline, history line, heatmap — no library needed |
| DI / state | Koin + ViewModel, StateFlow | |
| Smoothing | EMA α≈0.2 + median-of-5 per node | Configurable in Settings |
| Distance | log-distance model: `d = 10^((rssi@1m − rssi) / (10·n))` | Same model ESPresense uses; `n` = absorption. `rssi@1m` is `ref_rssi` for plain devices, the beacon's own measured power for iBeacons |

Android specifics: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+), plus
location permission on older APIs; keep scanning in a foreground service so the
screen can sleep during a survey. The phone's MAC randomisation does not
matter: the MQTT topic is keyed on the ESPresense fingerprint id.

iOS caveats: no MAC access (already handled), foreground scanning only is fine
for this tool, iBeacon advertising from the phone requires CoreLocation
`CLBeaconRegion` — a small platform-specific file.

## Milestones

1. **Scan spike (1–2 evenings)**: flash two AtomS3 Lites, Kable scan, list nodes with raw RSSI, settle the node-identity mapping.
2. **Live screen** with smoothing, sparkline, margin.
3. **Survey + Coverage** with SQLDelight persistence. This is the tuning loop.
4. **MQTT mode + Compare sources**.
5. **Node detail + config push**, calibration, export.
6. iOS build once BLE + MQTT libs prove out in common code.

## Open questions

- Firmware is settled as ESPresense in
  [decision 0001](decisions/0001-firmware-espresense.md); switching to Bermuda
  would change the Node detail config block and MQTT mode.
- Phone side: HA Companion app BLE transmitter (iBeacon) on Android is the
  simplest phone identity for ESPresense. Confirm you're fine running it.
