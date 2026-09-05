"""The scripted walk: waypoints with dwell times, interpolated at a fixed speed."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .geometry import House, Vec2, _load_mapping

DEFAULT_WALK = Path(__file__).resolve().parent / "config" / "walk.yaml"


@dataclass(frozen=True)
class Waypoint:
    position: Vec2
    dwell_s: float
    label: str


@dataclass
class Walk:
    """A looping walk. Legs are travelled at ``speed_mps``; dwells are stationary.

    The cycle is waypoint 0 dwell, travel to 1, dwell at 1, ... travel back to 0.
    """

    waypoints: list[Waypoint]
    speed_mps: float = 1.0

    def __post_init__(self) -> None:
        if not self.waypoints:
            raise ValueError("walk needs at least one waypoint")
        if self.speed_mps <= 0:
            raise ValueError("speed must be positive")
        if len(self.waypoints) > 1 and self.cycle_s <= 0:
            raise ValueError(
                "walk has zero duration: give at least one waypoint a dwell, or "
                "move two waypoints apart"
            )

    @property
    def leg_times(self) -> list[float]:
        """Travel time for leg i (waypoint i -> waypoint i+1, wrapping)."""
        n = len(self.waypoints)
        return [
            self.waypoints[i].position.distance_to(
                self.waypoints[(i + 1) % n].position
            )
            / self.speed_mps
            for i in range(n)
        ]

    @property
    def cycle_s(self) -> float:
        return sum(w.dwell_s for w in self.waypoints) + sum(self.leg_times)

    def position_at(self, t_s: float) -> Vec2:
        """Position at time ``t_s`` seconds after the walk started, looping."""
        if len(self.waypoints) == 1:
            return self.waypoints[0].position

        legs = self.leg_times
        t = t_s % self.cycle_s
        for i, wp in enumerate(self.waypoints):
            if t < wp.dwell_s:
                return wp.position
            t -= wp.dwell_s
            leg = legs[i]
            if t < leg:
                nxt = self.waypoints[(i + 1) % len(self.waypoints)].position
                frac = t / leg if leg > 0 else 0.0
                return wp.position + (nxt - wp.position).scaled(frac)
            t -= leg
        return self.waypoints[0].position  # only reachable through float slop


def walk_from_dict(data: dict[str, Any], house: House) -> Walk:
    waypoints: list[Waypoint] = []
    for entry in data.get("waypoints") or []:
        dwell = float(entry.get("dwell", 0.0))
        if "room" in entry:
            room = house.rooms.get(entry["room"])
            if room is None:
                raise ValueError(f"walk: unknown room {entry['room']!r}")
            pos = room.centre if "x" not in entry else Vec2(
                float(entry["x"]), float(entry["y"])
            )
            waypoints.append(Waypoint(pos, dwell, entry["room"]))
        else:
            pos = Vec2(float(entry["x"]), float(entry["y"]))
            waypoints.append(
                Waypoint(pos, dwell, entry.get("label", f"{pos.x:.1f},{pos.y:.1f}"))
            )
    return Walk(waypoints=waypoints, speed_mps=float(data.get("speed", 1.0)))


def load_walk(house: House, path: str | Path | None = None) -> Walk:
    return walk_from_dict(_load_mapping(Path(path) if path else DEFAULT_WALK), house)
