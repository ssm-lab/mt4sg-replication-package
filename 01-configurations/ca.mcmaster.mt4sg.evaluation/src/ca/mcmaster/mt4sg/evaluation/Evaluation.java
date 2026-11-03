package ca.mcmaster.mt4sg.evaluation;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.mcmaster.carla2ecore.JsonModelUpdater;
import ca.mcmaster.carla2ecore.ZmqSubscriber;
import ca.mcmaster.mt4sg.core.runner.Transformations;
import ca.mcmaster.mt4sg.models.scenegraph.Distance;
import ca.mcmaster.mt4sg.models.scenegraph.Edge;
import ca.mcmaster.mt4sg.models.scenegraph.LateralRSSViolation;
import ca.mcmaster.mt4sg.models.scenegraph.LongitudinalRSSViolation;
import ca.mcmaster.mt4sg.models.scenegraph.Node;
import ca.mcmaster.mt4sg.models.scenegraph.SceneGraph;
import ca.mcmaster.mt4sg.models.scenegraph.SceneGraphModelFactory;
import ca.mcmaster.mt4sg.models.scenegraph.SpatialPosition;
import ca.mcmaster.mt4sg.models.scenegraph.Vehicle;
import ca.mcmaster.mt4sg.models.scenegraph.VehicleInLaneSegment;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;

/**
 * Runs the analysis pipeline on live CARLA snapshots with 1 of 4 strategies, and writes the time
 * of each stage for every snapshot to a CSV file.
 *
 * <p>The incremental strategy (event driven with updates) keeps 1 scene graph and 1 query engine
 * for the whole run, applies each snapshot inside {@code delayUpdatePropagation}, and lets
 * VIATRA's scheduler fire the rules once the delayed update reaches the matches. The 2 batch
 * strategies keep 1 VIATRA batch transformation ({@link BatchTransformations}) with its query
 * engine for the whole run, apply each tick's changes inside {@code delayUpdatePropagation} too,
 * and fire its rules with {@code fireWhilePossible}. The batch-update strategy (batch with
 * updates) applies each snapshot to 1 scene graph for the whole run. The batch strategy (batch
 * with reconstruction) builds a new scene graph from the full snapshot on every tick and puts it
 * in place of the old one. The java-traversal strategy (Java graph traversal), the baseline
 * without VIATRA, applies each snapshot to 1 scene graph for the whole run and brings its edges up
 * to date with plain loops ({@link JavaGraphTraversal}) on every tick.
 *
 * <p>The stages are parsing, the model update, the query update, and the transformations. For
 * batch, the model update also includes replacing the old scene graph. For the VIATRA strategies,
 * the query update is the delayed update reaching the matches.
 *
 * <p>Usage:
 * {@code Evaluation <incremental|batch|batch-update|java-traversal> <endpoint> <out.csv>}, with the
 * ZeroMQ endpoint of the Carla2Ecore publisher, such as {@code tcp://127.0.0.1:5556}.
 * Stops {@value #IDLE_MS} ms after the last snapshot.
 */
public class Evaluation {

    /** How long the run waits after the last snapshot before it ends (ms). */
    private static final long IDLE_MS = 5000;

    /** How long the run waits for the first snapshot (ms). */
    private static final long FIRST_SNAPSHOT_TIMEOUT_MS = 300_000;

    /** The seconds at the start of a run the printed averages leave out, as in analyze.py. */
    private static final double WARMUP_S = 2;

    // JsonModelUpdater warns about every attribute Road and Lane don't have, which batch hits on
    // every tick. Held in a field, since java.util.logging forgets a collected logger's level.
    private static final java.util.logging.Logger UPDATER_LOG =
            java.util.logging.Logger.getLogger(JsonModelUpdater.class.getName());

    private static final Pattern NODE = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern NODE_ID = Pattern.compile("\"id\":\"([^\"]*)\"");
    private static final Pattern DELETED = Pattern.compile("\"deleted\":true");
    private static final Pattern TICK = Pattern.compile("\"tick\":(\\d+)");
    private static final Pattern TIMESTAMP = Pattern.compile("\"timestamp\":\"([^\"]+)\"");

    private static final String HEADER = "tick,sent_ms,received_ms,parse_ms,update_ms,query_ms,"
            + "transformation_ms,total_ms,vertices,vehicles,edges,distance,spatial,lane,"
            + "rss_longitudinal,rss_lateral";

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, Mode> modes = Map.of("incremental", Mode.INCREMENTAL, "batch", Mode.BATCH,
                "batch-update", Mode.BATCH_UPDATE, "java-traversal", Mode.JAVA_TRAVERSAL);
        if (args.length != 3 || !modes.containsKey(args[0])) {
            System.err.println("Usage: Evaluation <incremental|batch|batch-update|java-traversal>"
                    + " <endpoint> <out.csv>");
            System.exit(2);
        }
        Logger.getRootLogger().setLevel(Level.WARN);
        Logger.getLogger("ca.mcmaster.mt4sg").setLevel(Level.WARN);
        UPDATER_LOG.setLevel(java.util.logging.Level.SEVERE);

        Strategy strategy = new Strategy(modes.get(args[0]));
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[2])))) {
            out.println(HEADER);
            runLive(strategy, args[1], out);
        }
        strategy.dispose();
        printAverages(Path.of(args[2]));
    }

    /** Prints the average time of each stage per snapshot in {@code csv}, after the warmup. */
    private static void printAverages(Path csv) throws IOException {
        List<String[]> rows = Files.readAllLines(csv).stream().skip(1)
                .map(line -> line.split(",")).toList();
        if (rows.size() < 2) return;
        String[] first = rows.get(0);
        String[] last = rows.get(rows.size() - 1);
        long rateHz = Math.round((Double.parseDouble(last[0]) - Double.parseDouble(first[0]))
                * 1e3 / (Double.parseDouble(last[1]) - Double.parseDouble(first[1])));
        long warmup = Math.round(WARMUP_S * rateHz);
        List<String[]> steady = rows.stream().filter(r -> Long.parseLong(r[0]) > warmup).toList();
        System.out.printf("%d snapshots at %d Hz, averages of the %d after tick %d:%n",
                rows.size(), rateHz, steady.size(), warmup);
        String[] columns = HEADER.split(",");
        for (int c = 3; c <= 7; c++) {
            int column = c;
            System.out.printf("  %-18s %8.2f ms%n", columns[c], steady.stream()
                    .mapToDouble(r -> Double.parseDouble(r[column])).average().orElse(0));
        }
    }

    /** Returns {@code instant} in epoch milliseconds. */
    private static double epochMillis(Instant instant) {
        return instant.getEpochSecond() * 1e3 + instant.getNano() / 1e6;
    }

    /**
     * Processes snapshots from {@code endpoint} until none has arrived for {@link #IDLE_MS}. A
     * snapshot still in progress, such as one paused at a breakpoint, keeps the run going.
     */
    private static void runLive(Strategy strategy, String endpoint, PrintWriter out)
            throws InterruptedException {
        AtomicInteger snapshots = new AtomicInteger();
        AtomicLong lastSnapshot = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean processing = new AtomicBoolean();
        ZmqSubscriber subscriber = new ZmqSubscriber(endpoint, json -> {
            processing.set(true);
            try {
                out.println(strategy.process(json));
                snapshots.incrementAndGet();
            } finally {
                lastSnapshot.set(System.currentTimeMillis());
                processing.set(false);
            }
        });
        subscriber.start();
        long started = System.currentTimeMillis();
        while (processing.get() || (snapshots.get() == 0
                ? System.currentTimeMillis() - started < FIRST_SNAPSHOT_TIMEOUT_MS
                : System.currentTimeMillis() - lastSnapshot.get() < IDLE_MS)) {
            Thread.sleep(200);
        }
        subscriber.stop();
    }

    /** How the scene graph and the queries' matches are kept up to date. */
    private enum Mode {
        /** 1 scene graph and 1 query engine, with the rules fired by VIATRA's scheduler. */
        INCREMENTAL,
        /** A new scene graph every tick and 1 query engine, with the rules fired explicitly. */
        BATCH,
        /** 1 scene graph and 1 query engine, with the rules fired explicitly. */
        BATCH_UPDATE,
        /** 1 scene graph, with its edges brought up to date by plain loops every tick. */
        JAVA_TRAVERSAL
    }

    /** Keeps a scene graph up to date with the snapshots, by 1 strategy. */
    private static class Strategy {

        private final Mode mode;

        /** The full snapshot for batch: every node's JSON object by id. */
        private final Map<String, String> nodesById = new LinkedHashMap<>();

        private final Pipeline pipeline = new Pipeline();

        Strategy(Mode mode) {
            this.mode = mode;
            if (mode == Mode.INCREMENTAL) {
                pipeline.transformations = new Transformations(pipeline.resource);
                pipeline.transformations.startExecution();
            } else if (mode != Mode.JAVA_TRAVERSAL) {
                pipeline.batchTransformations = new BatchTransformations(pipeline.resource);
            }
        }

        /** Applies 1 snapshot to the scene graph and returns its CSV row of times and counts. */
        String process(String json) {
            String snapshot = mode == Mode.BATCH ? fullSnapshot(json) : json;
            Instant received = Instant.now();
            long arrived = System.nanoTime();
            long updated;
            long queried;
            if (mode == Mode.JAVA_TRAVERSAL) {
                pipeline.updater.applyJson(snapshot);
                updated = System.nanoTime();
                queried = updated;
                pipeline.domain.getCommandStack().execute(new RecordingCommand(pipeline.domain) {
                    @Override
                    protected void doExecute() {
                        JavaGraphTraversal.derive(pipeline.sceneGraph);
                    }
                });
            } else if (mode != Mode.INCREMENTAL) {
                long[] end = new long[1];
                pipeline.batchTransformations.delayUpdatePropagation(() -> {
                    if (mode == Mode.BATCH) {
                        pipeline.rebuild(snapshot);
                    } else {
                        pipeline.updater.applyJson(snapshot);
                    }
                    end[0] = System.nanoTime();
                });
                updated = end[0];
                queried = System.nanoTime();
                pipeline.batchTransformations.fireWhilePossible();
            } else {
                long[] end = new long[1];
                pipeline.transformations.delayUpdatePropagation(() -> {
                    pipeline.updater.applyJson(snapshot);
                    end[0] = System.nanoTime();
                });
                updated = end[0];
                queried = System.nanoTime();
                // The scheduler heard every change inside the delay, while the matches were still
                // stale, and nothing tells it when they catch up.
                pipeline.transformations.schedule();
            }
            long transformed = System.nanoTime();
            long parsed = pipeline.updater.getParsedNanos();

            Matcher tick = TICK.matcher(json);
            Matcher sent = TIMESTAMP.matcher(json);
            String row = String.format("%s,%s,%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%s",
                    tick.find() ? tick.group(1) : "",
                    sent.find() ? String.format("%.3f",
                            epochMillis(OffsetDateTime.parse(sent.group(1)).toInstant())) : "",
                    epochMillis(received),
                    (parsed - arrived) / 1e6, (updated - parsed) / 1e6,
                    (queried - updated) / 1e6,
                    (transformed - queried) / 1e6, (transformed - arrived) / 1e6,
                    pipeline.counts());
            // The command stack keeps every executed command for undo, and with them every tick's
            // changes and, for batch, every tick's old scene graph.
            pipeline.domain.getCommandStack().flush();
            return row;
        }

        /** Applies 1 snapshot to {@link #nodesById} and returns the full snapshot. */
        private String fullSnapshot(String json) {
            Matcher node = NODE.matcher(json);
            while (node.find()) {
                Matcher id = NODE_ID.matcher(node.group());
                if (!id.find()) continue;
                if (DELETED.matcher(node.group()).find()) {
                    nodesById.remove(id.group(1));
                } else {
                    nodesById.put(id.group(1), node.group());
                }
            }
            return "{\"nodes\":[" + String.join(",", nodesById.values()) + "]}";
        }

        void dispose() {
            pipeline.dispose();
        }
    }

    /**
     * An empty scene graph with its updater, set up the way
     * {@link ca.mcmaster.mt4sg.core.runner.Runner} sets it up.
     */
    private static class Pipeline {

        final TransactionalEditingDomain domain;
        final Resource resource;
        SceneGraph sceneGraph = SceneGraphModelFactory.eINSTANCE.createSceneGraph();
        JsonModelUpdater updater = new JsonModelUpdater(sceneGraph);
        Transformations transformations;
        BatchTransformations batchTransformations;

        Pipeline() {
            ResourceSetImpl rs = new ResourceSetImpl();
            rs.getResourceFactoryRegistry().getExtensionToFactoryMap()
                    .put("xmi", new XMIResourceFactoryImpl());
            rs.getResourceFactoryRegistry().getExtensionToFactoryMap()
                    .put(Resource.Factory.Registry.DEFAULT_EXTENSION, new XMIResourceFactoryImpl());
            domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(rs);
            resource = rs.createResource(URI.createURI("dummy:///in-memory.xmi"));
            sceneGraph.setId("CARLA_Live");
            domain.getCommandStack().execute(new RecordingCommand(domain) {
                @Override
                protected void doExecute() {
                    resource.getContents().add(sceneGraph);
                }
            });
        }

        /**
         * Builds a new scene graph from {@code json} outside the resource, so the query engine
         * sees it only once it is complete, and puts it in place of the current one.
         */
        void rebuild(String json) {
            SceneGraph built = SceneGraphModelFactory.eINSTANCE.createSceneGraph();
            built.setId("CARLA_Live");
            updater = new JsonModelUpdater(built);
            updater.applyJson(json);
            sceneGraph = built;
            domain.getCommandStack().execute(new RecordingCommand(domain) {
                @Override
                protected void doExecute() {
                    resource.getContents().set(0, built);
                }
            });
        }

        /** Returns the vertex, vehicle, and edge counts, and the edges by type, as CSV fields. */
        String counts() {
            int vertices = 0;
            int vehicles = 0;
            for (Iterator<EObject> it = sceneGraph.eAllContents(); it.hasNext(); ) {
                EObject o = it.next();
                if (o instanceof Node) vertices++;
                if (o instanceof Vehicle) vehicles++;
            }
            int distance = 0;
            int spatial = 0;
            int lane = 0;
            int rssLongitudinal = 0;
            int rssLateral = 0;
            for (Edge e : sceneGraph.getEdges()) {
                if (e instanceof LongitudinalRSSViolation) rssLongitudinal++;
                else if (e instanceof LateralRSSViolation) rssLateral++;
                else if (e instanceof Distance) distance++;
                else if (e instanceof SpatialPosition) spatial++;
                else if (e instanceof VehicleInLaneSegment) lane++;
            }
            return String.format("%d,%d,%d,%d,%d,%d,%d,%d", vertices, vehicles,
                    sceneGraph.getEdges().size(), distance, spatial, lane, rssLongitudinal,
                    rssLateral);
        }

        /** Disposes the editing domain so the scene graph and its query engine can be collected. */
        void dispose() {
            domain.dispose();
        }
    }
}
