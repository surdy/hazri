"""Command line entry point: wire the simulation to a broker and run it."""

from __future__ import annotations

import argparse
import random
import signal
import sys
import time
from dataclasses import dataclass
from typing import Callable

from .geometry import load_house
from .node import SCHEMA_V3, SCHEMA_V4, Device, SimNode
from .rf import (
    DEFAULT_NOISE_SIGMA_DB,
    DEFAULT_TRUE_PATH_LOSS,
    DEFAULT_TRUE_REF_RSSI,
    RadioModel,
)
from .settings import (
    DEFAULT_ABSORPTION,
    DEFAULT_MAX_DISTANCE,
    DEFAULT_RX_ADJ_RSSI,
    DEFAULT_RX_REF_RSSI,
    DEFAULT_SKIP_DISTANCE,
    DEFAULT_SKIP_MS,
    NodeSettings,
)
from .simulation import Simulation, TickResult
from .transport import BrokerConfig, FakeTransport, PahoTransport, Transport
from .walk import load_walk

# The docs' worked example. A "phone:..." style id is not something ESPresense
# can produce - report() drops anything at or below ID_TYPE_RAND_MAC - so the
# default is a real iBeacon id, as the Home Assistant Companion app emits.
DEFAULT_DEVICE_ID = "iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1"
DEFAULT_BEACON_1M_RSSI = -59
BEACON_ID_PREFIXES = ("iBeacon:", "altBeacon:")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="sim.py",
        description=(
            "Publish realistic ESPresense v4 traffic to an MQTT broker so the "
            "Hazri app can be developed without hardware."
        ),
    )
    broker = p.add_argument_group("broker")
    broker.add_argument("--host", default="localhost", help="broker host")
    broker.add_argument("--port", type=int, default=1883, help="broker port")
    broker.add_argument("--username", default=None)
    broker.add_argument("--password", default=None)
    broker.add_argument(
        "--dry-run",
        action="store_true",
        help="do not connect; print the messages that would be published",
    )

    device = p.add_argument_group("phone")
    device.add_argument(
        "--device-id",
        default=DEFAULT_DEVICE_ID,
        help=(
            "the phone's fingerprint id. ESPresense only publishes ids above "
            "ID_TYPE_RAND_MAC, so this defaults to an iBeacon id like the one "
            "the Home Assistant Companion app produces"
        ),
    )
    device.add_argument("--device-name", default="hazri-test-phone")
    device.add_argument(
        "--beacon-1m-rssi",
        type=int,
        default=None,
        help=(
            "advertise a calibrated 1 m power, the way an iBeacon does; nodes "
            f"then ignore ref_rssi. Implied as {DEFAULT_BEACON_1M_RSSI} for an "
            "iBeacon:/altBeacon: device id"
        ),
    )
    device.add_argument(
        "--mac-rotate",
        type=float,
        default=900.0,
        metavar="SECONDS",
        help="rotate the phone's random MAC this often (0 to pin it)",
    )

    world = p.add_argument_group("world")
    world.add_argument("--layout", default=None, help="house YAML/JSON (default: espresense_sim/config/house.yaml)")
    world.add_argument("--walk", default=None, help="walk YAML/JSON (default: espresense_sim/config/walk.yaml)")
    world.add_argument("--speed", type=float, default=None, help="override walk speed, m/s")
    world.add_argument("--rooms", default=None, help="comma separated subset of nodes to run")
    world.add_argument("--seed", type=int, default=None, help="RNG seed for repeatable runs")

    radio = p.add_argument_group("radio truth")
    radio.add_argument("--true-ref-rssi", type=float, default=DEFAULT_TRUE_REF_RSSI)
    radio.add_argument("--true-path-loss", type=float, default=DEFAULT_TRUE_PATH_LOSS)
    radio.add_argument("--noise-sigma", type=float, default=DEFAULT_NOISE_SIGMA_DB)

    node = p.add_argument_group("node settings (what the firmware believes)")
    node.add_argument("--ref-rssi", type=int, default=DEFAULT_RX_REF_RSSI)
    node.add_argument("--absorption", type=float, default=DEFAULT_ABSORPTION)
    node.add_argument(
        "--rx-adj-rssi",
        type=int,
        default=DEFAULT_RX_ADJ_RSSI,
        help="firmware default is 20 on esp32s3 builds, 0 on the m5atom ones",
    )
    node.add_argument("--max-distance", type=float, default=DEFAULT_MAX_DISTANCE)
    node.add_argument("--skip-ms", type=int, default=DEFAULT_SKIP_MS)
    node.add_argument("--skip-distance", type=float, default=DEFAULT_SKIP_DISTANCE)

    run = p.add_argument_group("run")
    run.add_argument(
        "--adv-hz",
        type=float,
        default=1.0,
        help=(
            "advertisements per second the phone emits. How often each node "
            "publishes is then governed by skip_ms and skip_distance."
        ),
    )
    run.add_argument("--once", action="store_true", help="run a single tick and exit")
    run.add_argument("--duration", type=float, default=None, metavar="SECONDS")
    run.add_argument(
        "--speedup", type=float, default=1.0, help="run the clock faster than real time"
    )
    run.add_argument(
        "--schema",
        choices=[SCHEMA_V4, SCHEMA_V3],
        default=SCHEMA_V4,
        help="device payload schema: v4 (default, matches v4.0.6) or legacy v3",
    )
    run.add_argument("--no-discovery", action="store_true", help="skip Home Assistant discovery")
    run.add_argument(
        "--publish-ref-rssi",
        action="store_true",
        help="also retain espresense/rooms/<room>/ref_rssi (real firmware does not)",
    )
    run.add_argument(
        "-q", "--quiet", action="store_true", help="suppress the per-tick summary line"
    )
    run.add_argument(
        "--log-publishes",
        action="store_true",
        help="print every message published and received (implied by --dry-run)",
    )
    return p


@dataclass
class LoggingTransport:
    """Wraps a transport and prints everything it publishes."""

    inner: Transport
    prefix: str = ""

    def set_will(self, topic: str, payload: str, qos: int, retain: bool) -> None:
        self.inner.set_will(topic, payload, qos, retain)

    def subscribe(self, topic_filter: str, qos: int = 0) -> None:
        self.inner.subscribe(topic_filter, qos)

    def on_message(self, handler) -> None:
        def logged(topic: str, payload: str) -> None:
            print(f"  <- {topic} {payload!r}")
            handler(topic, payload)

        self.inner.on_message(logged)

    def on_connect(self, handler) -> None:
        self.inner.on_connect(handler)

    def publish(self, topic: str, payload: str, qos: int = 0, retain: bool = False) -> bool:
        flag = "R" if retain else " "
        print(f"  -> [{flag}] {topic} {payload}")
        return self.inner.publish(topic, payload, qos, retain)


def build_simulation(
    args: argparse.Namespace, transport_factory: Callable[[str], Transport]
) -> Simulation:
    house = load_house(args.layout)
    walk = load_walk(house, args.walk)
    if args.speed:
        walk.speed_mps = args.speed

    rng = random.Random(args.seed)
    radio = RadioModel(
        ref_rssi_1m=args.true_ref_rssi,
        path_loss_exponent=args.true_path_loss,
        noise_sigma_db=args.noise_sigma,
        rng=rng,
    )

    wanted = (
        {name.strip() for name in args.rooms.split(",")} if args.rooms else set(house.nodes)
    )
    nodes: dict[str, SimNode] = {}
    for index, (name, spec) in enumerate(house.nodes.items()):
        if name not in wanted:
            continue
        settings = NodeSettings(
            name=name,
            ref_rssi=args.ref_rssi,
            absorption=args.absorption,
            max_distance=args.max_distance,
            skip_ms=args.skip_ms,
            skip_distance=args.skip_distance,
            rx_adj_rssi=args.rx_adj_rssi,
        )
        nodes[name] = SimNode(
            room=spec.room,
            transport=transport_factory(f"espresense-{spec.room}"),
            settings=settings,
            ip=f"10.0.0.{20 + index}",
            chip_id=0xA70000 + index,
            # Espressif OUI plus a per-node tail, so each node's iBeacon
            # major/minor (derived from the MAC) is distinct.
            mac=f"d83add{0x4B + index * 7:02x}{0x1F + index * 13:02x}{0x60 + index * 29:02x}",
            schema=args.schema,
            ha_discovery=not args.no_discovery,
            publish_ref_rssi=args.publish_ref_rssi,
        )

    # A beacon advertises its own calibrated power, and get1mRssi() prefers it
    # over ref_rssi, so an iBeacon id implies one unless told otherwise.
    cal_rssi = args.beacon_1m_rssi
    if cal_rssi is None and args.device_id.startswith(BEACON_ID_PREFIXES):
        cal_rssi = DEFAULT_BEACON_1M_RSSI
    device = Device(
        device_id=args.device_id,
        name=args.device_name or None,
        cal_rssi=cal_rssi,
    )
    return Simulation(
        house=house,
        walk=walk,
        radio=radio,
        device=device,
        nodes=nodes,
        adv_interval_ms=1000.0 / max(args.adv_hz, 0.01),
        mac_rotate_s=args.mac_rotate,
        rng=rng,
    )


def format_tick(result: TickResult) -> str:
    room = result.room or "outside"
    parts = [
        f"t={result.elapsed_s:7.1f}s ({result.position.x:5.2f},{result.position.y:5.2f}) {room:<8}"
    ]
    for obs in sorted(
        result.observations,
        key=lambda o: (o.smoothed_rssi if o.smoothed_rssi is not None else -999),
        reverse=True,
    ):
        if obs.smoothed_rssi is None:
            parts.append(f"{obs.room}:--")
            continue
        mark = "*" if obs.published else " "
        parts.append(
            f"{obs.room}:{obs.smoothed_rssi:6.1f}dB/{obs.reported_distance:5.1f}m{mark}"
        )
    margin = result.margin_db()
    if margin is not None:
        parts.append(f"margin={margin:4.1f}dB")
    return "  ".join(parts)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    transports: list[PahoTransport] = []

    log_messages = args.dry_run or args.log_publishes

    def factory(client_id: str) -> Transport:
        if args.dry_run:
            return LoggingTransport(FakeTransport())
        transport = PahoTransport(client_id, BrokerConfig(args.host, args.port, args.username, args.password))
        transports.append(transport)
        return LoggingTransport(transport) if log_messages else transport

    sim = build_simulation(args, factory)
    if not sim.nodes:
        print("no nodes selected; check --rooms against the layout", file=sys.stderr)
        return 2

    # Order matters: attach sets each will and must precede CONNECT, then the
    # nodes announce themselves. A dry run has nothing to connect, so it goes
    # straight from attach to start.
    sim.start_ms = time.time() * 1000.0
    sim.attach()
    for transport in transports:
        try:
            transport.connect()
        except OSError as exc:
            print(f"cannot reach {args.host}:{args.port}: {exc}", file=sys.stderr)
            return 1
    if args.dry_run:
        sim.start()

    if not args.quiet:
        target = "dry run" if args.dry_run else f"{args.host}:{args.port}"
        print(
            f"espresense-sim -> {target}  nodes={','.join(sim.nodes)}  "
            f"device={args.device_id}  schema={args.schema}  "
            f"cycle={sim.walk.cycle_s:.0f}s"
        )

    stopping = False

    def stop(signum, frame) -> None:  # pragma: no cover - signal path
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    started = time.monotonic()
    tick_index = 0
    try:
        while not stopping:
            now_ms = sim.start_ms + tick_index * sim.adv_interval_ms
            result = sim.tick(now_ms)
            if not args.quiet:
                print(format_tick(result))
            tick_index += 1
            if args.once:
                break
            if args.duration is not None and result.elapsed_s >= args.duration:
                break
            target = started + (tick_index * sim.adv_interval_ms / 1000.0) / args.speedup
            delay = target - time.monotonic()
            if delay > 0:
                time.sleep(delay)
    finally:
        sim.shutdown()
        for transport in transports:
            transport.disconnect()
    return 0
