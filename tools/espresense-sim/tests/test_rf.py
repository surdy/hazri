"""The radio model and the firmware's inverse of it."""

from __future__ import annotations

import math
import random

import pytest

from espresense_sim.geometry import Vec2, load_house
from espresense_sim.rf import RadioModel


def test_reference_rssi_at_one_metre() -> None:
    radio = RadioModel(ref_rssi_1m=-59.0, path_loss_exponent=3.5)
    assert radio.mean_rssi(1.0) == pytest.approx(-59.0)


def test_log_distance_slope() -> None:
    radio = RadioModel(ref_rssi_1m=-59.0, path_loss_exponent=3.5)
    # Every decade of distance costs 10 * n dB.
    assert radio.mean_rssi(10.0) == pytest.approx(-59.0 - 35.0)
    assert radio.mean_rssi(100.0) == pytest.approx(-59.0 - 70.0)
    # Every doubling costs 10 * n * log10(2).
    step = 10 * 3.5 * math.log10(2)
    assert radio.mean_rssi(4.0) == pytest.approx(radio.mean_rssi(2.0) - step)


def test_walls_and_antenna_offset_are_additive() -> None:
    radio = RadioModel(ref_rssi_1m=-59.0, path_loss_exponent=3.5)
    base = radio.mean_rssi(5.0)
    assert radio.mean_rssi(5.0, wall_db=6.0) == pytest.approx(base - 6.0)
    assert radio.mean_rssi(5.0, wall_db=6.0, antenna_offset_db=-3.0) == pytest.approx(
        base - 9.0
    )


def test_distance_inverts_the_model_when_calibration_is_right() -> None:
    """d = 10 ** ((rssi@1m - rssi) / (10 * absorption)) - BleFingerprint.cpp:515."""
    radio = RadioModel(ref_rssi_1m=-59.0, path_loss_exponent=3.5)
    for truth in (1.0, 2.5, 7.0, 12.0):
        rssi = radio.mean_rssi(truth)
        recovered = math.pow(10.0, (-59.0 - rssi) / (10.0 * 3.5))
        assert recovered == pytest.approx(truth, rel=1e-9)


def test_noise_is_seeded_and_repeatable() -> None:
    a = RadioModel(rng=random.Random(7)).sample_rssi(4.0)
    b = RadioModel(rng=random.Random(7)).sample_rssi(4.0)
    assert a == b
    assert a != RadioModel(rng=random.Random(8)).sample_rssi(4.0)


def test_noise_has_the_configured_sigma() -> None:
    radio = RadioModel(noise_sigma_db=3.0, rng=random.Random(1))
    samples = [radio.sample_rssi(5.0) for _ in range(20_000)]
    mean = sum(samples) / len(samples)
    sigma = math.sqrt(sum((s - mean) ** 2 for s in samples) / len(samples))
    assert mean == pytest.approx(radio.mean_rssi(5.0), abs=0.1)
    assert sigma == pytest.approx(3.0, abs=0.1)


def test_packet_loss_rises_with_distance_and_is_clamped() -> None:
    radio = RadioModel()
    near, mid, far = (radio.loss_probability(d) for d in (1.0, 10.0, 40.0))
    assert near < mid < far
    assert near >= radio.base_loss
    assert far == pytest.approx(radio.max_loss)


def test_delivery_rate_matches_loss_probability() -> None:
    radio = RadioModel(rng=random.Random(3))
    delivered = sum(radio.delivered(12.0) for _ in range(10_000))
    expected = 1.0 - radio.loss_probability(12.0)
    assert delivered / 10_000 == pytest.approx(expected, abs=0.02)


def test_house_distance_uses_height_and_a_floor() -> None:
    house = load_house()
    node = house.nodes["living"]
    # Standing right under the node still yields the 0.5 m floor, not 0.
    assert house.node_distance_m(Vec2(node.x, node.y), node) >= 0.5
    far = house.node_distance_m(Vec2(8.0, 10.0), node)
    assert far > 8.0


def test_wall_penalty_table_is_symmetric_with_a_default() -> None:
    house = load_house()
    assert house.wall_penalty_db("living", "living") == 0.0
    assert house.wall_penalty_db("living", "hall") == house.wall_penalty_db(
        "hall", "living"
    )
    assert house.wall_penalty_db(None, "hall") == house.default_wall_penalty_db
