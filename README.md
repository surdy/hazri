# Hazri

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Hazri (ਹਾਜ਼ਰੀ, "attendance") is a phone app for placing and tuning the BLE
presence-sensing nodes that tell Home Assistant which room you are in. You walk
the house with it, watch how strongly each node hears you, record a survey per
room, and push the resulting tuning back to the nodes over MQTT.

## How it works

In an ESPresense setup the nodes listen and the phone is the beacon, so a phone
app cannot simply "measure itself". Hazri works around that with two data
sources and lets you switch between them:

| Source | What it measures | Needs |
|---|---|---|
| Direct scan | The phone scans the nodes' own iBeacon advertisements and records RSSI per node | Bluetooth only while walking. The advert-to-room mapping is learned once from the broker, or entered by hand under Nodes & rooms |
| MQTT | What each node reports *about the phone* on `espresense/devices/<id>/<room>` | Wi-Fi and a reachable MQTT broker |

Every ESPresense node advertises a non-connectable iBeacon under the UUID
`e5ca1ade-f007-ba11-0000-000000000000`, with major/minor derived from its chip
id, and publishes the matching `iBeacon:…-<major>-<minor>` → room mapping to
MQTT. That is what makes direct scan possible, and it is why Hazri keys nodes on
advertised identity rather than MAC address. RSSI is roughly symmetric, so
direct scan is a good proxy for what the nodes hear; MQTT mode is the ground
truth, and a compare-sources tool shows the per-node delta between them.

The number Hazri optimises for is the **margin**: in a given room, the RSSI of
the strongest node minus the RSSI of the second strongest. A large margin means
the room is unambiguously owned by one node. Below roughly 5 dB the room is
contested and Home Assistant will flap between rooms.

## Status

Pre-hardware. No AtomS3 Lite nodes have been flashed yet, so the app is
developed against `tools/espresense-sim`, a simulator that publishes
ESPresense-shaped MQTT traffic. Every number in the docs that could be checked
against upstream sources has been; anything that still needs a real node is
marked "(unverified)".

## Repo layout

| Path | Contents |
|---|---|
| `app/` | Kotlin Multiplatform / Compose Multiplatform app, Android first |
| `tools/espresense-sim/` | Simulated ESPresense fleet for development without hardware |
| `docs/` | Plan, decision records, guides, protocol reference |
| `design/` | Design canvas artboards for the app mockups |

## Docs

- [docs/plan.md](docs/plan.md) — product scope, screens, tech plan, milestones
- [docs/hardware.md](docs/hardware.md) — bill of materials and node placement
- [docs/espresense-topics.md](docs/espresense-topics.md) — the MQTT topics Hazri reads and writes
- [docs/guides/flash-atoms3-lite.md](docs/guides/flash-atoms3-lite.md) — flashing ESPresense onto an AtomS3 Lite
- [docs/guides/phone-as-beacon.md](docs/guides/phone-as-beacon.md) — making the phone visible to the nodes
- [docs/decisions/0001-firmware-espresense.md](docs/decisions/0001-firmware-espresense.md) — ESPresense over ESPHome + Bermuda
- [docs/decisions/0002-board-atoms3-lite.md](docs/decisions/0002-board-atoms3-lite.md) — one board model, AtomS3 Lite
- [docs/decisions/0003-app-stack.md](docs/decisions/0003-app-stack.md) — Kotlin Multiplatform, Kable, MQTT behind an interface

## License

MIT — see [LICENSE](LICENSE).
