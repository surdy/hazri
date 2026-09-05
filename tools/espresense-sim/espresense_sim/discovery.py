"""Home Assistant MQTT discovery, ported from src/mqtt.cpp at tag v4.0.6.

https://github.com/ESPresense/ESPresense/blob/v4.0.6/src/mqtt.cpp

The firmware sends these once per connection, retained, when the "discovery"
setting is on (the default). Only the entities a bare AtomS3 Lite produces are
implemented: connectivity, Uptime, Free Mem, Restart, Max Distance, Absorption.
Sensor-, LED-, motion- and enrollment-related discovery is out of scope.
"""

from __future__ import annotations

import json
from typing import Any, Iterator

EC_DIAGNOSTIC = "diagnostic"
EC_CONFIG = "config"


def slugify(name: str) -> str:
    """string_utils.cpp slugify(): word separators normalised to underscores."""
    out = []
    prev_sep = False
    for ch in name.strip():
        if ch.isalnum():
            out.append(ch.lower())
            prev_sep = False
        elif not prev_sep:
            out.append("_")
            prev_sep = True
    return "".join(out).strip("_")


def _common(
    room: str, chip_id: int, mac: str, ip: str, version: str, firmware: str
) -> dict[str, Any]:
    """commonDiscovery(): the shared `dev` block."""
    pretty_mac = ":".join(mac[i : i + 2] for i in range(0, len(mac), 2)).upper()
    return {
        "dev": {
            "ids": [f"espresense_{chip_id:06x}"],
            "cns": [["mac", pretty_mac]],
            "name": f"ESPresense {room}",
            "sa": room,
            "sw": version,
            "mf": f"ESPresense ({firmware})",
            "cu": f"http://{ip}",
            "mdl": "ESP32-S3",
        }
    }


def discovery_messages(
    *,
    room: str,
    rooms_topic: str,
    chip_id: int,
    mac: str,
    ip: str,
    version: str,
    firmware: str,
    prefix: str = "homeassistant",
) -> Iterator[tuple[str, str]]:
    """Yield (topic, json payload) for each retained discovery message."""
    uid = f"espresense_{chip_id:06x}"

    def base() -> dict[str, Any]:
        doc = _common(room, chip_id, mac, ip, version, firmware)
        doc["~"] = rooms_topic
        return doc

    connectivity = base()
    connectivity.update(
        {
            "name": "Connectivity",
            "uniq_id": f"{uid}_connectivity",
            "json_attr_t": "~/telemetry",
            "stat_t": "~/status",
            "dev_cla": "connectivity",
            "pl_on": "online",
            "pl_off": "offline",
        }
    )
    yield f"{prefix}/binary_sensor/{uid}/connectivity/config", json.dumps(connectivity)

    for name, template, units in (
        ("Uptime", "{{ value_json.uptime }}", "s"),
        ("Free Mem", "{{ value_json.freeHeap }}", "bytes"),
    ):
        slug = slugify(name)
        doc = base()
        doc.update(
            {
                "name": name,
                "uniq_id": f"{uid}_{slug}",
                "avty_t": "~/status",
                "stat_t": "~/telemetry",
                "value_template": template,
                "entity_category": EC_DIAGNOSTIC,
                "unit_of_meas": units,
            }
        )
        yield f"{prefix}/sensor/{uid}/{slug}/config", json.dumps(doc)

    restart = base()
    restart.update(
        {
            "name": "Restart",
            "uniq_id": f"{uid}_restart",
            "avty_t": "~/status",
            "stat_t": "~/restart",
            "cmd_t": "~/restart/set",
            "entity_category": EC_DIAGNOSTIC,
        }
    )
    yield f"{prefix}/button/{uid}/restart/config", json.dumps(restart)

    for name in ("Max Distance", "Absorption"):
        slug = slugify(name)
        doc = base()
        doc.update(
            {
                "name": name,
                "uniq_id": f"{uid}_{slug}",
                "avty_t": "~/status",
                "stat_t": f"~/{slug}",
                "cmd_t": f"~/{slug}/set",
                "step": "0.1",
                "entity_category": EC_CONFIG,
            }
        )
        yield f"{prefix}/number/{uid}/{slug}/config", json.dumps(doc)
