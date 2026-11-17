package io.github.romanti3172.fasthtmlparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The expected output comes from z3l.FastHtmlParser (.NET) for the same pages (tools/conformance): tag names,
 * parents, depths, positions (as UTF-8 byte offsets), visible texts, attribute values, navigation and links,
 * and the results of 569 XPath / CSS queries. The Java port must give exactly the same.
 */
class ConformanceTest {
    static final Path DIR = Path.of("testdata", "conformance");
    static final String PAGE_URL = "https://Example.com:443/catalog/page-1.html?sort=asc#top"; // as in tools/conformance/Program.cs
    static final String[] ATTRIBUTES = {"class", "id", "href", "title", "value", "alt", "data-a", "x", "a", "src"};

    // page and query where the .NET result depends on the order of an unstable sort (see the Go port):
    // snip039 nests 600 divs; text nodes and flattened tags share document-order keys.
    static final Set<String> KNOWN_DIFFERENCES = new HashSet<>(List.of("snip039 //div/node()"));

    static List<String> inputs() throws IOException {
        try (Stream<Path> files = Files.list(DIR.resolve("input"))) {
            return files.map(f -> f.getFileName().toString())
                .filter(f -> f.endsWith(".html"))
                .map(f -> f.substring(0, f.length() - 5))
                .sorted()
                .collect(Collectors.toList());
        }
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Object json(String path) {
        return MiniJson.parse(read(DIR.resolve(path)));
    }

    /** UTF-8 byte offset of each UTF-16 index, as the .NET dump computes it. */
    static int[] byteOffsets(String source) {
        int[] bytes = new int[source.length() + 1];
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            int size = c >= 0xd800 && c <= 0xdbff ? 4 : c >= 0xdc00 && c <= 0xdfff ? 0 : c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            bytes[i + 1] = bytes[i] + size;
        }
        return bytes;
    }

    static long index(HtmlNode n) {
        return n == null ? -1 : n.index();
    }

    static String shortText(Object v) {
        String s = v instanceof String ? Json.quote((String) v) : String.valueOf(v);
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    @Test
    void inputsPresent() throws IOException {
        assertTrue(inputs().size() >= 100, inputs().size() + " inputs");
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> tagsTextsAttributesNavigationAndLinks() throws IOException {
        return inputs().stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            String source = read(DIR.resolve("input").resolve(name + ".html"));
            List<Object> expected = (List<Object>) json("expected/" + name + ".json");
            HtmlDocument doc = HtmlDocument.parse(source, PAGE_URL);
            int[] bytes = byteOffsets(source);
            List<String> problems = new ArrayList<>();
            if (doc.size() != expected.size()) problems.add("tags: " + doc.size() + ", .NET: " + expected.size());
            for (int i = 0; i < Math.min(doc.size(), expected.size()) && problems.size() < 10; i++) {
                HtmlNode n = doc.node(i);
                Map<String, Object> e = (Map<String, Object>) expected.get(i);
                Map<String, Object> got = new LinkedHashMap<>();
                got.put("name", n.name());
                got.put("parent", (long) n.parentIndex);
                got.put("depth", (long) n.depth());
                got.put("start", (long) bytes[n.start]);
                got.put("end", (long) (n.end == 0 ? 0 : bytes[n.end + 1] - 1));
                got.put("innerStart", (long) (n.innerStart == 0 ? 0 : bytes[n.innerStart]));
                got.put("innerEnd", (long) (n.innerEnd == 0 ? 0 : bytes[n.innerEnd + 1] - 1));
                got.put("text", n.text());
                got.put("textLineBreaks", n.textLineBreaks());
                got.put("source", n.source());
                got.put("next", index(n.next()));
                got.put("previous", index(n.previous()));
                got.put("nextSibling", index(n.nextSibling()));
                got.put("previousSibling", index(n.previousSibling()));
                got.put("children", n.children().stream().map(c -> String.valueOf(c.index())).collect(Collectors.joining(",")));
                got.put("href", n.href());
                got.put("src", n.src());
                got.put("mailto", n.hrefMailTo());
                got.put("tel", n.hrefTelephone());
                got.put("textAfter", n.textAfterTagUntilNextOne());
                got.put("textNoTagsAfter", n.textNoTagsAfterTagUntilNextOne());
                for (Map.Entry<String, Object> g : got.entrySet()) {
                    Object want = e.get(g.getKey());
                    if (!g.getValue().equals(want)) {
                        problems.add("[" + i + "] <" + n.name() + "> " + g.getKey() + ":\n  java: " + shortText(g.getValue())
                            + "\n  .net: " + shortText(want));
                    }
                }
                Map<String, Object> attrs = (Map<String, Object>) e.get("attrs");
                for (String a : ATTRIBUTES) {
                    Object want = attrs.getOrDefault(a, "");
                    if (!n.attr(a).equals(want)) {
                        problems.add("[" + i + "] <" + n.name() + "> @" + a + ": " + Json.quote(n.attr(a)) + ", .NET: " + shortText(want));
                    }
                }
            }
            assertTrue(problems.isEmpty(), String.join("\n", problems.subList(0, Math.min(10, problems.size()))));
        }));
    }

    @Test
    @SuppressWarnings("unchecked")
    void whichQueriesCompile() {
        Map<String, Object> expected = (Map<String, Object>) json("expected/queries/compile.json");
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Object> x : ((Map<String, Object>) expected.get("xpath")).entrySet()) {
            if (!okOrError(() -> XPathQuery.compile(x.getKey())).equals(x.getValue())) problems.add("XPath " + Json.quote(x.getKey()) + ": .NET " + x.getValue());
        }
        for (Map.Entry<String, Object> c : ((Map<String, Object>) expected.get("css")).entrySet()) {
            if (!okOrError(() -> CssQuery.compile(c.getKey())).equals(c.getValue())) problems.add("CSS " + Json.quote(c.getKey()) + ": .NET " + c.getValue());
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    static String okOrError(Supplier<?> f) {
        try {
            f.get();
            return "ok";
        } catch (RuntimeException e) {
            return "error";
        }
    }

    static final boolean FULL = System.getenv("FASTHTML_FULL") != null;

    /** [count, hash] of the values, or "throws" when the evaluation throws (.NET throws). */
    static Object result(Supplier<List<String>> values) {
        List<String> v;
        try {
            v = values.get();
        } catch (RuntimeException e) {
            return "throws";
        }
        if (FULL) return v;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\u0001", v).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return Arrays.asList(String.valueOf(v.size()), hex.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static List<String> indexes(List<HtmlNode> nodes) {
        List<String> out = new ArrayList<>(nodes.size());
        for (HtmlNode n : nodes) out.add(String.valueOf(n.index()));
        return out;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> xpathAndCssResults() throws IOException {
        return inputs().stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            Map<String, Object> expected = (Map<String, Object>) json("expected/queries/" + name + ".json");
            HtmlDocument doc = HtmlDocument.parse(read(DIR.resolve("input").resolve(name + ".html")));
            List<Object> scopes = (List<Object>) expected.get("scopes");
            List<String> problems = new ArrayList<>();

            for (Map.Entry<String, Object> entry : ((Map<String, Object>) expected.get("xpath")).entrySet()) {
                String x = entry.getKey();
                if (KNOWN_DIFFERENCES.contains(name + " " + x)) continue;
                Map<String, Object> want = (Map<String, Object>) entry.getValue();
                XPathQuery q = XPathQuery.compile(x);
                check(problems, "XPath tags " + x, want.get("tags"), result(() -> indexes(q.nodes(doc))));
                check(problems, "XPath values " + x, want.get("values"), result(() -> q.values(doc)));
                check(problems, "XPath value " + x, want.get("value"), result(() -> {
                    String v = q.value(doc);
                    return List.of(v == null ? "<null>" : v);
                }));
                List<Object> scoped = new ArrayList<>();
                for (Object s : scopes) {
                    HtmlNode n = doc.node((int) (long) (Long) s);
                    scoped.add(result(() -> {
                        List<String> r = new ArrayList<>(indexes(q.nodesFrom(n)));
                        r.add("|");
                        r.addAll(q.valuesFrom(n));
                        return r;
                    }));
                }
                check(problems, "XPath scoped " + x, want.get("scoped"), scoped);
            }

            for (Map.Entry<String, Object> entry : ((Map<String, Object>) expected.get("css")).entrySet()) {
                String c = entry.getKey();
                Map<String, Object> want = (Map<String, Object>) entry.getValue();
                CssQuery q = CssQuery.compile(c);
                check(problems, "CSS all " + c, want.get("all"), result(() -> indexes(q.all(doc))));
                check(problems, "CSS first " + c, want.get("first"), result(() -> {
                    HtmlNode f = q.first(doc);
                    return List.of(f == null ? "<null>" : String.valueOf(f.index()));
                }));
                check(problems, "CSS matches " + c, want.get("matches"), result(() -> {
                    List<HtmlNode> m = new ArrayList<>();
                    for (HtmlNode n : doc) if (q.matches(n)) m.add(n);
                    return indexes(m);
                }));
                List<Object> scoped = new ArrayList<>();
                for (Object s : scopes) {
                    HtmlNode n = doc.node((int) (long) (Long) s);
                    scoped.add(result(() -> indexes(q.allIn(n))));
                }
                check(problems, "CSS scoped " + c, want.get("scoped"), scoped);
            }
            assertTrue(problems.isEmpty(), String.join("\n", problems));
        }));
    }

    static void check(List<String> problems, String what, Object want, Object got) {
        if (problems.size() < 10 && !got.equals(want)) {
            problems.add(what + "\n  java: " + shortText(got) + "\n  .net: " + shortText(want));
        }
    }

    @Test
    void numberToString() {
        assertEquals("0.1", XPathQuery.numberToString(0.1));
        assertEquals("100000000000000000000", XPathQuery.numberToString(1e20));
        assertEquals("1000000000000000000000", XPathQuery.numberToString(1e21));
        assertEquals("0.0000001", XPathQuery.numberToString(1e-7));
        assertEquals("-123.456", XPathQuery.numberToString(-123.456));
        assertEquals("0.30000000000000004", XPathQuery.numberToString(0.1 + 0.2));
        assertEquals("12", XPathQuery.numberToString(12));
        assertEquals("NaN", XPathQuery.numberToString(Double.NaN));
    }
}
