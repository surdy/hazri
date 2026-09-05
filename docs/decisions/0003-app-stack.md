# 0003 — App stack: Kotlin Multiplatform and Compose Multiplatform

Status: accepted, 2026-09-04. Details drawn from [docs/plan.md](../plan.md).

## Context

Hazri is a phone app that scans BLE, subscribes to MQTT, keeps a small amount of
survey history, and draws live charts. Android is the first and only target that
has to work; iOS is wanted eventually but must not drive the design or double
the work.

## Options

A plain Android app in Kotlin and Compose would be the shortest path but makes
iOS a rewrite. React Native or Flutter would cover both, but neither matches the
existing Compose experience here, and both put a foreign-language bridge between
the app and the platform BLE APIs, which is exactly the part that is fiddly.

## Decision

Kotlin Multiplatform with Compose Multiplatform and Material 3, Android first,
with iOS as a build target rather than a port.

| Concern | Choice |
|---|---|
| UI | Compose Multiplatform, Material 3, custom dark theme from `design/` |
| BLE scanning | [Kable](https://github.com/JuulLabs/kable) — Android and iOS from common code |
| MQTT | A client behind a Hazri-owned interface; a multiplatform client such as KMQTT, or an Android-only client for v1 |
| Storage | SQLDelight (or Room KMP) for sessions, room surveys, node aliases |
| Charts | Compose `Canvas` — sparkline, history, heatmap; no charting library |
| DI and state | Koin, ViewModel, `StateFlow` |
| Smoothing | EMA with α≈0.2 plus median-of-5 per node, window configurable |
| Distance | `d = 10^((rssi@1m − rssi) / (10 · absorption))`, where `rssi@1m` is the node's `ref_rssi` for plain devices and the beacon's own measured power for iBeacons |

Two constraints shape the design more than the library choices.

**Nodes are keyed on advertised identity, never on MAC.** iOS does not hand out
peripheral MAC addresses, and Android randomises its own. ESPresense nodes
advertise an iBeacon under UUID `e5ca1ade-f007-ba11-0000-000000000000` with
major and minor derived from the chip id, and each node publishes a retained
`espresense/settings/iBeacon:<uuid>-<major>-<minor>/config` naming itself
`node:<room>`. Hazri identifies a node by that UUID plus major/minor and maps it
to a room name through that retained config, so the identity model is identical
on both platforms and iOS stays cheap. See
[docs/espresense-topics.md](../espresense-topics.md).

**MQTT sits behind an interface.** The direct-scan path must work with no broker
reachable on the walk — the advert-to-room mapping is learned from the broker
once, or entered by hand — so nothing above the data layer may assume a live
MQTT connection. That keeps the
Android-only client option open for v1 without committing the architecture to
it, and it is what lets `tools/espresense-sim` stand in for real hardware.

The distance formula above is the one the firmware itself uses
(`dist = pow(10, (get1mRssi() - rssi) / (10 * absorption))` in
[`src/BleFingerprint.cpp`](https://github.com/ESPresense/ESPresense/blob/master/src/BleFingerprint.cpp)),
so Hazri's estimate and the node's agree when fed the same settings. Note which
reference `get1mRssi()` returns: for an iBeacon — which is what an Android phone
running the Companion app is — it is the beacon's own advertised measured power,
and the node's `ref_rssi` is used only for devices that advertise no calibrated
power. So Node detail's Calibrate-at-1 m measures the phone's RSSI at 1 m and
reports the value to set as *Measured power at 1 m* in the Companion app; it
does not write `ref_rssi`.

## Consequences

- Android needs `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` on API 31+, plus
  location permission on older APIs, and a foreground service so a survey
  survives the screen sleeping.
- iOS gets foreground-only scanning, which is fine for a tool you hold while
  walking. Advertising *from* the phone as an iBeacon needs CoreLocation
  `CLBeaconRegion`, a small platform-specific file — but only for MQTT mode, and
  the iOS path for phone identity is IRK enrollment instead. See
  [docs/guides/phone-as-beacon.md](../guides/phone-as-beacon.md).
- Anything platform-specific is a small `expect`/`actual` file, not a fork.
- The MQTT client can be swapped later without touching screens or view models;
  the interface, not the library, is the commitment.
