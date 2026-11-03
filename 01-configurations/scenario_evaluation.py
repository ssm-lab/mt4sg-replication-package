#!/usr/bin/env python3
"""Plays a CARLA Scenario Editor scenario, with external vehicles.

Reads the town and every vehicle's spawn and steps from a scenario file of the
CARLA Scenario Editor (schema version 3), so the editor itself isn't needed.
Plays the keep_velocity, stop, and lane_change actions and the timer trigger.
The ego holds its target speed and brakes ego_response_s after the vehicle
ahead of it in its lane starts to stop.

CARLA runs in synchronous mode at scene_rate_hz, so each tick is 1 scene and a
seed always gives the same scenes. Each scene goes through the scene extractor
of the Carla2Ecore publisher into a JSON lines file. With --publish, it also
goes out over ZeroMQ, 1 scene per period of wall time.

The seed places the external vehicles in the lanes the scenario doesn't use and
picks their speeds and models. A summary of the run goes to <out>.meta.json
(without .gz). The run is invalid if an external vehicle didn't spawn, a
scenario vehicle collided, an external vehicle entered a scenario lane, or the
scenario didn't finish within timeout_s.

Typical usage example:

  python scenario_evaluation.py --config evaluation.json --scenario a \\
      --external 10 --seed 3 --out a-10-3.jsonl.gz --publish tcp://*:5556
"""

from __future__ import annotations

import argparse
import gzip
import json
import math
import random
import time
from pathlib import Path

import carla

from publisher import (
    CarlaCollector,
    build_snapshot,
    diff_nodes,
    open_zmq_publisher,
)

MIN_LANE_WIDTH_M = 3.0
MAX_YAW_DRIFT_DEG = 5.0
LANE_STEP_M = 10.0
MAX_STRAIGHT_M = 2000.0
LOOKAHEAD_S = 1.0
MIN_LOOKAHEAD_M = 5.0
SPEED_TOLERANCE_KMH = 0.5
CENTRED_M = 0.3
STOPPED_MPS = 0.1
ACTIONS = ("keep_velocity", "stop", "lane_change")


def speed(actor):
    velocity = actor.get_velocity()
    return math.hypot(velocity.x, velocity.y)


def kmh(action):
    """Returns the speed of a step's action in km/h, or None for a stop."""
    return None if action["type"] == "stop" else action["speed_ms"] * 3.6


def ahead_of(actor, reference):
    """Returns how far actor is ahead of reference, along its heading."""
    transform = reference.get_transform()
    forward = transform.get_forward_vector()
    delta = actor.get_location() - transform.location
    return delta.x * forward.x + delta.y * forward.y


def off_centre(actor, lane):
    """Returns how far actor is from the centre line of lane, in m."""
    location = actor.get_location()
    centre = lane.transform.location
    return math.hypot(location.x - centre.x, location.y - centre.y)


def lateral(location, transform):
    """Returns how far location is right of transform, in m."""
    right = transform.get_right_vector()
    delta = location - transform.location
    return delta.x * right.x + delta.y * right.y


def yaw_drift(waypoint, base_yaw):
    return abs(
        (waypoint.transform.rotation.yaw - base_yaw + 180.0) % 360.0 - 180.0
    )


def along(waypoint, offset):
    """Returns the waypoint offset m along waypoint's lane, or None.

    Where the lane splits, takes the branch closest to its heading.
    """
    if abs(offset) < 0.1:
        return waypoint
    found = waypoint.next(offset) if offset > 0 else waypoint.previous(-offset)
    base = waypoint.transform.rotation.yaw
    return min(found, key=lambda w: yaw_drift(w, base)) if found else None


def straight_extent(lane, step):
    """Returns how far lane stays straight and wide in the direction of step."""
    base = lane.transform.rotation.yaw
    cursor = lane
    travelled = 0.0
    while travelled < MAX_STRAIGHT_M:
        cursor = along(cursor, step)
        if (
            cursor is None
            or yaw_drift(cursor, base) > MAX_YAW_DRIFT_DEG
            or cursor.lane_width < MIN_LANE_WIDTH_M
            or cursor.lane_type != carla.LaneType.Driving
        ):
            break
        travelled += abs(step)
    return travelled


def carriageway(waypoint):
    """Returns the driving lanes beside waypoint that run in its direction."""
    lanes = [waypoint]
    for step in (lambda w: w.get_left_lane(), lambda w: w.get_right_lane()):
        side = step(waypoint)
        while (
            side is not None
            and side.lane_type == carla.LaneType.Driving
            and side.lane_id * waypoint.lane_id > 0
        ):
            lanes.append(side)
            side = step(side)
    return lanes


def beside_lane(waypoint, direction):
    """Returns the lane on the left or right of waypoint."""
    if direction == "left":
        return waypoint.get_left_lane()
    return waypoint.get_right_lane()


def load_scenario(path):
    """Returns a CARLA Scenario Editor scenario.

    Raises:
        ValueError: The file isn't schema version 3, or it has a step this
            script doesn't play.
    """
    scenario = json.loads(Path(path).read_text(encoding="utf-8"))
    if scenario.get("version") != 3:
        raise ValueError(
            f"{path}: not a CARLA Scenario Editor version 3 scenario"
        )
    for vehicle in scenario["vehicles"]:
        for step in vehicle["steps"]:
            until = step.get("until")
            if (
                step["action"]["type"] not in ACTIONS
                or step.get("wait_for")
                or (until and until["type"] != "timer")
            ):
                raise ValueError(f"{path}: unsupported step {step}")
    return scenario


def pose(world_map, spawn):
    """Returns the transform of a scenario spawn, 0.5 m above the road."""
    x, y = spawn["point"]
    road = world_map.get_waypoint(
        carla.Location(x, y, 0.0),
        project_to_road=True,
        lane_type=carla.LaneType.Driving,
    )
    location = carla.Location(x, y, road.transform.location.z + 0.5)
    return carla.Transform(location, carla.Rotation(yaw=spawn["yaw_deg"]))


def spawn(world, blueprint, name, transform):
    blueprint.set_attribute("role_name", name)
    return world.try_spawn_actor(blueprint, transform)


def spawn_externals(world, lanes, scenario_lanes, window, count, config, rng):
    """Spawns up to count external vehicles in lanes.

    Each starts at a random offset from the ego within window, a pair (behind,
    ahead) in m, at least external_min_gap_m from the others in its lane, with
    a random model. Each lane gets 1 speed within external_speed_spread of the
    ego's target speed.

    Returns:
        A list of (actor, speed in km/h) for the external vehicles that spawned.
    """
    blueprints = [
        bp
        for bp in world.get_blueprint_library().filter("vehicle.*")
        if int(bp.get_attribute("number_of_wheels")) == 4
    ]
    spread = config["external_speed_spread"]
    lane_kmh = [
        config["ego_kmh"] * (1.0 + rng.uniform(-spread, spread)) for _ in lanes
    ]
    taken = {index: [] for index in range(len(lanes))}
    externals = []
    for _ in range(200 * count):
        if len(externals) == count:
            break
        index = rng.randrange(len(lanes))
        offset = rng.uniform(-window[0], window[1])
        if any(
            abs(offset - other) < config["external_min_gap_m"]
            for other in taken[index]
        ):
            continue
        waypoint = along(lanes[index], offset)
        if waypoint is None:
            continue
        # Skips a point behind the ego on a lane that merges into another lane
        # before it reaches the ego.
        back = along(waypoint, -offset) if offset < 0 else lanes[index]
        if (
            back is None
            or back.transform.location.distance(lanes[index].transform.location)
            > 1.0
        ):
            continue
        # Skips a point that landed in a scenario lane, which happens where a
        # road section ends.
        beside = [along(lane, offset) for lane in scenario_lanes]
        if any(
            other is not None
            and other.transform.location.distance(waypoint.transform.location)
            < 2.0
            for other in beside
        ):
            continue
        transform = waypoint.transform
        transform.location.z += 0.5
        actor = spawn(
            world, rng.choice(blueprints), f"ext-{len(externals)}", transform
        )
        if actor is not None:
            taken[index].append(offset)
            externals.append((actor, lane_kmh[index]))
    return externals


def velocity(actor, speed_kmh):
    """Returns the command that moves actor at speed_kmh along its heading."""
    forward = actor.get_transform().get_forward_vector()
    mps = speed_kmh / 3.6
    return carla.command.ApplyTargetVelocity(
        actor.id,
        carla.Vector3D(forward.x * mps, forward.y * mps, forward.z * mps),
    )


class Steering:
    """Steers 1 vehicle along a lane by pure pursuit (Coulter, 1992).

    The vehicle aims at the lane's point LOOKAHEAD_S of travel ahead and turns
    its front wheels onto the circle through that point.
    """

    def __init__(self, actor):
        wheels = actor.get_physics_control().wheels
        # Wheel positions are in centimetres, front left first, rear left third.
        self._wheelbase = wheels[0].position.distance(wheels[2].position) / 100
        self._max_angle = math.radians(wheels[0].max_steer_angle)
        self._actor = actor

    def command(self, lane):
        """Returns the steering value toward lane, a waypoint beside actor."""
        transform = self._actor.get_transform()
        lookahead = max(MIN_LOOKAHEAD_M, LOOKAHEAD_S * speed(self._actor))
        target = along(lane, lookahead).transform.location
        forward = transform.get_forward_vector()
        delta = target - transform.location
        alpha = math.atan2(
            forward.x * delta.y - forward.y * delta.x,
            forward.x * delta.x + forward.y * delta.y,
        )
        distance = math.hypot(delta.x, delta.y)
        angle = math.atan(2.0 * self._wheelbase * math.sin(alpha) / distance)
        return max(-1.0, min(1.0, angle / self._max_angle))


class Steps:
    """Plays 1 vehicle's steps from the scenario file.

    Attributes:
        actor: The vehicle.
    """

    def __init__(self, actor, steps):
        self.actor = actor
        self._steps = steps
        self._index = 0
        self._started = 0.0
        self._driven = 0.0
        self._target = None
        self._in_target = None

    @property
    def action(self):
        return self._steps[self._index]["action"]

    @property
    def last(self):
        return self._index == len(self._steps) - 1

    @property
    def first_kmh(self):
        return kmh(self._steps[0]["action"])

    def lane(self, world_map):
        """Returns the waypoint of the lane the vehicle steers along now."""
        own = world_map.get_waypoint(self.actor.get_location())
        if self.action["type"] != "lane_change":
            return own
        if self._target is None:
            target = beside_lane(own, self.action.get("direction", "right"))
            self._target = (target.road_id, target.lane_id)
        if (own.road_id, own.lane_id) == self._target:
            return own
        return beside_lane(own, self.action.get("direction", "right"))

    def advance(self, elapsed, dt, world_map):
        """Moves to the next step when this one ends. Returns whether it did."""
        self._driven += speed(self.actor) * dt
        if self.last or not self._ended(elapsed, world_map):
            return False
        self._index += 1
        self._started = elapsed
        self._driven = 0.0
        self._target = None
        self._in_target = None
        return True

    def _ended(self, elapsed, world_map):
        until = self._steps[self._index].get("until")
        if until and elapsed - self._started >= until["duration_s"]:
            return True
        if self.action["type"] != "lane_change" or self._target is None:
            return False
        own = world_map.get_waypoint(self.actor.get_location())
        if (own.road_id, own.lane_id) != self._target:
            return False
        if self._in_target is None:
            self._in_target = self._driven
        return off_centre(
            self.actor, own
        ) < CENTRED_M and self._driven - self._in_target >= self.action.get(
            "distance_other_lane_m", 0.0
        )


class Scenario:
    """Plays a CARLA Scenario Editor scenario and records how it went.

    Attributes:
        event_time: Seconds into the run when a vehicle first moved to its next
            step, or None.
        settled_time: Seconds into the run when every vehicle was on its last
            step and stopped, or centred in its lane at its speed, or None.
        at_event: Each scenario vehicle's speed in km/h at the event, or None.
        intruders: Names of the external vehicles that entered a scenario lane.
    """

    def __init__(self, client, world_map, config, ego, steps, externals):
        """Initializes the scenario.

        Args:
            client: The CARLA client.
            world_map: The town's map.
            config: The evaluation configuration.
            ego: The ego vehicle.
            steps: A Steps for every other scenario vehicle.
            externals: (actor, speed in km/h) for each external vehicle.
        """
        self._client = client
        self._map = world_map
        self._config = config
        self._ego = ego
        self._steps = steps
        self._externals = [actor for actor, _ in externals]
        self._external_kmh = {a.id: speed_kmh for a, speed_kmh in externals}
        self._scenario_vehicles = [ego] + [s.actor for s in steps]
        self._steering = {
            v.id: Steering(v) for v in self._scenario_vehicles + self._externals
        }
        self._ego_brakes_at = None
        self._elapsed = 0.0
        self.event_time = None
        self.settled_time = None
        self.at_event = None
        self.intruders = set()

    def step(self, elapsed):
        """Sends this scene's controls, elapsed seconds into the run."""
        dt = elapsed - self._elapsed
        self._elapsed = elapsed
        for vehicle_steps in self._steps:
            if vehicle_steps.advance(elapsed, dt, self._map):
                if self.event_time is None:
                    self.event_time = elapsed
                    self.at_event = self._measure()
                self._watch_for_braking(vehicle_steps, elapsed)
        ego_lane = self._map.get_waypoint(self._ego.get_location())
        ego_kmh = self._config["ego_kmh"]
        if self._ego_brakes_at is not None and elapsed >= self._ego_brakes_at:
            ego_kmh = None
        commands = self._drive(self._ego, ego_lane, ego_kmh)
        for vehicle_steps in self._steps:
            commands += self._drive(
                vehicle_steps.actor,
                vehicle_steps.lane(self._map),
                kmh(vehicle_steps.action),
                vehicle_steps.action.get("brake", 1.0),
            )
        for actor in self._externals:
            lane = self._map.get_waypoint(actor.get_location())
            commands += self._drive(actor, lane, self._external_kmh[actor.id])
        # 1 synchronous batch, since a single apply_control call can reach the
        # server a tick late.
        self._client.apply_batch_sync(commands, False)
        self._track_settling(elapsed)
        self._track_intruders()

    def _drive(self, actor, lane, speed_kmh, brake=1.0):
        """Returns the commands that hold actor at speed_kmh, or brake."""
        control = carla.VehicleControl(
            throttle=0.0,
            brake=brake if speed_kmh is None else 0.0,
            steer=self._steering[actor.id].command(lane),
        )
        commands = [carla.command.ApplyVehicleControl(actor.id, control)]
        if speed_kmh is not None:
            commands.append(velocity(actor, speed_kmh))
        return commands

    def _watch_for_braking(self, vehicle_steps, elapsed):
        """Sets when the ego brakes, once the vehicle ahead of it stops."""
        if vehicle_steps.action["type"] != "stop" or self._ego_brakes_at:
            return
        ego_lane = self._map.get_waypoint(self._ego.get_location())
        lane = self._map.get_waypoint(vehicle_steps.actor.get_location())
        if (lane.road_id, lane.lane_id) == (
            ego_lane.road_id,
            ego_lane.lane_id,
        ) and ahead_of(vehicle_steps.actor, self._ego) > 0:
            self._ego_brakes_at = elapsed + self._config["ego_response_s"]

    def _measure(self):
        values = {"ego_kmh": round(speed(self._ego) * 3.6, 2)}
        for vehicle_steps in self._steps:
            name = vehicle_steps.actor.attributes["role_name"]
            values[f"{name}_kmh"] = round(speed(vehicle_steps.actor) * 3.6, 2)
        return values

    def _steady(self, vehicle, speed_kmh):
        if speed(vehicle) < STOPPED_MPS:
            return True
        lane = self._map.get_waypoint(vehicle.get_location())
        return (
            speed_kmh is not None
            and off_centre(vehicle, lane) < CENTRED_M
            and abs(speed(vehicle) * 3.6 - speed_kmh) <= SPEED_TOLERANCE_KMH
        )

    def _track_settling(self, elapsed):
        if self.event_time is None:
            return
        ego_kmh = None if self._ego_brakes_at else self._config["ego_kmh"]
        settled = self._steady(self._ego, ego_kmh) and all(
            s.last and self._steady(s.actor, kmh(s.action)) for s in self._steps
        )
        if not settled:
            self.settled_time = None
        elif self.settled_time is None:
            self.settled_time = elapsed

    def _track_intruders(self):
        """Records the external vehicles that are in a scenario lane."""
        lanes = {}
        for vehicle in self._scenario_vehicles:
            lane = self._map.get_waypoint(vehicle.get_location())
            lanes.setdefault((lane.road_id, lane.lane_id), (vehicle, lane))
        for actor in self._externals:
            for reference, lane in lanes.values():
                beside = along(lane, ahead_of(actor, reference))
                if beside is not None and off_centre(actor, beside) < (
                    beside.lane_width / 2
                ):
                    self.intruders.add(actor.attributes["role_name"])

    def valid(self):
        """Returns whether the scenario played out as its file specifies."""
        if self.settled_time is None or self.at_event is None:
            return False
        if self.intruders:
            return False
        wanted = {"ego_kmh": self._config["ego_kmh"]}
        for vehicle_steps in self._steps:
            name = vehicle_steps.actor.attributes["role_name"]
            if vehicle_steps.first_kmh is not None:
                wanted[f"{name}_kmh"] = vehicle_steps.first_kmh
        return all(
            abs(self.at_event[key] - speed_kmh) <= SPEED_TOLERANCE_KMH
            for key, speed_kmh in wanted.items()
        )


def play(world, scenario, collector, out_path, config, publisher=None):
    """Ticks the world until the scenario settles or times out.

    Writes every scene to out_path, and with a publisher also sends it, 1 scene
    per period of wall time.

    Returns:
        The number of scenes.
    """
    period = 1.0 / config["scene_rate_hz"]
    previous = {}
    start = world.get_snapshot().timestamp.elapsed_seconds
    tick = 0
    next_due = None
    opener = gzip.open if out_path.endswith(".gz") else open
    with opener(out_path, "wt", encoding="utf-8") as out:
        while True:
            world.tick()
            tick += 1
            elapsed = world.get_snapshot().timestamp.elapsed_seconds - start
            current = {
                node.external_id: node.to_dict() for node in collector.collect()
            }
            changes = diff_nodes(previous, current)
            previous = current
            if next_due is not None:
                time.sleep(max(0.0, next_due - time.perf_counter()))
            message = json.dumps(
                build_snapshot(
                    changes["added"]
                    + [update["after"] for update in changes["updated"]],
                    [node["id"] for node in changes["removed"]],
                    tick,
                ),
                separators=(",", ":"),
            )
            if publisher is not None:
                publisher.send_string(message)
                # The schedule starts after the first scene, which holds the
                # map and is slow to build. A late scene shifts the schedule.
                now = time.perf_counter()
                next_due = (
                    now + period
                    if next_due is None
                    else max(next_due + period, now)
                )
            out.write(message + "\n")
            scenario.step(elapsed)
            settled = scenario.settled_time
            if (
                settled is not None and elapsed - settled >= config["context_s"]
            ) or elapsed >= config["timeout_s"]:
                return tick


def set_synchronous(world, rate_hz):
    """Switches the world to synchronous mode at rate_hz, or back with None."""
    settings = world.get_settings()
    settings.synchronous_mode = rate_hz is not None
    settings.fixed_delta_seconds = 1.0 / rate_hz if rate_hz else None
    world.apply_settings(settings)


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config", required=True, help="Evaluation configuration JSON"
    )
    parser.add_argument(
        "--scenario",
        required=True,
        help="Key of the scenario file in the configuration's scenarios",
    )
    parser.add_argument(
        "--external",
        type=int,
        required=True,
        help="Number of external vehicles",
    )
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument(
        "--out",
        required=True,
        help="JSON lines file for the scenes, gzipped if it ends in .gz",
    )
    parser.add_argument(
        "--publish",
        default=None,
        help="ZeroMQ PUB endpoint to send each scene to, e.g. tcp://*:5556",
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=2000)
    return parser.parse_args()


def scenario_lanes(scenario, lanes, ego_transform):
    """Returns the indices into lanes the scenario's vehicles start in or enter.

    Args:
        scenario: The scenario, as loaded from its file.
        lanes: The ego's carriageway at its spawn, ordered left to right.
        ego_transform: The ego's spawn transform.
    """
    offsets = [
        lateral(lane.transform.location, ego_transform) for lane in lanes
    ]
    used = set()
    for spawn_pose, steps in [(scenario["ego"]["spawn"], [])] + [
        (v["spawn"], v["steps"]) for v in scenario["vehicles"]
    ]:
        offset = lateral(
            carla.Location(*spawn_pose["point"], 0.0), ego_transform
        )
        index = min(range(len(lanes)), key=lambda i: abs(offsets[i] - offset))
        used.add(index)
        for step in steps:
            if step["action"]["type"] == "lane_change":
                left = step["action"].get("direction", "right") == "left"
                index += -1 if left else 1
                used.add(index)
    return used


def straight_window(scenario, lanes, ego_transform, config):
    """Returns how far behind and ahead of the ego external vehicles can start.

    Behind, as far as every lane stays straight. Ahead, as far as still keeps
    the fastest external vehicle on the straight for a run of timeout_s.

    Raises:
        SystemExit: A scenario vehicle would leave the straight.
    """
    behind = min(straight_extent(lane, -LANE_STEP_M) for lane in lanes)
    ahead = min(straight_extent(lane, LANE_STEP_M) for lane in lanes)
    fastest_kmh = config["ego_kmh"] * (1.0 + config["external_speed_spread"])
    travel = fastest_kmh / 3.6 * config["timeout_s"]
    for vehicle in scenario["vehicles"]:
        top = max(s["action"].get("speed_ms", 0.0) for s in vehicle["steps"])
        start = carla.Location(*vehicle["spawn"]["point"], 0.0)
        offset = start.distance(ego_transform.location)
        if offset + top * config["timeout_s"] > ahead:
            raise SystemExit(f"{vehicle['id']} would leave the straight")
    if ahead - travel <= 0:
        raise SystemExit("The straight is too short for a whole run")
    return behind, ahead - travel


def setup(client, args, config, rng):
    """Loads the town, spawns every vehicle, and builds the Scenario.

    Returns:
        A tuple (world, Scenario, every spawned actor, number of scenario
        vehicles, run summary).
    """
    path = Path(args.config).parent / config["scenarios"][args.scenario]
    scenario = load_scenario(path)
    config["ego_kmh"] = scenario["ego"]["target_speed_ms"] * 3.6
    world = client.get_world()
    if not world.get_map().name.endswith(scenario["map"]):
        world = client.load_world(scenario["map"])
    set_synchronous(world, config["scene_rate_hz"])
    for vehicle in world.get_actors().filter("vehicle.*"):
        vehicle.destroy()
    world.tick()

    world_map = world.get_map()
    library = world.get_blueprint_library()
    ego_transform = pose(world_map, scenario["ego"]["spawn"])
    ego = spawn(
        world, library.find(scenario["ego"]["model"]), "ego", ego_transform
    )
    others = [
        spawn(
            world,
            library.find(vehicle["model"]),
            vehicle["id"],
            pose(world_map, vehicle["spawn"]),
        )
        for vehicle in scenario["vehicles"]
    ]
    if ego is None or None in others:
        raise SystemExit("A scenario vehicle failed to spawn")
    ego_lane = world_map.get_waypoint(ego_transform.location)
    lanes = sorted(
        carriageway(ego_lane),
        key=lambda lane: lateral(lane.transform.location, ego_transform),
    )
    used = scenario_lanes(scenario, lanes, ego_transform)
    window = straight_window(scenario, lanes, ego_transform, config)
    free = [lane for i, lane in enumerate(lanes) if i not in used]
    taken = [lane for i, lane in enumerate(lanes) if i in used]
    externals = spawn_externals(
        world, free, taken, window, args.external, config, rng
    )
    world.tick()
    steps = [
        Steps(actor, vehicle["steps"])
        for actor, vehicle in zip(others, scenario["vehicles"])
    ]
    played = Scenario(client, world_map, config, ego, steps, externals)
    # Sets every vehicle moving at its speed before the first scene.
    played.step(0.0)
    summary = {
        "scenario": args.scenario,
        "scenario_file": path.name,
        "seed": args.seed,
        "external_requested": args.external,
        "external": len(externals),
        "road": ego_lane.road_id,
        "lane": ego_lane.lane_id,
        "lanes": len(lanes),
        "free_lanes": len(free),
        "external_window_m": [round(-window[0], 1), round(window[1], 1)],
    }
    actors = [ego] + others + [actor for actor, _ in externals]
    return world, played, actors, len(others) + 1, summary


def main():
    args = parse_args()
    with open(args.config, encoding="utf-8") as f:
        config = json.load(f)
    rng = random.Random(args.seed)
    client = carla.Client(args.host, args.port)
    client.set_timeout(60.0)
    world, scenario, actors, scenario_count, summary = setup(
        client, args, config, rng
    )

    collisions = []
    sensors = []
    for actor in actors[:scenario_count]:
        sensor = world.spawn_actor(
            world.get_blueprint_library().find("sensor.other.collision"),
            carla.Transform(),
            attach_to=actor,
        )
        sensor.listen(
            lambda event: collisions.append(
                f"{event.actor.attributes.get('role_name')}:"
                f"{event.other_actor.type_id}"
            )
        )
        sensors.append(sensor)
    collector = CarlaCollector(args.host, args.port, 60.0)
    # Reads through the client that ticks. A second client's snapshot can lag
    # a tick behind.
    collector.world = world
    context = publisher = None
    if args.publish:
        context, publisher = open_zmq_publisher(args.publish)
        # Gives the subscriber time to connect, since PUB drops what it sends
        # before then.
        time.sleep(2.0)
    valid = False
    try:
        scenes = play(world, scenario, collector, args.out, config, publisher)
        valid = (
            scenario.valid()
            and not collisions
            and summary["external"] == args.external
        )
        summary.update(
            scenes=scenes,
            event_time=scenario.event_time,
            settled_time=scenario.settled_time,
            at_event=scenario.at_event,
            intruders=sorted(scenario.intruders),
            collisions=sorted(set(collisions)),
            valid=valid,
        )
        with open(
            args.out.removesuffix(".gz") + ".meta.json", "w", encoding="utf-8"
        ) as f:
            json.dump(summary, f)
        print(f"[scenario] {json.dumps(summary)}", flush=True)
    finally:
        if publisher is not None:
            # Gives scenes still queued for a slow subscriber 5 s to go out.
            publisher.close(linger=5000)
            context.term()
        for sensor in sensors:
            sensor.stop()
            sensor.destroy()
        for actor in actors:
            actor.destroy()
        world.tick()
        set_synchronous(world, None)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
