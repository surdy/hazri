"""House geometry: rooms with rectangular bounds, nodes, and wall attenuation.

Pure data plus a little maths; nothing in here knows about MQTT.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

DEFAULT_LAYOUT = Path(__file__).resolve().parent / "config" / "house.yaml"


@dataclass(frozen=True)
class Vec2:
    x: float
    y: float

    def __add__(self, other: "Vec2") -> "Vec2":
        return Vec2(self.x + other.x, self.y + other.y)

    def __sub__(self, other: "Vec2") -> "Vec2":
        return Vec2(self.x - other.x, self.y - other.y)

    def scaled(self, k: float) -> "Vec2":
        return Vec2(self.x * k, self.y * k)

    def length(self) -> float:
        return math.hypot(self.x, self.y)

    def distance_to(self, other: "Vec2") -> float:
        return math.hypot(self.x - other.x, self.y - other.y)


@dataclass(frozen=True)
class Room:
    """A rectangular room. Bounds are metres, origin bottom-left of the house."""

    name: str
    x1: float
    y1: float
    x2: float
    y2: float

    def __post_init__(self) -> None:
        if self.x2 <= self.x1 or self.y2 <= self.y1:
            raise ValueError(f"room {self.name}: bounds must have x2>x1 and y2>y1")

    @property
    def centre(self) -> Vec2:
        return Vec2((self.x1 + self.x2) / 2.0, (self.y1 + self.y2) / 2.0)

    def contains(self, p: Vec2) -> bool:
        return self.x1 <= p.x <= self.x2 and self.y1 <= p.y <= self.y2


@dataclass(frozen=True)
class NodeSpec:
    """One ESPresense node: a room name plus where the board hangs on the wall.

    ``rx_adj_rssi`` is the firmware setting (subtracted from the measured RSSI).
    ``antenna_offset_db`` is *physical*: how much hotter or colder this board's
    antenna reads than the reference board. The two cancel when they agree,
    which is exactly what the setting is for.
    """

    room: str
    x: float
    y: float
    z: float = 1.5
    antenna_offset_db: float = 0.0

    @property
    def position(self) -> Vec2:
        return Vec2(self.x, self.y)


@dataclass
class House:
    rooms: dict[str, Room]
    nodes: dict[str, NodeSpec]
    wall_penalties: dict[frozenset[str], float] = field(default_factory=dict)
    default_wall_penalty_db: float = 8.0
    phone_height_m: float = 1.1

    def room_at(self, p: Vec2) -> str | None:
        for room in self.rooms.values():
            if room.contains(p):
                return room.name
        return None

    def wall_penalty_db(self, room_a: str | None, room_b: str) -> float:
        """Attenuation in dB between a point in ``room_a`` and a node in ``room_b``."""
        if room_a is None:
            return self.default_wall_penalty_db
        if room_a == room_b:
            return 0.0
        return self.wall_penalties.get(
            frozenset((room_a, room_b)), self.default_wall_penalty_db
        )

    def node_distance_m(self, phone: Vec2, node: NodeSpec) -> float:
        """3D distance phone-to-node, floored at 0.5 m so the model stays sane."""
        dz = node.z - self.phone_height_m
        return max(0.5, math.sqrt(phone.distance_to(node.position) ** 2 + dz * dz))


def _load_mapping(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    if path.suffix in (".yaml", ".yml"):
        import yaml  # imported lazily so JSON layouts work without PyYAML

        data = yaml.safe_load(text)
    else:
        data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a mapping at the top level")
    return data


def house_from_dict(data: dict[str, Any]) -> House:
    rooms: dict[str, Room] = {}
    for name, spec in (data.get("rooms") or {}).items():
        bounds = spec["bounds"] if isinstance(spec, dict) else spec
        x1, y1, x2, y2 = (float(v) for v in bounds)
        rooms[name] = Room(name, x1, y1, x2, y2)

    nodes: dict[str, NodeSpec] = {}
    for name, spec in (data.get("nodes") or {}).items():
        room = spec.get("room", name)
        if room not in rooms:
            raise ValueError(f"node {name}: unknown room {room!r}")
        nodes[name] = NodeSpec(
            room=room,
            x=float(spec["x"]),
            y=float(spec["y"]),
            z=float(spec.get("z", 1.5)),
            antenna_offset_db=float(spec.get("antenna_offset_db", 0.0)),
        )

    penalties: dict[frozenset[str], float] = {}
    for entry in data.get("wall_penalties") or []:
        a, b, db = entry["between"][0], entry["between"][1], float(entry["db"])
        for name in (a, b):
            if name not in rooms:
                raise ValueError(f"wall_penalties: unknown room {name!r}")
        penalties[frozenset((a, b))] = db

    return House(
        rooms=rooms,
        nodes=nodes,
        wall_penalties=penalties,
        default_wall_penalty_db=float(data.get("default_wall_penalty_db", 8.0)),
        phone_height_m=float(data.get("phone_height_m", 1.1)),
    )


def load_house(path: str | Path | None = None) -> House:
    return house_from_dict(_load_mapping(Path(path) if path else DEFAULT_LAYOUT))
