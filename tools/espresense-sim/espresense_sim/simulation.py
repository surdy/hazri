"""Ties the house, the walk, the radio model and the nodes into a clock tick."""

from __future__ import annotations

import random
from dataclasses import dataclass, field

from .geometry import House, Vec2
from .node import Device, SimNode
from .rf import RadioModel
from .walk import Walk


@dataclass
class NodeObservation:
    room: str
    distance_m: float
    wall_db: float
    heard: bool
    raw_rssi: float | None
    smoothed_rssi: float | None
    reported_distance: float | None
    published: bool


@dataclass
class TickResult:
    now_ms: float
    elapsed_s: float
    position: Vec2
    room: str | None
    observations: list[NodeObservation] = field(default_factory=list)

    def best(self) -> NodeObservation | None:
        heard = [o for o in self.observations if o.smoothed_rssi is not None]
        return max(heard, key=lambda o: o.smoothed_rssi) if heard else None

    def margin_db(self) -> float | None:
        """Best minus second best: the number Hazri actually cares about."""
        rssis = sorted(
            (o.smoothed_rssi for o in self.observations if o.smoothed_rssi is not None),
            reverse=True,
        )
        return rssis[0] - rssis[1] if len(rssis) >= 2 else None


@dataclass
class Simulation:
    house: House
    walk: Walk
    radio: RadioModel
    device: Device
    nodes: dict[str, SimNode]
    adv_interval_ms: float = 1000.0
    mac_rotate_s: float = 900.0
    rng: random.Random = field(default_factory=random.Random)
    start_ms: float = 0.0
    _next_mac_rotate_ms: float = 0.0

    def attach(self) -> None:
        """Wills, handlers and subscriptions only. Must run before connecting:
        a will is only honoured if it rides along in the CONNECT packet."""
        for node in self.nodes.values():
            node.attach(self.start_ms)
        self._next_mac_rotate_ms = self.start_ms + self.mac_rotate_s * 1000.0

    def start(self) -> None:
        """Announce every node. Run this once the transports are connected."""
        for node in self.nodes.values():
            node.start()

    def shutdown(self) -> None:
        for node in self.nodes.values():
            node.go_offline()

    def _rotate_mac(self) -> None:
        # Random static address: top two bits set, as phones use.
        octets = [self.rng.randrange(256) for _ in range(6)]
        octets[0] |= 0xC0
        self.device.mac = "".join(f"{o:02x}" for o in octets)

    def tick(self, now_ms: float) -> TickResult:
        t_s = (now_ms - self.start_ms) / 1000.0
        position = self.walk.position_at(t_s)
        room = self.house.room_at(position)

        if self.mac_rotate_s > 0 and now_ms >= self._next_mac_rotate_ms:
            self._rotate_mac()
            self._next_mac_rotate_ms = now_ms + self.mac_rotate_s * 1000.0

        # Apply anything the transport threads parked since the last tick,
        # before touching fingerprint state.
        for node in self.nodes.values():
            node.drain_messages()

        result = TickResult(now_ms=now_ms, elapsed_s=t_s, position=position, room=room)
        for node_name, node in self.nodes.items():
            spec = self.house.nodes[node_name]
            distance = self.house.node_distance_m(position, spec)
            wall_db = self.house.wall_penalty_db(room, spec.room)
            heard = self.radio.delivered(distance)
            raw = None
            if heard:
                raw = self.radio.sample_rssi(distance, wall_db, spec.antenna_offset_db)
                node.observe(self.device, raw, now_ms)
            published = bool(node.report_devices(now_ms))
            node.maybe_send_telemetry(now_ms)

            fp = node.fingerprints.get(self.device.device_id)
            result.observations.append(
                NodeObservation(
                    room=node.room,
                    distance_m=distance,
                    wall_db=wall_db,
                    heard=heard,
                    raw_rssi=raw,
                    smoothed_rssi=fp.rssi if fp else None,
                    reported_distance=fp.dist if fp else None,
                    published=published,
                )
            )
        return result
