/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.yugabytedb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.yb.client.CdcSdkCheckpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.debezium.config.Configuration;
import io.debezium.connector.yugabytedb.connection.OpId;
import io.debezium.connector.yugabytedb.spi.Snapshotter;
import io.debezium.connector.yugabytedb.tla.TlaStateGraph;

/**
 * Conformance: replays YbExplicitCheckpoint.tla paths (from {@link
 * ExplicitCheckpointConformanceFixture}) through the real explicit checkpoint code:
 * {@link YugabyteDBStreamingChangeEventSource#getExplicitCheckpoint}, {@link
 * YugabyteDBStreamingChangeEventSource#commitOffset}, {@link
 * YugabyteDBConnectorTask#getHigherOffsets} and the split check {@code lastRecordCheckpoint == null
 * || lastRecordCheckpoint.isLesserThanOrEqualTo(explicitCheckpoint)} on real {@link OpId} and
 * {@link CdcSdkCheckpoint} values.
 *
 * <p>The driver plays the server (GetChanges responses, cdc_state, the children's checkpoint after
 * a split) and Kafka Connect (acks, the offset store); handleTabletSplit's change to
 * tabletToExplicitCheckpoint (drop the parent, add the children) is done by the test, because it
 * needs a YBClient. The source and the task are made without their constructors (they need a live
 * schema): the test sets the fields that these methods read.
 */
class ExplicitCheckpointConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static OpId opId(int index) {
        return new OpId(1, index, null, 0, 0);
    }

    @Test
    void javaFollowsTheModelOnEveryPath() throws Exception {
        JsonNode fixture = readFixture();
        assertEquals("none", fixture.get("constants").get("Mutation").asText());
        int split = fixture.get("constants").get("N").asInt() + 1;
        int paths = 0;
        int steps = 0;
        for (JsonNode path : fixture.get("paths")) {
            Driver d = new Driver(split);
            int index = 0;
            for (JsonNode step : path) {
                String where = "path " + paths + " step " + index + " (" + step.get("a").asText() + ")";
                assertEquals(step.get("x"), JSON.valueToTree(d.perform(step)), where);
                index++;
                steps++;
            }
            paths++;
        }
        System.out.printf("conformance %s: %d paths, %d steps%n", ExplicitCheckpointConformanceFixture.FIXTURE, paths, steps);
    }

    private static final class Driver {
        final int split;
        YugabyteDBStreamingChangeEventSource source;
        YugabyteDBConnectorTask task;

        Driver(int split) throws Exception {
            this.split = split;
            start(Map.of("P", 0));
        }

        /** A new task instance that polls from the cdc_state checkpoints (SCES:380-420). */
        void start(Map<String, Integer> checkpoints) throws Exception {
            source = allocate(YugabyteDBStreamingChangeEventSource.class);
            YugabyteDBTaskContext context = allocate(YugabyteDBTaskContext.class);
            set(context, "enableExplicitCheckpointing", true);
            set(context, "taskId", "0");
            set(source, "taskContext", context);
            set(source, "connectorConfig", config());
            set(source, "tabletToExplicitCheckpoint", new ConcurrentHashMap<String, CdcSdkCheckpoint>());
            set(source, "splitTabletsWaitingForCallback", new HashSet<String>());
            checkpoints.forEach((t, k) -> source.tabletToExplicitCheckpoint.put(t, opId(k).toCdcSdkCheckpoint()));
            task = allocate(YugabyteDBConnectorTask.class);
            set(task, "taskContext", context);
            YugabyteDBChangeEventSourceCoordinator coordinator = allocate(YugabyteDBChangeEventSourceCoordinator.class);
            set(coordinator, "snapshotter", Proxy.newProxyInstance(Snapshotter.class.getClassLoader(),
                    new Class<?>[]{ Snapshotter.class }, (proxy, m, args) -> m.getReturnType() == boolean.class ? false : null));
            set(task, "coordinator", coordinator); // streaming, not snapshot
        }

        Map<String, Object> perform(JsonNode step) throws Exception {
            Map<String, Object> x = new TreeMap<>();
            switch (step.get("a").asText()) {
                case "GetChanges" -> {
                    String t = step.get("t").asText();
                    CdcSdkCheckpoint ex = source.getExplicitCheckpoint(new YBPartition("table", t, false), opId(step.get("from").asInt()));
                    x.put("ex", (int) ex.getIndex());
                    if (step.get("resp").asText().equals("split")) {
                        boolean now = splitReady(step.get("last").asInt(), ex);
                        x.put("decision", now ? "split" : "wait");
                        if (now) {
                            handleTabletSplit();
                        }
                    }
                }
                case "SplitCallback" -> {
                    CdcSdkCheckpoint explicit = source.tabletToExplicitCheckpoint.get("P");
                    x.put("decision", explicit != null && splitReady(step.get("last").asInt(), explicit) ? "split" : "wait");
                    handleTabletSplit();
                }
                case "Commit" -> {
                    // YugabyteDBConnectorTask.commit: merge the stored offsets, then commitOffset
                    Map<String, String> stored = new HashMap<>();
                    step.get("stored").fields().forEachRemaining(e -> stored.put(e.getKey(), opId(e.getValue().asInt()).toSerString()));
                    @SuppressWarnings("unchecked")
                    Map<String, String> ybOffset = (Map<String, String>) get(task, "ybOffset");
                    if (ybOffset == null) {
                        ybOffset = new HashMap<>();
                    }
                    ybOffset.putAll(task.getHigherOffsets(stored));
                    set(task, "ybOffset", ybOffset);
                    source.commitOffset(ybOffset);
                }
                case "Restart" -> {
                    Map<String, Integer> srv = new HashMap<>();
                    step.get("srv").fields().forEachRemaining(e -> srv.put(e.getKey(), e.getValue().asInt()));
                    start(srv);
                }
                case "Ack", "Flush" -> {
                }
                default -> throw new IllegalArgumentException(step.toString());
            }
            Map<String, Object> explicit = new TreeMap<>();
            source.tabletToExplicitCheckpoint.forEach((t, c) -> explicit.put(t, (int) c.getIndex()));
            x.put("explicit", explicit);
            return x;
        }

        /** SCES:584-589 and 502-504. */
        private static boolean splitReady(int last, CdcSdkCheckpoint explicit) {
            OpId lastRecordCheckpoint = last < 0 ? null : opId(last);
            return lastRecordCheckpoint == null || lastRecordCheckpoint.isLesserThanOrEqualTo(explicit);
        }

        /** handleTabletSplit / addTabletIfNotPresent: the children get the server's checkpoint. */
        private void handleTabletSplit() {
            source.tabletToExplicitCheckpoint.remove("P");
            for (String c : new String[]{ "C1", "C2" }) {
                OpId checkpoint = opId(split);
                checkpoint.unsetTime();
                source.tabletToExplicitCheckpoint.put(c, checkpoint.toCdcSdkCheckpoint());
            }
        }
    }

    /**
     * The config without its constructor, which asks the master whether the stream is YSQL:
     * commitOffset only reads streamId().
     */
    private static YugabyteDBConnectorConfig config() throws Exception {
        YugabyteDBConnectorConfig config = allocate(YugabyteDBConnectorConfig.class);
        set(config, "config", Configuration.create().with("database.streamid", "conformance-stream").build());
        return config;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> c) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) ((sun.misc.Unsafe) f.get(null)).allocateInstance(c);
    }

    private static Field field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            }
            catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new IllegalArgumentException(o.getClass() + " has no field " + name);
    }

    private static void set(Object o, String name, Object value) throws IllegalAccessException {
        field(o, name).set(o, value);
    }

    private static Object get(Object o, String name) throws IllegalAccessException {
        return field(o, name).get(o);
    }

    /** Runs only when the spec is in the tree. */
    @Test
    void fixtureIsPinnedToTheModel() throws Exception {
        var dir = ExplicitCheckpointConformanceFixture.specDir();
        assumeTrue(Files.exists(dir.resolve(ExplicitCheckpointConformanceFixture.SPEC)));
        for (var it = readFixture().get("spec_sha256").fields(); it.hasNext();) {
            var pin = it.next();
            assertEquals(pin.getValue().asText(), TlaStateGraph.sha256(Files.readAllBytes(dir.resolve(pin.getKey()))),
                    pin.getKey() + " changed: regenerate with -Dconformance.generate=true");
        }
    }

    /** Read from the source tree: this build filters test resources, which breaks the gzip. */
    private static JsonNode readFixture() throws IOException {
        try (InputStream in = Files.newInputStream(java.nio.file.Path.of("src/test/resources/conformance", ExplicitCheckpointConformanceFixture.FIXTURE))) {
            assertNotNull(in);
            return JSON.readTree(new GZIPInputStream(in));
        }
    }
}
