package ca.mcmaster.mt4sg.evaluation;

import java.util.ArrayList;
import java.util.List;

import ca.mcmaster.mt4sg.models.scenegraph.Distance;
import ca.mcmaster.mt4sg.models.scenegraph.Edge;
import ca.mcmaster.mt4sg.models.scenegraph.Lane;
import ca.mcmaster.mt4sg.models.scenegraph.LaneSegment;
import ca.mcmaster.mt4sg.models.scenegraph.LateralRSSViolation;
import ca.mcmaster.mt4sg.models.scenegraph.LongitudinalRSSViolation;
import ca.mcmaster.mt4sg.models.scenegraph.Node;
import ca.mcmaster.mt4sg.models.scenegraph.Road;
import ca.mcmaster.mt4sg.models.scenegraph.SceneGraph;
import ca.mcmaster.mt4sg.models.scenegraph.SceneGraphModelFactory;
import ca.mcmaster.mt4sg.models.scenegraph.SemanticDistanceValue;
import ca.mcmaster.mt4sg.models.scenegraph.SemanticSpatialPositionValue;
import ca.mcmaster.mt4sg.models.scenegraph.SpatialPosition;
import ca.mcmaster.mt4sg.models.scenegraph.Vehicle;
import ca.mcmaster.mt4sg.models.scenegraph.VehicleInLaneSegment;
import ca.mcmaster.mt4sg.queries.SceneGraphHelper;

import org.eclipse.emf.common.util.EList;

/**
 * Keeps the edges of a scene graph up to date with plain nested loops over the EMF model, as a
 * non-incremental baseline for the VIATRA strategies. It uses no query engine, no index, and no
 * data structure beyond the scene graph's own lists.
 *
 * <p>Every tick, it checks the conditions of the patterns in {@code scenegraph.vql} again for
 * every pair of vehicles, and for every vehicle and lane segment. It finds each existing edge by
 * searching the edge list, then creates, relabels, or removes it as the rules in
 * {@link BatchTransformations} do, so an edge whose relation still holds stays in the model. The
 * caller runs it inside a write transaction of the scene graph's editing domain.
 */
final class JavaGraphTraversal {

    private static final SceneGraphModelFactory FACTORY = SceneGraphModelFactory.eINSTANCE;

    private JavaGraphTraversal() {}

    /** Brings the edges of {@code sceneGraph} up to date with its vehicles. */
    static void derive(SceneGraph sceneGraph) {
        EList<Node> nodes = sceneGraph.getNodes();
        EList<Edge> edges = sceneGraph.getEdges();

        // The RSS checks read each vehicle's lane segments from these edges, so they go first.
        for (int k = edges.size() - 1; k >= 0; k--) {
            if (edges.get(k) instanceof VehicleInLaneSegment edge
                    && !isOn((Vehicle) edge.getSource(), (LaneSegment) edge.getTarget())) {
                edges.remove(k);
            }
        }
        for (Node node : nodes) {
            if (!(node instanceof Vehicle vehicle)) continue;
            for (Node other : nodes) {
                if (!(other instanceof Road road)) continue;
                for (Lane lane : road.getLanes()) {
                    for (LaneSegment segment : lane.getLaneSegments()) {
                        if (!isOn(vehicle, segment)) continue;
                        if (find(edges, VehicleInLaneSegment.class, vehicle, segment) == null) {
                            add(edges, FACTORY.createVehicleInLaneSegment(), vehicle, segment);
                        }
                    }
                }
            }
        }

        for (int i = 0; i < nodes.size(); i++) {
            if (!(nodes.get(i) instanceof Vehicle a)) continue;
            for (int j = 0; j < nodes.size(); j++) {
                if (i == j || !(nodes.get(j) instanceof Vehicle b)) continue;
                SemanticSpatialPositionValue sector = sector(a, b);
                SpatialPosition spatial = find(edges, SpatialPosition.class, a, b);
                if (spatial == null) {
                    spatial = FACTORY.createSpatialPosition();
                    spatial.setValue(sector);
                    add(edges, spatial, a, b);
                } else if (spatial.getValue() != sector) {
                    spatial.setValue(sector);
                }

                SemanticDistanceValue band = band(a, b);
                // 1 distance and 1 lateral RSS edge per pair of vehicles, in either direction.
                if (i < j) {
                    Distance distance = findEitherWay(edges, Distance.class, a, b);
                    if (band == null) {
                        if (distance != null) edges.remove(distance);
                    } else if (distance == null) {
                        distance = FACTORY.createDistance();
                        distance.setValue(band);
                        add(edges, distance, a, b);
                    } else if (distance.getValue() != band) {
                        distance.setValue(band);
                    }

                    // Kept while the violation holds in the edge's own direction.
                    LateralRSSViolation lateral =
                            findEitherWay(edges, LateralRSSViolation.class, a, b);
                    if (lateral != null) {
                        Vehicle source = (Vehicle) lateral.getSource();
                        Vehicle target = (Vehicle) lateral.getTarget();
                        if (!isLateralViolation(edges, source, target, band,
                                sector(source, target))) {
                            edges.remove(lateral);
                            lateral = null;
                        }
                    }
                    if (lateral == null) {
                        if (isLateralViolation(edges, a, b, band, sector)) {
                            add(edges, FACTORY.createLateralRSSViolation(), a, b);
                        } else if (isLateralViolation(edges, b, a, band, sector(b, a))) {
                            add(edges, FACTORY.createLateralRSSViolation(), b, a);
                        }
                    }
                }

                boolean violation = isLongitudinalViolation(edges, a, b, band, sector);
                LongitudinalRSSViolation longitudinal =
                        find(edges, LongitudinalRSSViolation.class, a, b);
                if (violation && longitudinal == null) {
                    add(edges, FACTORY.createLongitudinalRSSViolation(), a, b);
                } else if (!violation && longitudinal != null) {
                    edges.remove(longitudinal);
                }
            }
        }
    }

    private static void add(EList<Edge> edges, Edge edge, Node source, Node target) {
        edge.setSource(source);
        edge.setTarget(target);
        edges.add(edge);
    }

    /**
     * Patterns {@code spatialEdgeExists}, {@code vehicleIsInLaneSegmentEdgeExists}, and
     * {@code longitudinalRSSViolationEdgeExists}: the edge of {@code type} from {@code source} to
     * {@code target}, or null.
     */
    private static <T extends Edge> T find(EList<Edge> edges, Class<T> type, Node source,
            Node target) {
        for (Edge edge : edges) {
            if (type.isInstance(edge) && edge.getSource() == source && edge.getTarget() == target) {
                return type.cast(edge);
            }
        }
        return null;
    }

    /**
     * Patterns {@code distanceEdgeExists} and {@code lateralRSSViolationEdgeExists}: the edge of
     * {@code type} between {@code a} and {@code b} in either direction, or null.
     */
    private static <T extends Edge> T findEitherWay(EList<Edge> edges, Class<T> type, Node a,
            Node b) {
        for (Edge edge : edges) {
            if (type.isInstance(edge) && (edge.getSource() == a && edge.getTarget() == b
                    || edge.getSource() == b && edge.getTarget() == a)) {
                return type.cast(edge);
            }
        }
        return null;
    }

    /** Pattern {@code vehicleOnLaneSegment}. */
    private static boolean isOn(Vehicle vehicle, LaneSegment segment) {
        double dx = vehicle.getX() - segment.getX();
        double dy = vehicle.getY() - segment.getY();
        double heading = segment.getHeading();
        double turn = vehicle.getHeading() - heading;
        return Math.abs(dx * Math.cos(heading) + dy * Math.sin(heading)) <= segment.getLength() / 2.0
                && Math.abs(-dx * Math.sin(heading) + dy * Math.cos(heading))
                        <= segment.getWidth() / 2.0
                && Math.abs(Math.atan2(Math.sin(turn), Math.cos(turn))) <= 0.15;
    }

    /** Patterns {@code preCollision} to {@code visibleDistance}, or null beyond 25 m. */
    private static SemanticDistanceValue band(Vehicle a, Vehicle b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double distSq = dx * dx + dy * dy;
        if (distSq <= 16.0) return SemanticDistanceValue.PRE_COLLISION;
        if (distSq <= 49.0) return SemanticDistanceValue.SUPER_NEAR;
        if (distSq <= 100.0) return SemanticDistanceValue.VERY_NEAR;
        if (distSq <= 256.0) return SemanticDistanceValue.NEAR;
        if (distSq <= 625.0) return SemanticDistanceValue.VISIBLE;
        return null;
    }

    /** Patterns {@code angleFromTo} and {@code left} to {@code rearLeft}. */
    private static SemanticSpatialPositionValue sector(Vehicle source, Vehicle target) {
        double bearing = Math.atan2(target.getY() - source.getY(), target.getX() - source.getX());
        double angle = Math.atan2(Math.sin(bearing - source.getHeading()),
                Math.cos(bearing - source.getHeading()));
        if (angle >= -1.963495 && angle < -1.178097) return SemanticSpatialPositionValue.LEFT;
        if (angle >= -1.178097 && angle < -0.392699) return SemanticSpatialPositionValue.FRONT_LEFT;
        if (angle >= -0.392699 && angle <= 0.392699) return SemanticSpatialPositionValue.FRONT;
        if (angle > 0.392699 && angle <= 1.178097) return SemanticSpatialPositionValue.FRONT_RIGHT;
        if (angle > 1.178097 && angle <= 1.963495) return SemanticSpatialPositionValue.RIGHT;
        if (angle > 1.963495 && angle <= 2.748894) return SemanticSpatialPositionValue.REAR_RIGHT;
        if (angle >= -2.748894 && angle < -1.963495) return SemanticSpatialPositionValue.REAR_LEFT;
        return SemanticSpatialPositionValue.REAR;
    }

    /**
     * Patterns {@code vLongitudinal} and {@code vLateral}: the vehicle's speed along or across
     * each lane segment it's on.
     */
    private static List<Double> laneSpeeds(EList<Edge> edges, Vehicle vehicle, boolean along) {
        List<Double> speeds = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge instanceof VehicleInLaneSegment && edge.getSource() == vehicle) {
                double turn = vehicle.getHeading() - ((LaneSegment) edge.getTarget()).getHeading();
                speeds.add(vehicle.getSpeed() * (along ? Math.cos(turn) : Math.sin(turn)));
            }
        }
        return speeds;
    }

    /** Pattern {@code longitudinalRSSViolation}, which needs a distance edge. */
    private static boolean isLongitudinalViolation(EList<Edge> edges, Vehicle a, Vehicle b,
            SemanticDistanceValue band, SemanticSpatialPositionValue sector) {
        if (band == null
                || sector != SemanticSpatialPositionValue.FRONT_LEFT
                        && sector != SemanticSpatialPositionValue.FRONT
                        && sector != SemanticSpatialPositionValue.FRONT_RIGHT) {
            return false;
        }
        List<Double> bSpeeds = laneSpeeds(edges, b, true);
        for (double aSpeed : laneSpeeds(edges, a, true)) {
            for (double bSpeed : bSpeeds) {
                if (SceneGraphHelper.isLongitudinalRSSViolation(Math.max(aSpeed, 0.0),
                        Math.max(bSpeed, 0.0), SceneGraphHelper.getDistanceThreshold(band),
                        a.getLength() / 2.0 + b.getLength() / 2.0)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Pattern {@code lateralRSSViolation}, which needs a distance edge. */
    private static boolean isLateralViolation(EList<Edge> edges, Vehicle a, Vehicle b,
            SemanticDistanceValue band, SemanticSpatialPositionValue sector) {
        boolean left = sector == SemanticSpatialPositionValue.LEFT
                || sector == SemanticSpatialPositionValue.FRONT_LEFT
                || sector == SemanticSpatialPositionValue.REAR_LEFT;
        boolean right = sector == SemanticSpatialPositionValue.FRONT_RIGHT
                || sector == SemanticSpatialPositionValue.RIGHT
                || sector == SemanticSpatialPositionValue.REAR_RIGHT;
        if (band == null || !left && !right) return false;
        List<Double> bSpeeds = laneSpeeds(edges, b, false);
        for (double aSpeed : laneSpeeds(edges, a, false)) {
            for (double bSpeed : bSpeeds) {
                double aToward = left ? Math.max(aSpeed, 0.0) : Math.max(-aSpeed, 0.0);
                double bToward = left ? Math.max(-bSpeed, 0.0) : Math.max(bSpeed, 0.0);
                if (SceneGraphHelper.isLateralRSSViolation(aToward, bToward,
                        SceneGraphHelper.getDistanceThreshold(band),
                        a.getWidth() / 2.0 + b.getWidth() / 2.0)) {
                    return true;
                }
            }
        }
        return false;
    }
}
