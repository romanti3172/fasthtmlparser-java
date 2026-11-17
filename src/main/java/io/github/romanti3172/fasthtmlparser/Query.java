package io.github.romanti3172.fasthtmlparser;

import java.util.Arrays;

/**
 * Selects tags by name and, optionally, class, id, attribute value, visible text, inner HTML and the source of the
 * opening tag. Queries are immutable: every method returns a new one. Start with {@code Query.by(Tag.DIV)},
 * {@code Query.by("my-widget")} or {@link #any()}.
 *
 * <pre>{@code
 * doc.findAll(Query.by(Tag.ARTICLE).hasClass("product_pod"));
 * pod.find(Query.by(Tag.P).hasClass("price_color")).text();
 * }</pre>
 */
public final class Query {
    private static final int HAS_CLASS = 1;
    private static final int HAS_ID = 2;
    private static final int HAS_ATTR = 4;
    private static final int HAS_TEXT = 8;
    private static final int HAS_ID_STARTS_WITH = 16;
    private static final int HAS_TEXT_STARTS_WITH = 32;
    private static final int HAS_BODY = 64;
    private static final int HAS_BODY_STARTS_WITH = 128;
    private static final int HAS_SOURCE = 256;

    private static final int SLOT_CLASS = 0;
    private static final int SLOT_ID = 1;
    private static final int SLOT_ATTR = 2;
    private static final int SLOT_ATTR_VALUE = 3;
    private static final int SLOT_TEXT = 4;
    private static final int SLOT_ID_STARTS_WITH = 5;
    private static final int SLOT_TEXT_STARTS_WITH = 6;
    private static final int SLOT_BODY = 7;
    private static final int SLOT_BODY_STARTS_WITH = 8;
    private static final int SLOT_SOURCE = 9;
    private static final int VALUE_SLOTS = 10;
    private static final String[] EMPTY = new String[0];

    private static final Query ANY = new Query(0, "", 0, EMPTY);

    /** Tag ordinal + 1; 0: by name or any */
    private final int key;
    private final String tagName;
    private final int has;
    private final String[] values;

    private Query(int key, String tagName, int has, String[] values) {
        this.key = key;
        this.tagName = tagName;
        this.has = has;
        this.values = values;
    }

    /** Selects tags with a known name. */
    public static Query by(Tag tag) {
        return new Query(tag.ordinal() + 1, "", 0, EMPTY);
    }

    /** Selects tags by name, case-insensitively; it works for names that are not in {@link Tag}. */
    public static Query by(String name) {
        int t = Parser.lookupTag(name);
        return t >= 0 ? new Query(t + 1, name, 0, EMPTY) : new Query(0, name, 0, EMPTY);
    }

    /** Selects every tag. */
    public static Query any() {
        return ANY;
    }

    private Query with(int bit, int slot, String value, int slot2, String value2) {
        String[] v = values.length > 0 ? values.clone() : newValues();
        v[slot] = value;
        if (slot2 >= 0) v[slot2] = value2;
        return new Query(key, tagName, has | bit, v);
    }

    private Query with(int bit, int slot, String value) {
        return with(bit, slot, value, -1, "");
    }

    private static String[] newValues() {
        String[] v = new String[VALUE_SLOTS];
        Arrays.fill(v, "");
        return v;
    }

    /** Adds a class criterion: one of the space-separated classes, case-insensitive. */
    public Query hasClass(String cls) {
        return with(HAS_CLASS, SLOT_CLASS, cls);
    }

    /** Adds an id criterion. */
    public Query id(String id) {
        return with(HAS_ID, SLOT_ID, id);
    }

    /** Adds an attribute criterion: the decoded value equals value. */
    public Query attr(String name, String value) {
        return with(HAS_ATTR, SLOT_ATTR, name, SLOT_ATTR_VALUE, value);
    }

    /** Adds a visible-text criterion (see {@link HtmlNode#text()}). */
    public Query text(String text) {
        return with(HAS_TEXT, SLOT_TEXT, text);
    }

    /** Adds a criterion: the decoded id starts with prefix. */
    public Query idStartsWith(String prefix) {
        return with(HAS_ID_STARTS_WITH, SLOT_ID_STARTS_WITH, prefix);
    }

    /** Adds a criterion: the visible text starts with prefix. */
    public Query textStartsWith(String prefix) {
        return with(HAS_TEXT_STARTS_WITH, SLOT_TEXT_STARTS_WITH, prefix);
    }

    /** Adds a criterion: the inner HTML (trimmed) equals body. */
    public Query body(String body) {
        return with(HAS_BODY, SLOT_BODY, body);
    }

    /** Adds a criterion: the inner HTML (trimmed) starts with prefix. */
    public Query bodyStartsWith(String prefix) {
        return with(HAS_BODY_STARTS_WITH, SLOT_BODY_STARTS_WITH, prefix);
    }

    /** Adds a criterion: the opening tag without '&lt;' and '&gt;' (see {@link HtmlNode#source()}) equals source. */
    public Query source(String source) {
        return with(HAS_SOURCE, SLOT_SOURCE, source);
    }

    /** Whether the tag matches every criterion; false for null. */
    public boolean matches(HtmlNode n) {
        if (n == null) return false;
        if (key != 0) {
            if (n.key != key - 1 && (n.key >= 0 || tagName.isEmpty() || !Chars.equalFold(n.custom, tagName))) return false;
        } else if (!tagName.isEmpty() && !Chars.equalFold(n.name(), tagName)) {
            return false;
        }
        int has = this.has;
        if (has == 0) return true;
        String[] v = values;
        if ((has & HAS_ID) != 0 && (v[SLOT_ID].isEmpty() || !Attrs.attrEquals(Attrs.attrRaw(n, "id"), v[SLOT_ID]))) return false;
        if ((has & HAS_ATTR) != 0 && !Attrs.attrEquals(Attrs.attrRaw(n, v[SLOT_ATTR]), v[SLOT_ATTR_VALUE])) return false;
        if ((has & HAS_CLASS) != 0 && !Attrs.hasClass(n, v[SLOT_CLASS])) return false;
        if ((has & HAS_ID_STARTS_WITH) != 0 && !Attrs.attrStartsWith(Attrs.attrRaw(n, Attr.ID), v[SLOT_ID_STARTS_WITH])) return false;
        if ((has & HAS_SOURCE) != 0 && !n.source().equals(v[SLOT_SOURCE])) return false;
        if ((has & HAS_BODY) != 0 && !n.body().equals(v[SLOT_BODY])) return false;
        if ((has & HAS_BODY_STARTS_WITH) != 0 && !n.body().startsWith(v[SLOT_BODY_STARTS_WITH])) return false;
        // the text last: it is the most expensive check
        if ((has & HAS_TEXT) != 0 && !Text.isText(n.body(), v[SLOT_TEXT], n)) return false;
        if ((has & HAS_TEXT_STARTS_WITH) != 0 && !n.text().startsWith(v[SLOT_TEXT_STARTS_WITH])) return false;
        return true;
    }

    // The older "optional" shortcuts skip an empty criterion.
    static Query optClass(Query q, String cls) {
        return cls.isEmpty() ? q : q.hasClass(cls);
    }

    static Query optId(Query q, String id) {
        return id.isEmpty() ? q : q.id(id);
    }

    static Query optText(Query q, String text) {
        return text.isEmpty() ? q : q.text(text);
    }

    static Query optBody(Query q, String body) {
        return body.isEmpty() ? q : q.body(body);
    }
}
