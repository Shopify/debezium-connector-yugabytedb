/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.yugabytedb.tla;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test support for conformance fixtures (tla/README.md): runs TLC on a spec in tla/specs, reads
 * the full state graph ({@code -dump dot}) and parses each state into Java values (sequences and
 * sets become lists, records and functions become maps).
 */
public final class TlaStateGraph {

    public static final String TLA_TOOLS_SHA256 = "936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88";

    /** A TLA+ model value. */
    public record Mv(String name) {
    }

    public record Edge(int from, String action, int to) {
    }

    /** States by number (0 = initial state), edges in TLC order. */
    public final List<Map<String, Object>> states = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final Map<Integer, List<Edge>> out = new HashMap<>();

    private TlaStateGraph() {
    }

    /** The repository's tla/ directory, found from the module directory (the test working directory). */
    public static Path tlaDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("tla/specs"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("no tla/specs above " + Path.of("").toAbsolutePath());
        }
        return dir.resolve("tla");
    }

    /** The pinned tla2tools.jar: TLA2TOOLS_JAR, or the cache that tla/bin/tlc --install fills. */
    public static Path tlaTools() throws IOException {
        String env = System.getenv("TLA2TOOLS_JAR");
        Path jar = env != null ? Path.of(env) : Path.of(System.getProperty("user.home"), ".cache", "tla", "tla2tools-1.7.4.jar");
        if (!Files.exists(jar)) {
            throw new IllegalStateException(jar + " is missing: run tla/bin/tlc --install");
        }
        if (!sha256(Files.readAllBytes(jar)).equals(TLA_TOOLS_SHA256)) {
            throw new IllegalStateException(jar + " does not match the pinned tla2tools 1.7.4 SHA-256");
        }
        return jar;
    }

    /**
     * Runs TLC on {@code spec} with the CONSTANTS and SPECIFICATION of {@code cfgText} only: the
     * invariants and properties are not checked here, so TLC explores the whole graph.
     */
    public static TlaStateGraph explore(Path specDir, String spec, String cfgText) throws IOException, InterruptedException {
        Path cfg = Files.createTempFile("conformance", ".cfg");
        Files.writeString(cfg, cfgText.replaceAll("(?s)\\n(INVARIANTS?|PROPERTY|PROPERTIES)\\b.*", "\n"));
        Path dot = Files.createTempFile("conformance", ".dot");
        Path meta = Files.createTempDirectory("tlc-states");
        try {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            Process tlc = new ProcessBuilder(java.toString(), "-cp", tlaTools().toAbsolutePath().toString(), "tlc2.TLC",
                    "-workers", "1", "-metadir", meta.toString(), "-cleanup", "-config", cfg.toString(),
                    "-dump", "dot,actionlabels", dot.toString(), spec)
                    .directory(specDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(tlc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (tlc.waitFor() != 0) {
                throw new IllegalStateException("TLC failed:\n" + output);
            }
            return read(dot);
        }
        finally {
            Files.deleteIfExists(cfg);
            Files.deleteIfExists(dot);
            try (var files = Files.walk(meta)) {
                files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    private static final Pattern NODE = Pattern.compile("^(-?\\d+) \\[label=\"((?:[^\"\\\\]|\\\\.)*)\"(,style = filled)?\\]");
    private static final Pattern EDGE = Pattern.compile("^(-?\\d+) -> (-?\\d+) \\[label=\"(\\w+)\"");

    private static TlaStateGraph read(Path dot) throws IOException {
        Map<String, String> labels = new LinkedHashMap<>();
        List<String[]> edges = new ArrayList<>();
        String init = null;
        try (BufferedReader r = Files.newBufferedReader(dot)) {
            for (String line; (line = r.readLine()) != null;) {
                Matcher n = NODE.matcher(line);
                if (n.find()) {
                    labels.put(n.group(1), n.group(2));
                    if (n.group(3) != null) {
                        init = n.group(1);
                    }
                    continue;
                }
                Matcher e = EDGE.matcher(line);
                if (e.find()) {
                    edges.add(new String[]{ e.group(1), e.group(3), e.group(2) });
                }
            }
        }
        TlaStateGraph g = new TlaStateGraph();
        Map<String, Integer> number = new HashMap<>();
        number.put(init, 0);
        g.states.add(parseState(labels.get(init)));
        for (var entry : labels.entrySet()) {
            if (!number.containsKey(entry.getKey())) {
                number.put(entry.getKey(), g.states.size());
                g.states.add(parseState(entry.getValue()));
            }
        }
        for (String[] e : edges) {
            Edge edge = new Edge(number.get(e[0]), e[1], number.get(e[2]));
            g.edges.add(edge);
            g.out.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
        }
        return g;
    }

    // --- TLC value parser -----------------------------------------------------------------

    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(<<|>>|\\|->|:>|@@|[\\[\\]{}(),]|\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+|[A-Za-z_][A-Za-z0-9_]*)");

    private static final class Parser {
        final List<String> toks = new ArrayList<>();
        int i;

        Parser(String text) {
            Matcher m = TOKEN.matcher(text);
            while (m.find()) {
                toks.add(m.group(1));
            }
        }

        String peek() {
            return i < toks.size() ? toks.get(i) : null;
        }

        String take(String expected) {
            String t = toks.get(i++);
            if (expected != null && !expected.equals(t)) {
                throw new IllegalStateException("expected " + expected + ", got " + t);
            }
            return t;
        }

        Object value() {
            return switch (peek()) {
                case "<<" -> list("<<", ">>");
                case "{" -> list("{", "}");
                case "[" -> record();
                case "(" -> function();
                default -> atom(take(null));
            };
        }

        private Map<String, Object> record() {
            take("[");
            Map<String, Object> rec = new LinkedHashMap<>();
            while (!"]".equals(peek())) {
                String name = take(null);
                take("|->");
                rec.put(name, value());
                if (",".equals(peek())) {
                    take(",");
                }
            }
            take("]");
            return rec;
        }

        private Map<Object, Object> function() {
            take("(");
            Map<Object, Object> fn = new LinkedHashMap<>();
            while (true) {
                Object k = value();
                take(":>");
                fn.put(k, value());
                if (!"@@".equals(peek())) {
                    break;
                }
                take("@@");
            }
            take(")");
            return fn;
        }

        private static Object atom(String t) {
            if (t.startsWith("\"")) {
                return t.substring(1, t.length() - 1);
            }
            if (t.matches("-?\\d+")) {
                return Integer.parseInt(t);
            }
            if (t.equals("TRUE") || t.equals("FALSE")) {
                return t.equals("TRUE");
            }
            return new Mv(t);
        }

        private List<Object> list(String open, String close) {
            take(open);
            List<Object> items = new ArrayList<>();
            while (!close.equals(peek())) {
                items.add(value());
                if (",".equals(peek())) {
                    take(",");
                }
            }
            take(close);
            return items;
        }
    }

    private static final String CONJUNCT = "/\\ ";

    static Map<String, Object> parseState(String label) {
        String text = label.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
        Map<String, Object> state = new LinkedHashMap<>();
        int at = text.indexOf(CONJUNCT);
        while (at >= 0) {
            int next = text.indexOf(CONJUNCT, at + CONJUNCT.length());
            String chunk = text.substring(at + CONJUNCT.length(), next < 0 ? text.length() : next);
            int eq = chunk.indexOf(" = ");
            state.put(chunk.substring(0, eq).trim(), new Parser(chunk.substring(eq + 3)).value());
            at = next;
        }
        return state;
    }

    // --- helpers --------------------------------------------------------------------------

    private static final Pattern CONSTANT = Pattern.compile("^\\s+(\\w+) = (TRUE|FALSE|-?\\d+|\"\\w+\")\\s*$", Pattern.MULTILINE);

    /** The scalar CONSTANTS of a cfg file. */
    public static Map<String, Object> constants(String cfg) {
        Map<String, Object> out = new TreeMap<>();
        Matcher m = CONSTANT.matcher(cfg);
        while (m.find()) {
            String v = m.group(2);
            out.put(m.group(1), v.equals("TRUE") || v.equals("FALSE") ? (Object) v.equals("TRUE")
                    : v.startsWith("\"") ? v.substring(1, v.length() - 1) : (Object) Integer.parseInt(v));
        }
        return out;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> rec(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> items(Object v) {
        return (List<Object>) v;
    }
}
