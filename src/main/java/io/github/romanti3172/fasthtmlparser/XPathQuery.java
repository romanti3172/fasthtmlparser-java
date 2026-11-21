package io.github.romanti3172.fasthtmlparser;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A compiled XPath 1.0 expression over the flat tag list: the root, elements, comments, text nodes (runs of source
 * text between tags) and attributes (positions in the opening tag). Compile once and reuse it on any number of
 * documents and threads; the string methods ({@code node.xpath(...)}, {@code doc.xpathValue(...)}, ...) compile and
 * cache expressions automatically.
 *
 * <p>All axes (except namespace), predicates, {@code |}, the operators and the XPath 1.0 functions are supported, plus
 * {@code ends-with()}, {@code lower-case()} and {@code upper-case()}. HTML rules apply: names are case-insensitive,
 * the string-value of an element is its visible text (as {@link HtmlNode#text()}), attribute values and text are
 * decoded. Variables are not supported. On a node, relative paths start at the node; '/' and '//' search the whole
 * document.
 */
public final class XPathQuery {
    private final String expression;
    private final Expr expr;

    private XPathQuery(String expression, Expr expr) {
        this.expression = expression;
        this.expr = expr;
    }

    /** Compiles an XPath 1.0 expression; throws {@link XPathException} on an invalid one. */
    public static XPathQuery compile(String expression) {
        Compiler c = new Compiler(expression);
        Expr expr = c.parseExpr();
        if (c.peek(0).kind != T_END) throw c.fail("unexpected " + c.describe(c.peek(0)));
        return new XPathQuery(expression, expr);
    }

    /** The source of the expression. */
    public String expression() {
        return expression;
    }

    @Override
    public String toString() {
        return expression;
    }

    /**
     * The selected tags (elements and comments) in document order, evaluated on the document. Throws
     * {@link XPathException} when the expression does not give a node-set (use {@link #value(HtmlDocument)}).
     */
    public List<HtmlNode> nodes(HtmlDocument d) {
        return tagsOf(rootNode(d));
    }

    /** {@link #nodes(HtmlDocument)} evaluated on a tag: relative paths start at it. */
    public List<HtmlNode> nodesFrom(HtmlNode n) {
        return tagsOf(tagNode(n));
    }

    /**
     * The string-values of the selected nodes, or the result of an expression that gives a number, string or
     * boolean (count(//a), concat(...)) as one value.
     */
    public List<String> values(HtmlDocument d) {
        return valuesOf(rootNode(d));
    }

    /** {@link #values(HtmlDocument)} evaluated on a tag. */
    public List<String> valuesFrom(HtmlNode n) {
        return valuesOf(tagNode(n));
    }

    /** The string-value of the first selected node, or the result of the expression; null when it selects no node. */
    public String value(HtmlDocument d) {
        return valueOf(rootNode(d));
    }

    /** {@link #value(HtmlDocument)} evaluated on a tag. */
    public String valueFrom(HtmlNode n) {
        return valueOf(tagNode(n));
    }

    private Object evaluate(XNode context) {
        return expr.eval(new Ctx(context, 1, 1, new EvalState(context.doc)));
    }

    private List<HtmlNode> tagsOf(XNode context) {
        if (expr.type != TYPE_NODE_SET) {
            throw new XPathException("the XPath " + Json.quote(expression) + " gives a " + TYPE_NAMES[expr.type]
                + ", not tags; use xpathValue");
        }
        List<XNode> set = nodeSet(evaluate(context));
        List<HtmlNode> tags = new ArrayList<>(set.size());
        for (XNode n : set) if (n.kind == K_ELEMENT || n.kind == K_COMMENT) tags.add(n.tag);
        return tags;
    }

    private List<String> valuesOf(XNode context) {
        Object result = evaluate(context);
        if (!(result instanceof List)) return Collections.singletonList(toStr(result));
        List<XNode> set = nodeSet(result);
        List<String> out = new ArrayList<>(set.size());
        for (XNode n : set) out.add(stringValue(n));
        return out;
    }

    private String valueOf(XNode context) {
        Object result = evaluate(context);
        if (result instanceof List) {
            List<XNode> set = nodeSet(result);
            return set.isEmpty() ? null : stringValue(set.get(0));
        }
        return toStr(result);
    }

    private static final Map<String, XPathQuery> CACHE = new ConcurrentHashMap<>();

    static XPathQuery cached(String expression) {
        XPathQuery q = CACHE.get(expression);
        if (q == null) {
            q = compile(expression);
            if (CACHE.size() >= 1024) CACHE.clear(); // dynamically built expressions must not grow the cache forever
            CACHE.put(expression, q);
        }
        return q;
    }

    @SuppressWarnings("unchecked")
    private static List<XNode> nodeSet(Object v) {
        return (List<XNode>) v;
    }

    // ----- the data model over the flat tag list -----

    private static final int K_ROOT = 0;
    private static final int K_ELEMENT = 1;
    private static final int K_COMMENT = 2;
    private static final int K_TEXT = 3;
    private static final int K_ATTRIBUTE = 4;

    private static final int COMMENT = Tag.COMMENT.ordinal();

    /** A node of the XPath data model. */
    private static final class XNode {
        final int kind;
        final HtmlDocument doc;
        /** element / comment: the tag; text / attribute: the parent element (null: the root) */
        final HtmlNode tag;
        /** text: first char and length; attribute: name start and length */
        final int start;
        final int length;
        /** attribute: value start (-1 for no value) and length */
        final int valueStart;
        final int valueLength;

        XNode(int kind, HtmlDocument doc, HtmlNode tag, int start, int length, int valueStart, int valueLength) {
            this.kind = kind;
            this.doc = doc;
            this.tag = tag;
            this.start = start;
            this.length = length;
            this.valueStart = valueStart;
            this.valueLength = valueLength;
        }

        XNode(int kind, HtmlDocument doc, HtmlNode tag) {
            this(kind, doc, tag, 0, 0, 0, 0);
        }

        /** Document order and identity: tags and text nodes by their first char; attributes right after their element. */
        int major() {
            switch (kind) {
                case K_ROOT:
                    return -1;
                case K_TEXT:
                    return start;
                default:
                    return tag.start;
            }
        }

        int minor() {
            return kind == K_ATTRIBUTE ? start : 0;
        }

        /** The source positions the node covers (for following / preceding). */
        int rangeStart() {
            switch (kind) {
                case K_ROOT:
                    return -1;
                case K_TEXT:
                    return start;
                default:
                    return tag.start;
            }
        }

        long rangeEnd() {
            switch (kind) {
                case K_ROOT:
                    return Long.MAX_VALUE;
                case K_ELEMENT:
                case K_COMMENT:
                    return tag.end;
                case K_TEXT:
                    return start + length - 1;
                default:
                    return tag.start; // attribute: the children of its element follow it
            }
        }

        String attrName() {
            return doc.source().substring(start, start + length);
        }
    }

    private static int compareKeys(XNode a, XNode b) {
        int c = Integer.compare(a.major(), b.major());
        return c != 0 ? c : Integer.compare(a.minor(), b.minor());
    }

    private static final Comparator<XNode> DOCUMENT_ORDER = XPathQuery::compareKeys;

    private static XNode rootNode(HtmlDocument d) {
        return new XNode(K_ROOT, d, null);
    }

    private static XNode tagNode(HtmlNode n) {
        return new XNode(n.key == COMMENT ? K_COMMENT : K_ELEMENT, n.document(), n);
    }

    // ----- node tests -----

    private static final int TK_NAME = 0;
    private static final int TK_PREFIX = 1; // dc:*
    private static final int TK_WILDCARD = 2; // *
    private static final int TK_NODE = 3; // node()
    private static final int TK_TEXT = 4; // text()
    private static final int TK_COMMENT = 5; // comment()
    private static final int TK_PI = 6; // processing-instruction(): never matches
    private static final int TK_CONTAINER = 7; // internal: the root and elements (// before a child or attribute step)

    private static final class NodeTest {
        final Query query;
        final int kind;
        final String name;

        NodeTest(int kind, String name) {
            this.kind = kind;
            this.name = name;
            this.query = kind == TK_NAME ? Query.by(name) : null;
        }

        NodeTest(int kind) {
            this(kind, "");
        }

        boolean matchesText() {
            return kind == TK_NODE || kind == TK_TEXT;
        }

        boolean matchesTags() {
            return kind != TK_TEXT && kind != TK_PI;
        }

        boolean matchesTag(HtmlNode n) {
            switch (kind) {
                case TK_NAME:
                    return query.matches(n);
                case TK_PREFIX: {
                    String nodeName = n.name();
                    return CssQuery.isElement(n) && nodeName.length() >= name.length()
                        && Chars.equalFold(nodeName.substring(0, name.length()), name);
                }
                case TK_WILDCARD:
                case TK_CONTAINER:
                    return CssQuery.isElement(n);
                case TK_NODE:
                    return CssQuery.isElement(n) || n.key == COMMENT;
                case TK_COMMENT:
                    return n.key == COMMENT;
                default:
                    return false;
            }
        }

        boolean matchesAttribute(String attribute) {
            switch (kind) {
                case TK_NAME:
                    return Chars.equalFold(attribute, name);
                case TK_PREFIX:
                    return attribute.length() >= name.length() && Chars.equalFold(attribute.substring(0, name.length()), name);
                case TK_WILDCARD:
                case TK_NODE:
                    return true;
                default:
                    return false;
            }
        }

        boolean matches(XNode x, boolean attributeAxis) {
            switch (x.kind) {
                case K_ROOT:
                    return kind == TK_NODE || kind == TK_CONTAINER;
                case K_ELEMENT:
                case K_COMMENT:
                    return matchesTag(x.tag);
                case K_TEXT:
                    return matchesText();
                default:
                    if (attributeAxis) return matchesAttribute(x.attrName());
                    return kind == TK_NODE;
            }
        }
    }

    private static final NodeTest ANY_NODE_TEST = new NodeTest(TK_NODE);
    private static final NodeTest CONTAINERS_TEST = new NodeTest(TK_CONTAINER);

    // ----- navigation -----

    /** Appends the children of the root or an element that match the test, text nodes included, in document order. */
    private static List<XNode> addChildren(XNode x, NodeTest test, List<XNode> out) {
        boolean wantText = test.matchesText();
        boolean wantTags = test.matchesTags();
        if (!wantText && !wantTags) return out;
        int first;
        int end;
        int childDepth;
        int p;
        int textEnd;
        if (x.kind == K_ROOT) {
            first = 0;
            end = Integer.MAX_VALUE;
            childDepth = 0;
            p = 0;
            textEnd = x.doc.source().length();
        } else if (x.kind == K_ELEMENT && x.tag.innerStart > 0 && x.tag.innerEnd > 0) {
            HtmlNode t = x.tag;
            first = t.index() + 1;
            end = t.end;
            childDepth = t.depth() + 1;
            p = t.innerStart;
            textEnd = t.innerEnd + 1;
        } else {
            return out;
        }
        HtmlNode[] nodes = x.doc.nodes;
        HtmlNode parent = x.kind == K_ELEMENT ? x.tag : null;
        for (int i = first; i < nodes.length; i++) {
            HtmlNode t = nodes[i];
            if (t.end > end) break;
            if (t.depth() != childDepth) continue;
            if (wantText && t.start > p) addText(x.doc, parent, p, Math.min(t.start, textEnd), out);
            if (wantTags && test.matchesTag(t)) out.add(tagNode(t));
            p = t.end + 1;
        }
        if (wantText && textEnd > p) addText(x.doc, parent, p, textEnd, out);
        return out;
    }

    private static void addText(HtmlDocument d, HtmlNode parent, int start, int end, List<XNode> out) {
        if (end > start) out.add(new XNode(K_TEXT, d, parent, start, end - start, 0, 0));
    }

    /** Appends the descendants of the root or an element that match the test, in document order. */
    private static List<XNode> addDescendants(XNode x, NodeTest test, List<XNode> out) {
        if (test.matchesText()) return addDescendantsWithText(x, test, out);
        if (!test.matchesTags()) return out;
        int first;
        int end;
        if (x.kind == K_ROOT) {
            first = 0;
            end = Integer.MAX_VALUE;
        } else if (x.kind == K_ELEMENT && x.tag.innerStart > 0 && x.tag.innerEnd > 0) {
            first = x.tag.index() + 1;
            end = x.tag.end;
        } else {
            return out;
        }
        // tags only: one linear scan of the flat list
        HtmlNode[] nodes = x.doc.nodes;
        for (int i = first; i < nodes.length; i++) {
            HtmlNode t = nodes[i];
            if (t.end > end) break;
            if (test.matchesTag(t)) out.add(tagNode(t));
        }
        return out;
    }

    private static List<XNode> addDescendantsWithText(XNode x, NodeTest test, List<XNode> out) {
        for (XNode child : addChildren(x, ANY_NODE_TEST, new ArrayList<>())) {
            if (test.matches(child, false)) out.add(child);
            if (child.kind == K_ELEMENT) addDescendantsWithText(child, test, out);
        }
        return out;
    }

    private static XNode parentNode(XNode x) {
        switch (x.kind) {
            case K_ROOT:
                return null;
            case K_TEXT:
            case K_ATTRIBUTE:
                return x.tag != null ? tagNode(x.tag) : rootNode(x.doc);
            default: {
                HtmlNode t = x.tag;
                return t.depth() > 0 && t.parentIndex >= 0 ? tagNode(x.doc.nodes[t.parentIndex]) : rootNode(x.doc);
            }
        }
    }

    private static List<XNode> addAttributes(XNode x, NodeTest test, List<XNode> out) {
        if (x.kind != K_ELEMENT) return out;
        Attrs.Scanner s = new Attrs.Scanner(x.tag);
        while (s.next()) {
            if (test.matchesAttribute(s.name())) {
                out.add(new XNode(K_ATTRIBUTE, x.doc, x.tag, s.nameStart, s.nameLength, s.valueStart, s.valueLength));
            }
        }
        return out;
    }

    /** The raw value of the first attribute with the name; null when the element has none. */
    private static String attributeOf(XNode x, String name) {
        if (x.kind != K_ELEMENT) return null;
        Attrs.Scanner s = new Attrs.Scanner(x.tag);
        while (s.next()) if (Chars.equalFold(s.name(), name)) return s.rawValue();
        return null;
    }

    /** The root and elements give their visible text; text nodes and attributes their decoded value; comments their content. */
    private static String stringValue(XNode x) {
        switch (x.kind) {
            case K_ROOT:
                return Text.clearTags(x.doc.source());
            case K_ELEMENT:
                return x.tag.text();
            case K_ATTRIBUTE:
                return x.valueStart < 0 ? "" : Decode.decode(x.doc.source().substring(x.valueStart, x.valueStart + x.valueLength), true);
            case K_TEXT: {
                String text = x.doc.source().substring(x.start, x.start + x.length);
                if (x.tag != null && Parser.isRawText(x.tag.key)) return Decode.normalizeNewlines(text);
                return Decode.decode(text, false);
            }
            default: {
                // comment
                HtmlNode t = x.tag;
                String source = Chars.slice(t.document().source(), t.start, t.end + 1);
                if (source.startsWith("<!--")) source = source.substring(4);
                if (source.endsWith("-->")) source = source.substring(0, source.length() - 3);
                return source;
            }
        }
    }

    /** name() / local-name(): the lower-case tag or attribute name; "" for other nodes. */
    private static String nodeName(XNode x) {
        if (x.kind == K_ELEMENT) return x.tag.name();
        if (x.kind == K_ATTRIBUTE) return Chars.lower(x.attrName());
        return "";
    }

    // ----- values: a node-set (List<XNode>), Double, String or Boolean -----

    private static final int TYPE_NODE_SET = 0;
    private static final int TYPE_NUMBER = 1;
    private static final int TYPE_STRING = 2;
    private static final int TYPE_BOOLEAN = 3;
    private static final String[] TYPE_NAMES = {"node-set", "number", "string", "boolean"};

    private static boolean toBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) {
            double d = (Double) v;
            return d != 0 && !Double.isNaN(d);
        }
        if (v instanceof String) return !((String) v).isEmpty();
        return !nodeSet(v).isEmpty();
    }

    private static double toNumber(Object v) {
        if (v instanceof Double) return (Double) v;
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        if (v instanceof String) return stringToNumber((String) v);
        List<XNode> set = nodeSet(v);
        return set.isEmpty() ? Double.NaN : stringToNumber(stringValue(set.get(0)));
    }

    /** Optional '-', digits with an optional decimal point; anything else is NaN. */
    private static double stringToNumber(String value) {
        String s = Chars.trimSpace(value);
        String body = s.startsWith("-") ? s.substring(1) : s;
        int digits = 0;
        int point = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c >= '0' && c <= '9') digits++;
            else if (c == '.') point++;
            else return Double.NaN;
        }
        if (digits == 0 || point > 1) return Double.NaN;
        return Double.parseDouble(s);
    }

    private static String toStr(Object v) {
        if (v instanceof String) return (String) v;
        if (v instanceof Boolean) return (Boolean) v ? "true" : "false";
        if (v instanceof Double) return numberToString((Double) v);
        List<XNode> set = nodeSet(v);
        return set.isEmpty() ? "" : stringValue(set.get(0));
    }

    /** The shortest round-trip digits, written without an exponent (XPath has none), as JavaScript writes a number. */
    static String numberToString(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (d == Double.POSITIVE_INFINITY) return "Infinity";
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (d == 0) return "0";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        // the fewest significant digits that read back as d, rounded from the exact binary value
        BigDecimal exact = new BigDecimal(d);
        BigDecimal r = exact;
        for (int precision = 1; precision <= 17; precision++) {
            r = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (r.doubleValue() == d) break;
        }
        r = r.stripTrailingZeros();
        String digits = r.unscaledValue().abs().toString();
        int pointAt = digits.length() - r.scale(); // position of the decimal point in digits
        StringBuilder out = new StringBuilder();
        if (d < 0) out.append('-');
        if (pointAt <= 0) {
            out.append("0.");
            for (int i = 0; i < -pointAt; i++) out.append('0');
            out.append(digits);
        } else if (pointAt >= digits.length()) {
            out.append(digits);
            for (int i = digits.length(); i < pointAt; i++) out.append('0');
        } else {
            out.append(digits, 0, pointAt).append('.').append(digits, pointAt, digits.length());
        }
        return out.toString();
    }

    /** Puts the nodes in document order without duplicates. */
    private static List<XNode> sortUnique(List<XNode> nodes) {
        if (nodes.size() < 2) return nodes;
        boolean sorted = true;
        for (int i = 1; i < nodes.size(); i++) {
            if (compareKeys(nodes.get(i - 1), nodes.get(i)) >= 0) {
                sorted = false;
                break;
            }
        }
        if (sorted) return nodes;
        nodes.sort(DOCUMENT_ORDER); // stable
        int w = 1;
        for (int i = 1; i < nodes.size(); i++) {
            if (compareKeys(nodes.get(i), nodes.get(w - 1)) != 0) nodes.set(w++, nodes.get(i));
        }
        nodes.subList(w, nodes.size()).clear();
        return nodes;
    }

    // ----- evaluation -----

    private static final class EvalState {
        private List<XNode> all; // every element, comment and text node in document order
        final HtmlDocument doc;

        EvalState(HtmlDocument doc) {
            this.doc = doc;
        }

        List<XNode> allNodes() {
            if (all == null) all = addDescendants(rootNode(doc), ANY_NODE_TEST, new ArrayList<>());
            return all;
        }
    }

    private static final class Ctx {
        final XNode node;
        final int position;
        final int size;
        final EvalState state;

        Ctx(XNode node, int position, int size, EvalState state) {
            this.node = node;
            this.position = position;
            this.size = size;
            this.state = state;
        }
    }

    private abstract static class Expr {
        final int type;

        Expr(int type) {
            this.type = type;
        }

        /** whether position() or last() is used in the current context (not inside nested predicates) */
        abstract boolean usesPosition();

        abstract Object eval(Ctx c);

        boolean evalBool(Ctx c) {
            return toBoolean(eval(c));
        }
    }

    private static List<XNode> evalNodes(Expr e, Ctx c) {
        Object v = e.eval(c);
        if (v instanceof List) return nodeSet(v);
        throw new XPathException("the XPath expression does not give a node-set");
    }

    private static String evalString(Expr e, Ctx c) {
        return toStr(e.eval(c));
    }

    private static double evalNumber(Expr e, Ctx c) {
        return toNumber(e.eval(c));
    }

    /** A number compares with the position, anything else is converted to boolean. */
    private static boolean evalPredicate(Expr e, Ctx c) {
        if (e.type == TYPE_BOOLEAN) return e.evalBool(c);
        Object v = e.eval(c);
        if (v instanceof Double) return (Double) v == c.position;
        return toBoolean(v);
    }

    /** Whether the predicate depends on the position of the node (a number, position(), last()). */
    private static boolean isPositional(Expr e) {
        return e.type == TYPE_NUMBER || e.usesPosition();
    }

    private static final class LiteralExpr extends Expr {
        final String value;

        LiteralExpr(String value) {
            super(TYPE_STRING);
            this.value = value;
        }

        @Override
        boolean usesPosition() {
            return false;
        }

        @Override
        Object eval(Ctx c) {
            return value;
        }

        @Override
        boolean evalBool(Ctx c) {
            return !value.isEmpty();
        }
    }

    private static final class NumberExpr extends Expr {
        final Double value;

        NumberExpr(double value) {
            super(TYPE_NUMBER);
            this.value = value;
        }

        @Override
        boolean usesPosition() {
            return false;
        }

        @Override
        Object eval(Ctx c) {
            return value;
        }
    }

    private static final class LogicExpr extends Expr {
        final boolean and;
        final Expr left;
        final Expr right;

        LogicExpr(boolean and, Expr left, Expr right) {
            super(TYPE_BOOLEAN);
            this.and = and;
            this.left = left;
            this.right = right;
        }

        @Override
        boolean usesPosition() {
            return left.usesPosition() || right.usesPosition();
        }

        @Override
        Object eval(Ctx c) {
            return evalBool(c);
        }

        @Override
        boolean evalBool(Ctx c) {
            return and ? left.evalBool(c) && right.evalBool(c) : left.evalBool(c) || right.evalBool(c);
        }
    }

    private static final int OP_EQ = 0;
    private static final int OP_NEQ = 1;
    private static final int OP_LT = 2;
    private static final int OP_LE = 3;
    private static final int OP_GT = 4;
    private static final int OP_GE = 5;

    private static final class CompareExpr extends Expr {
        final int op;
        final Expr left;
        final Expr right;

        CompareExpr(int op, Expr left, Expr right) {
            super(TYPE_BOOLEAN);
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        boolean usesPosition() {
            return left.usesPosition() || right.usesPosition();
        }

        @Override
        Object eval(Ctx c) {
            return evalBool(c);
        }

        @Override
        boolean evalBool(Ctx c) {
            return compare(op, left.eval(c), right.eval(c));
        }
    }

    private static boolean compare(int op, Object a, Object b) {
        if (a instanceof List) {
            List<XNode> setA = nodeSet(a);
            if (b instanceof List) {
                List<XNode> setB = nodeSet(b);
                if (setA.isEmpty() || setB.isEmpty()) return false;
                List<String> valuesB = new ArrayList<>(setB.size());
                for (XNode n : setB) valuesB.add(stringValue(n));
                for (XNode x : setA) {
                    String value = stringValue(x);
                    for (String other : valuesB) if (compareAtoms(op, value, other)) return true;
                }
                return false;
            }
            return compareWithNodes(op, setA, b, true);
        }
        if (b instanceof List) return compareWithNodes(op, nodeSet(b), a, false);
        return compareAtoms(op, a, b);
    }

    private static boolean compareWithNodes(int op, List<XNode> nodes, Object other, boolean nodesOnLeft) {
        if (other instanceof Boolean) {
            Boolean asBool = !nodes.isEmpty();
            return nodesOnLeft ? compareAtoms(op, asBool, other) : compareAtoms(op, other, asBool);
        }
        boolean isNumber = other instanceof Double;
        for (XNode n : nodes) {
            String s = stringValue(n);
            Object value = isNumber ? (Object) stringToNumber(s) : s;
            if (nodesOnLeft ? compareAtoms(op, value, other) : compareAtoms(op, other, value)) return true;
        }
        return false;
    }

    private static boolean compareAtoms(int op, Object a, Object b) {
        if (op == OP_EQ || op == OP_NEQ) {
            boolean eq;
            if (a instanceof Boolean || b instanceof Boolean) eq = toBoolean(a) == toBoolean(b);
            else if (a instanceof Double || b instanceof Double) eq = toNumber(a) == toNumber(b);
            else eq = toStr(a).equals(toStr(b));
            return eq == (op == OP_EQ);
        }
        double x = toNumber(a);
        double y = toNumber(b);
        switch (op) {
            case OP_LT:
                return x < y;
            case OP_LE:
                return x <= y;
            case OP_GT:
                return x > y;
            default:
                return x >= y;
        }
    }

    private static final int ARITH_ADD = 0;
    private static final int ARITH_SUB = 1;
    private static final int ARITH_MUL = 2;
    private static final int ARITH_DIV = 3;
    private static final int ARITH_MOD = 4;

    private static final class ArithExpr extends Expr {
        final int op;
        final Expr left;
        final Expr right;

        ArithExpr(int op, Expr left, Expr right) {
            super(TYPE_NUMBER);
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        boolean usesPosition() {
            return left.usesPosition() || right.usesPosition();
        }

        @Override
        Object eval(Ctx c) {
            double x = evalNumber(left, c);
            double y = evalNumber(right, c);
            switch (op) {
                case ARITH_ADD:
                    return x + y;
                case ARITH_SUB:
                    return x - y;
                case ARITH_MUL:
                    return x * y;
                case ARITH_DIV:
                    return x / y;
                default:
                    return x % y;
            }
        }
    }

    private static final class NegateExpr extends Expr {
        final Expr operand;

        NegateExpr(Expr operand) {
            super(TYPE_NUMBER);
            this.operand = operand;
        }

        @Override
        boolean usesPosition() {
            return operand.usesPosition();
        }

        @Override
        Object eval(Ctx c) {
            return -evalNumber(operand, c);
        }
    }

    private static final class UnionExpr extends Expr {
        final Expr left;
        final Expr right;

        UnionExpr(Expr left, Expr right) {
            super(TYPE_NODE_SET);
            this.left = left;
            this.right = right;
        }

        @Override
        boolean usesPosition() {
            return left.usesPosition() || right.usesPosition();
        }

        @Override
        Object eval(Ctx c) {
            List<XNode> a = evalNodes(left, c);
            List<XNode> b = evalNodes(right, c);
            List<XNode> all = new ArrayList<>(a.size() + b.size());
            all.addAll(a);
            all.addAll(b);
            return sortUnique(all);
        }
    }

    /** {@code @name = 'literal'}, compared in place without building a node-set. */
    private static final class AttributeEqualsExpr extends Expr {
        final String name;
        final String value;
        final boolean negate;

        AttributeEqualsExpr(String name, String value, boolean negate) {
            super(TYPE_BOOLEAN);
            this.name = name;
            this.value = value;
            this.negate = negate;
        }

        @Override
        boolean usesPosition() {
            return false;
        }

        @Override
        Object eval(Ctx c) {
            return evalBool(c);
        }

        @Override
        boolean evalBool(Ctx c) {
            // '!=' is true when the attribute exists and differs (a missing attribute is an empty node-set)
            String raw = attributeOf(c.node, name);
            return raw != null && Attrs.attrEquals(raw, value) != negate;
        }
    }

    /** {@code [@name]} */
    private static final class AttributeExistsExpr extends Expr {
        final String name;

        AttributeExistsExpr(String name) {
            super(TYPE_BOOLEAN);
            this.name = name;
        }

        @Override
        boolean usesPosition() {
            return false;
        }

        @Override
        Object eval(Ctx c) {
            return evalBool(c);
        }

        @Override
        boolean evalBool(Ctx c) {
            return attributeOf(c.node, name) != null;
        }
    }

    private static final int AXIS_CHILD = 0;
    private static final int AXIS_DESCENDANT = 1;
    private static final int AXIS_DESCENDANT_OR_SELF = 2;
    private static final int AXIS_SELF = 3;
    private static final int AXIS_PARENT = 4;
    private static final int AXIS_ANCESTOR = 5;
    private static final int AXIS_ANCESTOR_OR_SELF = 6;
    private static final int AXIS_FOLLOWING_SIBLING = 7;
    private static final int AXIS_PRECEDING_SIBLING = 8;
    private static final int AXIS_FOLLOWING = 9;
    private static final int AXIS_PRECEDING = 10;
    private static final int AXIS_ATTRIBUTE = 11;

    private static final class Step {
        final int axis;
        final NodeTest test;
        final List<Expr> predicates;

        Step(int axis, NodeTest test, List<Expr> predicates) {
            this.axis = axis;
            this.test = test;
            this.predicates = predicates;
        }

        Step(int axis, NodeTest test) {
            this(axis, test, new ArrayList<>());
        }

        boolean isReverse() {
            return axis == AXIS_ANCESTOR || axis == AXIS_ANCESTOR_OR_SELF || axis == AXIS_PRECEDING || axis == AXIS_PRECEDING_SIBLING;
        }

        boolean hasPositionalPredicates() {
            for (Expr p : predicates) if (isPositional(p)) return true;
            return false;
        }

        List<XNode> apply(List<XNode> input, EvalState state) {
            if (input.size() == 1) return selectFrom(input.get(0), new ArrayList<>(), state);

            // Nested input nodes (//div//a on divs inside divs, //a/ancestor::* on links in one list) give the same
            // nodes again and again: n nested nodes would collect about n² nodes before the duplicates are removed.
            // When the predicates do not depend on the position, a node passes them or not whichever input node it
            // comes from, so the input nodes whose results are already collected are skipped: the work stays linear.
            if (!hasPositionalPredicates()) {
                switch (axis) {
                    case AXIS_DESCENDANT:
                    case AXIS_DESCENDANT_OR_SELF:
                        if (!test.matchesText() && inDocumentOrder(input)) return sortUnique(selectDescendantsOfNested(input, state));
                        break;
                    case AXIS_ANCESTOR:
                    case AXIS_ANCESTOR_OR_SELF:
                        return sortUnique(selectSharedAncestors(input, state));
                    default:
                        break;
                }
            }

            List<XNode> out = new ArrayList<>();
            for (XNode x : input) selectFrom(x, out, state);
            return sortUnique(out);
        }

        /**
         * The descendant axes (tags only) on input nodes in document order. A tag that lies in the part of the tag
         * list already scanned for an earlier input node is skipped: that scan has tested it and every tag its own
         * scan would test.
         */
        List<XNode> selectDescendantsOfNested(List<XNode> input, EvalState state) {
            HtmlNode[] nodes = state.doc.nodes;
            List<XNode> out = new ArrayList<>();
            int scannedTo = -1; // the tags before this index were scanned for an earlier input node
            for (XNode x : input) {
                if (x.kind == K_ELEMENT || x.kind == K_COMMENT) {
                    HtmlNode t = x.tag;
                    if (t.index() < scannedTo) continue;
                    selectFrom(x, out, state);
                    // where addDescendants stops for this tag
                    if (x.kind == K_ELEMENT && t.innerStart > 0 && t.innerEnd > 0) {
                        int k = t.index() + 1;
                        while (k < nodes.length && nodes[k].end <= t.end) k++;
                        scannedTo = Math.max(scannedTo, k);
                    }
                } else {
                    selectFrom(x, out, state);
                    if (x.kind == K_ROOT) scannedTo = Integer.MAX_VALUE;
                }
            }
            return out;
        }

        /**
         * The walk up from an input node stops at an ancestor already reached from an earlier one, because that walk
         * has gone on to the root. Only the root and elements are marked: they are the only parents.
         */
        List<XNode> selectSharedAncestors(List<XNode> input, EvalState state) {
            Set<Integer> visited = new HashSet<>();
            List<XNode> out = new ArrayList<>();
            for (XNode x : input) {
                int start = out.size();
                XNode current = x;
                boolean skip = false;
                if (axis == AXIS_ANCESTOR_OR_SELF) {
                    if ((current.kind == K_ROOT || current.kind == K_ELEMENT) && !visited.add(current.major())) skip = true;
                    else if (test.matches(current, false)) out.add(current);
                }
                if (skip) continue;
                for (;;) {
                    XNode p = parentNode(current);
                    if (p == null || !visited.add(p.major())) break;
                    current = p;
                    if (test.matches(current, false)) out.add(current);
                }
                if (!predicates.isEmpty() && out.size() > start) {
                    List<XNode> kept = filter(new ArrayList<>(out.subList(start, out.size())), predicates, state);
                    out.subList(start, out.size()).clear();
                    out.addAll(kept);
                }
            }
            return out;
        }

        /** Appends the nodes of the axis that pass the test and the predicates, in document order. */
        List<XNode> selectFrom(XNode x, List<XNode> out, EvalState state) {
            if (predicates.isEmpty() && !isReverse()) return collect(x, out, state);
            List<XNode> selected = filter(collect(x, new ArrayList<>(), state), predicates, state);
            if (isReverse()) Collections.reverse(selected);
            out.addAll(selected);
            return out;
        }

        /** Appends the nodes of the axis that pass the node test, in axis order (reverse axes: nearest first). */
        List<XNode> collect(XNode x, List<XNode> out, EvalState state) {
            switch (axis) {
                case AXIS_CHILD:
                    return addChildren(x, test, out);
                case AXIS_DESCENDANT:
                    return addDescendants(x, test, out);
                case AXIS_DESCENDANT_OR_SELF:
                    if (test.matches(x, false)) out.add(x);
                    return addDescendants(x, test, out);
                case AXIS_SELF:
                    if (test.matches(x, false)) out.add(x);
                    return out;
                case AXIS_PARENT: {
                    XNode p = parentNode(x);
                    if (p != null && test.matches(p, false)) out.add(p);
                    return out;
                }
                case AXIS_ANCESTOR:
                case AXIS_ANCESTOR_OR_SELF: {
                    XNode current = x;
                    if (axis == AXIS_ANCESTOR_OR_SELF && test.matches(current, false)) out.add(current);
                    while ((current = parentNode(current)) != null) if (test.matches(current, false)) out.add(current);
                    return out;
                }
                case AXIS_FOLLOWING_SIBLING:
                case AXIS_PRECEDING_SIBLING:
                    return collectSiblings(x, test, out, axis == AXIS_FOLLOWING_SIBLING);
                case AXIS_FOLLOWING:
                    return collectFollowing(x, test, out, state);
                case AXIS_PRECEDING:
                    return collectPreceding(x, test, out, state);
                case AXIS_ATTRIBUTE:
                    return addAttributes(x, test, out);
                default:
                    return out;
            }
        }
    }

    private static boolean inDocumentOrder(List<XNode> nodes) {
        for (int i = 1; i < nodes.size(); i++) if (compareKeys(nodes.get(i - 1), nodes.get(i)) >= 0) return false;
        return true;
    }

    private static List<XNode> collectSiblings(XNode x, NodeTest test, List<XNode> out, boolean following) {
        if (x.kind == K_ROOT || x.kind == K_ATTRIBUTE) return out;
        if (!test.matchesText() && x.kind != K_TEXT) {
            // tags only: walk the flat list; deeper tags are inside the siblings, a shallower one ends the parent
            HtmlNode t = x.tag;
            HtmlNode[] nodes = t.document().nodes;
            int stepBy = following ? 1 : -1;
            for (int i = t.index() + stepBy; i >= 0 && i < nodes.length; i += stepBy) {
                HtmlNode item = nodes[i];
                if (item.depth() < t.depth()) break;
                if (item.depth() == t.depth() && test.matchesTag(item)) out.add(tagNode(item));
            }
            return out;
        }
        XNode parent = parentNode(x);
        if (parent == null) return out;
        List<XNode> siblings = addChildren(parent, ANY_NODE_TEST, new ArrayList<>());
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (compareKeys(siblings.get(i), x) == 0) {
                index = i;
                break;
            }
        }
        if (index < 0) return out;
        if (following) {
            for (int i = index + 1; i < siblings.size(); i++) if (test.matches(siblings.get(i), false)) out.add(siblings.get(i));
        } else {
            for (int i = index - 1; i >= 0; i--) if (test.matches(siblings.get(i), false)) out.add(siblings.get(i));
        }
        return out;
    }

    private static List<XNode> collectFollowing(XNode x, NodeTest test, List<XNode> out, EvalState state) {
        if (x.kind == K_ROOT) return out;
        long after = x.rangeEnd();
        if (test.matchesText()) {
            for (XNode n : state.allNodes()) if (n.rangeStart() > after && test.matches(n, false)) out.add(n);
            return out;
        }
        HtmlNode[] nodes = state.doc.nodes;
        int first = x.kind == K_ELEMENT || x.kind == K_COMMENT ? x.tag.index() + 1 : 0;
        for (int i = first; i < nodes.length; i++) {
            if (nodes[i].start > after && test.matchesTag(nodes[i])) out.add(tagNode(nodes[i]));
        }
        return out;
    }

    private static List<XNode> collectPreceding(XNode x, NodeTest test, List<XNode> out, EvalState state) {
        if (x.kind == K_ROOT) return out;
        int before = x.rangeStart();
        if (test.matchesText()) {
            List<XNode> all = state.allNodes();
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i).rangeEnd() < before && test.matches(all.get(i), false)) out.add(all.get(i));
            }
            return out;
        }
        HtmlNode[] nodes = state.doc.nodes;
        int last = x.kind == K_ELEMENT || x.kind == K_COMMENT ? x.tag.index() - 1 : nodes.length - 1;
        for (int i = last; i >= 0; i--) {
            if (nodes[i].end < before && test.matchesTag(nodes[i])) out.add(tagNode(nodes[i]));
        }
        return out;
    }

    /** Applies the predicates one after another; positions are 1-based in the order of the list. */
    private static List<XNode> filter(List<XNode> nodes, List<Expr> predicates, EvalState state) {
        for (Expr p : predicates) {
            int size = nodes.size();
            int w = 0;
            for (int i = 0; i < size; i++) {
                XNode n = nodes.get(i);
                if (evalPredicate(p, new Ctx(n, i + 1, size, state))) nodes.set(w++, n);
            }
            nodes.subList(w, size).clear();
        }
        return nodes;
    }

    /** A location path, absolute or relative, optionally starting from a filter expression: (//a)[1]/@href. */
    private static final class PathExpr extends Expr {
        final Expr start;
        final boolean absolute;
        final List<Step> steps;

        PathExpr(Expr start, boolean absolute, List<Step> steps) {
            super(TYPE_NODE_SET);
            this.start = start;
            this.absolute = absolute;
            this.steps = steps;
        }

        @Override
        boolean usesPosition() {
            return start != null && start.usesPosition();
        }

        @Override
        Object eval(Ctx c) {
            List<XNode> current;
            if (start != null) current = evalNodes(start, c);
            else if (absolute) current = new ArrayList<>(List.of(rootNode(c.state.doc)));
            else current = new ArrayList<>(List.of(c.node));
            for (Step s : steps) {
                if (current.isEmpty()) break;
                current = s.apply(current, c.state);
            }
            return current;
        }
    }

    /** A primary expression with predicates: (//li)[last()]. */
    private static final class FilterExpr extends Expr {
        final Expr primary;
        final List<Expr> predicates;

        FilterExpr(Expr primary, List<Expr> predicates) {
            super(TYPE_NODE_SET);
            this.primary = primary;
            this.predicates = predicates;
        }

        @Override
        boolean usesPosition() {
            return primary.usesPosition();
        }

        @Override
        Object eval(Ctx c) {
            return filter(new ArrayList<>(evalNodes(primary, c)), predicates, c.state);
        }
    }

    // ----- functions -----

    /** The number of arguments a function takes and the type it gives. */
    private static final class FnInfo {
        final int min;
        final int max;
        final int type;

        FnInfo(int min, int max, int type) {
            this.min = min;
            this.max = max;
            this.type = type;
        }
    }

    private static final Map<String, FnInfo> FUNCTIONS = new HashMap<>();

    static {
        FUNCTIONS.put("last", new FnInfo(0, 0, TYPE_NUMBER));
        FUNCTIONS.put("position", new FnInfo(0, 0, TYPE_NUMBER));
        FUNCTIONS.put("count", new FnInfo(1, 1, TYPE_NUMBER));
        FUNCTIONS.put("name", new FnInfo(0, 1, TYPE_STRING));
        FUNCTIONS.put("local-name", new FnInfo(0, 1, TYPE_STRING));
        FUNCTIONS.put("string", new FnInfo(0, 1, TYPE_STRING));
        FUNCTIONS.put("concat", new FnInfo(2, Integer.MAX_VALUE, TYPE_STRING));
        FUNCTIONS.put("starts-with", new FnInfo(2, 2, TYPE_BOOLEAN));
        FUNCTIONS.put("ends-with", new FnInfo(2, 2, TYPE_BOOLEAN));
        FUNCTIONS.put("contains", new FnInfo(2, 2, TYPE_BOOLEAN));
        FUNCTIONS.put("substring-before", new FnInfo(2, 2, TYPE_STRING));
        FUNCTIONS.put("substring-after", new FnInfo(2, 2, TYPE_STRING));
        FUNCTIONS.put("substring", new FnInfo(2, 3, TYPE_STRING));
        FUNCTIONS.put("string-length", new FnInfo(0, 1, TYPE_NUMBER));
        FUNCTIONS.put("normalize-space", new FnInfo(0, 1, TYPE_STRING));
        FUNCTIONS.put("translate", new FnInfo(3, 3, TYPE_STRING));
        FUNCTIONS.put("lower-case", new FnInfo(1, 1, TYPE_STRING));
        FUNCTIONS.put("upper-case", new FnInfo(1, 1, TYPE_STRING));
        FUNCTIONS.put("not", new FnInfo(1, 1, TYPE_BOOLEAN));
        FUNCTIONS.put("true", new FnInfo(0, 0, TYPE_BOOLEAN));
        FUNCTIONS.put("false", new FnInfo(0, 0, TYPE_BOOLEAN));
        FUNCTIONS.put("boolean", new FnInfo(1, 1, TYPE_BOOLEAN));
        FUNCTIONS.put("number", new FnInfo(0, 1, TYPE_NUMBER));
        FUNCTIONS.put("sum", new FnInfo(1, 1, TYPE_NUMBER));
        FUNCTIONS.put("floor", new FnInfo(1, 1, TYPE_NUMBER));
        FUNCTIONS.put("ceiling", new FnInfo(1, 1, TYPE_NUMBER));
        FUNCTIONS.put("round", new FnInfo(1, 1, TYPE_NUMBER));
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.floor(v + 0.5);
    }

    /** Splits on Unicode whitespace and joins with one space. */
    private static String normalizeSpace(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && Chars.isUnicodeSpace(s.charAt(i))) i++;
            int start = i;
            while (i < n && !Chars.isUnicodeSpace(s.charAt(i))) i++;
            if (i > start) {
                if (out.length() > 0) out.append(' ');
                out.append(s, start, i);
            }
        }
        return out.toString();
    }

    private static final class FunctionExpr extends Expr {
        final String fn;
        final List<Expr> args;

        FunctionExpr(String fn, List<Expr> args, int type) {
            super(type);
            this.fn = fn;
            this.args = args;
        }

        @Override
        boolean usesPosition() {
            if (fn.equals("position") || fn.equals("last")) return true;
            for (Expr a : args) if (a.usesPosition()) return true;
            return false;
        }

        private String arg(int i, Ctx c) {
            return evalString(args.get(i), c);
        }

        private String contextOrArg(Ctx c) {
            return args.isEmpty() ? stringValue(c.node) : arg(0, c);
        }

        @Override
        Object eval(Ctx c) {
            switch (fn) {
                case "last":
                    return (double) c.size;
                case "position":
                    return (double) c.position;
                case "count":
                    return (double) evalNodes(args.get(0), c).size();
                case "name":
                case "local-name": {
                    String name = "";
                    if (args.isEmpty()) {
                        name = nodeName(c.node);
                    } else {
                        List<XNode> set = evalNodes(args.get(0), c);
                        if (!set.isEmpty()) name = nodeName(set.get(0));
                    }
                    return fn.equals("local-name") ? name.substring(name.lastIndexOf(':') + 1) : name;
                }
                case "string":
                    return contextOrArg(c);
                case "concat": {
                    StringBuilder s = new StringBuilder();
                    for (Expr a : args) s.append(evalString(a, c));
                    return s.toString();
                }
                case "starts-with":
                    return arg(0, c).startsWith(arg(1, c));
                case "ends-with":
                    return arg(0, c).endsWith(arg(1, c));
                case "contains":
                    return arg(0, c).contains(arg(1, c));
                case "substring-before": {
                    String s = arg(0, c);
                    int i = s.indexOf(arg(1, c));
                    return i >= 0 ? s.substring(0, i) : "";
                }
                case "substring-after": {
                    String s = arg(0, c);
                    String t = arg(1, c);
                    int i = s.indexOf(t);
                    return i >= 0 ? s.substring(i + t.length()) : "";
                }
                case "substring":
                    return substring(c);
                case "string-length":
                    return (double) contextOrArg(c).length(); // UTF-16 units, as .NET counts
                case "normalize-space":
                    return normalizeSpace(contextOrArg(c));
                case "translate": {
                    String s = arg(0, c);
                    int[] from = arg(1, c).codePoints().toArray();
                    int[] to = arg(2, c).codePoints().toArray();
                    StringBuilder out = new StringBuilder(s.length());
                    s.codePoints().forEach(ch -> {
                        int i = -1;
                        for (int k = 0; k < from.length; k++) {
                            if (from[k] == ch) {
                                i = k;
                                break;
                            }
                        }
                        if (i < 0) out.appendCodePoint(ch);
                        else if (i < to.length) out.appendCodePoint(to[i]);
                    });
                    return out.toString();
                }
                case "lower-case":
                    return Chars.lower(arg(0, c));
                case "upper-case":
                    return Chars.upper(arg(0, c));
                case "not":
                    return !args.get(0).evalBool(c);
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                case "boolean":
                    return args.get(0).evalBool(c);
                case "number":
                    return args.isEmpty() ? stringToNumber(stringValue(c.node)) : evalNumber(args.get(0), c);
                case "sum": {
                    double sum = 0;
                    for (XNode n : evalNodes(args.get(0), c)) sum += stringToNumber(stringValue(n));
                    return sum;
                }
                case "floor":
                    return Math.floor(evalNumber(args.get(0), c));
                case "ceiling":
                    return Math.ceil(evalNumber(args.get(0), c));
                case "round":
                    return round(evalNumber(args.get(0), c));
                default:
                    throw new IllegalStateException("unknown XPath function " + fn);
            }
        }

        /** XPath 1.0: the chars at 1-based positions p with round(start) &lt;= p &lt; round(start) + round(length). */
        private String substring(Ctx c) {
            String s = arg(0, c);
            double from = round(evalNumber(args.get(1), c));
            double to = args.size() > 2 ? from + round(evalNumber(args.get(2), c)) : Double.POSITIVE_INFINITY;
            if (Double.isNaN(from) || Double.isNaN(to)) return "";
            int n = s.length();
            double first = from <= 1 ? 0 : from > n ? n : from - 1;
            double end = to >= n + 1 ? n : to <= 1 ? 0 : to - 1;
            return end > first ? s.substring((int) first, (int) end) : "";
        }
    }

    // ----- tokenizer and parser -----

    private static final int T_END = 0;
    private static final int T_SLASH = 1;
    private static final int T_DOUBLE_SLASH = 2;
    private static final int T_LPAREN = 3;
    private static final int T_RPAREN = 4;
    private static final int T_LBRACKET = 5;
    private static final int T_RBRACKET = 6;
    private static final int T_DOT = 7;
    private static final int T_DOT_DOT = 8;
    private static final int T_AT = 9;
    private static final int T_COMMA = 10;
    private static final int T_COLON_COLON = 11;
    private static final int T_PIPE = 12;
    private static final int T_PLUS = 13;
    private static final int T_MINUS = 14;
    private static final int T_EQ = 15;
    private static final int T_NEQ = 16;
    private static final int T_LT = 17;
    private static final int T_LE = 18;
    private static final int T_GT = 19;
    private static final int T_GE = 20;
    private static final int T_STAR = 21; // name test *
    private static final int T_MULTIPLY = 22; // operator *
    private static final int T_AND = 23;
    private static final int T_OR = 24;
    private static final int T_DIV = 25;
    private static final int T_MOD = 26;
    private static final int T_NAME = 27;
    private static final int T_LITERAL = 28;
    private static final int T_NUMBER = 29;
    private static final int T_DOLLAR = 30;

    private static final class Token {
        int kind;
        String text = "";
        double number;
        final int position; // in code points

        Token(int kind, int position) {
            this.kind = kind;
            this.position = position;
        }
    }

    private static boolean isXNameStart(int cp) {
        return cp >= 0 && (Character.isLetter(cp) || cp == '_');
    }

    private static boolean isXNameChar(int cp) {
        return cp >= 0 && (Character.isLetter(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER
            || cp == '_' || cp == '-' || cp == '.');
    }

    private static boolean isDigit(int cp) {
        return cp >= '0' && cp <= '9';
    }

    private static final Set<String> NODE_TYPES = Set.of("node", "text", "comment", "processing-instruction");

    private static final Map<String, Integer> AXES = new HashMap<>();
    private static final Map<String, Integer> OPERATORS = Map.of("and", T_AND, "or", T_OR, "div", T_DIV, "mod", T_MOD);

    static {
        AXES.put("child", AXIS_CHILD);
        AXES.put("descendant", AXIS_DESCENDANT);
        AXES.put("descendant-or-self", AXIS_DESCENDANT_OR_SELF);
        AXES.put("self", AXIS_SELF);
        AXES.put("parent", AXIS_PARENT);
        AXES.put("ancestor", AXIS_ANCESTOR);
        AXES.put("ancestor-or-self", AXIS_ANCESTOR_OR_SELF);
        AXES.put("following-sibling", AXIS_FOLLOWING_SIBLING);
        AXES.put("preceding-sibling", AXIS_PRECEDING_SIBLING);
        AXES.put("following", AXIS_FOLLOWING);
        AXES.put("preceding", AXIS_PRECEDING);
        AXES.put("attribute", AXIS_ATTRIBUTE);
    }

    /** Whether the expression is a bare @name (one attribute step, no predicates); its name, or null. */
    private static String attributeName(Expr e) {
        if (!(e instanceof PathExpr)) return null;
        PathExpr p = (PathExpr) e;
        if (p.start != null || p.absolute || p.steps.size() != 1) return null;
        Step s = p.steps.get(0);
        if (s.axis != AXIS_ATTRIBUTE || !s.predicates.isEmpty() || s.test.kind != TK_NAME) return null;
        return s.test.name;
    }

    /** {@code @name = 'literal'} is compared in place; everything else by the general rules. */
    private static Expr makeEquality(int op, Expr left, Expr right) {
        String l = attributeName(left);
        if (l != null && right instanceof LiteralExpr) return new AttributeEqualsExpr(l, ((LiteralExpr) right).value, op == OP_NEQ);
        String r = attributeName(right);
        if (r != null && left instanceof LiteralExpr) return new AttributeEqualsExpr(r, ((LiteralExpr) left).value, op == OP_NEQ);
        return new CompareExpr(op, left, right);
    }

    /**
     * //x is descendant-or-self::node()/child::x. Without positional predicates on x it is the same as descendant::x:
     * one linear scan of the tag list instead of the children of every node.
     */
    private static List<Step> optimize(List<Step> steps) {
        for (int i = 0; i < steps.size() - 1; i++) {
            Step s = steps.get(i);
            if (s.axis != AXIS_DESCENDANT_OR_SELF || s.test != ANY_NODE_TEST || !s.predicates.isEmpty()) continue;
            Step next = steps.get(i + 1);
            if (next.axis == AXIS_CHILD && !next.hasPositionalPredicates()) {
                steps.set(i, new Step(AXIS_DESCENDANT, next.test, next.predicates));
                steps.remove(i + 1);
            } else if (next.axis == AXIS_CHILD || next.axis == AXIS_ATTRIBUTE) {
                // only the root and elements have children or attributes: skip the text nodes
                steps.set(i, new Step(AXIS_DESCENDANT_OR_SELF, CONTAINERS_TEST));
            }
        }
        return steps;
    }

    private static boolean canStartStep(Token t) {
        return t.kind == T_DOT || t.kind == T_DOT_DOT || t.kind == T_AT || t.kind == T_STAR || t.kind == T_NAME;
    }

    private static final class Compiler {
        final int[] source; // code points
        final List<Token> tokens = new ArrayList<>();
        int index;
        final String expression;

        Compiler(String expression) {
            this.expression = expression;
            this.source = expression.codePoints().toArray();
            tokenize();
        }

        XPathException failAt(String msg, int position) {
            throw new XPathException("invalid XPath " + Json.quote(expression) + " at position " + position + ": " + msg);
        }

        XPathException fail(String msg) {
            throw failAt(msg, peek(0).position);
        }

        private int at(int k) {
            return k < source.length ? source[k] : -1;
        }

        private String text(int from, int to) {
            return new String(source, from, to - from);
        }

        private void tokenize() {
            int[] s = source;
            int i = 0;
            for (;;) {
                while (i < s.length && s[i] < 0x10000 && Chars.isUnicodeSpace(s[i])) i++;
                if (i >= s.length) {
                    tokens.add(new Token(T_END, i));
                    return;
                }
                int start = i;
                int ch = s[i];
                int next = at(i + 1);

                // XPath 1.0 lexical rule: after a token that can end an operand, '*' and and/or/div/mod are operators
                boolean operatorExpected = false;
                if (!tokens.isEmpty()) {
                    switch (tokens.get(tokens.size() - 1).kind) {
                        case T_RPAREN: case T_RBRACKET: case T_DOT: case T_DOT_DOT: case T_STAR: case T_NAME: case T_LITERAL: case T_NUMBER:
                            operatorExpected = true;
                            break;
                        default:
                            break;
                    }
                }

                Token tok = new Token(T_END, start);
                int single = single(ch);
                if (ch == '/') {
                    if (next == '/') {
                        tok.kind = T_DOUBLE_SLASH;
                        i += 2;
                    } else {
                        tok.kind = T_SLASH;
                        i++;
                    }
                } else if (single >= 0) {
                    tok.kind = single;
                    i++;
                } else if (ch == '*') {
                    tok.kind = operatorExpected ? T_MULTIPLY : T_STAR;
                    i++;
                } else if (ch == '!') {
                    if (next != '=') throw failAt("'!' must be followed by '='", i);
                    tok.kind = T_NEQ;
                    i += 2;
                } else if (ch == '<' || ch == '>') {
                    if (next == '=') {
                        tok.kind = ch == '<' ? T_LE : T_GE;
                        i += 2;
                    } else {
                        tok.kind = ch == '<' ? T_LT : T_GT;
                        i++;
                    }
                } else if (ch == ':') {
                    if (next != ':') throw failAt("unexpected ':'", i);
                    tok.kind = T_COLON_COLON;
                    i += 2;
                } else if (ch == '"' || ch == '\'') {
                    int end = -1;
                    for (int k = i + 1; k < s.length; k++) {
                        if (s[k] == ch) {
                            end = k;
                            break;
                        }
                    }
                    if (end < 0) throw failAt("unterminated string literal", i);
                    tok.kind = T_LITERAL;
                    tok.text = text(i + 1, end);
                    i = end + 1;
                } else if (ch == '.' && next == '.') {
                    tok.kind = T_DOT_DOT;
                    i += 2;
                } else if (ch == '.' && !isDigit(next)) {
                    tok.kind = T_DOT;
                    i++;
                } else if (isDigit(ch) || ch == '.') {
                    while (i < s.length && isDigit(s[i])) i++;
                    if (i < s.length && s[i] == '.') {
                        i++;
                        while (i < s.length && isDigit(s[i])) i++;
                    }
                    tok.kind = T_NUMBER;
                    tok.number = Double.parseDouble(text(start, i));
                } else if (isXNameStart(ch)) {
                    i++;
                    while (i < s.length) {
                        if (isXNameChar(s[i])) {
                            i++;
                        } else if (s[i] == ':' && i + 1 < s.length && s[i + 1] != ':' && (isXNameStart(s[i + 1]) || s[i + 1] == '*')) {
                            // a prefixed name (dc:creator) or prefix:*
                            i += 2;
                            if (s[i - 1] == '*') break;
                        } else {
                            break;
                        }
                    }
                    tok.kind = T_NAME;
                    tok.text = text(start, i);
                    if (operatorExpected) {
                        Integer op = OPERATORS.get(tok.text);
                        if (op == null) throw failAt("expected an operator, found '" + tok.text + "'", start);
                        tok.kind = op;
                    }
                } else {
                    throw failAt("unexpected character '" + new String(Character.toChars(ch)) + "'", i);
                }
                tokens.add(tok);
            }
        }

        private static int single(int ch) {
            switch (ch) {
                case '(': return T_LPAREN;
                case ')': return T_RPAREN;
                case '[': return T_LBRACKET;
                case ']': return T_RBRACKET;
                case '@': return T_AT;
                case ',': return T_COMMA;
                case '|': return T_PIPE;
                case '+': return T_PLUS;
                case '-': return T_MINUS;
                case '=': return T_EQ;
                case '$': return T_DOLLAR;
                default: return -1;
            }
        }

        // ----- parser helpers -----

        Token peek(int offset) {
            return tokens.get(Math.min(index + offset, tokens.size() - 1));
        }

        private Token advance() {
            return tokens.get(index++);
        }

        private boolean accept(int kind) {
            if (peek(0).kind != kind) return false;
            index++;
            return true;
        }

        private void expect(int kind, String what) {
            if (!accept(kind)) throw fail("expected " + what + ", found " + describe(peek(0)));
        }

        String describe(Token t) {
            switch (t.kind) {
                case T_END:
                    return "the end of the expression";
                case T_NAME:
                case T_LITERAL:
                    return "'" + t.text + "'";
                case T_NUMBER:
                    return numberToString(t.number);
                default: {
                    int to = Math.min(source.length, t.position + 2);
                    String s = text(t.position, to);
                    int end = s.length();
                    while (end > 0 && Chars.isUnicodeSpace(s.charAt(end - 1))) end--;
                    return "'" + s.substring(0, end) + "'";
                }
            }
        }

        // ----- grammar (XPath 1.0, section 3) -----

        Expr parseExpr() {
            return parseOr();
        }

        private Expr parseOr() {
            Expr left = parseAnd();
            while (accept(T_OR)) left = new LogicExpr(false, left, parseAnd());
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseEquality();
            while (accept(T_AND)) left = new LogicExpr(true, left, parseEquality());
            return left;
        }

        private Expr parseEquality() {
            Expr left = parseRelational();
            for (;;) {
                int op;
                if (accept(T_EQ)) op = OP_EQ;
                else if (accept(T_NEQ)) op = OP_NEQ;
                else return left;
                left = makeEquality(op, left, parseRelational());
            }
        }

        private Expr parseRelational() {
            Expr left = parseAdditive();
            for (;;) {
                int op;
                if (accept(T_LT)) op = OP_LT;
                else if (accept(T_LE)) op = OP_LE;
                else if (accept(T_GT)) op = OP_GT;
                else if (accept(T_GE)) op = OP_GE;
                else return left;
                left = new CompareExpr(op, left, parseAdditive());
            }
        }

        private Expr parseAdditive() {
            Expr left = parseMultiplicative();
            for (;;) {
                if (accept(T_PLUS)) left = new ArithExpr(ARITH_ADD, left, parseMultiplicative());
                else if (accept(T_MINUS)) left = new ArithExpr(ARITH_SUB, left, parseMultiplicative());
                else return left;
            }
        }

        private Expr parseMultiplicative() {
            Expr left = parseUnary();
            for (;;) {
                if (accept(T_MULTIPLY)) left = new ArithExpr(ARITH_MUL, left, parseUnary());
                else if (accept(T_DIV)) left = new ArithExpr(ARITH_DIV, left, parseUnary());
                else if (accept(T_MOD)) left = new ArithExpr(ARITH_MOD, left, parseUnary());
                else return left;
            }
        }

        private Expr parseUnary() {
            if (accept(T_MINUS)) return new NegateExpr(parseUnary());
            return parseUnion();
        }

        private Expr parseUnion() {
            Expr left = parsePath();
            while (peek(0).kind == T_PIPE) {
                int position = advance().position;
                Expr right = parsePath();
                if (left.type != TYPE_NODE_SET || right.type != TYPE_NODE_SET) throw failAt("'|' needs node-sets on both sides", position);
                left = new UnionExpr(left, right);
            }
            return left;
        }

        private Expr parsePath() {
            Token t = peek(0);
            boolean isFilter = t.kind == T_LITERAL || t.kind == T_NUMBER || t.kind == T_LPAREN || t.kind == T_DOLLAR
                || (t.kind == T_NAME && peek(1).kind == T_LPAREN && !NODE_TYPES.contains(t.text));
            if (!isFilter) return parseLocationPath();

            int position = t.position;
            Expr primary = parsePrimary();
            List<Expr> predicates = parsePredicates();
            if (!predicates.isEmpty()) {
                if (primary.type != TYPE_NODE_SET) throw failAt("a predicate needs a node-set", position);
                primary = new FilterExpr(primary, predicates);
            }
            int k = peek(0).kind;
            if (k != T_SLASH && k != T_DOUBLE_SLASH) return primary;
            if (primary.type != TYPE_NODE_SET) throw failAt("'/' needs a node-set on the left", position);
            return new PathExpr(primary, false, optimize(parseRelativeSteps(new ArrayList<>(), true)));
        }

        private Expr parseLocationPath() {
            List<Step> steps = new ArrayList<>();
            boolean absolute = false;
            if (accept(T_SLASH)) {
                absolute = true;
                if (canStartStep(peek(0))) steps = parseRelativeSteps(steps, false);
            } else if (peek(0).kind == T_DOUBLE_SLASH) {
                absolute = true;
                steps = parseRelativeSteps(steps, true);
            } else {
                if (!canStartStep(peek(0)))
                    throw fail(peek(0).kind == T_END ? "unexpected end of the expression" : "unexpected " + describe(peek(0)));
                steps = parseRelativeSteps(steps, false);
            }
            return new PathExpr(null, absolute, optimize(steps));
        }

        /** Steps separated by '/' or '//'; afterSlash: the next token is a '/' or '//' to read first. */
        private List<Step> parseRelativeSteps(List<Step> steps, boolean afterSlash) {
            if (!afterSlash) steps.add(parseStep());
            for (;;) {
                if (accept(T_SLASH)) {
                    steps.add(parseStep());
                } else if (accept(T_DOUBLE_SLASH)) {
                    steps.add(new Step(AXIS_DESCENDANT_OR_SELF, ANY_NODE_TEST));
                    steps.add(parseStep());
                } else {
                    return steps;
                }
            }
        }

        private Step parseStep() {
            if (accept(T_DOT)) return new Step(AXIS_SELF, ANY_NODE_TEST);
            if (accept(T_DOT_DOT)) return new Step(AXIS_PARENT, ANY_NODE_TEST);
            int axis = AXIS_CHILD;
            if (accept(T_AT)) {
                axis = AXIS_ATTRIBUTE;
            } else if (peek(0).kind == T_NAME && peek(1).kind == T_COLON_COLON) {
                Token name = advance();
                Integer a = AXES.get(name.text);
                if (a == null) {
                    if (name.text.equals("namespace")) throw failAt("the namespace axis is not supported", name.position);
                    throw failAt("unknown axis '" + name.text + "'", name.position);
                }
                axis = a;
                advance();
            }
            NodeTest test = parseNodeTest();
            return new Step(axis, test, parsePredicates());
        }

        private NodeTest parseNodeTest() {
            if (accept(T_STAR)) return new NodeTest(TK_WILDCARD);
            Token t = peek(0);
            if (t.kind != T_NAME) throw fail("expected a node test, found " + describe(t));
            advance();
            String name = t.text;
            if (peek(0).kind == T_LPAREN && NODE_TYPES.contains(name)) {
                advance();
                if (name.equals("processing-instruction")) accept(T_LITERAL);
                expect(T_RPAREN, "')'");
                switch (name) {
                    case "node":
                        return ANY_NODE_TEST;
                    case "text":
                        return new NodeTest(TK_TEXT);
                    case "comment":
                        return new NodeTest(TK_COMMENT);
                    default:
                        return new NodeTest(TK_PI);
                }
            }
            if (name.endsWith(":*")) return new NodeTest(TK_PREFIX, name.substring(0, name.length() - 1));
            return new NodeTest(TK_NAME, name);
        }

        private List<Expr> parsePredicates() {
            List<Expr> predicates = new ArrayList<>();
            while (accept(T_LBRACKET)) {
                Expr p = parseExpr();
                expect(T_RBRACKET, "']'");
                // [@name]: an existence check instead of a node-set
                String name = attributeName(p);
                if (name != null) p = new AttributeExistsExpr(name);
                predicates.add(p);
            }
            return predicates;
        }

        private Expr parsePrimary() {
            Token t = advance();
            switch (t.kind) {
                case T_LITERAL:
                    return new LiteralExpr(t.text);
                case T_NUMBER:
                    return new NumberExpr(t.number);
                case T_LPAREN: {
                    Expr e = parseExpr();
                    expect(T_RPAREN, "')'");
                    return e;
                }
                case T_DOLLAR:
                    throw failAt("variables are not supported", t.position);
                default:
                    break;
            }

            // function call
            FnInfo info = FUNCTIONS.get(t.text);
            if (info == null) throw failAt("unknown function '" + t.text + "()'", t.position);
            expect(T_LPAREN, "'('");
            List<Expr> args = new ArrayList<>();
            if (!accept(T_RPAREN)) {
                do args.add(parseExpr());
                while (accept(T_COMMA));
                expect(T_RPAREN, "')'");
            }
            if (args.size() < info.min || args.size() > info.max) throw failAt("wrong number of arguments for " + t.text + "()", t.position);
            boolean needsNodeSet = t.text.equals("count") || t.text.equals("sum") || t.text.equals("name") || t.text.equals("local-name");
            if (needsNodeSet && !args.isEmpty() && args.get(0).type != TYPE_NODE_SET) {
                throw failAt(t.text + "() needs a node-set argument", t.position);
            }
            return new FunctionExpr(t.text, args, info.type);
        }
    }
}
