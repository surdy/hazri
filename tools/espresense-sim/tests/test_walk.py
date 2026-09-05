"""Waypoint interpolation: dwell, travel, and looping."""

from __future__ import annotations

import pytest

from espresense_sim.geometry import Vec2, load_house
from espresense_sim.walk import Walk, Waypoint, load_walk, walk_from_dict


def square_walk(dwell: float = 10.0, speed: float = 1.0) -> Walk:
    return Walk(
        waypoints=[
            Waypoint(Vec2(0.0, 0.0), dwell, "a"),
            Waypoint(Vec2(10.0, 0.0), dwell, "b"),
        ],
        speed_mps=speed,
    )


def test_cycle_is_dwell_plus_travel() -> None:
    walk = square_walk()
    # 10 s dwell twice, 10 m each way at 1 m/s.
    assert walk.cycle_s == pytest.approx(40.0)


def test_dwell_holds_position() -> None:
    walk = square_walk()
    for t in (0.0, 4.0, 9.99):
        assert walk.position_at(t) == Vec2(0.0, 0.0)


def test_travel_is_linear_at_the_configured_speed() -> None:
    walk = square_walk()
    midway = walk.position_at(15.0)  # 5 s into a 10 s leg
    assert midway.x == pytest.approx(5.0)
    assert midway.y == pytest.approx(0.0)
    assert walk.position_at(20.0).x == pytest.approx(10.0)


def test_speed_scales_the_leg_time() -> None:
    fast = square_walk(dwell=0.0, speed=2.0)
    assert fast.cycle_s == pytest.approx(10.0)  # 20 m at 2 m/s
    assert fast.position_at(2.5).x == pytest.approx(5.0)


def test_walk_loops() -> None:
    walk = square_walk()
    assert walk.position_at(0.0) == walk.position_at(walk.cycle_s)
    assert walk.position_at(15.0) == walk.position_at(walk.cycle_s + 15.0)


def test_single_waypoint_is_stationary() -> None:
    walk = Walk(waypoints=[Waypoint(Vec2(1.0, 2.0), 5.0, "here")])
    assert walk.position_at(1234.5) == Vec2(1.0, 2.0)


def test_rejects_empty_or_stopped_walks() -> None:
    with pytest.raises(ValueError):
        Walk(waypoints=[])
    with pytest.raises(ValueError):
        square_walk(speed=0.0)


def test_room_waypoints_resolve_to_room_centres() -> None:
    house = load_house()
    walk = walk_from_dict({"speed": 1.0, "waypoints": [{"room": "kitchen", "dwell": 5}]}, house)
    assert walk.waypoints[0].position == house.rooms["kitchen"].centre


def test_default_walk_visits_every_room_including_the_blind_one() -> None:
    house = load_house()
    walk = load_walk(house)
    visited = {house.room_at(w.position) for w in walk.waypoints}
    assert visited == set(house.rooms)


def test_default_walk_passes_through_the_rooms_it_names() -> None:
    house = load_house()
    walk = load_walk(house)
    seen = set()
    t = 0.0
    while t < walk.cycle_s:
        room = house.room_at(walk.position_at(t))
        if room:
            seen.add(room)
        t += 0.5
    assert {"living", "kitchen", "hall", "bedroom", "office", "garage"} <= seen


def test_rejects_a_walk_with_no_duration() -> None:
    """Coincident waypoints with no dwell would make cycle_s zero and every
    position lookup a modulo by zero."""
    here = Vec2(3.0, 3.0)
    with pytest.raises(ValueError, match="zero duration"):
        Walk(waypoints=[Waypoint(here, 0.0, "a"), Waypoint(here, 0.0, "b")])

    # A dwell alone is enough to make it well defined.
    walk = Walk(waypoints=[Waypoint(here, 5.0, "a"), Waypoint(here, 0.0, "b")])
    assert walk.cycle_s == pytest.approx(5.0)
    assert walk.position_at(123.0) == here
