package io.github.romanti3172.fasthtmlparser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A parsed HTML or XML page: a flat list of tags in document order. Each tag holds only positions in the source; text,
 * attributes and entities are read lazily, when they are asked for, and the values are substrings of the page.
 * Documents are read-only and can be shared between threads.
 *
 * <pre>{@code
 * HtmlDocument doc = HtmlDocument.parse(html, "https://example.com/catalog/");
 * for (HtmlNode pod : doc.css("article.product_pod")) {
 *     String title = pod.cssFirst("h3 a").attr("title");
 *     String price = pod.findByNameClass(Tag.P, "price_color").text();
 * }
 * doc.xpathValue("//dt[.='Price']/following-sibling::dd[1]");
 * }</pre>
 */
public final class HtmlDocument implements Iterable<HtmlNode> {
    private final String source;
    private final String url;
    final HtmlNode[] nodes;
    private final List<HtmlNode> nodeList;
    /** the page URL parsed once, for resolving relative links */
    final Links.Url base;
    /** sibling links and positions, built by the first selector that needs them */
    volatile CssQuery.Tree cssTree;

    private HtmlDocument(String source, String url) {
        this.source = source;
        this.url = url;
        this.base = url.isEmpty() ? null : Links.absoluteUrl(url);
        this.nodes = Parser.parseTags(this, source);
        this.nodeList = Collections.unmodifiableList(Arrays.asList(nodes));
    }

    /** Parses an HTML or XML page. */
    public static HtmlDocument parse(String html) {
        return new HtmlDocument(html, "");
    }

    /**
     * Parses an HTML or XML page with its URL: relative links ({@link HtmlNode#href()}, {@link HtmlNode#src()})
     * are resolved against it.
     */
    public static HtmlDocument parse(String html, String url) {
        return new HtmlDocument(html, url == null ? "" : url);
    }

    /** Parses a page given as UTF-8 bytes (a leading byte order mark is skipped). */
    public static HtmlDocument parse(byte[] utf8) {
        return parse(utf8, "");
    }

    /** Parses a page given as UTF-8 bytes, with its URL for resolving relative links. */
    public static HtmlDocument parse(byte[] utf8, String url) {
        int skip = utf8.length >= 3 && (utf8[0] & 0xff) == 0xef && (utf8[1] & 0xff) == 0xbb && (utf8[2] & 0xff) == 0xbf ? 3 : 0;
        return parse(new String(utf8, skip, utf8.length - skip, StandardCharsets.UTF_8), url);
    }

    /** The page source. */
    public String source() {
        return source;
    }

    /** The page URL given to {@code parse}, or "". */
    public String url() {
        return url;
    }

    /** Every tag (comments included) in document order, as a read-only list. */
    public List<HtmlNode> nodes() {
        return nodeList;
    }

    /** The number of tags (comments included). */
    public int size() {
        return nodes.length;
    }

    /** The tag at index i in document order, or null when there is none. */
    public HtmlNode node(int i) {
        return i >= 0 && i < nodes.length ? nodes[i] : null;
    }

    @Override
    public Iterator<HtmlNode> iterator() {
        return nodeList.iterator();
    }

    // ----- search -----

    /** The first tag in the document that matches the query, or null. */
    public HtmlNode find(Query q) {
        return find(nodes, 0, source.length(), q);
    }

    /** All tags in the document that match the query. */
    public List<HtmlNode> findAll(Query q) {
        return findAll(nodes, 0, source.length(), q);
    }

    /** The tags that match a CSS selector (as querySelectorAll). Throws {@link CssSelectorException} on an invalid selector. */
    public List<HtmlNode> css(String selector) {
        return CssQuery.cached(selector).all(this);
    }

    /** The first tag that matches a CSS selector, or null. */
    public HtmlNode cssFirst(String selector) {
        return CssQuery.cached(selector).first(this);
    }

    /**
     * The tags an XPath expression selects. Throws {@link XPathException} on an invalid expression or one that gives
     * a number, string or boolean (use {@link #xpathValue(String)}).
     */
    public List<HtmlNode> xpath(String expression) {
        return XPathQuery.cached(expression).nodes(this);
    }

    /** The first tag an XPath expression selects, or null. */
    public HtmlNode xpathFirst(String expression) {
        List<HtmlNode> list = xpath(expression);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * The values an XPath expression selects: decoded attributes, text(), visible text of tags, or the result of
     * count(), string(), ... as one value.
     */
    public List<String> xpathValues(String expression) {
        return XPathQuery.cached(expression).values(this);
    }

    /** The first value an XPath expression selects, or "". */
    public String xpathValue(String expression) {
        String v = XPathQuery.cached(expression).value(this);
        return v == null ? "" : v;
    }

    static HtmlNode find(HtmlNode[] nodes, int first, int end, Query q) {
        for (int i = first; i < nodes.length; i++) {
            HtmlNode n = nodes[i];
            if (n.end > end) break;
            if (q.matches(n)) return n;
        }
        return null;
    }

    static List<HtmlNode> findAll(HtmlNode[] nodes, int first, int end, Query q) {
        List<HtmlNode> out = new ArrayList<>();
        for (int i = first; i < nodes.length; i++) {
            HtmlNode n = nodes[i];
            if (n.end > end) break;
            if (q.matches(n)) out.add(n);
        }
        return out;
    }

    // <shortcuts document>
    // Generated by tools/Gen.java; do not edit by hand.

    /** Returns the first tag with the id in the document, or null. */
    public HtmlNode findById(String id) {
        if (id.isEmpty()) return null;
        return find(Query.any().id(id));
    }

    /** Returns the first tag with the name in the document, or null. */
    public HtmlNode findByName(Tag name) {
        return find(Query.by(name));
    }

    /** Returns the first tag with the name in the document, or null. */
    public HtmlNode findByName(String name) {
        return find(Query.by(name));
    }

    /** Returns the first tag with the name and the inner HTML in the document, or null. */
    public HtmlNode findByNameBody(Tag name, String body) {
        return find(Query.by(name).body(body));
    }

    /** Returns the first tag with the name and the inner HTML in the document, or null. */
    public HtmlNode findByNameBody(String name, String body) {
        return find(Query.by(name).body(body));
    }

    /** Returns the first tag with the name and the decoded attribute value in the document, or null. */
    public HtmlNode findByNameAttribute(Tag name, String attribute, String value) {
        return find(Query.by(name).attr(attribute, value));
    }

    /** Returns the first tag with the name and the decoded attribute value in the document, or null. */
    public HtmlNode findByNameAttribute(String name, String attribute, String value) {
        return find(Query.by(name).attr(attribute, value));
    }

    /** Returns the first tag with the name and the class in the document, or null. */
    public HtmlNode findByNameClass(Tag name, String cls) {
        return find(Query.by(name).hasClass(cls));
    }

    /** Returns the first tag with the name and the class in the document, or null. */
    public HtmlNode findByNameClass(String name, String cls) {
        return find(Query.by(name).hasClass(cls));
    }

    /** Returns the first tag with the name, the class and the inner HTML in the document, or null. */
    public HtmlNode findByNameClassBody(Tag name, String cls, String body) {
        return find(Query.by(name).hasClass(cls).body(body));
    }

    /** Returns the first tag with the name, the class and the inner HTML in the document, or null. */
    public HtmlNode findByNameClassBody(String name, String cls, String body) {
        return find(Query.by(name).hasClass(cls).body(body));
    }

    /** Returns the first tag with the name, and the class and visible text when they are not empty in the document, or null. */
    public HtmlNode findByNameClassText(Tag name, String cls, String text) {
        return find(Query.optText(Query.optClass(Query.by(name), cls), text));
    }

    /** Returns the first tag with the name, and the class and visible text when they are not empty in the document, or null. */
    public HtmlNode findByNameClassText(String name, String cls, String text) {
        return find(Query.optText(Query.optClass(Query.by(name), cls), text));
    }

    /** Returns the first tag with the name, and the id and visible text when they are not empty in the document, or null. */
    public HtmlNode findByNameIdText(Tag name, String id, String text) {
        return find(Query.optText(Query.optId(Query.by(name), id), text));
    }

    /** Returns the first tag with the name, and the id and visible text when they are not empty in the document, or null. */
    public HtmlNode findByNameIdText(String name, String id, String text) {
        return find(Query.optText(Query.optId(Query.by(name), id), text));
    }

    /** Returns the first tag with the name and the visible text in the document, or null. */
    public HtmlNode findByNameText(Tag name, String text) {
        return find(Query.by(name).text(text));
    }

    /** Returns the first tag with the name and the visible text in the document, or null. */
    public HtmlNode findByNameText(String name, String text) {
        return find(Query.by(name).text(text));
    }

    /** Returns the first tag with the name and the id in the document, or null. */
    public HtmlNode findByNameId(Tag name, String id) {
        return find(Query.by(name).id(id));
    }

    /** Returns the first tag with the name and the id in the document, or null. */
    public HtmlNode findByNameId(String name, String id) {
        return find(Query.by(name).id(id));
    }

    /** Returns the first tag with the name whose id starts with the prefix in the document, or null. */
    public HtmlNode findByNameIdStartsWith(Tag name, String idPrefix) {
        return find(Query.by(name).idStartsWith(idPrefix));
    }

    /** Returns the first tag with the name whose id starts with the prefix in the document, or null. */
    public HtmlNode findByNameIdStartsWith(String name, String idPrefix) {
        return find(Query.by(name).idStartsWith(idPrefix));
    }

    /** Returns the first tag with the name whose inner HTML starts with the prefix in the document, or null. */
    public HtmlNode findByNameBodyStartsWith(Tag name, String bodyPrefix) {
        return find(Query.by(name).bodyStartsWith(bodyPrefix));
    }

    /** Returns the first tag with the name whose inner HTML starts with the prefix in the document, or null. */
    public HtmlNode findByNameBodyStartsWith(String name, String bodyPrefix) {
        return find(Query.by(name).bodyStartsWith(bodyPrefix));
    }

    /** Returns the first tag with the name whose visible text starts with the prefix in the document, or null. */
    public HtmlNode findByNameTextStartsWith(Tag name, String textPrefix) {
        if (textPrefix.isEmpty()) return null;
        return find(Query.by(name).textStartsWith(textPrefix));
    }

    /** Returns the first tag with the name whose visible text starts with the prefix in the document, or null. */
    public HtmlNode findByNameTextStartsWith(String name, String textPrefix) {
        if (textPrefix.isEmpty()) return null;
        return find(Query.by(name).textStartsWith(textPrefix));
    }

    /** Returns the first tag with the name and the class whose visible text starts with the prefix in the document, or null. */
    public HtmlNode findByNameClassTextStartsWith(Tag name, String cls, String textPrefix) {
        if (textPrefix.isEmpty()) return null;
        return find(Query.by(name).hasClass(cls).textStartsWith(textPrefix));
    }

    /** Returns the first tag with the name and the class whose visible text starts with the prefix in the document, or null. */
    public HtmlNode findByNameClassTextStartsWith(String name, String cls, String textPrefix) {
        if (textPrefix.isEmpty()) return null;
        return find(Query.by(name).hasClass(cls).textStartsWith(textPrefix));
    }

    /** Returns the first tag with the name and the itemprop in the document, or null. */
    public HtmlNode findByNameItemprop(Tag name, String itemprop) {
        return find(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Returns the first tag with the name and the itemprop in the document, or null. */
    public HtmlNode findByNameItemprop(String name, String itemprop) {
        return find(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Returns all tags with the name in the document. */
    public List<HtmlNode> findAllByName(Tag name) {
        return findAll(Query.by(name));
    }

    /** Returns all tags with the name in the document. */
    public List<HtmlNode> findAllByName(String name) {
        return findAll(Query.by(name));
    }

    /** Returns all tags with the name and the decoded attribute value in the document. */
    public List<HtmlNode> findAllByNameAttribute(Tag name, String attribute, String value) {
        return findAll(Query.by(name).attr(attribute, value));
    }

    /** Returns all tags with the name and the decoded attribute value in the document. */
    public List<HtmlNode> findAllByNameAttribute(String name, String attribute, String value) {
        return findAll(Query.by(name).attr(attribute, value));
    }

    /** Returns all tags with the name and the class in the document. */
    public List<HtmlNode> findAllByNameClass(Tag name, String cls) {
        return findAll(Query.by(name).hasClass(cls));
    }

    /** Returns all tags with the name and the class in the document. */
    public List<HtmlNode> findAllByNameClass(String name, String cls) {
        return findAll(Query.by(name).hasClass(cls));
    }

    /** Returns all tags with the name and the id in the document. */
    public List<HtmlNode> findAllByNameId(Tag name, String id) {
        return findAll(Query.by(name).id(id));
    }

    /** Returns all tags with the name and the id in the document. */
    public List<HtmlNode> findAllByNameId(String name, String id) {
        return findAll(Query.by(name).id(id));
    }

    /** Returns all tags with the name whose id starts with the prefix in the document. */
    public List<HtmlNode> findAllByNameIdStartsWith(Tag name, String idPrefix) {
        return findAll(Query.by(name).idStartsWith(idPrefix));
    }

    /** Returns all tags with the name whose id starts with the prefix in the document. */
    public List<HtmlNode> findAllByNameIdStartsWith(String name, String idPrefix) {
        return findAll(Query.by(name).idStartsWith(idPrefix));
    }

    /** Returns all tags with the name and the itemprop in the document. */
    public List<HtmlNode> findAllByNameItemprop(Tag name, String itemprop) {
        return findAll(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Returns all tags with the name and the itemprop in the document. */
    public List<HtmlNode> findAllByNameItemprop(String name, String itemprop) {
        return findAll(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Returns all tags with the name whose inner HTML starts with the prefix in the document. */
    public List<HtmlNode> findAllByNameBodyStartsWith(Tag name, String bodyPrefix) {
        return findAll(Query.by(name).bodyStartsWith(bodyPrefix));
    }

    /** Returns all tags with the name whose inner HTML starts with the prefix in the document. */
    public List<HtmlNode> findAllByNameBodyStartsWith(String name, String bodyPrefix) {
        return findAll(Query.by(name).bodyStartsWith(bodyPrefix));
    }
    // </shortcuts document>
}
