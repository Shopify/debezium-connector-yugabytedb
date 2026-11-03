/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.yugabytedb;

import static io.debezium.connector.yugabytedb.tla.TlaStateGraph.items;
import static io.debezium.connector.yugabytedb.tla.TlaStateGraph.rec;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.debezium.connector.yugabytedb.tla.TlaStateGraph;

/**
 * Writes the conformance fixture for {@link ExplicitCheckpointConformanceTest}: paths through
 * tla/specs/explicit-checkpoint/YbExplicitCheckpoint.tla that cover every edge, with what the
 * driver feeds the Java (the server's responses, the Connect offset store) and what the model
 * gives (the explicit checkpoint sent, the split decision, tabletToExplicitCheckpoint).
 *
 * <p>Run: {@code mvn test -Dtest=ExplicitCheckpointConformanceFixture -Dconformance.generate=true}.
 * Needs the pinned tla2tools.jar (~/.cache/tla/tla2tools-1.7.4.jar or TLA2TOOLS_JAR).
 */
@EnabledIfSystemProperty(named = "conformance.generate", matches = "true")
class ExplicitCheckpointConformanceFixture {

    static final String CONFIG = "YbExplicitCheckpoint.conformance.cfg";
    static final String SPEC = "YbExplicitCheckpoint.tla";
    static final String FIXTURE = "ybexplicitcheckpoint-paths.json.gz";
    private static final int MAX_PATH_LENGTH = 60;

    static Path specDir() {
        return TlaStateGraph.tlaDir().resolve("specs/explicit-checkpoint");
    }

    @Test
    void write() throws Exception {
        String cfg = Files.readString(specDir().resolve(CONFIG));
        TlaStateGraph g = TlaStateGraph.explore(specDir(), SPEC, cfg);
        List<Object> paths = new ArrayList<>();
        Map<String, Integer> actions = new TreeMap<>();
        int steps = 0;
        for (List<TlaStateGraph.Edge> path : selectPaths(g)) {
            List<Object> out = new ArrayList<>();
            for (TlaStateGraph.Edge e : path) {
                Map<String, Object> step = step(e.action(), g.states.get(e.from()), g.states.get(e.to()));
                out.add(step);
                actions.merge((String) step.get("a"), 1, Integer::sum);
            }
            paths.add(out);
            steps += out.size();
        }
        Map<String, Object> fixture = new TreeMap<>();
        fixture.put("generator", "ExplicitCheckpointConformanceFixture (-Dconformance.generate=true)");
        fixture.put("config", CONFIG);
        fixture.put("constants", TlaStateGraph.constants(cfg));
        fixture.put("spec_sha256", new TreeMap<>(Map.of(
                SPEC, TlaStateGraph.sha256(Files.readAllBytes(specDir().resolve(SPEC))),
                CONFIG, TlaStateGraph.sha256(cfg.getBytes(StandardCharsets.UTF_8)))));
        fixture.put("states", g.states.size());
        fixture.put("edges", g.edges.size());
        fixture.put("paths", paths);
        Path file = Path.of("src/test/resources/conformance").resolve(FIXTURE);
        Files.createDirectories(file.getParent());
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(file))) {
            os.write(new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(fixture));
        }
        System.out.printf("%s: %d states, %d edges%n%d paths, %d steps -> %s (%d bytes)%n%s%n", CONFIG, g.states.size(),
                g.edges.size(), paths.size(), steps, file, Files.size(file), actions);
    }

    private static Map<String, Object> step(String action, Map<String, Object> s, Map<String, Object> t) {
        Map<String, Object> p = new TreeMap<>();
        Map<String, Object> x = new TreeMap<>();
        p.put("a", action);
        switch (action) {
            case "GetChanges" -> {
                List<Object> before = items(s.get("sent"));
                List<Object> after = items(t.get("sent"));
                String tablet;
                if (after.size() > before.size()) {
                    tablet = (String) items(after.get(after.size() - 1)).get(0);
                    p.put("resp", "record");
                    p.put("k", items(after.get(after.size() - 1)).get(1));
                }
                else if (!s.get("assigned").equals(t.get("assigned")) || !s.get("waiting").equals(t.get("waiting"))) {
                    tablet = "P";
                    p.put("resp", "split");
                    x.put("decision", Boolean.TRUE.equals(t.get("waiting")) ? "wait" : "split");
                }
                else {
                    tablet = changed(rec(s.get("srv")), rec(t.get("srv")));
                    p.put("resp", "none");
                }
                p.put("t", tablet);
                p.put("from", rec(s.get("cursor")).get(tablet));
                p.put("last", rec(s.get("lastRec")).get(tablet));
                x.put("ex", rec(t.get("srv")).get(tablet));
            }
            case "SplitCallback" -> {
                p.put("last", rec(s.get("lastRec")).get("P"));
                x.put("decision", "split");
            }
            case "Commit" -> p.put("stored", entries(rec(s.get("stored"))));
            case "Restart" -> {
                Map<String, Object> srv = new TreeMap<>();
                for (Object tab : items(t.get("assigned"))) {
                    srv.put((String) tab, rec(s.get("srv")).get(tab));
                }
                p.put("srv", srv);
            }
            default -> {
            }
        }
        x.put("explicit", entries(rec(t.get("explicit"))));
        p.put("x", x);
        return p;
    }

    private static String changed(Map<String, Object> a, Map<String, Object> b) {
        for (String k : a.keySet()) {
            if (!a.get(k).equals(b.get(k))) {
                return k;
            }
        }
        throw new IllegalStateException("no tablet changed: " + a + " -> " + b);
    }

    /** The entries of a tablet map that are not None (-1). */
    private static Map<String, Object> entries(Map<String, Object> m) {
        Map<String, Object> out = new TreeMap<>();
        m.forEach((k, v) -> {
            if (!Integer.valueOf(-1).equals(v)) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** Paths from the initial state that cover every edge (self-loops excluded). */
    private static List<List<TlaStateGraph.Edge>> selectPaths(TlaStateGraph g) {
        Map<Integer, TlaStateGraph.Edge> parent = new HashMap<>();
        Map<Integer, Integer> depth = new HashMap<>(Map.of(0, 0));
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(0));
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (TlaStateGraph.Edge e : g.out.getOrDefault(u, List.of())) {
                if (!depth.containsKey(e.to())) {
                    depth.put(e.to(), depth.get(u) + 1);
                    parent.put(e.to(), e);
                    queue.add(e.to());
                }
            }
        }
        List<TlaStateGraph.Edge> order = new ArrayList<>();
        g.edges.stream().filter(e -> e.from() != e.to()).forEach(order::add);
        order.sort((a, b) -> Integer.compare(depth.get(b.from()), depth.get(a.from())));
        Set<TlaStateGraph.Edge> covered = new HashSet<>();
        List<List<TlaStateGraph.Edge>> paths = new ArrayList<>();
        for (TlaStateGraph.Edge edge : order) {
            if (covered.contains(edge)) {
                continue;
            }
            List<TlaStateGraph.Edge> path = new ArrayList<>();
            for (TlaStateGraph.Edge e = parent.get(edge.from()); e != null; e = parent.get(e.from())) {
                path.add(e);
            }
            Collections.reverse(path);
            path.add(edge);
            int node = edge.to();
            while (path.size() <= MAX_PATH_LENGTH) {
                TlaStateGraph.Edge next = null;
                for (TlaStateGraph.Edge e : g.out.getOrDefault(node, List.of())) {
                    if (e.from() != e.to() && !covered.contains(e) && !e.equals(edge)) {
                        next = e;
                        break;
                    }
                }
                if (next == null) {
                    break;
                }
                path.add(next);
                covered.add(next);
                node = next.to();
            }
            covered.addAll(path);
            paths.add(path);
        }
        return paths;
    }
}
