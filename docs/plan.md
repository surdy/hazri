# Hazri — BLE placement & tuning companion for home presence sensing

Name: **Hazri** (ਹਾਜ਼ਰੀ, attendance or roll call).

## Problem

A handful of M5Stack boards (Atom Lite / AtomS3 / StickC) will run BLE
presence-sensing firmware and report to Home Assistant. Placement and per-node
tuning (reference RSSI, absorption, max distance) is trial and error. Hazri is a
phone app you carry while walking the house that shows, per room, how strongly
each node is heard and whether the room is unambiguously "owned" by one node.

## Key insight: two data sources, and both matter

With ESPresense / ESPHome-Bermuda style setups the **nodes listen, the phone is
the beacon**. Hazri therefore supports two sources and lets you flip between them:

| Source | What it measures | Needs | Use it for |
|---|---|---|---|
| **Direct scan** | Phone scans the nodes' own BLE advertisements, RSSI per node | Nodes must advertise (see Firmware) | Fast placement work, offline, no broker |
| **MQTT** | What each node reports *about the phone* (`rssi`, `distance`) | Wi-Fi + broker reachable | Ground truth: what HA will actually see |

RSSI is roughly symmetric, so direct scan is a good proxy. A "Compare sources"
tool shows the per-node delta so you can trust direct mode once calibrated.

The single most useful number is the **margin**: best node RSSI minus
second-best, per room. Under ~5 dB the room is ambiguous and HA will flap.

## Decisions

| | Choice | Why |
|---|---|---|
| Board | **M5Stack AtomS3 Lite** (ESP32-S3FN8, 8 MB flash, native USB CDC, 3D antenna) | One model for every node so RSSI is comparable across nodes |
| Firmware | **ESPresense** (v4.0.6 stable, Feb 2026) | Nodes advertise out of the box, every setting is live over MQTT, per-node RSSI streams as JSON. See the comparison in the 2026-09-04 session notes; Bermuda was the runner-up |
| Broker | Mosquitto add-on in HA | Only extra infrastructure ESPresense needs |
| Companion | Skip for now | Hazri covers placement; Companion can be added later for floor-plan trilateration, it reads the same topics |

### Flashing an AtomS3 Lite with ESPresense

1. Use the **`esp32s3-cdc`** build. The AtomS3 Lite has no USB-serial chip;
   the S3's native USB is the console, which is what the `-cdc` variant
   targets. The plain `esp32s3` build runs but logs go nowhere.
2. Enter bootloader mode: hold the side reset button about 2 s until the
   internal green LED lights, then release.
3. Flash from Chrome/Edge at espresense.com/firmware, or with esptool if the
   web flasher dislikes the CDC port (reported on some hosts).
4. Join the `ESPresense-xxxx` access point, set Wi-Fi, MQTT broker, and the
   room name. Set the status LED to the WS2812 on GPIO 35 if you want it.
5. Node name convention: room name only, lower-case, one word
   (`kitchen`, `hall`). ESPresense uses it in topics and in Companion.

### ESPresense topics Hazri uses

- `espresense/devices/<phone-id>/<room>` — JSON `{rssi, raw, distance, …}`
  per node, the ground-truth source for MQTT mode.
- `espresense/rooms/<room>/<setting>/set` — write a setting live
  (`ref_rssi`, `absorption`, `max_distance`, …); Hazri's "Push via MQTT".
- `espresense/rooms/<room>/telemetry` — node health, uptime, free heap.

Hazri keys nodes on what they **advertise**, never on MAC, because iOS hides
MACs. ESPresense nodes advertise an iBeacon for node-to-node calibration.
First task of the scan spike: capture one node's advertisement and confirm
which field carries the node identity (name vs. iBeacon major/minor) and
map it to the ESPresense room name.

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
   Calibrate-at-1 m, Copy config, Push via MQTT.
5. **Tools**: Calibrate reference · Beacon check (is the phone advertising, at
   what interval, iBeacon UUID) · MQTT inspector · Compare sources · Session
   export (CSV/JSON) · Settings (broker, phone ID, smoothing window, node
   aliases).

Later (not in v1): floor-plan image with node pins; multi-phone comparison;
push node config directly to ESPHome via its API.

## Tech plan

**Recommendation: Kotlin Multiplatform + Compose Multiplatform**, Android
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
| Distance | log-distance model: `d = 10^((ref_rssi − rssi) / (10·n))` | Same model ESPresense uses; `n` = absorption |

Android specifics: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+), plus
location permission on older APIs; keep scanning in a foreground service so the
screen can sleep during a survey. Turn off the phone's own MAC randomisation
concerns by using the ESPresense iBeacon/fingerprint ID for the MQTT topic.

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

- Firmware is taken as ESPresense per the recommendation; say so if you'd
  rather go Bermuda and the Node detail config block and MQTT mode change.
- Phone side: HA Companion app BLE transmitter (iBeacon) on Android is the
  simplest phone identity for ESPresense. Confirm you're fine running it.
