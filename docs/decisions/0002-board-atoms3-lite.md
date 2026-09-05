# 0002 — Node board: M5Stack AtomS3 Lite, one model everywhere

Status: accepted, 2026-09-04.

## Context

Hazri compares RSSI across nodes: the margin between the best and second-best
node in a room is the number the whole app is built around. That comparison is
only meaningful if the receivers are the same. Antenna design and RF front-end
vary between boards, and ESPresense's own hardware notes say two unbranded
boards "from the same batch routinely differ by several dB at the same distance"
([nodes](https://espresense.com/nodes/)).

## Options

The realistic candidates were the M5 Atom S3 Lite, the M5 Stamp C3 Mate (the
cheaper ESP32-C3 pick), and generic ESP32 dev boards. ESPresense recommends the
Atom S3 Lite as the default board for new deployments and the Stamp C3 Mate as
the cost-conscious alternative, and it explicitly steers away from unbranded dev
boards ([nodes](https://espresense.com/nodes/)).

## Decision

Every Hazri node is an **M5Stack AtomS3 Lite**, and the fleet is never mixed.

The board is an ESP32-S3FN8 at 240 MHz with 8 MB flash, a built-in 3D antenna, a
WS2812C-2020 RGB LED, a button, and USB Type-C
([M5 docs](https://docs.m5stack.com/en/core/AtomS3%20Lite)). The S3 has native
USB, so the USB-C port is the console with no serial bridge chip in the way. It
ships enclosed, which matters for something that will sit on a shelf in a
living room for years.

The one-model rule is the load-bearing part of this decision. A mixed fleet can
be normalised with ESPresense's per-node `rx_adj_rssi` offset, but that is a
calibration step per node before any of Hazri's numbers mean anything, and it
has to be redone whenever a node is replaced.

## Consequences

- Flash the **`esp32s3-cdc`** build. In ESPresense's `platformio.ini` that
  environment is the plain `esp32s3` build plus `ARDUINO_USB_MODE=1` and
  `ARDUINO_USB_CDC_ON_BOOT=1`, i.e. console over the S3's native USB. The plain
  `esp32s3` build runs but its logs go nowhere reachable. `m5atom.bin` is *not*
  the right asset — that environment targets `board = m5stack-atom`, the
  original ESP32 Atom Lite. See
  [docs/guides/flash-atoms3-lite.md](../guides/flash-atoms3-lite.md).
- Because the build is the generic S3 one rather than an M5-specific one, its
  compiled-in defaults are the generic S3 defaults: `rx_adj_rssi` starts at 20
  and the status LED defaults to PWM on pin 2, not the AtomS3 Lite's addressable
  LED on GPIO 35. Both are settings, not reflashes.
- All nodes share one `ref_rssi` and one `absorption`, and `rx_adj_rssi` should
  stay at its build default on every node unless a specific unit is measurably
  deaf. A per-node offset in a single-model fleet is usually a sign the node is
  badly placed, not badly calibrated.
- Buying spares from the same product line keeps a replacement node
  interchangeable. If M5 revises the board's RF front-end, treat it as a new
  model and re-survey.
- ~$15 per node plus a 5 V USB-C charger, so a six-room house is roughly $140.
  See [docs/hardware.md](../hardware.md).
