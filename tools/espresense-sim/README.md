# espresense-sim

A houseful of fake ESPresense nodes for developing Hazri's MQTT mode before any
hardware exists. It publishes what real nodes publish — per-device readings,
retained settings, telemetry, status with a last will, Home Assistant discovery
— and it accepts `<setting>/set` writes, so Hazri's "Push via MQTT" has
something to push at.

Everything is a port of **ESPresense v4.0.6** (the version the plan pins), read
from the firmware source rather than from documentation. Each module names the
file it came from.

## Quick start

Zero arguments talks to `localhost:1883`:

```bash
python -m venv .venv && .venv/bin/pip install -e .        # add ".[dev]" for pytest
.venv/bin/python sim.py
```

With uv:

```bash
uv run --with paho-mqtt --with pyyaml sim.py
```

No broker to hand? `--dry-run` prints every message instead of sending it, and
`--once` runs a single tick. Against a real broker, `--log-publishes` prints
the same trace while still sending:

```bash
.venv/bin/python sim.py --once --dry-run
.venv/bin/python sim.py --log-publishes
```

Broker plus simulator in containers:

```bash
docker compose up
```

That exposes MQTT on 1883 and websockets on 9001, anonymous. An Android
emulator reaches the host broker at `10.0.2.2:1883`; a phone on the same Wi-Fi
needs the machine's LAN address.

Watch the traffic:

```bash
mosquitto_sub -h localhost -t 'espresense/#' -v
```

Push a setting the way the app will:

```bash
mosquitto_pub -h localhost -t espresense/rooms/kitchen/absorption/set -m 3.2
mosquitto_pub -h localhost -t 'espresense/rooms/*/absorption/set' -m 3.2  # whole fleet
mosquitto_pub -h localhost -t espresense/rooms/kitchen/rx_adj_rssi/set -m 17
```

The node applies it, republishes its retained settings, and every subsequent
`distance` uses the new value.

`absorption` and `rx_adj_rssi` are the two to push while you are watching,
because they move every reading. **`ref_rssi` does nothing to the default
phone**: it is an iBeacon, and `get1mRssi()` prefers the beacon's own
calibrated power over the setting. Run with `--device-id phone:synthetic` if
you want a device that `ref_rssi` does steer.

### Against the Home Assistant Mosquitto add-on

The add-on wants credentials, so make a Home Assistant user for the simulator
(Settings > People > Users) and pass them:

```bash
.venv/bin/python sim.py --host homeassistant.local --port 1883 \
  --username hazri-sim --password '...'
```

Two cautions. The simulator publishes retained topics under
`espresense/rooms/<room>/…` and `homeassistant/…`, so it will create real
ESPresense devices in that Home Assistant. Use room names that no real node
will ever use (`--layout` with a `sim-` prefix on every room), or point it at a
throwaway broker instead. To clean up afterwards, publish an empty retained
message to each topic, or restart the add-on with persistence cleared.

## Tests

```bash
.venv/bin/pip install -e ".[dev]"
.venv/bin/python -m pytest
```

They cover the RSSI model, the walk interpolation, the payload schema and the
`/set` handling with a fake transport. No broker is needed and nothing sleeps.

## What it models

A house of rectangular rooms with a node in each
(`espresense_sim/config/house.yaml`), a phone walking a looping waypoint script
at 1 m/s (`espresense_sim/config/walk.yaml`), and a radio:

```
rssi = ref_rssi_1m - 10 * n * log10(d) - wall_penalty + antenna_offset + noise
```

with `ref_rssi_1m` −59 dBm, `n` 3.5, per-room-pair wall penalties in dB, and
Gaussian noise with σ 3 dB. Packet loss rises with distance
(`base + (d / 18) ** 2`, capped at 0.95), so distant nodes go quiet rather than
reporting garbage.

Each node then runs the firmware's own maths over those samples: subtract
`rx_adj_rssi`, smooth with ESPresense's 15-second Tukey-fenced trimmed mean,
and derive `distance = 10 ** ((rssi@1m - rssi) / (10 * absorption))`. The
default layout gives the ESP32-S3 boards a physical +20 dB antenna offset,
which the firmware's `rx_adj_rssi` default of 20 cancels — except in the
kitchen, which is 3 dB deaf on purpose.

### The calibration the app should discover

The kitchen node reads 3 dB deaf, so `rx_adj_rssi = 17` is the finding waiting
there — live-settable, and it changes the very next reading.

`ref_rssi` is the subtler one. The default phone is an **iBeacon**, so
`get1mRssi()` takes the beacon's own −59 dBm and ignores `ref_rssi` entirely;
pushing `ref_rssi` at these nodes changes nothing, which is exactly the trap
the app's Calibrate-at-1 m needs to avoid. Run with
`--device-id phone:synthetic` to get a device with no calibrated power, where
`rssi@1m` falls back to `ref_rssi + DEFAULT_TX` = −65 + −6 = **−71** against a
true −59 dBm at 1 m, so distances read short by `10 ** (12 / 27)` ≈ 2.7 and
`ref_rssi = -53` is the fix.

The garage has no node, so it is the blind spot the Coverage screen should call
out. Hall and living sit within a few dB of each other in the hallway, which is
the ambiguous-margin case.

## Options

```
--host --port --username --password    broker (default localhost:1883)
--dry-run                              print instead of publishing
--device-id --device-name              the phone. Defaults to the iBeacon id
                                       iBeacon:1d4b2e16-...-100-1, because
                                       ESPresense never publishes a device
                                       whose idType is at or below RAND_MAC
--beacon-1m-rssi N                     advertise a calibrated 1 m power, as an
                                       iBeacon does; nodes then ignore ref_rssi.
                                       Implied as -59 for an iBeacon:/altBeacon:
                                       device id
--mac-rotate SECONDS                   rotate the phone's random MAC (default 900)
--layout --walk                        YAML or JSON overrides for house and walk
--speed --rooms --seed                 walk speed, node subset, RNG seed
--true-ref-rssi --true-path-loss --noise-sigma   the physical truth
--ref-rssi --absorption --max-distance --rx-adj-rssi --skip-ms --skip-distance
                                       what the firmware starts out believing
                                       (--skip-ms 0 turns rate limiting off)
--adv-hz                               advertisements per second (default 1)
--once --duration --speedup            single tick, time limit, faster clock
--schema v4|v3                         payload shape (default v4)
--no-discovery                         skip Home Assistant discovery
--publish-ref-rssi                     retain ref_rssi too (firmware does not)
--log-publishes                        print every message sent and received
-q --quiet                             suppress the per-tick summary line
```

How often a node publishes is not `--adv-hz`: the firmware rate-limits each
device to one report per `skip_ms` (5 s) slot, allowing an early report when
the device has moved more than `skip_distance` (0.5 m). A walking phone
therefore produces roughly 0.2–2 reports per second per node.

## Topic and payload reference

Verified against the firmware source at tag
[v4.0.6](https://github.com/ESPresense/ESPresense/tree/v4.0.6) on 2026-09-04,
cross-checked with [espresense.com/configuration/mqtt](https://espresense.com/configuration/mqtt/)
and [espresense.com/configuration/settings](https://espresense.com/configuration/settings/).

| Topic | Retained | Source |
|---|---|---|
| `espresense/devices/<id>/<room>` | no | `reportDevice()`, [main.cpp](https://github.com/ESPresense/ESPresense/blob/v4.0.6/src/main.cpp) |
| `espresense/rooms/<room>/status` | yes (LWT) | `sendTelemetry()`, `connectToMqtt()` |
| `espresense/rooms/<room>/telemetry` | no, every 15 s | `sendTelemetry()` |
| `espresense/rooms/<room>/<setting>` | yes | `sendTelemetry()`'s `!online` branch |
| `espresense/rooms/<room>/<setting>/set` | subscribed | `onMqttMessage()` |
| `espresense/rooms/*/<setting>/set` | subscribed | fleet-wide, same handler |
| `espresense/settings/<id>/config` | yes, and subscribed | `sendConfig()` / `Config()` |
| `homeassistant/<component>/espresense_<chipid>/<slug>/config` | yes | [mqtt.cpp](https://github.com/ESPresense/ESPresense/blob/v4.0.6/src/mqtt.cpp) |

### Device reading

From `BleFingerprint::fill()` in
[BleFingerprint.cpp](https://github.com/ESPresense/ESPresense/blob/v4.0.6/src/BleFingerprint.cpp):

```json
{"mac":"fd39dd10fb29","id":"phone:hazri-test","name":"Hazri test phone",
 "rssi@1m":-71,"rssi":-82.48,"rxAdj":20,"rssiVar":3.92,
 "distance":2.66,"var":0.22,"int":666}
```

| Key | Meaning |
|---|---|
| `mac` | 12 lower-case hex digits, no colons. Rotates on a phone; never key on it |
| `id` | The fingerprint id, e.g. `iBeacon:<uuid>-<major>-<minor>` |
| `name` | Present only when the device has one |
| `rssi@1m` | `get1mRssi()`: a beacon's own calibrated power if it advertises one, otherwise `ref_rssi + DEFAULT_TX` where `DEFAULT_TX` is −6 |
| `rssi` | **Smoothed**, after `rx_adj_rssi` is subtracted. Two decimals |
| `rxAdj` | The node's `rx_adj_rssi` at the time of the reading |
| `rssiVar` | Population variance of the RSSI window |
| `distance` | `10 ** ((rssi@1m - rssi) / (10 * absorption))` |
| `var` | Variance of the distance over the same window |
| `close` | Present and `true` only while latched close (see below) |
| `int` | Mean advertisement interval, ms: `(millis - firstSeen) / seenCount` |

**There is no `raw` key in 4.x, and no `speed` or `idType`.** Older write-ups
mention them because v3.x published `rssi` unsmoothed alongside `raw`, which
was the unfiltered *distance* — not a raw RSSI. Pass `--schema v3` to emit the
legacy shape if you need to test against it.

`close` latches when the smoothed RSSI rises above `CLOSE_RSSI + rx_adj_rssi`
(−40 dBm) and clears below `LEFT_RSSI + rx_adj_rssi` (−50 dBm) — hysteresis
from `shouldCount()`.

### Settings

Defaults from
[include/defaults.h](https://github.com/ESPresense/ESPresense/blob/v4.0.6/include/defaults.h);
commands from `BleFingerprintCollection::Command()`. An empty payload resets a
setting to its default.

| Setting | Default | Retained? | Effect |
|---|---|---|---|
| `ref_rssi` | −65 | **no** | RSSI expected from a 0 dBm transmitter at 1 m. Ignored for iBeacons |
| `absorption` | 2.7 | yes | Path-loss exponent, 1–5 |
| `max_distance` | 16.0 | yes | Readings computed beyond this are dropped |
| `rx_adj_rssi` | 20 on esp32s3 builds, 0 on m5atom/m5stick | yes | Per-node receiver offset |
| `tx_ref_rssi` | −59 | yes | Power the node claims in its own iBeacon |
| `skip_ms` / `skip_distance` / `max_divisor` | 5000 / 0.5 / 10 | no | Report rate limiting |
| `query` `include` `exclude` `known_macs` `known_irks` `count_ids` | empty | yes | Filtering and counting |
| `name` | — | yes | Writes the new room to flash; applies on restart |
| `restart` / `reboot` | — | — | Reboots |

`ref_rssi` being settable but never published back is real firmware behaviour,
not an omission here: the online snapshot publishes `tx_ref_rssi` and
`rx_adj_rssi` but not `ref_rssi`, so an app cannot read the current value from
MQTT. Pass `--publish-ref-rssi` if you want the simulator to retain it anyway
while you build the UI, but do not depend on it against real nodes.

### Node identity

Outside enrollment each node advertises an iBeacon with UUID
`e5ca1ade-f007-ba11-0000-000000000000`, major and minor derived from the eFuse
MAC and tx power from `tx_ref_rssi`, and publishes itself retained:

```
espresense/settings/iBeacon:e5ca1ade-f007-ba11-0000-000000000000-125-11346/config
{"id":"node:kitchen","name":"kitchen"}
```

That is the mapping from a scanned advertisement to a room, with no MAC
involved — what Hazri's direct-scan mode keys on. The simulator publishes one
per node, in the same discovery block the firmware uses (so `--no-discovery`
suppresses it, exactly as the firmware's `discovery` setting does).

Nodes also **subscribe** to `espresense/settings/+/config`, so the alias path
works both ways. Publish one retained and every node adopts it:

```bash
mosquitto_pub -h localhost -r -t 'espresense/settings/iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1/config' \
  -m '{"id":"known:harpreet","name":"Harpreet"}'
```

From the next advertisement the phone is published as
`espresense/devices/known:harpreet/<room>` with that name, matching `setId()`,
which re-ids a fingerprint to its alias. A `rssi@1m` in the payload becomes the
device's `calRssi` and overrides everything else. An empty retained payload
removes the config.

### Home Assistant discovery

Implemented: `connectivity` (binary_sensor), `Uptime` and `Free Mem` (sensor),
`Restart` (button), `Max Distance` and `Absorption` (number), all retained, all
built the way `mqtt.cpp` builds them. Out of scope: enroll, LEDs, motion,
switches, buttons, the updater and the I2C sensor zoo.

## Known gaps versus real firmware

- **Radio.** Log-distance plus a per-room-pair penalty is a caricature. Real
  RSSI is multipath, body-shadowed and orientation-dependent; standing still
  can move it 10 dB. Rooms are rectangles and a point on a shared boundary
  belongs to whichever room the layout lists first.
- **Node-to-node.** Nodes here do not hear each other, so no node publishes a
  reading about another node's iBeacon. Only the node identity configs are
  published.
- **Numbers.** The firmware formats floats with exactly two decimals
  (`4.20`); this emits JSON numbers (`4.2`). Numerically identical, textually
  not.
- **Filtering.** The 15-second window and the Tukey fence are ported exactly;
  the ring buffer's dynamic resizing is not, because at realistic
  advertisement rates it cannot change the result.
- **Query and enrollment.** `query`, `include`, `exclude`, `known_macs`,
  `known_irks` and `count_ids` are stored and republished, but nothing filters
  on them. Of the device-config path only alias, name and `rssi@1m` are
  honoured; the BLE enrollment handshake (`enroll/set`, the heart-rate service,
  IRK extraction), GATT queries, sub-reports on
  `espresense/devices/<id>/<room>/<report>` and battery or temperature keys are
  all absent.
- **Id types.** Every device keeps the id it is given. The firmware's
  `setId()` promotion rules, `idType` ordering and the rule that ids at or
  below `ID_TYPE_RAND_MAC` are never published are not modelled — pick a
  realistic `--device-id` and the distinction does not arise.
- **Telemetry.** Plausible constants for heap and stack, not a model of them.
  `fingerprints`, `queried`, `failed` and the reconnect counters are omitted.
- **Lifecycle.** No Wi-Fi drops and no spontaneous reboots, though a reconnect
  is handled properly: each node re-announces status, settings and its identity
  when the transport reconnects, because the broker has published its will by
  then. No `forget_ms` expiry of stale fingerprints, and no clock skew between
  nodes.
- **Web UI, OTA, improv serial.** Not simulated at all.
