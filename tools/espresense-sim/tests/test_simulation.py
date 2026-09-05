"""End to end over the default layout, with no broker."""

from __future__ import annotations

import json

import pytest

from espresense_sim.cli import (
    DEFAULT_BEACON_1M_RSSI,
    DEFAULT_DEVICE_ID,
    build_parser,
    build_simulation,
    format_tick,
)
from espresense_sim.geometry import load_house
from espresense_sim.transport import FakeTransport

PHONE_ID = DEFAULT_DEVICE_ID


def build(argv: list[str]) -> tuple:
    """Build a simulation on fake transports, wired attach -> start as the CLI
    does (a will is only honoured inside CONNECT, so attach publishes nothing)."""
    args = build_parser().parse_args(argv)
    transports: dict[str, FakeTransport] = {}

    def factory(client_id: str) -> FakeTransport:
        transports[client_id] = FakeTransport()
        return transports[client_id]

    return build_simulation(args, factory), transports


def test_default_layout_has_a_node_in_every_room_but_the_garage() -> None:
    house = load_house()
    assert set(house.rooms) == {
        "living",
        "kitchen",
        "hall",
        "bedroom",
        "office",
        "garage",
    }
    assert set(house.nodes) == {"living", "kitchen", "hall", "bedroom", "office"}


def test_zero_argument_run_publishes_status_and_device_reports() -> None:
    sim, transports = build(["--seed", "1"])
    sim.attach()
    sim.start()
    for i in range(6):
        sim.tick(i * sim.adv_interval_ms)

    assert (
        transports["espresense-kitchen"].last("espresense/rooms/kitchen/status").payload
        == "online"
    )
    # The walk starts in the living room, so that node is the one certain to
    # place the phone inside its max_distance.
    living = transports["espresense-living"]
    device_topics = [
        t for t in living.topics() if t.startswith(f"espresense/devices/{PHONE_ID}/")
    ]
    assert device_topics, "expected at least one device report"
    doc = json.loads(living.last(device_topics[0]).payload)
    assert doc["id"] == PHONE_ID


def test_the_nearest_node_hears_the_phone_loudest() -> None:
    sim, _ = build(["--seed", "5", "--noise-sigma", "0"])
    sim.attach()
    sim.start()
    result = None
    for i in range(20):  # the walk starts dwelling in the living room
        result = sim.tick(i * sim.adv_interval_ms)
    assert result.room == "living"
    best = result.best()
    assert best is not None and best.room == "living"
    assert result.margin_db() > 0


def test_the_garage_is_a_blind_spot() -> None:
    sim, _ = build(["--seed", "5", "--noise-sigma", "0"])
    sim.attach()
    sim.start()
    garage_centre = sim.house.rooms["garage"].centre
    living_centre = sim.house.rooms["living"].centre
    living_node = sim.house.nodes["living"]
    kitchen_node = sim.house.nodes["kitchen"]

    def rssi(position, node) -> float:
        room = sim.house.room_at(position)
        return sim.radio.mean_rssi(
            sim.house.node_distance_m(position, node),
            sim.house.wall_penalty_db(room, node.room),
            node.antenna_offset_db,
        )

    # The nearest node is far weaker from the garage than a node in its own room.
    assert rssi(garage_centre, kitchen_node) < rssi(living_centre, living_node) - 15


def test_shutdown_publishes_offline() -> None:
    sim, transports = build([])
    sim.attach()
    sim.start()
    sim.tick(0.0)
    sim.shutdown()
    status = transports["espresense-hall"].last("espresense/rooms/hall/status")
    assert status.payload == "offline" and status.retain is True


def test_same_seed_gives_the_same_traffic() -> None:
    def run() -> list[str]:
        sim, transports = build(["--seed", "42"])
        sim.attach()
        sim.start()
        for i in range(30):
            sim.tick(i * sim.adv_interval_ms)
        return [m.payload for m in transports["espresense-living"].published]

    assert run() == run()


def test_a_set_over_the_wire_changes_what_a_node_reports() -> None:
    """A set arriving on the transport thread is applied by the next tick."""
    sim, transports = build(["--seed", "3", "--noise-sigma", "0"])
    sim.attach()
    sim.start()
    for i in range(12):
        sim.tick(i * sim.adv_interval_ms)
    hall = transports["espresense-hall"]
    topic = f"espresense/devices/{PHONE_ID}/hall"
    before = json.loads(hall.last(topic).payload)

    hall.deliver("espresense/rooms/hall/ref_rssi/set", "-53")
    hall.deliver("espresense/rooms/hall/absorption/set", "3.5")
    assert sim.nodes["hall"].settings.absorption == pytest.approx(2.7)  # not yet
    for i in range(12, 40):
        sim.tick(i * sim.adv_interval_ms)
    after = json.loads(hall.last(topic).payload)

    assert sim.nodes["hall"].settings.absorption == pytest.approx(3.5)
    assert after["distance"] != before["distance"]
    # The default phone is an iBeacon, so it carries its own 1 m power and
    # ref_rssi is ignored - get1mRssi() prefers calRssi. This is the trap the
    # app's Calibrate-at-1 m has to know about.
    assert before["rssi@1m"] == after["rssi@1m"] == DEFAULT_BEACON_1M_RSSI


def test_rooms_flag_selects_a_subset() -> None:
    sim, transports = build(["--rooms", "kitchen,hall"])
    assert set(sim.nodes) == {"kitchen", "hall"}
    assert set(transports) == {"espresense-kitchen", "espresense-hall"}


def test_mac_rotation_keeps_one_fingerprint() -> None:
    sim, transports = build(["--seed", "2", "--mac-rotate", "5"])
    sim.attach()
    sim.start()
    macs = set()
    for i in range(40):
        sim.tick(i * sim.adv_interval_ms)
        macs.add(sim.device.mac)
    assert len(macs) > 1
    living = sim.nodes["living"]
    assert list(living.fingerprints) == [PHONE_ID]


def test_v3_schema_flag_reaches_the_payload() -> None:
    sim, transports = build(["--seed", "1", "--schema", "v3"])
    sim.attach()
    sim.start()
    for i in range(8):
        sim.tick(i * sim.adv_interval_ms)
    living = transports["espresense-living"]
    doc = json.loads(living.last(f"espresense/devices/{PHONE_ID}/living").payload)
    assert "raw" in doc and "idType" in doc


def test_tick_line_is_printable() -> None:
    sim, _ = build(["--seed", "1"])
    sim.attach()
    sim.start()
    line = format_tick(sim.tick(0.0))
    assert "living" in line and "t=" in line


def test_beacon_calibration_overrides_ref_rssi() -> None:
    sim, transports = build(["--seed", "1", "--beacon-1m-rssi", "-59"])
    sim.attach()
    sim.start()
    for i in range(8):
        sim.tick(i * sim.adv_interval_ms)
    living = transports["espresense-living"]
    doc = json.loads(living.last(f"espresense/devices/{PHONE_ID}/living").payload)
    assert doc["rssi@1m"] == -59


def test_default_device_id_is_one_espresense_can_actually_publish() -> None:
    """report() drops anything at or below ID_TYPE_RAND_MAC, so a made-up
    "phone:..." id never appears on a real broker. The default is an iBeacon
    id, and that implies the beacon's own calibrated 1 m power."""
    sim, _ = build([])
    assert sim.device.device_id.startswith("iBeacon:")
    assert sim.device.cal_rssi == DEFAULT_BEACON_1M_RSSI


def test_an_explicit_beacon_calibration_still_wins() -> None:
    sim, _ = build(["--beacon-1m-rssi", "-45"])
    assert sim.device.cal_rssi == -45


def test_a_non_beacon_id_implies_no_calibration() -> None:
    sim, _ = build(["--device-id", "phone:synthetic"])
    assert sim.device.cal_rssi is None


def test_tick_drains_inbound_sets_before_observing() -> None:
    sim, transports = build(["--seed", "1", "--noise-sigma", "0"])
    sim.attach()
    sim.start()
    sim.tick(0.0)
    transports["espresense-living"].deliver(
        "espresense/rooms/living/absorption/set", "4.0"
    )
    assert sim.nodes["living"].settings.absorption == pytest.approx(2.7)
    sim.tick(1000.0)
    assert sim.nodes["living"].settings.absorption == pytest.approx(4.0)
