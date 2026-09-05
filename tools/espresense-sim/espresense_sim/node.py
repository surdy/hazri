"""One simulated ESPresense node: fingerprints, reporting, settings, telemetry.

Every behaviour here is a port of ESPresense v4.0.6. Cited files:
  src/BleFingerprint.cpp   fill(), report(), seen(), get1mRssi(), shouldCount()
  src/main.cpp             sendTelemetry(), onMqttMessage(), reportDevice()
  src/mqtt.cpp             Home Assistant discovery payloads
https://github.com/ESPresense/ESPresense/tree/v4.0.6/src
"""

from __future__ import annotations

import json
import math
import queue
from dataclasses import dataclass, field
from typing import Any, Iterable

from .discovery import discovery_messages
from .filtering import AdaptivePercentileRSSI
from .settings import (
    DEFAULT_TX,
    RETAINED_SETTING_ORDER,
    CommandResult,
    NodeSettings,
    apply_command,
)
from .transport import Transport

CHANNEL = "espresense"
# Enrollment.cpp: outside enrollment every node advertises this iBeacon, with
# major/minor taken from the top bytes of the eFuse MAC and the tx power from
# tx_ref_rssi, then publishes itself as a retained device config so the fleet
# (and Hazri's direct scan) can map the advert back to a room.
NODE_BEACON_UUID = "e5ca1ade-f007-ba11-0000-000000000000"
CLOSE_RSSI = -40  # src/rssi.h
LEFT_RSSI = -50
TELEMETRY_INTERVAL_MS = 15_000  # main.cpp: `if (now - lastTeleMillis < 15000) return`
MAX_TIME_SLOTS = 64  # include/defaults.h
ID_TYPE_IBEACON = 180  # BleFingerprint.h
ID_TYPE_ALIAS = 250
# Pseudo-topic used to hand a connect event from the network thread to the
# simulation thread through the same queue the /set messages use.
CONNECTED_EVENT = "\x00connected"


def time_slot(device_id: str) -> int:
    """BleFingerprint::calculateTimeSlot(): djb2 over the id, mod 64.

    The firmware staggers each device's reporting slot inside the skip_ms
    window so a fleet of nodes does not publish everything on the same tick.
    """
    h = 5381
    for ch in device_id:
        h = ((h << 5) + h + ord(ch)) & 0xFFFFFFFF
    return h % MAX_TIME_SLOTS


SCHEMA_V4 = "v4"
SCHEMA_V3 = "v3"


@dataclass
class Device:
    """A tracked BLE advertiser (the phone). ``cal_rssi`` mimics a beacon's own
    calibrated 1 m power, which get1mRssi() prefers over the ref_rssi setting."""

    device_id: str
    name: str | None = None
    mac: str = "d83addb1ee01"
    cal_rssi: int | None = None
    id_type: int = ID_TYPE_IBEACON  # only published by the v3 schema


@dataclass(frozen=True)
class DeviceConfig:
    """One entry of BleFingerprintCollection's deviceConfigs vector.

    Published retained to ``espresense/settings/<id>/config`` as
    ``{"id": <alias>, "name": <name>, "rssi@1m": <calRssi>}``; an empty payload
    removes it (``Config()`` -> ``removeConfig()``).
    """

    alias: str = ""
    name: str = ""
    cal_rssi: int | None = None

    @classmethod
    def from_payload(cls, payload: str) -> "DeviceConfig | None":
        if not payload.strip():
            return None
        try:
            doc = json.loads(payload)
        except json.JSONDecodeError:
            return None
        if not isinstance(doc, dict):
            return None
        cal = doc.get("rssi@1m")
        return cls(
            alias=str(doc.get("id", "") or ""),
            name=str(doc.get("name", "") or ""),
            cal_rssi=int(cal) if isinstance(cal, (int, float)) else None,
        )


@dataclass
class Fingerprint:
    """Per-device state inside one node. Mirrors class BleFingerprint."""

    device: Device
    first_seen_ms: float
    seen_count: int = 0
    raw_rssi: float = 0.0
    rssi: float = 0.0
    rssi_var: float = 0.0
    dist: float = 0.0
    dist_var: float = 0.0
    raw_dist: float = 0.0  # v3 schema only: unfiltered distance
    close: bool = False
    reported: bool = False
    last_reported: float = 0.0
    next_report_ms: float = 0.0
    filter: AdaptivePercentileRSSI = field(default_factory=AdaptivePercentileRSSI)

    def one_m_rssi(self, settings: NodeSettings) -> int:
        """get1mRssi(): a calibrated beacon power wins, else ref_rssi + DEFAULT_TX."""
        if self.device.cal_rssi is not None:
            return self.device.cal_rssi
        return settings.ref_rssi + DEFAULT_TX

    def seen(self, raw_rssi: float, now_ms: float, settings: NodeSettings) -> None:
        """BleFingerprint::seen() - rx adjust, smooth, then derive distance."""
        self.seen_count += 1
        self.reported = False
        self.raw_rssi = raw_rssi
        self.filter.add_measurement(raw_rssi - settings.rx_adj_rssi, now_ms)
        self.rssi = self.filter.median_iqr()
        self.rssi_var = self.filter.rssi_variance()
        ref = self.one_m_rssi(settings)
        self.dist = math.pow(10.0, (ref - self.rssi) / (10.0 * settings.absorption))
        self.dist_var = self.filter.distance_variance(ref, settings.absorption)
        self.raw_dist = math.pow(
            10.0,
            (ref - (raw_rssi - settings.rx_adj_rssi)) / (10.0 * settings.absorption),
        )
        # shouldCount()'s close/left hysteresis, run every loop by main.cpp
        if not self.close and self.rssi > CLOSE_RSSI + settings.rx_adj_rssi:
            self.close = True
        elif self.close and self.rssi < LEFT_RSSI + settings.rx_adj_rssi:
            self.close = False

    def should_report(self, now_ms: float, settings: NodeSettings) -> bool:
        """BleFingerprint::report() gating: max_distance, then the skip_ms slot
        with an early-report allowance that shrinks as the device moves."""
        if self.reported:
            return False
        if settings.max_distance > 0 and self.dist > settings.max_distance:
            return False

        # skip_ms 0 turns rate limiting off: every advertisement is reported.
        # The firmware's slot arithmetic divides by it, so guard the whole
        # block rather than letting it divide by zero.
        if settings.skip_ms <= 0:
            self.last_reported = self.dist
            self.next_report_ms = now_ms
            self.reported = True
            return True

        if now_ms < self.next_report_ms:
            movement = abs(self.dist - self.last_reported)
            if settings.skip_distance <= 0.0 or movement < settings.skip_distance:
                return False
            # log2(round(2 ** x)) round-trips to ~x; clamped so a big jump in
            # distance cannot overflow the float the firmware uses here.
            ratio = min(movement / settings.skip_distance, 1024.0)
            rounded_log2 = int(math.log2(max(1.0, round(math.pow(2.0, ratio)))))
            divisor = max(2, min(settings.max_divisor, settings.max_divisor - rounded_log2))
            early_report_ms = settings.skip_ms / divisor
            if now_ms < self.next_report_ms - early_report_ms:
                return False
            self.last_reported = self.dist

        self.next_report_ms = now_ms + (
            settings.skip_ms - (int(now_ms) % settings.skip_ms)
        ) % settings.skip_ms
        self.last_reported = self.dist
        self.reported = True
        return True

    def fill(
        self, now_ms: float, settings: NodeSettings, schema: str = SCHEMA_V4
    ) -> dict[str, Any]:
        """BleFingerprint::fill(). Key order matches the firmware's."""
        doc: dict[str, Any] = {"mac": self.device.mac, "id": self.device.device_id}
        if self.device.name:
            doc["name"] = self.device.name
        if schema == SCHEMA_V3:
            # v3.x also published idType, an unsmoothed rssi and the raw distance.
            doc["idType"] = self.device.id_type
            doc["rssi@1m"] = self.one_m_rssi(settings)
            doc["rssi"] = round(self.raw_rssi)
            doc["raw"] = round(self.raw_dist, 2)
            doc["distance"] = round(self.dist, 2)
            doc["var"] = round(self.dist_var, 2)
        else:
            doc["rssi@1m"] = self.one_m_rssi(settings)
            doc["rssi"] = round(self.rssi, 2)
            doc["rxAdj"] = settings.rx_adj_rssi
            # fill() guards this with isnormal(rssiVar), so a single sample or a
            # perfectly steady signal publishes no rssiVar at all.
            if self.rssi_var > 0.0:
                doc["rssiVar"] = round(self.rssi_var, 2)
            doc["distance"] = round(self.dist, 2)
            doc["var"] = round(self.dist_var, 2)
        if self.close:
            doc["close"] = True
        doc["int"] = int((now_ms - self.first_seen_ms) // max(1, self.seen_count))
        return doc


@dataclass
class SimNode:
    """A node's MQTT personality: topics, retained settings, telemetry, sets."""

    room: str
    transport: Transport
    settings: NodeSettings
    ip: str = "10.0.0.2"
    version: str = "4.0.6"
    firmware: str = "m5stack-atoms3"
    schema: str = SCHEMA_V4
    ha_discovery: bool = True
    discovery_prefix: str = "homeassistant"
    chip_id: int = 0x000001
    mac: str = "d83add000001"
    publish_ref_rssi: bool = False

    fingerprints: dict[str, Fingerprint] = field(default_factory=dict)
    device_configs: dict[str, DeviceConfig] = field(default_factory=dict)
    online: bool = False
    sent_discovery: bool = False
    _last_tele_ms: float = -TELEMETRY_INTERVAL_MS
    _boot_ms: float = 0.0
    _total_adverts: int = 0
    _total_reported: int = 0
    _restart_requested: bool = False
    # Inbound /set messages arrive on the transport's network thread. They are
    # parked here and applied by drain_messages() on the simulation thread, so
    # that a restart cannot clear the fingerprints mid-iteration.
    _inbox: "queue.SimpleQueue[tuple[str, str]]" = field(
        default_factory=queue.SimpleQueue, repr=False
    )

    # -- topics -----------------------------------------------------------
    @property
    def rooms_topic(self) -> str:
        return f"{CHANNEL}/rooms/{self.room}"

    @property
    def status_topic(self) -> str:
        return f"{self.rooms_topic}/status"

    @property
    def telemetry_topic(self) -> str:
        return f"{self.rooms_topic}/telemetry"

    def device_topic(self, device_id: str) -> str:
        return f"{CHANNEL}/devices/{device_id}/{self.room}"

    @property
    def beacon_major_minor(self) -> tuple[int, int]:
        """Enrollment.cpp: nodeId = efuseMac >> 24, split into major and minor.

        ESP.getEfuseMac() hands back the address byte-reversed, so the bits that
        survive the shift are the *last* three octets of the printed MAC.
        """
        octets = [self.mac[i : i + 2] for i in range(0, 12, 2)]
        node_id = int("".join(reversed(octets)), 16) >> 24
        return (node_id & 0xFFFF0000) >> 16, node_id & 0xFFFF

    @property
    def beacon_id(self) -> str:
        major, minor = self.beacon_major_minor
        return f"iBeacon:{NODE_BEACON_UUID}-{major}-{minor}"

    @property
    def config_topic(self) -> str:
        return f"{CHANNEL}/settings/{self.beacon_id}/config"

    # -- lifecycle --------------------------------------------------------
    def attach(self, now_ms: float = 0.0) -> None:
        """connectToMqtt(): set the will and the handler, declare subscriptions.

        Nothing is published here. MQTT only accepts a will in the CONNECT
        packet, so this must run *before* the transport connects; ``start()``
        does the publishing afterwards.
        """
        self._boot_ms = now_ms
        self.transport.set_will(self.status_topic, "offline", 0, True)
        self.transport.on_message(self.enqueue_message)
        # main.cpp onMqttConnect(): a fleet-wide filter plus this room's.
        self.transport.subscribe(f"{CHANNEL}/rooms/*/+/set", 1)
        self.transport.subscribe(f"{self.rooms_topic}/+/set", 1)
        self.transport.subscribe(f"{CHANNEL}/settings/+/config", 1)
        self.transport.on_connect(self.on_connect)

    def start(self) -> None:
        """First trip through sendTelemetry()'s `!online` branch, post-connect."""
        self.send_online()

    def on_connect(self) -> None:
        """Called on the transport's network thread when a session comes up.

        Parks the event rather than publishing: onMqttDisconnect() sets
        online=false, so the announcement is real work (status, every retained
        setting, discovery) and belongs on the simulation thread.
        """
        self._inbox.put((CONNECTED_EVENT, ""))

    def send_online(self) -> None:
        """sendTelemetry()'s `if (!online)` branch: status then every setting,
        all retained. Any accepted setting change forces this to run again."""
        self.transport.publish(self.status_topic, "online", 0, True)
        payloads = self.settings.retained_payloads()
        for key in RETAINED_SETTING_ORDER:
            self.transport.publish(f"{self.rooms_topic}/{key}", payloads[key], 0, True)
        if self.publish_ref_rssi:  # not firmware behaviour; see README
            self.transport.publish(
                f"{self.rooms_topic}/ref_rssi", str(self.settings.ref_rssi), 0, True
            )
        self.online = True
        if self.ha_discovery and not self.sent_discovery:
            # Enrollment::SendDiscovery(), which the firmware runs inside the
            # same discovery block: publish this node's own beacon identity.
            self.transport.publish(
                self.config_topic,
                json.dumps({"id": f"node:{self.room}", "name": self.room}),
                0,
                True,
            )
            for topic, payload in discovery_messages(
                room=self.room,
                rooms_topic=self.rooms_topic,
                chip_id=self.chip_id,
                mac=self.mac,
                ip=self.ip,
                version=self.version,
                firmware=self.firmware,
                prefix=self.discovery_prefix,
            ):
                self.transport.publish(topic, payload, 0, True)
            self.sent_discovery = True

    def go_offline(self) -> None:
        """What the broker would do with the will; used on a clean shutdown."""
        self.transport.publish(self.status_topic, "offline", 0, True)
        self.online = False

    # -- per-tick work ----------------------------------------------------
    def resolve(self, device: Device) -> Device:
        """setId()'s device-config lookup: an alias re-ids the fingerprint and
        carries the configured name, and a configured rssi@1m wins over
        everything (it becomes calRssi, the first branch of get1mRssi())."""
        config = self.device_configs.get(device.device_id)
        if config is None:
            return device
        return Device(
            device_id=config.alias or device.device_id,
            name=config.name or device.name,
            mac=device.mac,
            cal_rssi=config.cal_rssi if config.cal_rssi is not None else device.cal_rssi,
            id_type=ID_TYPE_ALIAS if config.alias else device.id_type,
        )

    def observe(self, device: Device, raw_rssi: float, now_ms: float) -> None:
        device = self.resolve(device)
        fp = self.fingerprints.get(device.device_id)
        if fp is None:
            fp = Fingerprint(device=device, first_seen_ms=now_ms)
            # setId(): line the first report up with this device's time slot.
            skip_ms = self.settings.skip_ms
            if skip_ms > 0:
                interval_start = (int(now_ms) // skip_ms) * skip_ms
                fp.next_report_ms = interval_start + time_slot(device.device_id) * (
                    skip_ms // MAX_TIME_SLOTS
                )
            else:  # rate limiting off: report from the first advertisement
                fp.next_report_ms = now_ms
            self.fingerprints[device.device_id] = fp
        fp.device = device  # a rotated MAC keeps the same fingerprint
        fp.seen(raw_rssi, now_ms, self.settings)
        self._total_adverts += 1

    def report_devices(self, now_ms: float) -> list[tuple[str, str]]:
        """reportDevice() for every fingerprint. Returns what was published."""
        sent: list[tuple[str, str]] = []
        for device_id, fp in self.fingerprints.items():
            if not fp.should_report(now_ms, self.settings):
                continue
            payload = json.dumps(fp.fill(now_ms, self.settings, self.schema))
            topic = self.device_topic(device_id)
            self.transport.publish(topic, payload, 0, False)
            self._total_reported += 1
            sent.append((topic, payload))
        return sent

    def telemetry_payload(self, now_ms: float) -> dict[str, Any]:
        doc: dict[str, Any] = {
            "ip": self.ip,
            "uptime": int((now_ms - self._boot_ms) / 1000),
            "firm": self.firmware,
            "rssi": -55,  # the node's own wifi rssi
            "ver": self.version,
        }
        if self.settings.count_ids:
            doc["count"] = sum(1 for fp in self.fingerprints.values() if fp.close)
        if self._total_adverts:
            doc["adverts"] = self._total_adverts
            doc["seen"] = len(self.fingerprints)
        if self._total_reported:
            doc["reported"] = self._total_reported
        doc["freeHeap"] = 148_000
        doc["maxHeap"] = 110_000
        doc["scanStack"] = 2048
        doc["loopStack"] = 3600
        doc["bleStack"] = 4400
        return doc

    def maybe_send_telemetry(self, now_ms: float) -> bool:
        if not self.online:
            self.send_online()
        if now_ms - self._last_tele_ms < TELEMETRY_INTERVAL_MS:
            return False
        self._last_tele_ms = now_ms
        self.transport.publish(
            self.telemetry_topic, json.dumps(self.telemetry_payload(now_ms)), 0, False
        )
        return True

    # -- inbound ----------------------------------------------------------
    def enqueue_message(self, topic: str, payload: str) -> None:
        """Called on the transport's thread; does no work of its own."""
        self._inbox.put((topic, payload))

    def drain_messages(self) -> list[CommandResult]:
        """Apply everything the transport parked since the last tick."""
        results: list[CommandResult] = []
        while True:
            try:
                topic, payload = self._inbox.get_nowait()
            except queue.Empty:
                return results
            if topic == CONNECTED_EVENT:
                self.online = False
                self.send_online()
                results.append(CommandResult(handled=True))
                continue
            results.append(self.handle_message(topic, payload))

    def handle_message(self, topic: str, payload: str) -> CommandResult:
        """onMqttMessage(): route `.../<id>/config` and `.../<command>/set`."""
        if topic.endswith("/config"):
            return self.handle_config(topic, payload)
        if not topic.endswith("/set"):
            return CommandResult(handled=False)
        parts = topic.split("/")
        if len(parts) < 5 or parts[0] != CHANNEL or parts[1] != "rooms":
            return CommandResult(handled=False)
        room = parts[2]
        if room not in (self.room, "*"):
            return CommandResult(handled=False)
        command = parts[-2]

        result = apply_command(self.settings, command, payload)
        if result.restart:
            self.restart()
        elif result.changed:
            # main.cpp sets online=false, which re-publishes every setting.
            self.online = False
            self.send_online()
        return result

    def handle_config(self, topic: str, payload: str) -> CommandResult:
        """BleFingerprintCollection::Config(): store or remove a device config.

        Only the alias, name and rssi@1m fields are honoured here; enrollment
        and IRK resolution are out of scope (see README).
        """
        parts = topic.split("/")
        if len(parts) < 4 or parts[0] != CHANNEL or parts[1] != "settings":
            return CommandResult(handled=False)
        device_id = "/".join(parts[2:-1])
        config = DeviceConfig.from_payload(payload)
        if config is None:
            self.device_configs.pop(device_id, None)
        else:
            self.device_configs[device_id] = config
        # A config can re-id a fingerprint, so drop the old one and let the
        # next advertisement rebuild it under the alias.
        self.fingerprints.pop(device_id, None)
        return CommandResult(handled=True)

    def restart(self) -> None:
        self._restart_requested = True
        if self.settings.pending_name:
            self.room = self.settings.pending_name
            self.settings.name = self.settings.pending_name
            self.settings.pending_name = None
        self.online = False
        self.sent_discovery = False
        self.fingerprints.clear()

    def iter_fingerprints(self) -> Iterable[Fingerprint]:
        return self.fingerprints.values()
