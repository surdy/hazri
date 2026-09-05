# 0001 — Node firmware: ESPresense

Status: accepted, 2026-09-04.

## Context

Hazri needs firmware on the ESP32 nodes that (a) lets the phone see the nodes
directly over BLE so placement work runs without Wi-Fi or a broker, (b) exposes
what each node hears about the phone as machine-readable ground truth, and
(c) accepts tuning written back from the phone. The two mature options for
room-level BLE presence in Home Assistant are ESPresense and ESPHome
Bluetooth proxies plus the Bermuda integration.

## Options

| | ESPresense | ESPHome + Bermuda |
|---|---|---|
| Flash a node | Browser installer on [espresense.com/firmware](https://espresense.com/firmware/) (Chrome/Edge, WebSerial); the M5 Atom S3 Lite is the documented default board | [ESPHome](https://esphome.io/) Device Builder, one YAML per node, then adopt the device in Home Assistant |
| Extra infra | An MQTT broker — the Mosquitto add-on in HA | None beyond Home Assistant and ESPHome |
| Where you tune | Per-node web UI or MQTT, live, no reflash ([calibration](https://espresense.com/guides/calibration/)) | Bermuda's options flow in the HA UI, live |
| Phone identity | HA Companion BLE transmitter (Android) or IRK enrollment (iOS) | Same keys, surfaced through HA's Private BLE Device integration |
| Nodes advertise | Yes, out of the box: a non-connectable iBeacon under UUID `e5ca1ade-f007-ba11-0000-000000000000` ([`src/Enrollment.cpp`](https://github.com/ESPresense/ESPresense/blob/master/src/Enrollment.cpp), [`src/util.h`](https://github.com/ESPresense/ESPresense/blob/master/src/util.h)) | No. It needs [`esp32_ble_beacon`](https://esphome.io/components/esp32_ble_beacon.html) added by hand; that page documents no coexistence guidance with `bluetooth_proxy`/`esp32_ble_tracker` and warns "Crashes are likely to occur if you include too many additional components" because of BLE stack RAM |
| Per-node RSSI for the phone | JSON on MQTT, one topic per node per device: `espresense/devices/<id>/<room>` ([MQTT reference](https://espresense.com/configuration/mqtt/)) | Per-scanner sensors inside Home Assistant, reachable only through the HA API |
| Push config from the app | Yes — one retained MQTT publish per setting, e.g. `espresense/rooms/kitchen/absorption/set` | Not practical; settings live in a config-entry options flow |
| Optional extras | [Companion add-on](https://espresense.com/companion/): floor plan, trilateration, automatic optimisation of `rx_adj_rssi` and `absorption` | The proxies double as general Bluetooth proxies for other HA integrations |

Bermuda's own README describes the receiver requirement as "ESPHome devices with
the `bluetooth_proxy` component enabled" and its tunables as "rssi reference
level, environmental attenuation, max tracking radius", all set in the HA UI
([agittins/bermuda](https://github.com/agittins/bermuda)).

The common complaints about ESPresense are real and worth naming. Derek Seaman:
"I found it much harder to configure and tweak than Bermuda BLE. Not to mention
the ESP32 device must be dedicated to ESPresense"
([derekseaman.com](https://www.derekseaman.com/2025/12/home-assistant-track-whos-in-each-room-with-esphome-bermuda-ble.html)).
zorruno: "You can't use an ESPresense node for other things" and "ESPresense
only uses MQTT"
([zorruno.com](https://zorruno.com/2024/replacing-espresense-with-bermuda-for-ble-tracking/)).
That post also worries that "the last official release for ESpresense was Jan
2022"; that is no longer true — v4.0.6 shipped on 28 February 2026 and is the
current stable release
([releases](https://github.com/ESPresense/ESPresense/releases)).

## Decision

Use ESPresense.

The app works on day one with no firmware changes: the nodes already advertise
so direct scan needs nothing added, ground truth already streams as JSON on
MQTT, and every calibration setting is already writable over MQTT. With Bermuda,
two of those three would have to be built — an extra beacon component on each
node with undocumented coexistence behaviour, and an HA API client to read
per-scanner values and an options-flow writer to change them.

The "hard to tweak" complaint is the thing Hazri exists to fix, so it argues for
ESPresense rather than against it: the tuning surface is wide open over MQTT and
nobody has put a walk-the-house UI in front of it.

## Consequences

- An MQTT broker is required. Mosquitto as a Home Assistant add-on is the
  assumed setup, and Hazri's MQTT mode depends on it being reachable from the
  phone.
- The nodes are dedicated to presence sensing. They cannot also act as general
  Bluetooth proxies for other Home Assistant integrations.
- ESPresense does not support TLS on MQTT and sends credentials in plaintext
  ([quick start](https://espresense.com/quick-start/)), so this stays on a
  trusted home network.
- Hazri's protocol surface is the topic set in
  [docs/espresense-topics.md](../espresense-topics.md); it should be kept
  pinned to a known firmware version, currently v4.0.6.
- Revisit if the ESP32s should also serve other BLE sensors to Home Assistant,
  or if the owner would rather never leave the HA UI. In that case Bermuda is
  the better fit, and Hazri's MQTT mode and node-config screen would have to be
  rebuilt against the HA API.
