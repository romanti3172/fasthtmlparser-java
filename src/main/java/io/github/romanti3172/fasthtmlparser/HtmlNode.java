package io.github.romanti3172.fasthtmlparser;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One tag of a document. Positions are UTF-16 indexes into the document source. Methods that look for another tag
 * return null when there is none; methods that read a value return "" when there is none.
 */
public final class HtmlNode {
    private final HtmlDocument document;
    private final int index;
    /** index of the enclosing tag, -1 at the top level */
    final int parentIndex;
    /** Tag ordinal, or -1 */
    final int key;
    /** lower-case name for tags that are not in Tag, "" for known tags */
    final String custom;
    final int start;
    int end;
    /** right after the opening tag; 0 when the tag is not closed */
    int innerStart;
    /** the last char before the end tag; 0 when the tag is not closed */
    int innerEnd;
    private final int depth;

    HtmlNode(HtmlDocument document, int index, int parentIndex, int key, String custom, int start, int end,
        int innerStart, int depth) {
        this.document = document;
        this.index = index;
        this.parentIndex = parentIndex;
        this.key = key;
        this.custom = custom;
        this.start = start;
        this.end = end;
        this.innerStart = innerStart;
        this.innerEnd = 0;
        this.depth = depth;
    }

    // ----- reading a tag -----

    /** The document the tag belongs to. */
    public HtmlDocument document() {
        return document;
    }

    /** The position of the tag in the document. */
    public int index() {
        return index;
    }

    /** The position of the opening '&lt;' in the source. */
    public int start() {
        return start;
    }

    /** The position of the last '&gt;': of the end tag when there is one, otherwise of the opening tag. */
    public int end() {
        return end;
    }

    /** The nesting depth: 0 for top-level tags. */
    public int depth() {
        return depth;
    }

    /** The tag as a {@link Tag}, or null for a name that is not one of the known tags. */
    public Tag tag() {
        return key >= 0 ? Tag.VALUES[key] : null;
    }

    /** The lower-case tag name ("div", "!--" for comments, "custom-tag", ...). */
    public String name() {
        return key >= 0 ? Parser.TAG_NAMES[key] : custom;
    }

    /** The inner HTML (trimmed); "" for tags that are not closed. */
    public String body() {
        if (innerStart < 0 || innerEnd <= 0) return "";
        return Chars.trimSpace(Chars.slice(document.source(), innerStart, innerEnd + 1));
    }

    /** The source of the whole element; "" for tags that are not closed. */
    public String outerHtml() {
        if (innerStart <= 0 || innerEnd <= 0) return "";
        return Chars.trimSpace(Chars.slice(document.source(), start, end + 1));
    }

    /**
     * The visible text in one line with entities decoded, as jsoup's {@code text()}: tags, comments and the content of
     * script, style, noscript, template, noframes, noembed removed; {@code <br>}, block elements and table cells
     * separate words ("a<br>b": "a b"), inline tags do not ("a<b>b</b>": "ab"); whitespace, line breaks included,
     * collapsed to one space and trimmed. Inside {@code <pre>} whitespace is kept; script, style, textarea, title, ...
     * are their content as written.
     */
    public String text() {
        String body = body();
        boolean leaf = !hasChildren();
        // script, style, textarea, title, ... without child tags: their content is text as written
        if (leaf && Parser.isRawText(key)) return Decode.decode(body, false);
        // no child tags, no '<' and no whitespace to collapse: the body is the text
        boolean plain = leaf && body.indexOf('<') < 0;
        if (plain && !Text.needsCollapse(body)) return Decode.decode(body, false);
        // <pre> and what is in it keep their whitespace (looked up only when there is whitespace to keep)
        boolean inPre = inPre();
        if (plain && inPre) return Decode.decode(body, false);
        return Text.clearTags(body, inPre);
    }

    /** Whether the tag is pre, listing or plaintext, or inside one. */
    boolean inPre() {
        if (!document.hasPre()) return false;
        for (HtmlNode t = this; t != null; t = t.parent()) {
            if (t.key == Tag.PRE.ordinal() || t.key == Tag.LISTING.ordinal() || t.key == Tag.PLAINTEXT.ordinal()) return true;
        }
        return false;
    }

    /**
     * The text with the visible line structure kept: {@code <br>} and block elements give new lines, p and headings
     * a blank line, td / th a space; lines are trimmed and joined with "\n".
     */
    public String textLineBreaks() {
        if (innerStart < 0 || innerEnd <= 0) return "";
        return TextLines.clearTagsLineBreaks(body());
    }

    /** Whether another tag starts inside this one. */
    public boolean hasChildren() {
        HtmlNode next = document.node(index + 1);
        return next != null && next.start < end;
    }

    /** The opening tag without '&lt;' and '&gt;': the name and the attributes. */
    public String source() {
        String src = document.source();
        int length;
        if (innerStart > 0 && innerEnd > 0) length = innerStart - start - 2;
        else if (end > 0 && Chars.at(src, end - 1) == '/') length = end - start - 2;
        else length = end - start - 1;
        return Chars.trimSpace(Chars.slice(src, start + 1, start + 1 + length));
    }

    @Override
    public String toString() {
        return "<" + source() + ">";
    }

    // ----- attributes -----

    /** The decoded value of the first attribute with the name (case-insensitive); "" when it is missing or has no value. */
    public String attr(String name) {
        return Attrs.attrValue(this, name);
    }

    /** The value of the attribute as written in the source. */
    public String attrRaw(String name) {
        return Attrs.attrRaw(this, name);
    }

    /** Whether the tag has the attribute, with or without a value (case-insensitive name). */
    public boolean hasAttr(String name) {
        return Attrs.hasAttribute(this, name);
    }

    /** Whether one of the space-separated classes equals cls (case-insensitive). */
    public boolean hasClass(String cls) {
        return Attrs.hasClass(this, cls);
    }

    /** Every attribute of the opening tag: (name as written, raw value) pairs in source order. */
    public List<Map.Entry<String, String>> attrs() {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (key == Tag.COMMENT.ordinal()) return out;
        Attrs.Scanner s = new Attrs.Scanner(this);
        while (s.next()) out.add(new AbstractMap.SimpleImmutableEntry<>(s.name(), s.rawValue()));
        return out;
    }

    /** The link a browser follows: href decoded and made absolute against the page URL; an empty or missing href gives the page URL. */
    public String href() {
        return Links.correctLink(Attrs.attrRaw(this, Attr.HREF), document.url(), document.base);
    }

    /** {@link #href()} resolved against baseUrl instead of the page URL. */
    public String hrefFrom(String baseUrl) {
        return Links.correctLink(Attrs.attrRaw(this, Attr.HREF), baseUrl, Links.absoluteUrl(baseUrl));
    }

    /** The src attribute decoded and made absolute against the page URL. */
    public String src() {
        return Links.correctLink(Attrs.attrRaw(this, Attr.SRC), document.url(), document.base);
    }

    /** {@link #src()} resolved against baseUrl instead of the page URL. */
    public String srcFrom(String baseUrl) {
        return Links.correctLink(Attrs.attrRaw(this, Attr.SRC), baseUrl, Links.absoluteUrl(baseUrl));
    }

    /** The address of a mailto: link (everything after the first ':'), or "". */
    public String hrefMailTo() {
        return Links.afterScheme(Attrs.attrRaw(this, Attr.HREF), "mailto");
    }

    /** The number of a tel: link (everything after the first ':'), or "". */
    public String hrefTelephone() {
        return Links.afterScheme(Attrs.attrRaw(this, Attr.HREF), "tel");
    }

    // ----- navigation -----

    /** The enclosing tag, or null. */
    public HtmlNode parent() {
        return parentIndex >= 0 ? document.nodes[parentIndex] : null;
    }

    /** The direct children: the tags inside this one, one level deeper. */
    public List<HtmlNode> children() {
        List<HtmlNode> out = new ArrayList<>();
        HtmlNode[] nodes = document.nodes;
        for (int i = index + 1; i < nodes.length && nodes[i].end <= end; i++) {
            if (nodes[i].depth == depth + 1) out.add(nodes[i]);
        }
        return out;
    }

    /** The tag that follows in document order (the first child, if there is one), or null. */
    public HtmlNode next() {
        return document.node(index + 1);
    }

    /** The tag before this one in document order, or null. */
    public HtmlNode previous() {
        return index > 0 ? document.nodes[index - 1] : null;
    }

    /** The first tag after the end of this one that matches the query (tags inside it are skipped), or null. */
    public HtmlNode nextMatching(Query q) {
        HtmlNode[] nodes = document.nodes;
        for (int i = index + 1; i < nodes.length; i++) {
            if (nodes[i].start > end && q.matches(nodes[i])) return nodes[i];
        }
        return null;
    }

    /** The next tag at the same level inside the same parent, or null. */
    public HtmlNode nextSibling() {
        return nextSiblingMatching(Query.any());
    }

    /** The next sibling that matches the query, or null. */
    public HtmlNode nextSiblingMatching(Query q) {
        // deeper tags are inside the following siblings; a shallower one ends the parent
        HtmlNode[] nodes = document.nodes;
        for (int i = index + 1; i < nodes.length && nodes[i].depth >= depth; i++) {
            if (nodes[i].depth == depth && q.matches(nodes[i])) return nodes[i];
        }
        return null;
    }

    /** The previous tag at the same level inside the same parent, or null. */
    public HtmlNode previousSibling() {
        return previousSiblingMatching(Query.any());
    }

    /** The nearest previous sibling that matches the query, or null. */
    public HtmlNode previousSiblingMatching(Query q) {
        // deeper tags are inside the previous siblings; a shallower one is the parent
        HtmlNode[] nodes = document.nodes;
        for (int i = index - 1; i >= 0 && nodes[i].depth >= depth; i--) {
            if (nodes[i].depth == depth && q.matches(nodes[i])) return nodes[i];
        }
        return null;
    }

    /** The nearest enclosing tag that matches the query, or null. */
    public HtmlNode parentMatching(Query q) {
        for (HtmlNode p = parent(); p != null; p = p.parent()) if (q.matches(p)) return p;
        return null;
    }

    /** The source text right after the tag up to the next '&lt;' (or the end of the page). */
    public String textAfterTagUntilNextOne() {
        String src = document.source();
        int from = end + 1;
        if (from >= src.length()) return "";
        int next = src.indexOf('<', from);
        return next >= 0 ? src.substring(from, next) : src.substring(from);
    }

    /** {@link #textAfterTagUntilNextOne()} as visible text: entities decoded, whitespace collapsed. */
    public String textNoTagsAfterTagUntilNextOne() {
        return Text.clearTags(textAfterTagUntilNextOne());
    }

    /** The source between the end of the tag and the next tag that matches the query; "" when there is none. */
    public String textAfterUntil(Query q) {
        HtmlNode until = nextMatching(q);
        return until == null ? "" : Chars.slice(document.source(), end + 1, until.start);
    }

    // ----- search -----

    /** The first tag inside this one (a descendant) that matches the query, or null. */
    public HtmlNode find(Query q) {
        return HtmlDocument.find(document.nodes, index + 1, end, q);
    }

    /** All tags inside this one (descendants) that match the query. */
    public List<HtmlNode> findAll(Query q) {
        return HtmlDocument.findAll(document.nodes, index + 1, end, q);
    }

    /** Whether the tag matches the query. */
    public boolean is(Query q) {
        return q.matches(this);
    }

    /** The tags inside this one that match a CSS selector (as querySelectorAll); {@code :scope} is this tag, "&gt; li" its children. */
    public List<HtmlNode> css(String selector) {
        return CssQuery.cached(selector).allIn(this);
    }

    /** The first tag inside this one that matches a CSS selector, or null. */
    public HtmlNode cssFirst(String selector) {
        return CssQuery.cached(selector).firstIn(this);
    }

    /** Whether the tag matches a CSS selector (as element.matches()). */
    public boolean isCss(String selector) {
        return CssQuery.cached(selector).matches(this);
    }

    /** The tags an XPath expression selects; relative paths start at this tag, '/' and '//' search the whole document. */
    public List<HtmlNode> xpath(String expression) {
        return XPathQuery.cached(expression).nodesFrom(this);
    }

    /** The first tag an XPath expression selects from this tag, or null. */
    public HtmlNode xpathFirst(String expression) {
        List<HtmlNode> list = xpath(expression);
        return list.isEmpty() ? null : list.get(0);
    }

    /** The values an XPath expression selects from this tag. */
    public List<String> xpathValues(String expression) {
        return XPathQuery.cached(expression).valuesFrom(this);
    }

    /** The first value an XPath expression selects from this tag, or "". */
    public String xpathValue(String expression) {
        String v = XPathQuery.cached(expression).valueFrom(this);
        return v == null ? "" : v;
    }

    // <shortcuts node>
    // Generated by tools/Gen.java; do not edit by hand.

    /** Returns the first tag with the id inside the tag, or null. */
    public HtmlNode findById(String id) {
        if (id.isEmpty()) return null;
        return find(Query.any().id(id));
    }

    /** Returns the first tag with the name inside the tag, or null. */
    public HtmlNode findByName(Tag name) {
        return find(Query.by(name));
    }

    /** Returns the first tag with the name inside the tag, or null. */
    public HtmlNode findByName(String name) {
        return find(Query.by(name));
    }

    /** Returns the first tag with the name and the inner HTML inside the tag, or null. */
    public HtmlNode findByNameBody(Tag name, String body) {
        return find(Query.by(name).body(body));
    }

    /** Returns the first tag with the name and the inner HTML inside the tag, or null. */
    public HtmlNode findByNameBody(String name, String body) {
        return find(Query.by(name).body(body));
    }

    /** Returns the first tag with the name and the decoded attribute value inside the tag, or null. */
    public HtmlNode findByNameAttribute(Tag name, String attribute, String value) {
        return find(Query.by(name).attr(attribute, value));
    }

    /** Returns the first tag with the name and the decoded attribute value inside the tag, or null. */
    public HtmlNode findByNameAttribute(String name, String attribute, String value) {
        return find(Query.by(name).attr(attribute, value));
    }

    /** Returns the first tag with the name and the class inside the tag, or null. */
    public HtmlNode findByNameClass(Tag name, String cls) {
        return find(Query.by(name).hasClass(cls));
    }

    /** Returns the first tag with the name and the class inside the tag, or null. */
    public HtmlNode findByNameClass(String name, String cls) {
        return find(Query.by(name).hasClass(cls));
    }

    /** Returns the first tag with the name, the class and the inner HTML inside the tag, or null. */
    public HtmlNode findByNameClassBody(Tag name, String cls, String body) {
        return find(Query.by(name).hasClass(cls).body(body));
    }

    /** Returns the first tag with the name, the class and the inner HTML inside the tag, or null. */
    public HtmlNode findByNameClassBody(String name, String cls, String body) {
        return find(Query.by(name).hasClass(cls).body(body));
    }

    /** Returns the first tag with the name, and the class and visible text when they are not empty inside the tag, or null. */
    public HtmlNode findByNameClassText(Tag name, String cls, String text) {
        return find(Query.optText(Query.optClass(Query.by(name), cls), text));
    }

    /** Returns the first tag with the name, and the class and visible text when they are not empty inside the tag, or null. */
    public HtmlNode findByNameClassText(String name, String cls, String text) {
        return find(Query.optText(Query.optClass(Query.by(name), cls), text));
    }

    /** Returns the first tag with the name, and the id and visible text when they are not empty inside the tag, or null. */
    public HtmlNode findByNameIdText(Tag name, String id, String text) {
        return find(Query.optText(Query.optId(Query.by(name), id), text));
    }

    /** Returns the first tag with the name, and the id and visible text when they are not empty inside the tag, or null. */
    public HtmlNode findByNameIdText(String name, String id, String text) {
        return find(Query.optText(Query.optId(Query.by(name), id), text));
    }

    /** Returns the first tag with the name and the visible text inside the tag, or null. */
    public HtmlNode findByNameText(Tag name, String text) {
        return find(Query.by(name).text(text));
    }

    /** Returns the first tag with the name and the visible text inside the tag, or null. */
    public HtmlNode findByNameText(String name, String text) {
        return find(Query.by(name).text(text));
    }

    /** Returns the first tag with the name and the id inside the tag, or null. */
    public HtmlNode findByNameId(Tag name, String id) {
        return find(Query.by(name).id(id));
    }

    /** Returns the first tag with the name and the id inside the tag, or null. */
    public HtmlNode findByNameId(String name, String id) {
        return find(Query.by(name).id(id));
    }

    /** Returns the first tag with the name whose id starts with the prefix inside the tag, or null. */
    public HtmlNode findByNameIdStartsWith(Tag name, String idPrefix) {
        return find(Query.by(name).idStartsWith(idPrefix));
    }

    /** Returns the first tag with the name whose id starts with the prefix inside the tag, or null. */
    public HtmlNode findByNameIdStartsWith(String name, String idPrefix) {
        return find(Query.by(name).idStartsWith(idPrefix));
    }

    /** Returns the first tag with the name whose inner HTML starts with the prefix inside the tag, or null. */
    public HtmlNode findByNameBodyStartsWith(Tag name, String bodyPrefix) {
        return find(Query.by(name).bodyStartsWith(bodyPrefix));
    }

    /** Returns the first tag with the name whose inner HTML starts with the prefix inside the tag, or null. */
    public HtmlNode findByNameBodyStartsWith(String name, String bodyPrefix) {
        return find(Query.by(name).bodyStartsWith(bodyPrefix));
    }

    /** Returns the first tag with the name whose visible text starts with the prefix inside the tag, or null. */
    public HtmlNode findByNameTextStartsWith(Tag name, String textPrefix) {
        if (textPrefix.isEmpty()) return null;
        return find(Query.by(name).textStartsWith(textPrefix));
    }

    /** Returns the first tag with the name whose visible text starts with the prefix inside the tag, or null. */
    public HtmlNode findByNameTextStartsWith(String name, String textPrefix) {
        if (textPrefix.isEmpty()) return null;
        return find(Query.by(name).textStartsWith(textPrefix));
    }

    /** Returns the first tag with the name and the class whose visible text starts with the prefix inside the tag, or null. */
    public HtmlNode findByNameClassTextStartsWith(Tag name, String cls, String textPrefix) {
        if (textPrefix.isEmpty()) return null;
        return find(Query.by(name).hasClass(cls).textStartsWith(textPrefix));
    }

    /** Returns the first tag with the name and the class whose visible text starts with the prefix inside the tag, or null. */
    public HtmlNode findByNameClassTextStartsWith(String name, String cls, String textPrefix) {
        if (textPrefix.isEmpty()) return null;
        return find(Query.by(name).hasClass(cls).textStartsWith(textPrefix));
    }

    /** Returns the first tag with the name and the itemprop inside the tag, or null. */
    public HtmlNode findByNameItemprop(Tag name, String itemprop) {
        return find(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Returns the first tag with the name and the itemprop inside the tag, or null. */
    public HtmlNode findByNameItemprop(String name, String itemprop) {
        return find(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Returns all tags with the name inside the tag. */
    public List<HtmlNode> findAllByName(Tag name) {
        return findAll(Query.by(name));
    }

    /** Returns all tags with the name inside the tag. */
    public List<HtmlNode> findAllByName(String name) {
        return findAll(Query.by(name));
    }

    /** Returns all tags with the name and the decoded attribute value inside the tag. */
    public List<HtmlNode> findAllByNameAttribute(Tag name, String attribute, String value) {
        return findAll(Query.by(name).attr(attribute, value));
    }

    /** Returns all tags with the name and the decoded attribute value inside the tag. */
    public List<HtmlNode> findAllByNameAttribute(String name, String attribute, String value) {
        return findAll(Query.by(name).attr(attribute, value));
    }

    /** Returns all tags with the name and the class inside the tag. */
    public List<HtmlNode> findAllByNameClass(Tag name, String cls) {
        return findAll(Query.by(name).hasClass(cls));
    }

    /** Returns all tags with the name and the class inside the tag. */
    public List<HtmlNode> findAllByNameClass(String name, String cls) {
        return findAll(Query.by(name).hasClass(cls));
    }

    /** Returns all tags with the name and the id inside the tag. */
    public List<HtmlNode> findAllByNameId(Tag name, String id) {
        return findAll(Query.by(name).id(id));
    }

    /** Returns all tags with the name and the id inside the tag. */
    public List<HtmlNode> findAllByNameId(String name, String id) {
        return findAll(Query.by(name).id(id));
    }

    /** Returns all tags with the name whose id starts with the prefix inside the tag. */
    public List<HtmlNode> findAllByNameIdStartsWith(Tag name, String idPrefix) {
        return findAll(Query.by(name).idStartsWith(idPrefix));
    }

    /** Returns all tags with the name whose id starts with the prefix inside the tag. */
    public List<HtmlNode> findAllByNameIdStartsWith(String name, String idPrefix) {
        return findAll(Query.by(name).idStartsWith(idPrefix));
    }

    /** Returns all tags with the name and the itemprop inside the tag. */
    public List<HtmlNode> findAllByNameItemprop(Tag name, String itemprop) {
        return findAll(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Returns all tags with the name and the itemprop inside the tag. */
    public List<HtmlNode> findAllByNameItemprop(String name, String itemprop) {
        return findAll(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Returns all tags with the name whose inner HTML starts with the prefix inside the tag. */
    public List<HtmlNode> findAllByNameBodyStartsWith(Tag name, String bodyPrefix) {
        return findAll(Query.by(name).bodyStartsWith(bodyPrefix));
    }

    /** Returns all tags with the name whose inner HTML starts with the prefix inside the tag. */
    public List<HtmlNode> findAllByNameBodyStartsWith(String name, String bodyPrefix) {
        return findAll(Query.by(name).bodyStartsWith(bodyPrefix));
    }

    /** Whether the tag has the name and the decoded attribute value. */
    public boolean isNameAttribute(Tag name, String attribute, String value) {
        return is(Query.by(name).attr(attribute, value));
    }

    /** Whether the tag has the name and the decoded attribute value. */
    public boolean isNameAttribute(String name, String attribute, String value) {
        return is(Query.by(name).attr(attribute, value));
    }

    /** Whether the tag has the name and the inner HTML. */
    public boolean isNameBody(Tag name, String body) {
        return is(Query.by(name).body(body));
    }

    /** Whether the tag has the name and the inner HTML. */
    public boolean isNameBody(String name, String body) {
        return is(Query.by(name).body(body));
    }

    /** Whether the tag has the name and the visible text. */
    public boolean isNameText(Tag name, String text) {
        return is(Query.by(name).text(text));
    }

    /** Whether the tag has the name and the visible text. */
    public boolean isNameText(String name, String text) {
        return is(Query.by(name).text(text));
    }

    /** Whether the tag has the name and the class. */
    public boolean isNameClass(Tag name, String cls) {
        return is(Query.by(name).hasClass(cls));
    }

    /** Whether the tag has the name and the class. */
    public boolean isNameClass(String name, String cls) {
        return is(Query.by(name).hasClass(cls));
    }

    /** Whether the tag has the name, the class and the inner HTML. */
    public boolean isNameClassBody(Tag name, String cls, String body) {
        return is(Query.by(name).hasClass(cls).body(body));
    }

    /** Whether the tag has the name, the class and the inner HTML. */
    public boolean isNameClassBody(String name, String cls, String body) {
        return is(Query.by(name).hasClass(cls).body(body));
    }

    /** Whether the tag has the name, and the class, id and inner HTML when they are not empty. */
    public boolean isNameClassIdBody(Tag name, String cls, String id, String body) {
        return is(Query.optBody(Query.optId(Query.optClass(Query.by(name), cls), id), body));
    }

    /** Whether the tag has the name, and the class, id and inner HTML when they are not empty. */
    public boolean isNameClassIdBody(String name, String cls, String id, String body) {
        return is(Query.optBody(Query.optId(Query.optClass(Query.by(name), cls), id), body));
    }

    /** Whether the tag has the name, and the class and visible text when they are not empty. */
    public boolean isNameClassText(Tag name, String cls, String text) {
        return is(Query.optText(Query.optClass(Query.by(name), cls), text));
    }

    /** Whether the tag has the name, and the class and visible text when they are not empty. */
    public boolean isNameClassText(String name, String cls, String text) {
        return is(Query.optText(Query.optClass(Query.by(name), cls), text));
    }

    /** Whether the tag has the name, and the id and visible text when they are not empty. */
    public boolean isNameIdText(Tag name, String id, String text) {
        return is(Query.optText(Query.optId(Query.by(name), id), text));
    }

    /** Whether the tag has the name, and the id and visible text when they are not empty. */
    public boolean isNameIdText(String name, String id, String text) {
        return is(Query.optText(Query.optId(Query.by(name), id), text));
    }

    /** Whether the tag has the name and the id. */
    public boolean isNameId(Tag name, String id) {
        return is(Query.by(name).id(id));
    }

    /** Whether the tag has the name and the id. */
    public boolean isNameId(String name, String id) {
        return is(Query.by(name).id(id));
    }

    /** Whether the tag has the name and its visible text starts with the prefix. */
    public boolean isNameTextStartsWith(Tag name, String textPrefix) {
        return is(Query.by(name).textStartsWith(textPrefix));
    }

    /** Whether the tag has the name and its visible text starts with the prefix. */
    public boolean isNameTextStartsWith(String name, String textPrefix) {
        return is(Query.by(name).textStartsWith(textPrefix));
    }

    /** Whether the tag has the name and its inner HTML starts with the prefix. */
    public boolean isNameBodyStartsWith(Tag name, String bodyPrefix) {
        return is(Query.by(name).bodyStartsWith(bodyPrefix));
    }

    /** Whether the tag has the name and its inner HTML starts with the prefix. */
    public boolean isNameBodyStartsWith(String name, String bodyPrefix) {
        return is(Query.by(name).bodyStartsWith(bodyPrefix));
    }

    /** Whether the tag has the name and the class and its visible text starts with the prefix. */
    public boolean isNameClassTextStartsWith(Tag name, String cls, String textPrefix) {
        return is(Query.by(name).hasClass(cls).textStartsWith(textPrefix));
    }

    /** Whether the tag has the name and the class and its visible text starts with the prefix. */
    public boolean isNameClassTextStartsWith(String name, String cls, String textPrefix) {
        return is(Query.by(name).hasClass(cls).textStartsWith(textPrefix));
    }

    /** Whether the tag has the name and its id starts with the prefix. */
    public boolean isNameIdStartsWith(Tag name, String idPrefix) {
        return is(Query.by(name).idStartsWith(idPrefix));
    }

    /** Whether the tag has the name and its id starts with the prefix. */
    public boolean isNameIdStartsWith(String name, String idPrefix) {
        return is(Query.by(name).idStartsWith(idPrefix));
    }

    /** Whether the tag has the name and the itemprop. */
    public boolean isNameItemprop(Tag name, String itemprop) {
        return is(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Whether the tag has the name and the itemprop. */
    public boolean isNameItemprop(String name, String itemprop) {
        return is(Query.by(name).attr(Attr.ITEMPROP, itemprop));
    }

    /** Whether the tag has the name and its opening tag (without '&lt;' and '&gt;') equals source. */
    public boolean isNameSource(Tag name, String source) {
        return is(Query.by(name).source(source));
    }

    /** Whether the tag has the name and its opening tag (without '&lt;' and '&gt;') equals source. */
    public boolean isNameSource(String name, String source) {
        return is(Query.by(name).source(source));
    }

    /** Returns the nearest enclosing tag with the name, or null. */
    public HtmlNode parentByName(Tag name) {
        return parentMatching(Query.by(name));
    }

    /** Returns the nearest enclosing tag with the name, or null. */
    public HtmlNode parentByName(String name) {
        return parentMatching(Query.by(name));
    }

    /** Returns the nearest enclosing tag with the name and the class, or null. */
    public HtmlNode parentByNameClass(Tag name, String cls) {
        return parentMatching(Query.by(name).hasClass(cls));
    }

    /** Returns the nearest enclosing tag with the name and the class, or null. */
    public HtmlNode parentByNameClass(String name, String cls) {
        return parentMatching(Query.by(name).hasClass(cls));
    }

    /** Returns the first tag with the name after the end of this one, or null. */
    public HtmlNode nextByName(Tag name) {
        return nextMatching(Query.by(name));
    }

    /** Returns the first tag with the name after the end of this one, or null. */
    public HtmlNode nextByName(String name) {
        return nextMatching(Query.by(name));
    }

    /** Returns the first tag with the name and the class after the end of this one, or null. */
    public HtmlNode nextByNameClass(Tag name, String cls) {
        return nextMatching(Query.by(name).hasClass(cls));
    }

    /** Returns the first tag with the name and the class after the end of this one, or null. */
    public HtmlNode nextByNameClass(String name, String cls) {
        return nextMatching(Query.by(name).hasClass(cls));
    }

    /** Returns the next sibling with the name, or null. */
    public HtmlNode nextSiblingByName(Tag name) {
        return nextSiblingMatching(Query.by(name));
    }

    /** Returns the next sibling with the name, or null. */
    public HtmlNode nextSiblingByName(String name) {
        return nextSiblingMatching(Query.by(name));
    }

    /** Returns the next sibling with the name and the class, or null. */
    public HtmlNode nextSiblingByNameClass(Tag name, String cls) {
        return nextSiblingMatching(Query.by(name).hasClass(cls));
    }

    /** Returns the next sibling with the name and the class, or null. */
    public HtmlNode nextSiblingByNameClass(String name, String cls) {
        return nextSiblingMatching(Query.by(name).hasClass(cls));
    }

    /** Returns the nearest previous sibling with the name, or null. */
    public HtmlNode previousSiblingByName(Tag name) {
        return previousSiblingMatching(Query.by(name));
    }

    /** Returns the nearest previous sibling with the name, or null. */
    public HtmlNode previousSiblingByName(String name) {
        return previousSiblingMatching(Query.by(name));
    }

    /** Returns the source between the end of this tag and the next tag with the name. */
    public String textAfterUntilTagByName(Tag name) {
        return textAfterUntil(Query.by(name));
    }

    /** Returns the source between the end of this tag and the next tag with the name. */
    public String textAfterUntilTagByName(String name) {
        return textAfterUntil(Query.by(name));
    }

    /** Returns the source between the end of this tag and the next tag with the name and the class. */
    public String textAfterUntilTagByNameClass(Tag name, String cls) {
        return textAfterUntil(Query.by(name).hasClass(cls));
    }

    /** Returns the source between the end of this tag and the next tag with the name and the class. */
    public String textAfterUntilTagByNameClass(String name, String cls) {
        return textAfterUntil(Query.by(name).hasClass(cls));
    }

    /** The decoded value of the alt attribute; "" when it is missing. */
    public String attributeAlt() {
        return attr(Attr.ALT);
    }

    /** The decoded value of the class attribute; "" when it is missing. */
    public String attributeClass() {
        return attr(Attr.CLASS);
    }

    /** The decoded value of the content attribute; "" when it is missing. */
    public String attributeContent() {
        return attr(Attr.CONTENT);
    }

    /** The decoded value of the id attribute; "" when it is missing. */
    public String attributeId() {
        return attr(Attr.ID);
    }

    /** The decoded value of the itemprop attribute; "" when it is missing. */
    public String attributeItemProp() {
        return attr(Attr.ITEMPROP);
    }

    /** The decoded value of the name attribute; "" when it is missing. */
    public String attributeName() {
        return attr(Attr.NAME);
    }

    /** The decoded value of the style attribute; "" when it is missing. */
    public String attributeStyle() {
        return attr(Attr.STYLE);
    }

    /** The decoded value of the title attribute; "" when it is missing. */
    public String attributeTitle() {
        return attr(Attr.TITLE);
    }

    /** The decoded value of the value attribute; "" when it is missing. */
    public String attributeValue() {
        return attr(Attr.VALUE);
    }
    // </shortcuts node>
}
