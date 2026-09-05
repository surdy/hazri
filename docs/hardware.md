# Hardware and placement

## Bill of materials

For a house with N rooms you want presence in:

| Item | Qty | Approx. | Notes |
|---|---|---|---|
| M5Stack AtomS3 Lite | N | ~$15 each | ESP32-S3FN8, 8 MB flash, 3D antenna, enclosed, USB-C ([M5 docs](https://docs.m5stack.com/en/core/AtomS3%20Lite)). One model for the whole fleet — see [decision 0002](decisions/0002-board-atoms3-lite.md) |
| USB-C wall charger, 5 V | N | ~$8 each | Any phone charger. This is how a node lives once placed |
| USB-C cable, power-only is fine | N | | Short ones keep the node close to the outlet and off the floor |
| USB-C data cable | 1 | | For flashing only. Charger cables are often power-only and will not enumerate |
| Optional: mounts | N | | 3M command strips, a small shelf, or a printed bracket. ESPresense keeps an [enclosures](https://espresense.com/enclosures/) page |
| Optional: spare board | 1 | ~$15 | Same product line, so a replacement drops in without re-surveying |

Six rooms is about $140. The other requirement is not hardware: an MQTT broker,
normally the Mosquitto add-on in Home Assistant.

## Placement

One node per room. ESPresense's own quick start puts it plainly: room-level
presence is reliable once you have one node per room and have calibrated. Two
nodes in one room compete and produce exactly the ambiguity Hazri is built to
find.

Mount at waist-to-head height, roughly 1.2–1.8 m. Floor level puts furniture and
bodies between the node and the phone; ceiling level over-hears the room next
door. (Hazri's heuristic, not from upstream docs.)

Keep clear of metal and appliances. Fridges, ovens, metal shelving, media
cabinets and Wi-Fi access points all move readings around. ESPresense's
calibration triage attributes distances that "jitter, occasionally jump 5–10 m"
to antenna placement, a metal enclosure or 2.4 GHz interference rather than to
any setting, and its calibration procedure wants the reference node "in an open
area, away from walls and metal"
([calibration](https://espresense.com/guides/calibration/)).

Avoid mirrored positions across a wall. Two nodes back to back on either side of
the same partition hear a phone in either room at almost the same strength, and
the margin collapses to nothing. Offset them — put one near the far end of its
room, or shift one along the wall — so each room has a node that is clearly
closest. (Hazri's heuristic.)

Put the node where people are, not where the router is. A node in the doorway of
a room reports the hallway as often as the room.

## What to optimise for

The metric is the **margin**: in a surveyed room, the mean RSSI of the strongest
node minus the mean RSSI of the second strongest.

| Verdict | Condition |
|---|---|
| Clear | Margin ≥ ~5 dB. The room is unambiguously owned by one node |
| Tight | Margin < ~5 dB. Home Assistant will flap between the two rooms |
| Blind | No node above −85 dBm. Add a node, or treat the room as away |

Move a node before you change a setting. Absorption, `ref_rssi` and
`max_distance` change how RSSI is turned into a distance; none of them changes
which node hears you loudest, so none of them can create margin where the
geometry has none. Tuning is for making distances honest across an already
well-placed fleet.

Re-survey a room after moving its node, or after moving furniture that sits
between the node and where people actually are. Hazri lists surveyed rooms with
their age for that reason.

## Next

- [Flash the boards](guides/flash-atoms3-lite.md)
- [Make the phone visible](guides/phone-as-beacon.md)
- [The topics involved](espresense-topics.md)
