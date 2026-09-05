# Making the phone visible to the nodes

Verified on 2026-09-04 against
[enrolling devices](https://espresense.com/guides/enrolling-devices/),
[Android](https://espresense.com/android/),
[Apple](https://espresense.com/apple/) and the
[Home Assistant Companion sensor docs](https://companion.home-assistant.io/docs/core/sensors/).

ESPresense nodes listen; they never ask the phone anything. So MQTT mode only
works once the phone emits something the nodes can fingerprint, and that means a
different mechanism on each platform. Hazri's direct-scan mode does not need any
of this — it measures the nodes' own advertisements — so a phone that cannot
beacon can still do placement work, just without ground truth.

## Android — Home Assistant Companion BLE Transmitter

Android will not advertise on its own and rotates its BLE address, so the
settled path is the Companion app broadcasting an iBeacon with a UUID you
choose.

1. Install the Home Assistant Companion app and sign in.
2. **Settings → Companion app → Manage sensors → BLE Transmitter → enable.**
3. Leave the UUID at its per-device default, or set your own. Major and minor
   must be 0–65535.
4. Exempt the app from battery optimisation, or Android will kill the
   transmitter.

(ESPresense's guide still gives the older two-step path, `Settings → Manage
Sensors`; current Companion builds put it under Companion app.)

Settings worth changing for survey work, all on the same sensor page:

| Setting | Options | For Hazri |
|---|---|---|
| Advertise mode | Low Power (1 Hz), Balanced (3 Hz), Low Latency (10 Hz) | Low Latency while walking a survey — more samples per second per node means a tighter mean and a trustworthy margin. Drop back to Balanced or Low Power afterwards |
| Transmit power | Ultra Low, Low, Medium, High | Medium for surveys. High flatters coverage: it makes marginal rooms look fine and then day-to-day presence flaps at a lower power |
| Measured power at 1 m | negative integer | Leave alone unless you have measured it; it feeds the nodes' distance estimate, not their RSSI |

The Companion docs warn that "this sensor can impact battery life, particularly
if used with Transmit Power set to High", and suggest enabling the transmitter
only when you need it. For Hazri that is fine — turn it on for a survey session,
off afterwards — but leave it on if you want Home Assistant presence day to day.

The app reports the current transmitting ID as a `UUID-Major-Minor` attribute.
ESPresense fingerprints the same beacon as:

```
iBeacon:<uuid>-<major>-<minor>
```

That whole string is the `<id>` in `espresense/devices/<id>/<room>`, and it is
what goes in Hazri's phone-id setting. Watch the separators: the Companion
sensor shows the major and minor joined with underscores in Home Assistant
(`…-ffc32e4a1758_100_1`) while ESPresense uses hyphens
(`…-ffc32e4a1758-100-1`); HA templates comparing the two need
`replace('_', '-')`.

## iOS — IRK enrollment

iOS does not advertise a stable iBeacon in the background, so instead of
beaconing, the phone is identified by its Identity Resolving Key: the key that
lets a listener resolve the phone's rotating random addresses back to one
device. Enrollment captures it over a normal Bluetooth pairing.

1. Open `http://<node-slug>.local/ui/#/devices` (or `http://<node-ip>/ui/#/devices`).
2. Type a stable id in the Enroll field — no spaces, e.g. `harpreet-iphone` —
   and click **Enroll**. The node advertises a Heart Rate Monitor service named
   ESPresense for 120 seconds.
3. On the phone, **Settings → Bluetooth**, tap the `ESPresense` entry, accept
   the pairing prompt. An Apple Watch pairs the same way from its own Bluetooth
   settings.
4. The node publishes, retained:
   `espresense/settings/irk:<32-hex>/config` with
   `{"id":"harpreet-iphone","name":"harpreet-iphone"}`.

Every other node on the broker picks that retained config up, so enrollment is
done once per phone, not once per room. The firmware reads the IRK from the
NimBLE bond store after an encrypted read and then deletes the bond, so the raw
key never appears in the UI.

If the pair succeeds but no IRK arrives — reported on some iOS 17+ devices —
the fallback is to read the key from iCloud Keychain on a paired Mac (Keychain
Access → search `bluetooth` → the `Public: XX:XX:…` entry → Show Password) and
publish the 32-hex key to the same topic yourself.

The same IRK is what Home Assistant's Private BLE Device integration wants, so a
phone enrolled here can be used by both.

Practical caveat: a locked iPhone advertises only when something gives it a
reason to (a paired Apple Watch, Handoff, iCloud activity). None of that is a
calibration problem, and no ESPresense setting fixes it. For survey work, keep
the screen on.

## Enrolling any other device

Enrollment is just a retained MQTT publish. For anything ESPresense cannot pair
with, find the fingerprint in a node's web UI at
`http://<node-slug>.local/ui/#/devices` and publish:

```
mosquitto_pub -h homeassistant.local -u <user> -P <pass> -r \
  -t "espresense/settings/<fingerprint>/config" \
  -m '{"id":"my-phone","name":"My Phone"}'
```

`<fingerprint>` is whatever the node settled on: `iBeacon:…`, `irk:…`,
`mifit:…`, `name:…`, `known:<mac>`. Devices already advertising an iBeacon or
Eddystone profile need no enrollment at all — the firmware picks them up on
first sight, which is exactly why the Android path above works out of the box.

See [docs/espresense-topics.md](../espresense-topics.md) for the payload shapes,
and [docs/guides/flash-atoms3-lite.md](flash-atoms3-lite.md) for getting a node
to that web UI in the first place.
