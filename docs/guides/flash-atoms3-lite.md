# Flashing ESPresense onto an M5Stack AtomS3 Lite

Verified against ESPresense v4.0.6 (28 February 2026) and the M5Stack AtomS3
Lite docs on 2026-09-04. Claims that no source confirms are marked
"(unverified)"; this guide is still pre-hardware.

## Which build, and why

Use **`esp32s3-cdc`**. The AtomS3 Lite has no USB-to-serial bridge — the
ESP32-S3's native USB is the console — and in
[`platformio.ini`](https://github.com/ESPresense/ESPresense/blob/master/platformio.ini)
the `esp32s3-cdc` environment is the plain `esp32s3` build plus
`ARDUINO_USB_MODE=1` and `ARDUINO_USB_CDC_ON_BOOT=1`, which is exactly that. The
plain `esp32s3` build runs, but its console goes to a UART that is not wired to
the USB-C port.

Do not use `m5atom.bin`: that environment sets `board = m5stack-atom`, the
original ESP32 Atom Lite. The
[v4.0.6 assets](https://github.com/ESPresense/ESPresense/releases) are
`esp32.bin`, `esp32c3.bin`, `esp32c3-cdc.bin`, `esp32s3.bin`,
`esp32s3-cdc.bin`, three `-verbose` variants, `m5atom.bin`, `m5stickc.bin`,
`m5stickc-plus.bin` and `macchina-a0.bin`.

## Flash with the web installer

You need a data-capable USB-C cable (charger cables are often power-only and
will not enumerate) and Chrome, Edge or another Chromium browser — the installer
uses WebSerial, and Firefox and Safari will not work
([firmware](https://espresense.com/firmware/),
[quick start](https://espresense.com/quick-start/)).

1. Plug the board in, open <https://espresense.com/firmware/>, click
   **Connect**.
2. Pick the port that appeared: `usbmodem…` on macOS, a new COM port on Windows,
   `/dev/ttyACM0` on Linux.
3. The installer should pre-select `esp32s3-cdc` for this board; confirm it in
   the flavour dropdown before clicking Install
   ([nodes](https://espresense.com/nodes/),
   [quick start](https://espresense.com/quick-start/)).
4. Click **Install ESPresense**, wait about a minute. The LED changes colour and
   the board reboots into the first-boot captive portal.

If no port appears, try another cable, close anything else holding the port
(Arduino IDE, PlatformIO, a serial monitor), and on Linux run
`sudo usermod -a -G dialout $USER` and log back in.

### Bootloader mode

Only needed if the board never shows up as a serial port. M5's procedure: "Press
and hold the reset button (for about 2 seconds) until the internal green LED
lights up, then release the button. After release, the green LED turns off,
indicating that the device has entered download mode"
([M5 docs](https://docs.m5stack.com/en/core/AtomS3%20Lite)). That is the small
side button, not the large top one. With native USB CDC the installer can
usually reset the board itself (unverified on this board).

### esptool fallback

The release assets are application images, not merged factory images — the build
workflow copies `.pio/build/<env>/firmware.bin` to `<env>.bin` — and
`partitions_singleapp.csv` puts `app0` at `0x10000`. For a board that already
carries ESPresense's bootloader and partition table:

```
esptool.py --chip esp32s3 --port /dev/cu.usbmodem1101 --baud 921600 \
  write_flash 0x10000 esp32s3-cdc.bin
```

That is the esptool v4 spelling; v5 ships the command as `esptool` with
hyphenated subcommands, so it becomes `esptool … write-flash 0x10000 …`
([esptool docs](https://docs.espressif.com/projects/esptool/en/latest/esp32/esptool/basic-commands.html)).

A factory-fresh board also needs a bootloader at `0x0` and a partition table at
`0x8000`, which ESPresense does not publish as release assets. Use the web
installer for the first flash.

## First boot

The board comes up as an open access point whose SSID starts with
`ESPresense-`. Join it; the captive portal should open by itself, otherwise
browse to <http://192.168.4.1> and fill in
([quick start](https://espresense.com/quick-start/)):

| Field | Value |
|---|---|
| Room name | The room this node represents — see below |
| Wi-Fi SSID | A 2.4 GHz network; the ESP32 cannot see 5 GHz |
| Wi-Fi password | |
| Server | MQTT broker host, e.g. `homeassistant.local` |
| Port | `1883` |
| Username / Password | Broker credentials |

Leave the rest at defaults and save. MQTT credentials go over the wire in
plaintext — ESPresense has no TLS — so keep this on a trusted network.

## Naming convention

One word, lower case, the room and nothing else: `kitchen`, `hall`, `study`. The
firmware slugifies the room name into the node id used in every topic
(`id = slugify(room)` in
[`src/main.cpp`](https://github.com/ESPresense/ESPresense/blob/master/src/main.cpp)),
and the web UI is at `http://<slug>.local`. One lower-case word keeps the
display name, slug, topic and hostname the same string, which is what Hazri's
node list, survey rooms and Coverage matrix key on.

## Status LED

The AtomS3 Lite's RGB LED is a WS2812C-2020 on **GPIO 35**
([M5 docs](https://docs.m5stack.com/en/core/AtomS3%20Lite),
[ESPHome device page](https://devices.esphome.io/devices/m5stack-atoms3-lite/)),
but the generic S3 build defaults to PWM on pin 2. In the node's web UI, under
**LEDs**, set LED 1 to type *WS2812*, pin `35`, count `1`, control *Status*
([hardware settings](https://espresense.com/configuration/hardware/)).
(Unverified: the exact label for that strip type in the v4.0.6 UI, and whether
its colour order matches this part.) Pin `-1` disables the LED, which is the
better choice for a bedroom node.

## Calibration, first pass

Leave `ref_rssi` at `-65`, `absorption` at `2.7`, and `rx_adj_rssi` at the S3
build default of `20`. Because every Hazri node is the same board, keep those
identical across the fleet and tune them together later with the
[calibration guide](https://espresense.com/guides/calibration/) rather than per
node.

## Verify

```
mosquitto_sub -h homeassistant.local -u <user> -P <pass> -v \
  -t "espresense/rooms/kitchen/#"
```

Expect `espresense/rooms/kitchen/status online` and then the retained settings
snapshot. Then watch what the node hears with
`-t "espresense/devices/#"`; payload shapes are in
[docs/espresense-topics.md](../espresense-topics.md).

Repeat per node. Device enrollment is fleet-wide and only needs doing once.
