// Benchmarks of fasthtmlparser against other Java HTML parsers: parsing pages, and scraping records
// (parse + extract). Every scraper is first checked to return exactly the same records.
//
//   mvn install -DskipTests          (in the repository root)
//   mvn -q compile exec:java         (in this folder)
package bench;

import io.github.romanti3172.fasthtmlparser.HtmlDocument;
import io.github.romanti3172.fasthtmlparser.HtmlNode;
import io.github.romanti3172.fasthtmlparser.Query;
import io.github.romanti3172.fasthtmlparser.Tag;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import net.htmlparser.jericho.Config;
import net.htmlparser.jericho.LoggerProvider;
import net.htmlparser.jericho.Source;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.dom.HtmlDocumentBuilder;
import org.htmlcleaner.CleanerProperties;
import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class Bench {
    static volatile Object sink;

    static String read(String name) throws Exception {
        return Files.readString(Path.of("..", "testdata", name), StandardCharsets.UTF_8);
    }

    /** Median time per call in microseconds: batches of calls for about `seconds`, after a warm-up. */
    static double measure(Supplier<Object> fn, double seconds) {
        int batch = 1;
        for (;;) {
            long t = System.nanoTime();
            for (int i = 0; i < batch; i++) sink = fn.get();
            if (System.nanoTime() - t > 20_000_000L) break;
            batch *= 2;
        }
        long until = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < until) sink = fn.get(); // warm-up: the JIT compiles the hot paths
        System.gc();
        List<Double> samples = new ArrayList<>();
        long end = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < end) {
            long t = System.nanoTime();
            for (int i = 0; i < batch; i++) sink = fn.get();
            samples.add((System.nanoTime() - t) / 1000.0 / batch);
        }
        samples.sort(null);
        return samples.get(samples.size() / 2);
    }

    static String fmt(double us) {
        return us >= 1000 ? String.format(Locale.ROOT, "%.2f ms", us / 1000) : String.format(Locale.ROOT, "%,d μs", Math.round(us));
    }

    static String lastWord(String s) {
        return s.substring(s.lastIndexOf(' ') + 1);
    }

    static String or(String s) {
        return s == null ? "" : s;
    }

    // ----- scrapers: each gives [title, price, rating] for books and [text, author, tags] for quotes -----

    static final ThreadLocal<XPath> XPATH = ThreadLocal.withInitial(() -> XPathFactory.newInstance().newXPath());

    static String domValue(String expr, Object node) {
        try {
            return (String) XPATH.get().evaluate(expr, node, XPathConstants.STRING);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Map<String, Function<String, List<List<String>>>> bookScrapers() {
        Map<String, Function<String, List<List<String>>>> m = new LinkedHashMap<>();
        m.put("fasthtmlparser find", page -> HtmlDocument.parse(page).findAll(Query.by(Tag.ARTICLE).hasClass("product_pod")).stream()
            .map(pod -> List.of(
                pod.find(Query.by(Tag.H3)).find(Query.by(Tag.A)).attr("title"),
                pod.find(Query.by(Tag.P).hasClass("price_color")).text(),
                lastWord(pod.find(Query.by(Tag.P).hasClass("star-rating")).attr("class"))))
            .collect(Collectors.toList()));
        m.put("fasthtmlparser CSS", page -> HtmlDocument.parse(page).css("article.product_pod").stream()
            .map(pod -> List.of(
                pod.cssFirst("h3 a").attr("title"),
                pod.cssFirst("p.price_color").text(),
                lastWord(pod.cssFirst("p.star-rating").attr("class"))))
            .collect(Collectors.toList()));
        m.put("fasthtmlparser XPath", page -> HtmlDocument.parse(page).xpath("//article[@class='product_pod']").stream()
            .map(pod -> List.of(
                pod.xpathValue(".//h3/a/@title"),
                pod.xpathValue(".//p[@class='price_color']"),
                lastWord(pod.xpathValue(".//p[contains(@class,'star-rating')]/@class"))))
            .collect(Collectors.toList()));
        m.put("jsoup CSS", page -> Jsoup.parse(page).select("article.product_pod").stream()
            .map(pod -> List.of(
                pod.selectFirst("h3 a").attr("title"),
                pod.selectFirst("p.price_color").text(),
                lastWord(pod.selectFirst("p.star-rating").attr("class"))))
            .collect(Collectors.toList()));
        m.put("jsoup XPath", page -> Jsoup.parse(page).selectXpath("//article[@class='product_pod']").stream()
            .map(pod -> List.of(
                pod.selectXpath(".//h3/a").first().attr("title"),
                pod.selectXpath(".//p[@class='price_color']").first().text(),
                lastWord(pod.selectXpath(".//p[contains(@class,'star-rating')]").first().attr("class"))))
            .collect(Collectors.toList()));
        m.put("jsoup + javax.xml XPath", page -> {
            org.w3c.dom.Document d = new W3CDom().namespaceAware(false).fromJsoup(Jsoup.parse(page));
            try {
                NodeList pods = (NodeList) XPATH.get().evaluate("//article[@class='product_pod']", d, XPathConstants.NODESET);
                List<List<String>> out = new ArrayList<>();
                for (int i = 0; i < pods.getLength(); i++) {
                    org.w3c.dom.Node pod = pods.item(i);
                    out.add(List.of(domValue(".//h3/a/@title", pod), domValue(".//p[@class='price_color']", pod).trim(),
                        lastWord(domValue(".//p[contains(@class,'star-rating')]/@class", pod))));
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        m.put("HtmlCleaner", page -> {
            TagNode root = htmlCleaner().clean(page);
            List<List<String>> out = new ArrayList<>();
            for (TagNode pod : root.getElementsByAttValue("class", "product_pod", true, false)) {
                if (!pod.getName().equals("article")) continue;
                TagNode a = pod.findElementByName("h3", true).findElementByName("a", true);
                TagNode price = pod.findElementByAttValue("class", "price_color", true, false);
                String rating = "";
                for (TagNode p : pod.getElementsByName("p", true)) {
                    String cls = or(p.getAttributeByName("class"));
                    if (cls.startsWith("star-rating")) {
                        rating = cls;
                        break;
                    }
                }
                out.add(List.of(a.getAttributeByName("title"), price.getText().toString().trim(), lastWord(rating)));
            }
            return out;
        });
        return m;
    }

    static Map<String, Function<String, List<List<String>>>> quoteScrapers() {
        Map<String, Function<String, List<List<String>>>> m = new LinkedHashMap<>();
        m.put("fasthtmlparser find", page -> HtmlDocument.parse(page).findAll(Query.by(Tag.DIV).hasClass("quote")).stream()
            .map(q -> List.of(
                q.find(Query.by(Tag.SPAN).hasClass("text")).text(),
                q.find(Query.by(Tag.SMALL).hasClass("author")).text(),
                q.findAll(Query.by(Tag.A).hasClass("tag")).stream().map(HtmlNode::text).collect(Collectors.joining(","))))
            .collect(Collectors.toList()));
        m.put("fasthtmlparser CSS", page -> HtmlDocument.parse(page).css("div.quote").stream()
            .map(q -> List.of(
                q.cssFirst("span.text").text(),
                q.cssFirst("small.author").text(),
                q.css("a.tag").stream().map(HtmlNode::text).collect(Collectors.joining(","))))
            .collect(Collectors.toList()));
        m.put("jsoup CSS", page -> Jsoup.parse(page).select("div.quote").stream()
            .map(q -> List.of(
                q.selectFirst("span.text").text(),
                q.selectFirst("small.author").text(),
                q.select("a.tag").stream().map(Element::text).collect(Collectors.joining(","))))
            .collect(Collectors.toList()));
        return m;
    }

    /** HtmlCleaner with attribute values decoded (&amp;#39; → '), as the other parsers give them. */
    static HtmlCleaner htmlCleaner() {
        CleanerProperties props = new CleanerProperties();
        props.setDeserializeEntities(true);
        return new HtmlCleaner(props);
    }

    // ----- parsers -----

    static Map<String, Function<String, Object>> parsers() {
        Map<String, Function<String, Object>> m = new LinkedHashMap<>();
        m.put("fasthtmlparser", HtmlDocument::parse);
        m.put("jsoup", Jsoup::parse);
        m.put("HtmlCleaner", p -> htmlCleaner().clean(p));
        m.put("validator.nu (DOM)", p -> {
            try {
                HtmlDocumentBuilder b = new HtmlDocumentBuilder(XmlViolationPolicy.ALTER_INFOSET);
                return b.parse(new InputSource(new StringReader(p)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        m.put("Jericho", p -> {
            Source s = new Source(p);
            s.fullSequentialParse();
            return s;
        });
        return m;
    }

    public static void main(String[] args) throws Exception {
        Config.LoggerProvider = LoggerProvider.DISABLED;
        String books = read("books/index.html");
        String quotes = read("quotes/page1.html");
        String laptops = read("webscraper/laptops.html");
        String[][] pages = {
            {"quotes, 11 KB", quotes},
            {"laptops, 38 KB", laptops},
            {"books, 51 KB", books},
            {"books x10, 510 KB", books.repeat(10)},
            {"books without </li> </p>", books.replace("</li>", "").replace("</p>", "")},
        };
        double seconds = args.length > 0 ? Double.parseDouble(args[0]) : 2;

        // the same records from every scraper
        Map<String, Map<String, Function<String, List<List<String>>>>> scrapers = new LinkedHashMap<>();
        scrapers.put("books", bookScrapers());
        scrapers.put("quotes", quoteScrapers());
        Map<String, String> pageOf = Map.of("books", books, "quotes", quotes);
        for (Map.Entry<String, Map<String, Function<String, List<List<String>>>>> e : scrapers.entrySet()) {
            String page = pageOf.get(e.getKey());
            List<List<String>> expected = e.getValue().get("fasthtmlparser find").apply(page);
            int want = e.getKey().equals("books") ? 20 : 10;
            if (expected.size() != want) throw new AssertionError(e.getKey() + ": " + expected.size() + " records");
            for (Map.Entry<String, Function<String, List<List<String>>>> s : e.getValue().entrySet()) {
                List<List<String>> got = s.getValue().apply(page);
                if (!got.equals(expected)) {
                    throw new AssertionError(e.getKey() + ": " + s.getKey() + " gives\n" + got + "\ninstead of\n" + expected);
                }
            }
        }
        System.out.println("every scraper returns the same records\n");

        System.out.println("Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + "); median time per call\n");
        System.out.println("Parse a page:\n");
        Map<String, Function<String, Object>> parsers = parsers();
        System.out.println("| Page | " + String.join(" | ", parsers.keySet()) + " |");
        System.out.println("|---|" + parsers.keySet().stream().map(k -> "---:").collect(Collectors.joining("|")) + "|");
        for (String[] page : pages) {
            List<String> cells = new ArrayList<>();
            for (Function<String, Object> parse : parsers.values()) cells.add(fmt(measure(() -> parse.apply(page[1]), seconds)));
            System.out.println("| " + page[0] + " | " + String.join(" | ", cells) + " |");
        }

        System.out.println("\nScrape a page (parse + extract the records):\n");
        System.out.println("| Task | Time |\n|---|---:|");
        for (Map.Entry<String, Map<String, Function<String, List<List<String>>>>> e : scrapers.entrySet()) {
            String page = pageOf.get(e.getKey());
            for (Map.Entry<String, Function<String, List<List<String>>>> s : e.getValue().entrySet()) {
                System.out.println("| " + e.getKey() + ", " + s.getKey() + " | " + fmt(measure(() -> s.getValue().apply(page), seconds)) + " |");
            }
        }
    }
}
