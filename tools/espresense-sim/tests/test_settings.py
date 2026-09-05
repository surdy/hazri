"""`<setting>/set` handling: the path Hazri's "Push via MQTT" exercises."""

from __future__ import annotations

import math

import pytest

from espresense_sim.node import Device, SimNode
from espresense_sim.settings import (
    DEFAULT_ABSORPTION,
    DEFAULT_MAX_DISTANCE,
    DEFAULT_TX,
    NodeSettings,
    apply_command,
)
from espresense_sim.transport import FakeTransport

PHONE_ID = "iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1"
PHONE = Device(device_id=PHONE_ID, name="hazri-test-phone")


class QueuedFake(FakeTransport):
    """FakeTransport that drains the node's inbox after delivering, so a test
    can assert straight after a deliver. Production code drains in tick()."""

    def bind(self, node: SimNode) -> "QueuedFake":
        self._node = node
        return self

    def deliver(self, topic: str, payload: str) -> None:
        super().deliver(topic, payload)
        node = getattr(self, "_node", None)
        if node is not None:
            node.drain_messages()


def make_node() -> tuple[SimNode, QueuedFake]:
    transport = QueuedFake()
    node = SimNode(room="kitchen", transport=transport, settings=NodeSettings(name="kitchen"))
    transport.bind(node)
    node.attach(0.0)
    node.start()
    transport.clear()
    return node, transport


def report(node: SimNode, rssi: float, now_ms: float) -> dict:
    node.observe(PHONE, rssi, now_ms)
    fp = node.fingerprints[PHONE.device_id]
    return fp.fill(now_ms, node.settings, node.schema)


def test_absorption_set_changes_later_distances() -> None:
    node, transport = make_node()
    before = report(node, -80.0, 0.0)["distance"]

    transport.deliver("espresense/rooms/kitchen/absorption/set", "3.5")
    assert node.settings.absorption == pytest.approx(3.5)

    after = report(node, -80.0, 1000.0)["distance"]
    assert after < before  # a higher path-loss exponent reads the same rssi as nearer
    ref = node.settings.ref_rssi + DEFAULT_TX
    smoothed = -80.0 - node.settings.rx_adj_rssi
    assert after == pytest.approx(math.pow(10.0, (ref - smoothed) / 35.0), abs=0.05)


def test_ref_rssi_set_changes_the_published_rssi_at_1m() -> None:
    node, transport = make_node()
    assert report(node, -75.0, 0.0)["rssi@1m"] == -65 + DEFAULT_TX

    transport.deliver("espresense/rooms/kitchen/ref_rssi/set", "-59")
    doc = report(node, -75.0, 1000.0)
    assert node.settings.ref_rssi == -59
    assert doc["rssi@1m"] == -59 + DEFAULT_TX


def test_rx_adj_rssi_shifts_the_smoothed_rssi() -> None:
    node, transport = make_node()
    transport.deliver("espresense/rooms/kitchen/rx_adj_rssi/set", "5")
    doc = report(node, -70.0, 0.0)
    assert doc["rxAdj"] == 5
    assert doc["rssi"] == pytest.approx(-75.0)


def test_max_distance_set_suppresses_distant_reports() -> None:
    node, transport = make_node()
    transport.deliver("espresense/rooms/kitchen/max_distance/set", "2")
    assert node.settings.max_distance == pytest.approx(2.0)
    node.observe(PHONE, -60.0, 0.0)  # ~2.2 m with the default calibration
    assert node.fingerprints[PHONE.device_id].dist > 2.0
    assert node.report_devices(0.0) == []

    transport.deliver("espresense/rooms/kitchen/max_distance/set", "40")
    node.observe(PHONE, -60.0, 6000.0)
    assert node.report_devices(6000.0) != []


def test_a_setting_change_republishes_every_retained_setting() -> None:
    node, transport = make_node()
    transport.deliver("espresense/rooms/kitchen/absorption/set", "3.1")
    topics = transport.topics()
    assert "espresense/rooms/kitchen/status" in topics
    assert transport.last("espresense/rooms/kitchen/absorption").payload == "3.10"
    assert transport.last("espresense/rooms/kitchen/absorption").retain is True
    # every retained key comes back, not just the one that changed
    assert transport.last("espresense/rooms/kitchen/max_distance") is not None
    assert transport.last("espresense/rooms/kitchen/known_macs") is not None


def test_empty_payload_resets_to_the_firmware_default() -> None:
    node, transport = make_node()
    transport.deliver("espresense/rooms/kitchen/absorption/set", "4.2")
    assert node.settings.absorption == pytest.approx(4.2)
    transport.deliver("espresense/rooms/kitchen/absorption/set", "")
    assert node.settings.absorption == pytest.approx(DEFAULT_ABSORPTION)
    transport.deliver("espresense/rooms/kitchen/max_distance/set", "")
    assert node.settings.max_distance == pytest.approx(DEFAULT_MAX_DISTANCE)


def test_string_settings_round_trip() -> None:
    node, transport = make_node()
    transport.deliver("espresense/rooms/kitchen/known_macs/set", "aabbccddeeff")
    assert node.settings.known_macs == "aabbccddeeff"
    assert transport.last("espresense/rooms/kitchen/known_macs").payload == "aabbccddeeff"


def test_wildcard_room_applies_to_every_node() -> None:
    node, transport = make_node()
    transport.deliver("espresense/rooms/*/absorption/set", "3.9")
    assert node.settings.absorption == pytest.approx(3.9)


def test_another_rooms_set_is_ignored() -> None:
    node, transport = make_node()
    result = node.handle_message("espresense/rooms/hall/absorption/set", "3.9")
    assert result.handled is False
    assert node.settings.absorption == pytest.approx(DEFAULT_ABSORPTION)


def test_unknown_commands_and_non_set_topics_are_ignored() -> None:
    node, _ = make_node()
    assert node.handle_message("espresense/rooms/kitchen/wibble/set", "1").handled is False
    assert node.handle_message("espresense/rooms/kitchen/absorption", "1").handled is False
    assert node.handle_message("espresense/devices/phone:x/kitchen", "{}").handled is False


def test_garbage_numbers_become_zero_like_arduino_tofloat() -> None:
    settings = NodeSettings(name="kitchen")
    apply_command(settings, "absorption", "not-a-number")
    assert settings.absorption == 0.0


def test_name_set_is_pending_until_restart() -> None:
    node, transport = make_node()
    transport.deliver("espresense/rooms/kitchen/name/set", "pantry")
    assert node.settings.pending_name == "pantry"
    assert node.room == "kitchen"  # firmware only writes it to flash

    transport.deliver("espresense/rooms/kitchen/restart/set", "PRESS")
    assert node.room == "pantry"
    assert node.status_topic == "espresense/rooms/pantry/status"
    assert node.fingerprints == {}


def test_restart_forces_a_fresh_online_announcement() -> None:
    node, transport = make_node()
    transport.deliver("espresense/rooms/kitchen/restart/set", "1")
    assert node.online is False and node.sent_discovery is False
    transport.clear()
    node.maybe_send_telemetry(1000.0)
    assert transport.last("espresense/rooms/kitchen/status").payload == "online"


def test_skip_ms_rate_limits_reports() -> None:
    node, transport = make_node()
    transport.deliver("espresense/rooms/kitchen/skip_ms/set", "5000")
    transport.deliver("espresense/rooms/kitchen/skip_distance/set", "0.5")
    transport.clear()

    published = 0
    for i in range(30):  # 30 s of a stationary phone at 1 Hz
        now = 1_000_000_137.0 + i * 1000.0  # wall-clock ms, arbitrary phase
        node.observe(PHONE, -70.0, now)
        published += len(node.report_devices(now))
    device_topic = node.device_topic(PHONE.device_id)
    assert published == len([t for t in transport.topics() if t == device_topic])
    # One slot per 5 s window, not one per advertisement.
    assert 5 <= published <= 8


def test_movement_beats_the_rate_limit() -> None:
    """report()'s early-report allowance: once a device has moved more than
    skip_distance, it may publish up to skip_ms / 2 before its next slot."""
    node, _ = make_node()
    node.settings.skip_ms = 5000
    node.settings.skip_distance = 0.5
    base = 1_000_000_000.0

    for i in range(10):  # settle far away
        node.observe(PHONE, -90.0, base + i * 100.0)
    fp = node.fingerprints[PHONE.device_id]
    far = fp.dist
    fp.last_reported = far
    fp.next_report_ms = base + 6000.0  # a full slot away
    fp.reported = False

    # A wobble in place stays quiet.
    node.observe(PHONE, -90.5, base + 1100.0)
    assert fp.should_report(base + 1100.0, node.settings) is False

    # Walking right up to the node moves metres, so it reports early.
    for i in range(10):
        node.observe(PHONE, -40.0, base + 2000.0 + i * 100.0)
    assert far - fp.dist > node.settings.skip_distance
    # A move that large divides skip_ms by 2, so the slot opens 2500 ms early
    # and not a millisecond sooner.
    assert fp.should_report(base + 3000.0, node.settings) is False
    assert fp.should_report(base + 4000.0, node.settings) is True
