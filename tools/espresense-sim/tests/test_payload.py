"""Device payload schema, topics, telemetry, status and discovery."""

from __future__ import annotations

import json

import pytest

from espresense_sim.node import SCHEMA_V3, Device, SimNode
from espresense_sim.settings import DEFAULT_TX, NodeSettings
from espresense_sim.transport import FakeTransport

PHONE_ID = "iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1"
PHONE = Device(device_id=PHONE_ID, name="hazri-test-phone", mac="d83addb1ee01")


def make_node(**kwargs) -> tuple[SimNode, FakeTransport]:
    transport = FakeTransport()
    node = SimNode(
        room="kitchen",
        transport=transport,
        settings=NodeSettings(name="kitchen"),
        **kwargs,
    )
    node.attach(0.0)
    return node, transport


class Clock:
    """A monotonic ms clock, so the filter's 15 s window behaves."""

    def __init__(self, start: float = 1_000_000.0) -> None:
        self.now = start

    def advance(self, ms: float) -> float:
        self.now += ms
        return self.now


def feed(
    node: SimNode,
    rssi: float,
    clock: Clock,
    ticks: int = 12,
    step_ms: float = 1000.0,
    jitter: float = 0.0,
) -> None:
    """Feed `ticks` advertisements. A non-zero jitter walks the RSSI around so
    the filter reports a real variance, as a live radio would."""
    for i in range(ticks):
        wobble = jitter * (1 if i % 2 else -1)
        node.observe(PHONE, rssi + wobble, clock.advance(step_ms))


def test_topics_follow_the_firmware_shape() -> None:
    node, _ = make_node()
    assert node.status_topic == "espresense/rooms/kitchen/status"
    assert node.telemetry_topic == "espresense/rooms/kitchen/telemetry"
    assert node.device_topic(PHONE_ID) == f"espresense/devices/{PHONE_ID}/kitchen"


def test_node_subscribes_to_its_own_and_the_fleet_set_topics() -> None:
    _, transport = make_node()
    assert transport.subscriptions == [
        "espresense/rooms/*/+/set",
        "espresense/rooms/kitchen/+/set",
        "espresense/settings/+/config",
    ]


def test_last_will_is_retained_offline() -> None:
    _, transport = make_node()
    assert transport.will is not None
    assert transport.will.topic == "espresense/rooms/kitchen/status"
    assert transport.will.payload == "offline"
    assert transport.will.retain is True


def test_v4_payload_has_exactly_the_firmware_keys() -> None:
    node, transport = make_node()
    clock = Clock()
    feed(node, -70.0, clock, jitter=2.0)  # noisy, so rssiVar is published
    node.report_devices(clock.now)
    message = transport.last(f"espresense/devices/{PHONE_ID}/kitchen")
    assert message is not None and message.retain is False and message.qos == 0
    doc = json.loads(message.payload)
    assert set(doc) == {
        "mac",
        "id",
        "name",
        "rssi@1m",
        "rssi",
        "rxAdj",
        "rssiVar",
        "distance",
        "var",
        "int",
    }
    assert doc["id"] == PHONE_ID
    assert doc["mac"] == "d83addb1ee01"
    assert isinstance(doc["rssi@1m"], int)
    assert doc["rssi"] == pytest.approx(-70.0 - node.settings.rx_adj_rssi)
    assert doc["rssiVar"] > 0
    assert doc["distance"] > 0
    assert doc["int"] == pytest.approx(1000, abs=100)


def test_v4_payload_drops_raw_and_id_type() -> None:
    """v4.0.6's fill() publishes neither `raw` nor `idType`; v3.x did both."""
    node, transport = make_node()
    clock = Clock()
    feed(node, -70.0, clock)
    node.report_devices(clock.now)
    doc = json.loads(transport.last(node.device_topic(PHONE.device_id)).payload)
    assert "raw" not in doc
    assert "idType" not in doc
    assert "speed" not in doc


def test_v3_schema_adds_the_legacy_keys() -> None:
    node, transport = make_node(schema=SCHEMA_V3)
    clock = Clock()
    feed(node, -70.0, clock)
    node.report_devices(clock.now)
    doc = json.loads(transport.last(node.device_topic(PHONE.device_id)).payload)
    assert doc["idType"] == 180
    assert doc["raw"] > 0  # v3's `raw` is the unfiltered *distance*
    assert doc["distance"] > 0
    assert "rxAdj" not in doc


def test_smoothed_rssi_differs_from_the_raw_sample() -> None:
    node, transport = make_node()
    node.settings.rx_adj_rssi = 0
    clock = Clock()
    feed(node, -70.0, clock, ticks=10)
    node.observe(PHONE, -50.0, clock.advance(1000.0))  # one hot outlier
    node.report_devices(clock.now)
    doc = json.loads(transport.last(node.device_topic(PHONE.device_id)).payload)
    fingerprint = node.fingerprints[PHONE.device_id]
    assert fingerprint.raw_rssi == -50.0
    assert doc["rssi"] == pytest.approx(-70.0)  # the Tukey fence rejects it
    assert doc["rssi"] != fingerprint.raw_rssi


def test_rssi_at_one_metre_follows_ref_rssi_unless_the_beacon_says_otherwise() -> None:
    node, _ = make_node()
    fingerprint_device = Device(device_id="phone:x", mac="aabbccddeeff")
    node.observe(fingerprint_device, -70.0, 0.0)
    fp = node.fingerprints["phone:x"]
    assert fp.one_m_rssi(node.settings) == node.settings.ref_rssi + DEFAULT_TX

    beacon = Device(device_id="iBeacon:x-1-2", mac="aabbccddeeff", cal_rssi=-59)
    node.observe(beacon, -70.0, 0.0)
    assert node.fingerprints["iBeacon:x-1-2"].one_m_rssi(node.settings) == -59


def test_close_flag_uses_the_firmware_hysteresis() -> None:
    """shouldCount(): cross CLOSE_RSSI to latch, fall past LEFT_RSSI to clear.
    Both thresholds are offset by rx_adj_rssi, as the firmware does."""
    node, _ = make_node()
    node.settings.rx_adj_rssi = 0
    clock = Clock()
    feed(node, -35.0, clock)  # above CLOSE_RSSI (-40)
    fp = node.fingerprints[PHONE.device_id]
    assert fp.close is True
    assert fp.fill(clock.now, node.settings)["close"] is True

    feed(node, -45.0, clock, ticks=20)  # between LEFT and CLOSE: still close
    assert fp.close is True
    feed(node, -60.0, clock, ticks=20)  # below LEFT_RSSI (-50)
    assert fp.close is False


def test_status_and_settings_are_retained_on_connect() -> None:
    node, transport = make_node()
    node.send_online()
    status = transport.last("espresense/rooms/kitchen/status")
    assert status.payload == "online" and status.retain is True
    for key, expected in (
        ("name", "kitchen"),
        ("max_distance", "16.00"),
        ("absorption", "2.70"),
        ("tx_ref_rssi", "-59"),
        ("rx_adj_rssi", "20"),
    ):
        message = transport.last(f"espresense/rooms/kitchen/{key}")
        assert message is not None, key
        assert message.retain is True
        assert message.payload == expected


def test_ref_rssi_is_not_retained_by_default() -> None:
    """Matches the firmware: settable, never published back. See README."""
    node, transport = make_node()
    node.send_online()
    assert transport.last("espresense/rooms/kitchen/ref_rssi") is None

    node2, transport2 = make_node(publish_ref_rssi=True)
    node2.send_online()
    assert transport2.last("espresense/rooms/kitchen/ref_rssi").payload == "-65"


def test_telemetry_is_json_and_not_retained() -> None:
    node, transport = make_node()
    feed(node, -70.0, Clock(0.0), ticks=3)
    assert node.maybe_send_telemetry(20_000.0) is True
    message = transport.last("espresense/rooms/kitchen/telemetry")
    assert message.retain is False
    doc = json.loads(message.payload)
    for key in ("ip", "uptime", "firm", "rssi", "ver", "freeHeap", "maxHeap"):
        assert key in doc
    assert doc["uptime"] == 20
    assert doc["adverts"] == 3


def test_telemetry_is_rate_limited_to_15_seconds() -> None:
    node, transport = make_node()
    assert node.maybe_send_telemetry(0.0) is True
    assert node.maybe_send_telemetry(14_000.0) is False
    assert node.maybe_send_telemetry(15_000.0) is True


def test_home_assistant_discovery_is_retained_and_addressable() -> None:
    node, transport = make_node()
    node.send_online()
    topics = [t for t in transport.topics() if t.startswith("homeassistant/")]
    assert any("/binary_sensor/" in t and t.endswith("/connectivity/config") for t in topics)
    number = next(t for t in topics if t.endswith("/max_distance/config"))
    assert "/number/" in number
    doc = json.loads(transport.last(number).payload)
    assert doc["~"] == "espresense/rooms/kitchen"
    assert doc["cmd_t"] == "~/max_distance/set"
    assert doc["stat_t"] == "~/max_distance"
    assert transport.last(number).retain is True


def test_discovery_can_be_switched_off() -> None:
    node, transport = make_node(ha_discovery=False)
    node.send_online()
    assert not [t for t in transport.topics() if t.startswith("homeassistant/")]


def test_node_publishes_its_own_ibeacon_identity() -> None:
    """Enrollment::SendDiscovery(): the retained config that maps the node's
    advertised iBeacon major/minor back to a room name."""
    node, transport = make_node(mac="d83add0000a5")
    node.send_online()
    major, minor = node.beacon_major_minor
    topic = (
        "espresense/settings/iBeacon:"
        f"e5ca1ade-f007-ba11-0000-000000000000-{major}-{minor}/config"
    )
    message = transport.last(topic)
    assert message is not None and message.retain is True
    assert json.loads(message.payload) == {"id": "node:kitchen", "name": "kitchen"}


def test_node_beacon_ids_differ_between_nodes() -> None:
    a, _ = make_node(mac="d83add000001")
    b, _ = make_node(mac="d83add000002")
    assert a.beacon_id != b.beacon_id


# -- lifecycle: the will must be armed before CONNECT (issue 1) --------------


def test_attach_publishes_nothing_so_the_will_can_ride_in_connect() -> None:
    """MQTT only accepts a will inside the CONNECT packet. attach() therefore
    sets the will and subscribes but must not publish; start() does that."""
    transport = FakeTransport()
    node = SimNode(room="kitchen", transport=transport, settings=NodeSettings(name="kitchen"))
    node.attach(0.0)
    assert transport.will is not None
    assert transport.published == []
    assert node.online is False

    node.start()
    assert transport.last("espresense/rooms/kitchen/status").payload == "online"
    assert node.online is True


def test_reconnect_re_announces_status_and_settings() -> None:
    """onMqttDisconnect() sets online=false, so the node re-sends everything
    once the session is back - the broker has published the will by then."""
    node, transport = make_node()
    node.start()
    transport.clear()

    transport.reconnect()
    assert transport.published == [], "the network thread must not publish"
    node.drain_messages()  # the simulation thread does the announcing
    assert transport.last("espresense/rooms/kitchen/status").payload == "online"
    assert transport.last("espresense/rooms/kitchen/absorption") is not None
    # sentDiscovery is a one-shot global in the firmware, so it is not re-sent.
    assert not [t for t in transport.topics() if t.startswith("homeassistant/")]


# -- inbound is queued, not applied on the transport thread (issue 2) --------


def test_inbound_messages_are_queued_until_drained() -> None:
    node, transport = make_node()
    node.start()
    transport.deliver("espresense/rooms/kitchen/absorption/set", "3.3")
    assert node.settings.absorption == pytest.approx(2.7), "applied too early"

    results = node.drain_messages()
    assert [r.handled for r in results] == [True]
    assert node.settings.absorption == pytest.approx(3.3)
    assert node.drain_messages() == []


def test_a_queued_restart_cannot_clear_fingerprints_mid_iteration() -> None:
    node, transport = make_node()
    node.start()
    clock = Clock()
    feed(node, -60.0, clock, ticks=3)
    assert node.fingerprints

    transport.deliver("espresense/rooms/kitchen/restart/set", "1")
    node.report_devices(clock.now)  # still safe to iterate: nothing applied yet
    assert node.fingerprints
    node.drain_messages()
    assert node.fingerprints == {}


# -- skip_ms 0 disables rate limiting rather than dividing by zero (issue 3) --


def test_skip_ms_zero_reports_every_advertisement() -> None:
    node, transport = make_node()
    node.settings.skip_ms = 0
    clock = Clock()
    published = 0
    for _ in range(5):
        node.observe(PHONE, -60.0, clock.advance(1000.0))
        published += len(node.report_devices(clock.now))
    assert published == 5


def test_skip_ms_zero_survives_a_fresh_fingerprint() -> None:
    """The slot arithmetic in observe() divides by skip_ms too."""
    node, _ = make_node()
    node.settings.skip_ms = 0
    node.observe(PHONE, -60.0, 1_000_000.0)
    assert node.fingerprints[PHONE.device_id].next_report_ms == 1_000_000.0


# -- rssiVar is conditional, as isnormal() makes it (issue 4) ----------------


def test_rssi_var_is_omitted_when_there_is_nothing_to_vary() -> None:
    node, _ = make_node()
    node.observe(PHONE, -70.0, 1000.0)  # a single sample: variance is 0
    fp = node.fingerprints[PHONE.device_id]
    assert "rssiVar" not in fp.fill(1000.0, node.settings)

    clock = Clock()
    feed(node, -70.0, clock, ticks=6, jitter=1.5)
    assert "rssiVar" in fp.fill(clock.now, node.settings)


# -- device configs: the alias path (issue 7) -------------------------------


def test_a_device_config_aliases_the_id_and_carries_a_name() -> None:
    node, transport = make_node()
    node.attach(0.0)
    transport.deliver(
        f"espresense/settings/{PHONE_ID}/config",
        json.dumps({"id": "known:harpreet", "name": "Harpreet"}),
    )
    node.drain_messages()

    node.observe(PHONE, -60.0, 1000.0)
    assert list(node.fingerprints) == ["known:harpreet"]
    doc = node.fingerprints["known:harpreet"].fill(1000.0, node.settings)
    assert doc["id"] == "known:harpreet"
    assert doc["name"] == "Harpreet"


def test_a_device_config_can_set_the_calibrated_one_metre_power() -> None:
    node, transport = make_node()
    node.attach(0.0)
    transport.deliver(
        f"espresense/settings/{PHONE_ID}/config", json.dumps({"id": "", "rssi@1m": -62})
    )
    node.drain_messages()
    node.observe(PHONE, -60.0, 1000.0)
    assert node.fingerprints[PHONE_ID].fill(1000.0, node.settings)["rssi@1m"] == -62


def test_an_empty_config_payload_removes_the_config() -> None:
    node, transport = make_node()
    node.attach(0.0)
    topic = f"espresense/settings/{PHONE_ID}/config"
    transport.deliver(topic, json.dumps({"id": "known:harpreet"}))
    node.drain_messages()
    transport.deliver(topic, "")
    node.drain_messages()
    assert node.device_configs == {}

    node.observe(PHONE, -60.0, 1000.0)
    assert list(node.fingerprints) == [PHONE_ID]
