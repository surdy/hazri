# ESPresense MQTT topics Hazri uses

Verified on 2026-09-04 against the
[MQTT reference](https://espresense.com/configuration/mqtt/), the
[calibration guide](https://espresense.com/guides/calibration/) and the
firmware source at
[ESPresense/ESPresense](https://github.com/ESPresense/ESPresense) (`master`,
commit `b5ffb89`; release v4.0.6). Where the docs and the source disagree, the
source is quoted and the disagreement is called out.

## Shape

The base prefix `espresense` is hard-coded as `CHANNEL` in `include/defaults.h`.
`<room>` is the room name slugified (`id = slugify(room)`), and `<id>` is the
device fingerprint.

```
espresense/rooms/<room>/<key>          node state and settings   (retained)
espresense/rooms/<room>/<key>/set      write a setting
espresense/rooms/<room>/status         online | offline          (LWT, retained)
espresense/rooms/<room>/telemetry      JSON node health          (not retained)
espresense/devices/<id>/<room>         JSON reading, one per node per device
espresense/settings/<id>/config        device identity/alias     (retained)
```

A `<room>` of `*` in a `/set` topic writes to every node; publish it retained
and nodes joining later pick the value up at boot.

## Device readings — what Hazri reads in MQTT mode

`espresense/devices/<id>/<room>`, published when `pub_devices` is on (default),
not retained. The keys come from `BleFingerprint::fill()` in
[`src/BleFingerprint.cpp`](https://github.com/ESPresense/ESPresense/blob/master/src/BleFingerprint.cpp):

| Key | Meaning |
|---|---|
| `mac` | Current BLE address. Rotates for phones — never key on it |
| `id` | Fingerprint id, e.g. `iBeacon:<uuid>-<major>-<minor>`, `irk:<32-hex>`, `apple:…`, `known:<mac>` |
| `name` | Friendly name, when the device is enrolled or advertises one |
| `rssi@1m` | Reference power used for this device: the iBeacon's own measured power, otherwise the node's `ref_rssi` |
| `rssi` | Filtered RSSI in dBm, two decimals — this is the value Hazri charts and compares |
| `rxAdj` | The node's `rx_adj_rssi` at the time of the reading |
| `rssiVar` | Variance of the filtered RSSI |
| `distance` | Metres, from `d = 10^((rssi@1m − rssi) / (10 · absorption))` |
| `var` | Variance of the distance estimate |
| `close` | Present and `true` only when the device is very close |
| `int` | Mean advertisement interval in ms: `(millis − firstSeen) / seenCount` |
| `mV`, `batt`, `temp`, `rh`, `irk` | Only when the device supplies them |

There is no `raw` key in 4.x, despite older write-ups mentioning one. Rare
sub-reports (e.g. a battery query) go to
`espresense/devices/<id>/<room>/<report>` as scalars.

Worked example — a phone advertising through the Home Assistant Companion app,
heard by the kitchen node:

```
espresense/devices/iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1/kitchen
{"mac":"5a3f1c9d0e42","id":"iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1",
 "name":"pixel-8","rssi@1m":-59,"rssi":-72.35,"rxAdj":20,"rssiVar":1.84,
 "distance":3.42,"var":0.21,"int":1032}
```

The same phone produces one such topic per node. Hazri's margin is the
difference between the best and second-best `rssi` across those topics at a
given moment.

## Rooms — status and telemetry

`status` carries `online`, or `offline` published by the broker as the last
will. `telemetry` is JSON, unretained, at most every 15 s; keys from
`sendTelemetry()` in `src/main.cpp`: `ip`, `uptime` (s), `firm`, `ver`, `rssi`
(the node's Wi-Fi RSSI, not a device's), `freeHeap`, `maxHeap`,
`fingerprints`, `adverts`, `seen`, `queried`, `reported`, `failed`,
`teleFails`, `reconnectTries`, `scanStack`, `loopStack`, `bleStack`, and
`count` when counting is enabled.

On connect the node republishes a retained settings snapshot: `name`,
`max_distance`, `absorption`, `tx_ref_rssi`, `rx_adj_rssi`, `query`, `include`,
`exclude`, `known_macs`, `known_irks`, `count_ids`, plus updater state.

## Settings — read the topic, write `<topic>/set`

Values published on `espresense/rooms/<room>/<key>` are status. Write to
`espresense/rooms/<room>/<key>/set`; the node persists them, so they survive a
reboot.

| Key | Default | Meaning |
|---|---|---|
| `ref_rssi` | `-65` | RSSI expected from a 0 dBm transmitter at 1 m. Set globally, identically on every node. Not used for iBeacons or Eddystone, which carry their own calibrated power |
| `rx_adj_rssi` | `20` on the S3 builds, `0` elsewhere | Additive per-node dB offset for a deaf or loud radio. Leave alone in a single-model fleet |
| `absorption` | `2.7` | Path-loss exponent, range 1–5. Free air ≈2.0, drywall 2.5–3.0, brick 3.0–3.5 |
| `tx_ref_rssi` | `-59` | Power advertised in the node's own iBeacon |
| `max_distance` | `16.0` | Drop readings computed beyond this many metres |
| `skip_distance` | `0.5` | Report immediately if the device moved more than this |
| `skip_ms` | `5000` | Otherwise skip reports younger than this |
| `max_divisor` | `10` | How far `skip_ms` may be divided for large movements |
| `forget_ms` | `150000` | Drop a fingerprint unseen for this long |
| `include` / `exclude` | empty | Whitespace-separated id-prefix allow/deny lists |
| `known_macs` / `known_irks` | empty | Stable MACs, and 32-hex IRKs for Apple devices |
| `query` | empty | Id prefixes to actively connect to and query |
| `count_ids` | empty | Id prefixes to include in the occupancy count |
| `auto_update`, `prerelease`, `arduino_ota` | `OFF` | Updater state |

Documentation drift to be aware of: the MQTT page lists `forget_ms` as boot-only
and the calibration page gives its default as `300000`, but `master` handles it
in the live `Command()` dispatcher and `include/defaults.h` defines
`DEFAULT_FORGET_MS 150000`. (Unverified on v4.0.6 hardware.)

Commands take the same `/set` shape: `restart` (any payload), `name` (renames
and re-slugifies the node), `enroll` (`id|name`, `name`, or `PRESS`; opens a
two-minute window) and `cancelEnroll`.

## Node identity — what makes direct scan work

Outside enrollment each node advertises a non-connectable iBeacon: UUID
`e5ca1ade-f007-ba11-0000-000000000000`, major and minor derived from the top
bytes of the chip's eFuse MAC, transmit power taken from `tx_ref_rssi`
(`src/Enrollment.cpp`, `src/util.h`). The node then publishes itself as a device
config:

```
espresense/settings/iBeacon:e5ca1ade-f007-ba11-0000-000000000000-<major>-<minor>/config
{"id":"node:kitchen","name":"kitchen"}   (retained)
```

Hazri scans for that UUID, reads major/minor from the advertisement, and looks
the pair up in these retained configs to get the room name — the mapping from a
BLE advert to a room, with no MAC involved.

`espresense/settings/<id>/config` is also how any device is enrolled: publish
`{"id":"<stable-id>","name":"<display name>"}` retained and every node adopts it.

## Home Assistant discovery, in brief

With `discovery` on (default) each node publishes retained discovery payloads
under the `discovery_prefix` (default `homeassistant`), as
`homeassistant/binary_sensor/espresense_<chipid>/connectivity/config` and
`homeassistant/sensor/espresense_<chipid>/<slug>/config` (`src/mqtt.cpp`), which
is what makes a node appear in HA as a device with connectivity, uptime, free
memory, restart, enroll and the Max Distance / Absorption numbers. Per-device
room tracking is separate and is wired up by hand with the `mqtt_room`
integration against `espresense/devices/<id>`
([HA integration](https://espresense.com/integrations/home-assistant/)). Hazri
does not publish discovery payloads of its own.
