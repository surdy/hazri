"""ESPresense node settings and the ``<setting>/set`` command handler.

Defaults come from include/defaults.h at tag v4.0.6:
https://github.com/ESPresense/ESPresense/blob/v4.0.6/include/defaults.h

The command names and the "empty payload resets to default" behaviour come from
BleFingerprintCollection::Command and main.cpp's onMqttMessage:
https://github.com/ESPresense/ESPresense/blob/v4.0.6/src/BleFingerprintCollection.cpp
https://github.com/ESPresense/ESPresense/blob/v4.0.6/src/main.cpp
"""

from __future__ import annotations

from dataclasses import dataclass, fields
from typing import Any

DEFAULT_MAX_DISTANCE = 16.0
DEFAULT_ABSORPTION = 2.7
DEFAULT_RX_REF_RSSI = -65  # the "ref_rssi" setting
DEFAULT_TX_REF_RSSI = -59
# defaults.h picks this per board: 0 for M5STICK and M5ATOM builds, 20 for a
# generic ESP32S3 build. The plan flashes AtomS3 Lites with the esp32s3-cdc
# image, so 20 is the default here; override with --rx-adj-rssi.
DEFAULT_RX_ADJ_RSSI = 20
DEFAULT_SKIP_DISTANCE = 0.5
DEFAULT_SKIP_MS = 5000
DEFAULT_MAX_DIVISOR = 10
DEFAULT_TX = -6  # rssi.h: assumed tx power of an unknown advertiser

# Settings the firmware republishes retained when it comes online, in order.
# Note what is missing: ref_rssi, skip_ms, skip_distance and max_divisor are
# settable but never published back. See README "Known gaps".
RETAINED_SETTING_ORDER = (
    "name",
    "max_distance",
    "absorption",
    "tx_ref_rssi",
    "rx_adj_rssi",
    "query",
    "include",
    "exclude",
    "known_macs",
    "known_irks",
    "count_ids",
)


def arduino_float(value: float) -> str:
    """Arduino's ``String(float)`` renders two decimals; retained payloads show that."""
    return f"{value:.2f}"


@dataclass
class NodeSettings:
    """Live settings of one simulated node."""

    name: str
    max_distance: float = DEFAULT_MAX_DISTANCE
    absorption: float = DEFAULT_ABSORPTION
    ref_rssi: int = DEFAULT_RX_REF_RSSI
    tx_ref_rssi: int = DEFAULT_TX_REF_RSSI
    rx_adj_rssi: int = DEFAULT_RX_ADJ_RSSI
    skip_distance: float = DEFAULT_SKIP_DISTANCE
    skip_ms: int = DEFAULT_SKIP_MS
    max_divisor: int = DEFAULT_MAX_DIVISOR
    query: str = ""
    include: str = ""
    exclude: str = ""
    known_macs: str = ""
    known_irks: str = ""
    count_ids: str = ""
    # Set by name/set; the firmware only writes it to flash, so it takes a
    # restart to apply. Kept here so restart/set can be demonstrated.
    pending_name: str | None = None

    def retained_payloads(self) -> dict[str, str]:
        return {
            "name": self.name,
            "max_distance": arduino_float(self.max_distance),
            "absorption": arduino_float(self.absorption),
            "tx_ref_rssi": str(self.tx_ref_rssi),
            "rx_adj_rssi": str(self.rx_adj_rssi),
            "query": self.query,
            "include": self.include,
            "exclude": self.exclude,
            "known_macs": self.known_macs,
            "known_irks": self.known_irks,
            "count_ids": self.count_ids,
        }

    def as_dict(self) -> dict[str, Any]:
        return {f.name: getattr(self, f.name) for f in fields(self)}


def _to_float(payload: str, default: float) -> float:
    payload = payload.strip()
    if not payload:
        return default
    try:  # Arduino's String::toFloat() yields 0 on garbage rather than throwing
        return float(payload)
    except ValueError:
        return 0.0


def _to_int(payload: str, default: int) -> int:
    payload = payload.strip()
    if not payload:
        return default
    try:
        return int(float(payload))
    except ValueError:
        return 0


# command -> (attribute, parser, default)
_NUMERIC_COMMANDS: dict[str, tuple[str, Any, Any]] = {
    "max_distance": ("max_distance", _to_float, DEFAULT_MAX_DISTANCE),
    "absorption": ("absorption", _to_float, DEFAULT_ABSORPTION),
    "ref_rssi": ("ref_rssi", _to_int, DEFAULT_RX_REF_RSSI),
    "tx_ref_rssi": ("tx_ref_rssi", _to_int, DEFAULT_TX_REF_RSSI),
    "rx_adj_rssi": ("rx_adj_rssi", _to_int, DEFAULT_RX_ADJ_RSSI),
    "skip_distance": ("skip_distance", _to_float, DEFAULT_SKIP_DISTANCE),
    "skip_ms": ("skip_ms", _to_int, DEFAULT_SKIP_MS),
    "max_divisor": ("max_divisor", _to_int, DEFAULT_MAX_DIVISOR),
}

_STRING_COMMANDS = (
    "query",
    "include",
    "exclude",
    "known_macs",
    "known_irks",
    "count_ids",
)


@dataclass(frozen=True)
class CommandResult:
    handled: bool
    changed: bool = False  # firmware sets online=false, forcing a settings resync
    restart: bool = False


def apply_command(settings: NodeSettings, command: str, payload: str) -> CommandResult:
    """Apply one ``espresense/rooms/<room>/<command>/set`` message."""
    if command in ("restart", "reboot"):
        return CommandResult(handled=True, restart=True)

    if command == "name":
        # main.cpp only spurts the new room to flash; it applies on restart.
        settings.pending_name = payload.strip() or None
        return CommandResult(handled=True)

    if command in _NUMERIC_COMMANDS:
        attr, parse, default = _NUMERIC_COMMANDS[command]
        setattr(settings, attr, parse(payload, default))
        return CommandResult(handled=True, changed=True)

    if command in _STRING_COMMANDS:
        setattr(settings, command, payload.strip())
        return CommandResult(handled=True, changed=True)

    return CommandResult(handled=False)
