package io.github.romanti3172.fasthtmlparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ApiTest {
    static String fixture(String path) throws Exception {
        return Files.readString(Path.of("testdata", path), StandardCharsets.UTF_8);
    }

    // The shortcuts on the books page give what the scraper code of the .NET examples expects.
    @Test
    void shortcuts() throws Exception {
        HtmlDocument doc = HtmlDocument.parse(fixture("books/index.html"), "https://books.toscrape.com/index.html");
        List<HtmlNode> pods = doc.findAllByNameClass(Tag.ARTICLE, "product_pod");
        assertEquals(20, pods.size());
        HtmlNode first = pods.get(0);
        assertEquals("A Light in the Attic", first.findByName(Tag.H3).findByName(Tag.A).attributeTitle());
        assertEquals("£51.77", first.findByNameClass(Tag.P, "price_color").text());
        assertEquals("price_color", first.findByNameClassTextStartsWith(Tag.P, "price_color", "£5").attributeClass());
        assertEquals("https://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html", first.findByName(Tag.A).href());
        assertEquals("https://books.toscrape.com/media/cache/2c/da/2cdad67c44b002e7ead0cc35693c0e8b.jpg", first.findByName(Tag.IMG).src());
        assertEquals("product_pod", first.findByName(Tag.IMG).parentByName(Tag.ARTICLE).attributeClass());
        assertEquals("product_price", first.findByName(Tag.H3).nextSiblingByName(Tag.DIV).attributeClass());
        // names as strings work too
        assertEquals("£51.77", first.findByNameClass("p", "price_color").text());

        assertEquals(1, Html.firstLevelByName(doc.findAll(Query.any()), Tag.HTML).size());
        assertTrue(first.isNameClass(Tag.ARTICLE, "product_pod"));
        assertFalse(first.isNameClass(Tag.DIV, "product_pod"));
        assertNull(doc.findByNameTextStartsWith(Tag.A, ""), "an empty criterion of the older shortcuts gives null");
        assertNull(doc.findById(""));
    }

    @Test
    void scrapingWithFindCssAndXPathGivesTheSameRecords() throws Exception {
        HtmlDocument doc = HtmlDocument.parse(fixture("books/index.html"));
        List<List<String>> byFind = doc.findAll(Query.by(Tag.ARTICLE).hasClass("product_pod")).stream().map(pod -> List.of(
            pod.find(Query.by(Tag.H3)).find(Query.by(Tag.A)).attr(Attr.TITLE),
            pod.find(Query.by(Tag.P).hasClass("price_color")).text(),
            pod.find(Query.by(Tag.P).hasClass("star-rating")).attr(Attr.CLASS))).collect(Collectors.toList());
        List<List<String>> byCss = doc.css("article.product_pod").stream().map(pod -> List.of(
            pod.cssFirst("h3 a").attr("title"),
            pod.cssFirst("p.price_color").text(),
            pod.cssFirst("p.star-rating").attr("class"))).collect(Collectors.toList());
        List<List<String>> byXPath = doc.xpath("//article[@class='product_pod']").stream().map(pod -> List.of(
            pod.xpathValue(".//h3/a/@title"),
            pod.xpathValue(".//p[@class='price_color']"),
            pod.xpathValue(".//p[contains(@class,'star-rating')]/@class"))).collect(Collectors.toList());
        assertEquals(20, byFind.size());
        assertEquals(byFind, byCss);
        assertEquals(byFind, byXPath);
    }

    // CR LF and a lone CR are LF in values, as a browser reads the page; a CR written as a character reference stays.
    @Test
    void lineBreaksAreReadAsInABrowser() {
        HtmlDocument doc = HtmlDocument.parse("<meta name=\"d\" content=\"one\r\ntwo\rthree&#13;four\">"
            + "<textarea>a\r\nb</textarea><pre>c\r\nd</pre><div class=\"x\r\ny\">t</div>");
        HtmlNode div = doc.find(Query.by(Tag.DIV));
        assertEquals("one\ntwo\nthree\rfour", doc.find(Query.by(Tag.META)).attr("content"));
        assertEquals("a\nb", doc.find(Query.by(Tag.TEXTAREA)).text());
        assertEquals("c\nd", doc.find(Query.by(Tag.PRE)).text());
        assertEquals("a\nb", doc.xpathValue("//textarea/text()"));
        assertEquals("one\ntwo\nthree\rfour", doc.xpathValue("//meta/@content"));
        assertEquals("x\r\ny", div.attrRaw("class"));
        assertTrue(div.hasClass("y"));
        assertTrue(div.is(Query.any().attr("class", "x\ny")));
        assertEquals(1, doc.css("div[class=\"x\\a y\"]").size());
    }

    @Test
    void optionalEndTagsAsInABrowser() {
        HtmlDocument doc = HtmlDocument.parse("<ul><li>one<li>two<ul><li>a<li>b</ul><li>three</ul>"
            + "<table><tr><td>1<td>2<tr><td>3</table><p>x<div>y</div>");
        assertEquals(List.of("li1", "li1", "li3", "li3", "li1"),
            doc.css("ul > li").stream().map(n -> n.name() + n.depth()).collect(Collectors.toList()));
        assertEquals(List.of("one", "two\na\nb", "three"),
            doc.cssFirst("ul").css("> li").stream().map(HtmlNode::textLineBreaks).collect(Collectors.toList()));
        assertEquals(List.of(2, 1), doc.css("tr").stream().map(n -> n.children().size()).collect(Collectors.toList()));
        assertNull(doc.cssFirst("div").parent(), "<div> ends an open <p>");
    }

    @Test
    void textEntitiesRawTextAndComments() {
        HtmlDocument doc = HtmlDocument.parse("<div> a &amp;&nbsp;b <script>var x = \"<b>\";</script><!-- c --> <i>d</i>&copy2024 </div>"
            + "<title>a &lt; b</title>");
        assertEquals("a & b d©2024", doc.cssFirst("div").text(), "&nbsp; is a no-break space");
        assertEquals("a < b", doc.cssFirst("title").text());
        assertEquals(" c ", doc.xpathValue("//comment()"));
        assertEquals("onetwo three", Html.toText("<p>one</p><p>two&nbsp;three</p>"));
        assertEquals("one\n\ntwo\nthree\n\nx y", Html.toTextLineBreaks("<p>one</p><p>two<br>three</p><td>x</td><td>y</td>"));
        assertEquals("<AB&bogus;", Html.decode("&lt;&#x41;&#66;&bogus;"));
    }

    @Test
    void attributes() {
        HtmlDocument doc = HtmlDocument.parse("<a href=/p?a=1&copy=2 title=\"see id=5\" data-x='<b>' id=main CLASS=\"One two\" hidden>x</a>");
        HtmlNode a = doc.cssFirst("a");
        assertEquals("/p?a=1&copy=2", a.attr("href"));
        assertEquals("main", a.attr("id"));
        assertEquals("<b>", a.attr("data-x"));
        assertEquals("", a.attr("hidden"));
        assertEquals("", a.attr("missing"));
        assertTrue(a.hasAttr("hidden"));
        assertFalse(a.hasAttr("missing"));
        assertTrue(a.hasClass("one") && a.hasClass("TWO") && !a.hasClass("on"));
        assertEquals(List.of("href", "title", "data-x", "id", "CLASS", "hidden"),
            a.attrs().stream().map(Map.Entry::getKey).collect(Collectors.toList()));
        assertTrue(a.isCss("[hidden]") && a.isCss("a#main.One"));
        assertEquals("a href=/p?a=1&copy=2 title=\"see id=5\" data-x='<b>' id=main CLASS=\"One two\" hidden", a.source());
    }

    @Test
    void links() {
        HtmlDocument doc = HtmlDocument.parse(
            "<a href=\"../x/y.html?q=1#f\">1</a><a href=\"//cdn.example.com/a b\">2</a><a href=\"\">3</a>"
                + "<a href=\"mailto:me@example.com?subject=hi\">4</a><a href=\"tel:+1 555\">5</a><a href=\"https://Other.org/%7Ea\">6</a>",
            "https://Example.com:443/catalog/page/index.html?sort=asc#top");
        List<HtmlNode> a = doc.css("a");
        assertEquals("https://example.com/catalog/x/y.html?q=1#f", a.get(0).href());
        assertEquals("https://cdn.example.com/a b", a.get(1).href());
        assertEquals("https://Example.com:443/catalog/page/index.html?sort=asc#top", a.get(2).href());
        assertEquals("me@example.com?subject=hi", a.get(3).hrefMailTo());
        assertEquals("+1 555", a.get(4).hrefTelephone());
        assertEquals("https://Other.org/%7Ea", a.get(5).href(), "absolute links are returned as written");
        assertEquals("http://h.test/a/x/y.html?q=1#f", a.get(0).hrefFrom("http://h.test/a/b/"));
    }

    @Test
    void xpathValues() {
        HtmlDocument doc = HtmlDocument.parse("<dl><dt>Price</dt><dd>12.50</dd><dt>Stock</dt><dd>3</dd></dl><a href='/x'>a</a><a>b</a>");
        assertEquals("12.50", doc.xpathValue("//dt[.='Price']/following-sibling::dd[1]"));
        assertEquals("2", doc.xpathValue("count(//a)"));
        assertEquals("15.5", doc.xpathValue("sum(//dd)"));
        assertEquals(List.of("/x"), doc.xpathValues("//a/@href"));
        assertEquals("", doc.xpathValue("//missing"));
        assertNull(doc.xpathFirst("//missing"));
        assertEquals("true", doc.xpathValue("boolean(//dl)"));
        assertThrows(XPathException.class, () -> doc.xpath("count(//a)"), "a number is not tags");
    }

    @Test
    void invalidQueries() {
        HtmlDocument doc = HtmlDocument.parse("<p>x</p>");
        CssSelectorException css = assertThrows(CssSelectorException.class, () -> doc.css("p >"));
        assertTrue(css.getMessage().contains("\"p >\""), css.getMessage());
        assertThrows(CssSelectorException.class, () -> CssQuery.compile("::before"));
        XPathException xpath = assertThrows(XPathException.class, () -> doc.xpath("//p["));
        assertTrue(xpath.getMessage().contains("\"//p[\""), xpath.getMessage());
        assertThrows(XPathException.class, () -> XPathQuery.compile("$var"));
        assertThrows(IllegalArgumentException.class, () -> doc.xpath("//p[unknown()]"));
    }

    @Test
    void compiledQueriesAndBytes() {
        HtmlDocument doc = HtmlDocument.parse("﻿<p class=a>é</p><p class=b>x</p>".getBytes(StandardCharsets.UTF_8), "https://h.test/");
        assertTrue(doc.source().startsWith("<p"), "the byte order mark is skipped");
        CssQuery css = CssQuery.compile("p.b");
        XPathQuery xpath = XPathQuery.compile("//p[@class='a']");
        assertEquals("x", css.first(doc).text());
        assertEquals("é", xpath.value(doc));
        assertEquals(2, doc.size());
        assertEquals(Tag.P, doc.node(0).tag());
        assertEquals("p", doc.node(0).name());
        assertNull(doc.node(5));
        int count = 0;
        for (HtmlNode ignored : doc) count++;
        assertEquals(2, count);
    }

    @Test
    void customTagsAndComments() {
        HtmlDocument doc = HtmlDocument.parse("<my-widget data-id=1><svg:path d=x/></my-widget><!-- note -->");
        HtmlNode w = doc.findByName("my-widget");
        assertNull(w.tag());
        assertEquals("my-widget", w.name());
        assertEquals("1", w.attr("data-id"));
        assertEquals(1, doc.css("my-widget").size());
        assertEquals(Tag.COMMENT, doc.node(2).tag());
        assertEquals("!--", doc.node(2).name());
        assertEquals("", doc.node(2).attr("note"), "a comment has no attributes");
    }

    // Parsing uses per-thread buffers: documents parsed on many threads at once come out the same.
    @Test
    void parsingOnManyThreads() throws Exception {
        String page = fixture("books/index.html");
        String expected = String.join("|", HtmlDocument.parse(page).css("article.product_pod h3 a").stream()
            .map(a -> a.attr("title")).collect(Collectors.toList()));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                results.add(pool.submit(() -> {
                    HtmlDocument d = HtmlDocument.parse(page);
                    return String.join("|", d.css("article.product_pod h3 a").stream()
                        .map(a -> a.attr("title")).collect(Collectors.toList()));
                }));
            }
            for (Future<String> r : results) assertEquals(expected, r.get());
        } finally {
            pool.shutdown();
        }
    }
}
