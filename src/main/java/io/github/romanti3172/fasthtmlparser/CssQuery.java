package io.github.romanti3172.fasthtmlparser;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A compiled CSS selector (or a comma-separated list of them), matched right to left over the flat tag list as browsers
 * do. Compile once and reuse it on any number of documents and threads; the string methods ({@code doc.css(...)},
 * {@code node.cssFirst(...)}, ...) compile and cache selectors automatically.
 *
 * <p>Supported: type, {@code *}, {@code #id}, {@code .class}, attribute selectors ({@code [a]}, {@code =}, {@code ~=},
 * {@code |=}, {@code ^=}, {@code $=}, {@code *=}, with the {@code i} / {@code s} flags), the combinators (descendant,
 * {@code >}, {@code +}, {@code ~}), selector lists and the pseudo-classes {@code :first-child}, {@code :last-child},
 * {@code :only-child}, {@code :first-of-type}, {@code :last-of-type}, {@code :only-of-type},
 * {@code :nth-child(An+B [of S])}, {@code :nth-last-child()}, {@code :nth-of-type()}, {@code :nth-last-of-type()},
 * {@code :not()}, {@code :is()}, {@code :where()}, {@code :has()}, {@code :empty}, {@code :root}, {@code :scope},
 * {@code :link}, {@code :any-link}, {@code :checked}, {@code :disabled}, {@code :enabled}, {@code :required},
 * {@code :optional}, and {@code :contains("text")}. Pseudo-elements and namespaces are not supported.
 *
 * <p>HTML rules: tag and attribute names are case-insensitive; ids, classes and attribute values are case-sensitive,
 * except the values the HTML standard lists (type, rel, lang, method, ...). Only elements are selected, never comments.
 */
public final class CssQuery {
    private final String selector;
    private final CssList list;

    private CssQuery(String selector, CssList list) {
        this.selector = selector;
        this.list = list;
    }

    /** Compiles a CSS selector; throws {@link CssSelectorException} on an invalid one. */
    public static CssQuery compile(String selector) {
        Compiler c = new Compiler(selector);
        CssList list = c.parseList(false, true);
        if (c.p < c.s.length()) c.fail("unexpected '" + c.s.charAt(c.p) + "'");
        return new CssQuery(selector, list);
    }

    /** The source of the selector. */
    public String selector() {
        return selector;
    }

    @Override
    public String toString() {
        return selector;
    }

    /** The matching tags in the document, in document order. */
    public List<HtmlNode> all(HtmlDocument d) {
        return scan(d, null, 0, d.source().length(), false);
    }

    /** The first matching tag in the document, or null. */
    public HtmlNode first(HtmlDocument d) {
        List<HtmlNode> r = scan(d, null, 0, d.source().length(), true);
        return r.isEmpty() ? null : r.get(0);
    }

    /** The matching tags inside n (as querySelectorAll). */
    public List<HtmlNode> allIn(HtmlNode n) {
        return scan(n.document(), n, n.index() + 1, n.end, false);
    }

    /** The first matching tag inside n, or null. */
    public HtmlNode firstIn(HtmlNode n) {
        List<HtmlNode> r = scan(n.document(), n, n.index() + 1, n.end, true);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Whether the tag matches (as element.matches(): :scope is the tag itself); false for comments and null. */
    public boolean matches(HtmlNode n) {
        return n != null && isElement(n) && list.matches(n, new Context(n.document(), n, n));
    }

    private List<HtmlNode> scan(HtmlDocument d, HtmlNode scope, int from, int end, boolean firstOnly) {
        Context ctx = new Context(d, scope, scope);
        List<HtmlNode> out = new ArrayList<>();
        HtmlNode[] nodes = d.nodes;
        for (int i = from; i < nodes.length; i++) {
            HtmlNode n = nodes[i];
            if (n.end > end) break;
            if (list.matches(n, ctx)) {
                out.add(n);
                if (firstOnly) break;
            }
        }
        return out;
    }

    private static final Map<String, CssQuery> CACHE = new ConcurrentHashMap<>();

    static CssQuery cached(String selector) {
        CssQuery q = CACHE.get(selector);
        if (q == null) {
            q = compile(selector);
            if (CACHE.size() >= 1024) CACHE.clear(); // dynamically built selectors must not grow the cache forever
            CACHE.put(selector, q);
        }
        return q;
    }

    private static final int COMMENT = Tag.COMMENT.ordinal();
    private static final int DOCTYPE = Tag.DOCTYPE.ordinal();
    private static final int XML_DECLARATION = Tag.XML_DECLARATION.ordinal();

    /** Not a comment, the doctype or the XML declaration. */
    static boolean isElement(HtmlNode n) {
        if (n.key >= 0) return n.key != COMMENT && n.key != DOCTYPE && n.key != XML_DECLARATION;
        if (n.custom.isEmpty()) return false;
        char c = n.custom.charAt(0);
        return c != '!' && c != '?';
    }

    // ----- the tree: sibling links and positions, built once per document -----

    static final class Tree {
        final HtmlNode[] nodes;
        final int[] previous; // previous element sibling, -1 for none
        final int[] next;
        final int[] position; // 1-based position among the element siblings
        final int[] fromEnd;
        private volatile int[][] types; // {position, fromEnd} among the siblings of the same type

        Tree(HtmlNode[] nodes) {
            int count = nodes.length;
            this.nodes = nodes;
            previous = new int[count];
            next = new int[count];
            Arrays.fill(previous, -1);
            Arrays.fill(next, -1);
            position = new int[count];
            fromEnd = new int[count];
            // per parent (slot = parent index + 1, slot 0: the top level): its last element child so far and their number
            int[] lastChild = new int[count + 1];
            Arrays.fill(lastChild, -1);
            int[] children = new int[count + 1];
            for (int i = 0; i < count; i++) {
                if (!isElement(nodes[i])) continue;
                int slot = nodes[i].parentIndex + 1;
                int prev = lastChild[slot];
                previous[i] = prev;
                if (prev >= 0) next[prev] = i;
                lastChild[slot] = i;
                position[i] = ++children[slot];
            }
            for (int i = 0; i < count; i++) {
                if (position[i] > 0) fromEnd[i] = children[nodes[i].parentIndex + 1] - position[i] + 1;
            }
        }

        HtmlNode prev(HtmlNode n) {
            int i = previous[n.index()];
            return i < 0 ? null : nodes[i];
        }

        HtmlNode nextOf(HtmlNode n) {
            int i = next[n.index()];
            return i < 0 ? null : nodes[i];
        }

        int[][] typePositions() {
            int[][] t = types;
            if (t != null) return t;
            int count = nodes.length;
            int[] pos = new int[count];
            int[] end = new int[count];
            Map<String, Integer> seen = new HashMap<>();
            String[] keys = new String[count];
            for (int i = 0; i < count; i++) {
                if (position[i] == 0) continue; // not an element
                HtmlNode n = nodes[i];
                String k = n.parentIndex + "|" + n.key + "|" + (n.key < 0 ? n.custom : "");
                keys[i] = k;
                int v = seen.getOrDefault(k, 0) + 1;
                seen.put(k, v);
                pos[i] = v;
            }
            for (int i = 0; i < count; i++) {
                if (pos[i] > 0) end[i] = seen.get(keys[i]) - pos[i] + 1;
            }
            t = new int[][] {pos, end};
            types = t;
            return t;
        }
    }

    private static Tree treeOf(HtmlDocument d) {
        Tree t = d.cssTree;
        if (t == null) {
            t = new Tree(d.nodes);
            d.cssTree = t;
        }
        return t;
    }

    // ----- matching -----

    private static final int COMB_NONE = 0;
    private static final int COMB_DESCENDANT = 1; // "a b"
    private static final int COMB_CHILD = 2; // "a > b"
    private static final int COMB_ADJACENT = 3; // "a + b"
    private static final int COMB_SIBLING = 4; // "a ~ b"

    /**
     * The document, the tag the query was called on (:scope, null for the document) and the tag a relative selector
     * starts from (:has(&gt; a), or node.css("&gt; li"); null: the document).
     */
    private static final class Context {
        final HtmlDocument doc;
        final HtmlNode scope;
        final HtmlNode anchor;

        Context(HtmlDocument doc, HtmlNode scope, HtmlNode anchor) {
            this.doc = doc;
            this.scope = scope;
            this.anchor = anchor;
        }

        Tree tree() {
            return treeOf(doc);
        }
    }

    private interface Simple {
        boolean matches(HtmlNode n, Context ctx);
    }

    private static final class CssList {
        final List<Complex> items = new ArrayList<>();

        boolean matches(HtmlNode n, Context ctx) {
            for (Complex item : items) if (item.matches(item.compounds.size() - 1, n, ctx)) return true;
            return false;
        }
    }

    /** Compounds joined by combinators; combinators[i] stands between compounds[i] and [i+1]. */
    private static final class Complex {
        int leading = COMB_NONE;
        final List<Compound> compounds = new ArrayList<>();
        final List<Integer> combinators = new ArrayList<>();

        /** n matches compound i and the part to the left of it matches the tags the combinators lead to. */
        boolean matches(int i, HtmlNode n, Context ctx) {
            if (!compounds.get(i).matches(n, ctx)) return false;
            if (i == 0) return leading == COMB_NONE || isRelated(leading, ctx.anchor, n, ctx);
            switch (combinators.get(i - 1)) {
                case COMB_CHILD: {
                    HtmlNode p = n.parent();
                    return p != null && matches(i - 1, p, ctx);
                }
                case COMB_DESCENDANT:
                    for (HtmlNode p = n.parent(); p != null; p = p.parent()) if (matches(i - 1, p, ctx)) return true;
                    return false;
                case COMB_ADJACENT: {
                    HtmlNode p = ctx.tree().prev(n);
                    return p != null && matches(i - 1, p, ctx);
                }
                case COMB_SIBLING: {
                    Tree t = ctx.tree();
                    for (HtmlNode p = t.prev(n); p != null; p = t.prev(p)) if (matches(i - 1, p, ctx)) return true;
                    return false;
                }
                default:
                    return false;
            }
        }
    }

    private static final class Compound {
        int key = -1; // Tag, or -1
        String name = ""; // lower-case name not in Tag; "" with key -1: any element
        final List<Simple> simples = new ArrayList<>();

        boolean matches(HtmlNode n, Context ctx) {
            if (key >= 0) {
                if (n.key != key) return false;
            } else if (!name.isEmpty()) {
                if (n.key >= 0 || !n.custom.equals(name)) return false;
            } else if (!isElement(n)) {
                return false;
            }
            for (Simple s : simples) if (!s.matches(n, ctx)) return false;
            return true;
        }
    }

    /** n stands in the combinator relation to anchor (null: the document). */
    private static boolean isRelated(int c, HtmlNode anchor, HtmlNode n, Context ctx) {
        switch (c) {
            case COMB_CHILD:
                return anchor == null ? n.parentIndex < 0 : n.parentIndex == anchor.index();
            case COMB_DESCENDANT:
                if (anchor == null) return true;
                for (HtmlNode p = n.parent(); p != null; p = p.parent()) if (p == anchor) return true;
                return false;
            case COMB_ADJACENT:
                return anchor != null && ctx.tree().prev(n) == anchor;
            case COMB_SIBLING:
                return anchor != null && anchor.index() < n.index() && anchor.parentIndex == n.parentIndex && isElement(anchor);
            default:
                return false;
        }
    }

    /** :has(): whether a tag in the anchor's scope matches the relative selector. */
    private static boolean hasMatch(CssList relative, HtmlNode anchor, Context ctx) {
        HtmlNode[] nodes = anchor.document().nodes;
        Context inner = new Context(ctx.doc, ctx.scope, anchor);
        for (Complex item : relative.items) {
            int last = item.compounds.size() - 1;
            int i = anchor.index() + 1;
            if (item.leading == COMB_DESCENDANT || item.leading == COMB_CHILD) {
                // the tags inside the anchor
                for (; i < nodes.length && nodes[i].end <= anchor.end; i++) {
                    if (isElement(nodes[i]) && item.matches(last, nodes[i], inner)) return true;
                }
            } else {
                // the following siblings and the tags inside them, up to the end of the parent
                while (i < nodes.length && nodes[i].end <= anchor.end) i++;
                HtmlNode parent = anchor.parent();
                int end = parent != null ? parent.end : Integer.MAX_VALUE;
                for (; i < nodes.length && nodes[i].end <= end; i++) {
                    if (isElement(nodes[i]) && item.matches(last, nodes[i], inner)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isCssSpace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    /** Whether a whitespace-separated list contains word. */
    private static boolean containsWord(String list, String word, boolean fold) {
        if (word.isEmpty() || list.length() < word.length()) return false;
        int i = 0;
        int n = list.length();
        for (;;) {
            while (i < n && isCssSpace(list.charAt(i))) i++;
            if (i == n) return false;
            int end = i;
            while (end < n && !isCssSpace(list.charAt(end))) end++;
            if (equal(list.substring(i, end), word, fold)) return true;
            if (end == n) return false;
            i = end;
        }
    }

    private static boolean equal(String a, String b, boolean fold) {
        return fold ? Chars.equalFold(a, b) : a.equals(b);
    }

    private static String decoded(String raw) {
        return Decode.decode(raw, true);
    }

    // ----- simple selectors -----

    private static final class IdSelector implements Simple {
        final String id;

        IdSelector(String id) {
            this.id = id;
        }

        @Override
        public boolean matches(HtmlNode n, Context ctx) {
            return Attrs.attrEquals(Attrs.attrRaw(n, "id"), id);
        }
    }

    private static final class ClassSelector implements Simple {
        final String name;

        ClassSelector(String name) {
            this.name = name;
        }

        @Override
        public boolean matches(HtmlNode n, Context ctx) {
            String raw = Attrs.attrRaw(n, "class");
            return !raw.isEmpty() && containsWord(decoded(raw), name, false);
        }
    }

    private static final int OP_EXISTS = 0; // [a]
    private static final int OP_EQUALS = 1; // [a=v]
    private static final int OP_INCLUDES = 2; // [a~=v]
    private static final int OP_DASH = 3; // [a|=v]
    private static final int OP_PREFIX = 4; // [a^=v]
    private static final int OP_SUFFIX = 5; // [a$=v]
    private static final int OP_SUBSTRING = 6; // [a*=v]

    // HTML: the values of these attributes are compared ignoring ASCII case unless the selector has the 's' flag.
    private static final Set<String> CASE_INSENSITIVE_VALUES = new HashSet<>(Arrays.asList((
        "accept accept-charset align alink axis bgcolor charset checked clear codetype color "
            + "compact declare defer dir direction disabled enctype face frame hreflang http-equiv "
            + "lang language link media method multiple nohref noresize noshade nowrap readonly "
            + "rel rev rules scope scrolling selected shape target text type valign valuetype vlink").split(" ")));

    private static final class AttributeSelector implements Simple {
        final String name;
        final int op;
        final String value;
        final boolean fold;

        AttributeSelector(String name, int op, String value, boolean fold) {
            this.name = name;
            this.op = op;
            this.value = value;
            this.fold = fold;
        }

        @Override
        public boolean matches(HtmlNode n, Context ctx) {
            String raw = Attrs.attrRaw(n, name);
            String v = value;
            if (raw.isEmpty()) {
                // missing or empty: only these can match an empty value
                if (op == OP_EXISTS) return Attrs.hasAttribute(n, name);
                if (op == OP_EQUALS || op == OP_DASH) return v.isEmpty() && Attrs.hasAttribute(n, name);
                return false;
            }
            if (op == OP_EXISTS) return true;
            String actual = decoded(raw);
            switch (op) {
                case OP_EQUALS:
                    return equal(actual, v, fold);
                case OP_INCLUDES:
                    return !hasCssSpace(v) && containsWord(actual, v, fold);
                case OP_DASH:
                    return equal(actual, v, fold)
                        || (actual.length() > v.length() && actual.charAt(v.length()) == '-' && equal(actual.substring(0, v.length()), v, fold));
                case OP_PREFIX:
                    return !v.isEmpty() && actual.length() >= v.length() && equal(actual.substring(0, v.length()), v, fold);
                case OP_SUFFIX:
                    return !v.isEmpty() && actual.length() >= v.length() && equal(actual.substring(actual.length() - v.length()), v, fold);
                case OP_SUBSTRING:
                    if (v.isEmpty()) return false;
                    if (!fold) return actual.contains(v);
                    for (int i = 0; i + v.length() <= actual.length(); i++) {
                        if (Chars.equalFold(actual.substring(i, i + v.length()), v)) return true;
                    }
                    return false;
                default:
                    return false;
            }
        }
    }

    private static boolean hasCssSpace(String s) {
        for (int i = 0; i < s.length(); i++) if (isCssSpace(s.charAt(i))) return true;
        return false;
    }

    /** :nth-child(An+B [of S]) and its relatives; :first-child is :nth-child(1), and so on. */
    private static final class Nth implements Simple {
        final int a;
        final int b;
        final boolean fromEnd;
        final boolean ofType;
        final CssList of;

        Nth(int a, int b, boolean fromEnd, boolean ofType, CssList of) {
            this.a = a;
            this.b = b;
            this.fromEnd = fromEnd;
            this.ofType = ofType;
            this.of = of;
        }

        @Override
        public boolean matches(HtmlNode n, Context ctx) {
            Tree t = ctx.tree();
            int position;
            if (of != null) {
                if (!of.matches(n, ctx)) return false;
                position = 1;
                for (HtmlNode sib = fromEnd ? t.nextOf(n) : t.prev(n); sib != null; sib = fromEnd ? t.nextOf(sib) : t.prev(sib)) {
                    if (of.matches(sib, ctx)) position++;
                }
            } else if (ofType) {
                int[][] tp = t.typePositions();
                position = fromEnd ? tp[1][n.index()] : tp[0][n.index()];
            } else {
                position = fromEnd ? t.fromEnd[n.index()] : t.position[n.index()];
            }
            if (position <= 0) return false; // not an element
            // position = a*n + b for some n >= 0
            if (a == 0) return position == b;
            long diff = (long) position - b;
            return diff % a == 0 && diff / a >= 0;
        }
    }

    private static final class Not implements Simple {
        final CssList list;

        Not(CssList list) {
            this.list = list;
        }

        @Override
        public boolean matches(HtmlNode n, Context ctx) {
            return !list.matches(n, ctx);
        }
    }

    private static final class Is implements Simple {
        final CssList list;

        Is(CssList list) {
            this.list = list;
        }

        @Override
        public boolean matches(HtmlNode n, Context ctx) {
            return list.matches(n, ctx);
        }
    }

    private static final class Has implements Simple {
        final CssList list;

        Has(CssList list) {
            this.list = list;
        }

        @Override
        public boolean matches(HtmlNode n, Context ctx) {
            return hasMatch(list, n, ctx);
        }
    }

    private static final class Contains implements Simple {
        final String text;

        Contains(String text) {
            this.text = text;
        }

        @Override
        public boolean matches(HtmlNode n, Context ctx) {
            return n.text().contains(text);
        }
    }

    private static final int PSEUDO_ROOT = 0;
    private static final int PSEUDO_SCOPE = 1;
    private static final int PSEUDO_EMPTY = 2;
    private static final int PSEUDO_LINK = 3;
    private static final int PSEUDO_CHECKED = 4;
    private static final int PSEUDO_DISABLED = 5;
    private static final int PSEUDO_ENABLED = 6;
    private static final int PSEUDO_REQUIRED = 7;
    private static final int PSEUDO_OPTIONAL = 8;

    private static boolean isFormControl(String name) {
        switch (name) {
            case "button": case "input": case "select": case "textarea": case "optgroup": case "option": case "fieldset":
                return true;
            default:
                return false;
        }
    }

    private static boolean isInput(String name) {
        return name.equals("input") || name.equals("select") || name.equals("textarea");
    }

    /** the first top-level element: &lt;html&gt; on a page */
    private static boolean isRoot(HtmlNode n, Context ctx) {
        return n.parentIndex < 0 && ctx.tree().prev(n) == null;
    }

    /** No child elements and no text; comments are allowed. */
    private static boolean isEmpty(HtmlNode n) {
        if (n.innerStart <= 0 || n.innerEnd <= 0) return true; // a void or unclosed tag has no content
        int position = n.innerStart;
        HtmlNode[] nodes = n.document().nodes;
        for (int i = n.index() + 1; i < nodes.length && nodes[i].end <= n.end; i++) {
            HtmlNode child = nodes[i];
            if (isElement(child) || child.start > position) return false; // an element, or text before a comment
            position = Math.max(position, child.end + 1);
        }
        return position > n.innerEnd;
    }

    private static final class Pseudo implements Simple {
        final int kind;

        Pseudo(int kind) {
            this.kind = kind;
        }

        @Override
        public boolean matches(HtmlNode n, Context ctx) {
            String name = n.name();
            switch (kind) {
                case PSEUDO_ROOT:
                    return isRoot(n, ctx);
                case PSEUDO_SCOPE:
                    return ctx.scope != null ? n == ctx.scope : isRoot(n, ctx);
                case PSEUDO_EMPTY:
                    return isEmpty(n);
                case PSEUDO_LINK:
                    return (name.equals("a") || name.equals("area")) && Attrs.hasAttribute(n, "href");
                case PSEUDO_CHECKED:
                    return (name.equals("input") && Attrs.hasAttribute(n, "checked"))
                        || (name.equals("option") && Attrs.hasAttribute(n, "selected"));
                case PSEUDO_DISABLED:
                    return isFormControl(name) && Attrs.hasAttribute(n, "disabled");
                case PSEUDO_ENABLED:
                    return isFormControl(name) && !Attrs.hasAttribute(n, "disabled");
                case PSEUDO_REQUIRED:
                    return isInput(name) && Attrs.hasAttribute(n, "required");
                case PSEUDO_OPTIONAL:
                    return isInput(name) && !Attrs.hasAttribute(n, "required");
                default:
                    return false;
            }
        }
    }

    // ----- compiler -----

    private static boolean isNameStart(int c) {
        return ((c | 0x20) >= 'a' && (c | 0x20) <= 'z') || c == '_' || c >= 0x80;
    }

    private static boolean isNthChar(int c) {
        return c == '+' || c == '-' || c == 'n' || c == 'N' || (c >= '0' && c <= '9') || isCssSpace(c);
    }

    /**
     * Parses a selector list (Selectors Level 3 and the common Level 4 pseudo-classes).
     * Identifiers and strings follow the CSS syntax, escapes included (#a\:b, .\31 0, [title="a\"b"]).
     */
    private static final class Compiler {
        int p;
        final String s;

        Compiler(String s) {
            this.s = s;
        }

        CssSelectorException fail(String msg) {
            throw new CssSelectorException("invalid CSS selector " + Json.quote(s) + " at position " + p + ": " + msg);
        }

        int peek(int offset) {
            int i = p + offset;
            return i < s.length() ? s.charAt(i) : 0;
        }

        boolean skipSpace() {
            int start = p;
            while (p < s.length() && isCssSpace(s.charAt(p))) p++;
            return p > start;
        }

        void expect(char ch) {
            if (peek(0) != ch) {
                if (p >= s.length()) throw fail("'" + ch + "' expected");
                throw fail("'" + ch + "' expected instead of '" + s.charAt(p) + "'");
            }
            p++;
        }

        /** selector-list: complex (',' complex)* */
        CssList parseList(boolean relative, boolean topLevel) {
            CssList list = new CssList();
            for (;;) {
                list.items.add(parseComplex(relative, topLevel));
                skipSpace();
                if (peek(0) != ',') return list;
                p++;
            }
        }

        /** complex: [combinator] compound (combinator compound)* */
        Complex parseComplex(boolean relative, boolean topLevel) {
            skipSpace();
            Complex cx = new Complex();
            if (relative || topLevel) {
                int comb = readCombinator();
                if (comb != COMB_NONE) {
                    if (topLevel && comb != COMB_CHILD) {
                        throw fail("a selector can start only with '>' (the children of the tag it is called on)");
                    }
                    cx.leading = comb;
                } else if (relative) {
                    cx.leading = COMB_DESCENDANT;
                }
            }
            cx.compounds.add(parseCompound());
            for (;;) {
                boolean ws = skipSpace();
                int ch = peek(0);
                if (ch == 0 || ch == ',' || ch == ')') return cx; // end, ',' or ')'
                int comb = readCombinator();
                if (comb == COMB_NONE) {
                    if (!ws) throw fail("unexpected '" + s.charAt(p) + "'");
                    comb = COMB_DESCENDANT;
                }
                cx.combinators.add(comb);
                cx.compounds.add(parseCompound());
            }
        }

        int readCombinator() {
            int comb;
            switch (peek(0)) {
                case '>':
                    comb = COMB_CHILD;
                    break;
                case '+':
                    comb = COMB_ADJACENT;
                    break;
                case '~':
                    comb = COMB_SIBLING;
                    break;
                default:
                    return COMB_NONE;
            }
            p++;
            skipSpace();
            return comb;
        }

        /** compound: [type | '*'] (#id | .class | [attribute] | :pseudo)* */
        Compound parseCompound() {
            Compound cp = new Compound();
            boolean hasType = false;
            if (peek(0) == '*') {
                p++;
                hasType = true;
            } else if (isIdentStart(p)) {
                String name = Chars.lower(readName());
                hasType = true;
                int t = Parser.lookupTag(name);
                if (t >= 0) cp.key = t;
                else cp.name = name;
            }
            if (peek(0) == '|') throw fail("namespaces are not supported; escape the colon of a prefixed name: dc\\:creator");

            for (;;) {
                int ch = peek(0);
                if (ch == '#') {
                    p++;
                    if (!isNameChar(p)) throw fail("an id expected after '#'");
                    cp.simples.add(new IdSelector(readName()));
                } else if (ch == '.') {
                    p++;
                    if (!isIdentStart(p)) throw fail("a class name expected after '.'");
                    cp.simples.add(new ClassSelector(readName()));
                } else if (ch == '[') {
                    cp.simples.add(parseAttribute());
                } else if (ch == ':') {
                    parsePseudo(cp);
                } else {
                    break;
                }
            }

            if (!hasType && cp.simples.isEmpty()) {
                if (p >= s.length()) throw fail("a selector expected");
                throw fail("unexpected '" + s.charAt(p) + "'");
            }
            return cp;
        }

        /** '[' name [op value [i|s]] ']' */
        Simple parseAttribute() {
            p++; // '['
            skipSpace();
            if (!isIdentStart(p)) throw fail("an attribute name expected");
            String name = readName();
            skipSpace();
            if (peek(0) == ']') {
                p++;
                return new AttributeSelector(name, OP_EXISTS, "", false);
            }

            int op;
            int ch = peek(0);
            if (ch == '=') {
                op = OP_EQUALS;
                p++;
            } else if (ch == '~' || ch == '|' || ch == '^' || ch == '$' || ch == '*') {
                op = ch == '~' ? OP_INCLUDES : ch == '|' ? OP_DASH : ch == '^' ? OP_PREFIX : ch == '$' ? OP_SUFFIX : OP_SUBSTRING;
                if (peek(1) != '=') throw fail("'=' expected after '" + (char) ch + "'");
                p += 2;
            } else {
                throw fail("'=', '~=', '|=', '^=', '$=', '*=' or ']' expected");
            }

            skipSpace();
            String value;
            if (peek(0) == '"' || peek(0) == '\'') value = readString();
            else if (isIdentStart(p)) value = readName();
            else throw fail("an attribute value expected (quote it if it is not a plain word)");

            skipSpace();
            boolean fold = CASE_INSENSITIVE_VALUES.contains(Chars.lower(name));
            if (isIdentStart(p)) {
                String flag = readName();
                if (Chars.equalFold(flag, "i")) fold = true;
                else if (Chars.equalFold(flag, "s")) fold = false;
                else throw fail("unknown attribute flag '" + flag + "'");
                skipSpace();
            }
            expect(']');
            return new AttributeSelector(name, op, value, fold);
        }

        void parsePseudo(Compound cp) {
            p++; // ':'
            if (peek(0) == ':') throw fail("pseudo-elements (::before, ::text) select no tags");
            if (!isIdentStart(p)) throw fail("a pseudo-class name expected after ':'");
            int start = p;
            String name = Chars.lower(readName());
            List<Simple> add = cp.simples;

            if (peek(0) == '(') {
                p++;
                skipSpace();
                switch (name) {
                    case "not":
                        add.add(new Not(parseList(false, false)));
                        break;
                    case "is":
                    case "where":
                    case "matches":
                    case "any":
                        add.add(new Is(parseList(false, false)));
                        break;
                    case "has":
                        add.add(new Has(parseList(true, false)));
                        break;
                    case "nth-child":
                    case "nth-last-child":
                    case "nth-of-type":
                    case "nth-last-of-type": {
                        int[] ab = parseNth();
                        boolean ofType = name.endsWith("of-type");
                        CssList of = null;
                        skipSpace();
                        if (!ofType && isWord("of")) {
                            p += 2;
                            of = parseList(false, false);
                        }
                        add.add(new Nth(ab[0], ab[1], name.contains("last"), ofType, of));
                        break;
                    }
                    case "contains": {
                        String text;
                        if (peek(0) == '"' || peek(0) == '\'') text = readString();
                        else if (isIdentStart(p)) text = readName();
                        else throw fail("a string expected in :contains()");
                        add.add(new Contains(text));
                        break;
                    }
                    default:
                        p = start;
                        throw fail("unknown pseudo-class ':" + name + "()'");
                }
                skipSpace();
                expect(')');
                return;
            }

            switch (name) {
                case "first-child":
                    add.add(new Nth(0, 1, false, false, null));
                    return;
                case "last-child":
                    add.add(new Nth(0, 1, true, false, null));
                    return;
                case "only-child":
                    add.add(new Nth(0, 1, false, false, null));
                    add.add(new Nth(0, 1, true, false, null));
                    return;
                case "first-of-type":
                    add.add(new Nth(0, 1, false, true, null));
                    return;
                case "last-of-type":
                    add.add(new Nth(0, 1, true, true, null));
                    return;
                case "only-of-type":
                    add.add(new Nth(0, 1, false, true, null));
                    add.add(new Nth(0, 1, true, true, null));
                    return;
                case "root":
                    add.add(new Pseudo(PSEUDO_ROOT));
                    return;
                case "scope":
                    add.add(new Pseudo(PSEUDO_SCOPE));
                    return;
                case "empty":
                    add.add(new Pseudo(PSEUDO_EMPTY));
                    return;
                case "link":
                case "any-link":
                    add.add(new Pseudo(PSEUDO_LINK));
                    return;
                case "checked":
                    add.add(new Pseudo(PSEUDO_CHECKED));
                    return;
                case "disabled":
                    add.add(new Pseudo(PSEUDO_DISABLED));
                    return;
                case "enabled":
                    add.add(new Pseudo(PSEUDO_ENABLED));
                    return;
                case "required":
                    add.add(new Pseudo(PSEUDO_REQUIRED));
                    return;
                case "optional":
                    add.add(new Pseudo(PSEUDO_OPTIONAL));
                    return;
                default:
                    p = start;
                    throw fail("unknown pseudo-class ':" + name + "'");
            }
        }

        /** An+B: odd, even, 3, n, -n+3, 2n+1, +2n - 1 */
        int[] parseNth() {
            if (isWord("odd")) {
                p += 3;
                return new int[] {2, 1};
            }
            if (isWord("even")) {
                p += 4;
                return new int[] {2, 0};
            }
            int start = p;
            StringBuilder tb = new StringBuilder();
            while (p < s.length()) {
                char ch = s.charAt(p);
                if (!isNthChar(ch)) break;
                if (!isCssSpace(ch)) tb.append(ch == 'N' ? 'n' : ch);
                p++;
            }
            String t = tb.toString();
            int n = t.indexOf('n');
            if (n < 0) {
                Integer v = parseInt32(t);
                if (v != null) return new int[] {0, v};
            } else if (t.indexOf('n', n + 1) < 0) {
                String aText = t.substring(0, n);
                String bText = t.substring(n + 1);
                Integer a = aText.isEmpty() || aText.equals("+") ? Integer.valueOf(1) : aText.equals("-") ? Integer.valueOf(-1) : parseInt32(aText);
                Integer b = 0;
                if (!bText.isEmpty()) {
                    boolean ok = (bText.charAt(0) == '+' || bText.charAt(0) == '-') && bText.length() > 1
                        && bText.charAt(1) >= '0' && bText.charAt(1) <= '9';
                    b = ok ? parseInt32(bText) : null;
                }
                if (a != null && b != null) return new int[] {a, b};
            }
            p = start;
            throw fail("An+B expected (odd, even, 3, 2n+1, -n+3)");
        }

        /** The identifier at the position is word (case-insensitive) and ends there. */
        boolean isWord(String word) {
            return p + word.length() <= s.length() && Chars.equalFold(s.substring(p, p + word.length()), word)
                && !isNameChar(p + word.length());
        }

        boolean isEscape(int p) {
            if (p >= s.length() || s.charAt(p) != '\\') return false;
            if (p + 1 >= s.length()) return true;
            int c = s.charAt(p + 1);
            return c != '\n' && c != '\r' && c != '\f';
        }

        boolean isNameChar(int p) {
            if (p >= s.length()) return false;
            int c = s.charAt(p);
            return isNameStart(c) || (c >= '0' && c <= '9') || c == '-' || isEscape(p);
        }

        boolean isIdentStart(int p) {
            if (p >= s.length()) return false;
            if (s.charAt(p) == '-') {
                p++;
                if (p >= s.length()) return false;
                int c = s.charAt(p);
                return isNameStart(c) || c == '-' || isEscape(p);
            }
            return isNameStart(s.charAt(p)) || isEscape(p);
        }

        String readName() {
            StringBuilder b = new StringBuilder();
            while (isNameChar(p)) {
                if (s.charAt(p) == '\\') b.append(readEscape());
                else b.append(s.charAt(p++));
            }
            return b.toString();
        }

        String readString() {
            char quote = s.charAt(p);
            int start = p;
            p++;
            StringBuilder b = new StringBuilder();
            for (;;) {
                if (p >= s.length()) {
                    p = start;
                    throw fail("unterminated string");
                }
                char ch = s.charAt(p);
                if (ch == quote) {
                    p++;
                    return b.toString();
                }
                if (ch == '\\') {
                    int next = peek(1);
                    if (p + 1 < s.length() && (next == '\n' || next == '\f')) {
                        p += 2; // an escaped line break continues the string
                    } else if (p + 2 < s.length() && next == '\r') {
                        p += peek(2) == '\n' ? 3 : 2;
                    } else {
                        b.append(readEscape());
                    }
                } else {
                    b.append(ch);
                    p++;
                }
            }
        }

        /** '\' then up to 6 hex digits and one optional whitespace, or any other char as itself. */
        String readEscape() {
            p++; // '\'
            if (p >= s.length()) return "�";
            if (!Chars.isHexChar(s.charAt(p))) {
                int cp = s.codePointAt(p);
                p += Character.charCount(cp);
                return new String(Character.toChars(cp));
            }
            int code = 0;
            int digits = 0;
            while (digits < 6 && p < s.length() && Chars.isHexChar(s.charAt(p))) {
                code = code * 16 + Character.digit(s.charAt(p), 16);
                p++;
                digits++;
            }
            if (p < s.length() && isCssSpace(s.charAt(p))) {
                p += s.charAt(p) == '\r' && peek(1) == '\n' ? 2 : 1;
            }
            if (code == 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return "�";
            return new String(Character.toChars(code));
        }
    }

    private static Integer parseInt32(String s) {
        int i = 0;
        if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) i = 1;
        if (i == s.length()) return null;
        for (int k = i; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c < '0' || c > '9') return null;
        }
        BigInteger v = new BigInteger(s);
        return v.bitLength() < 32 ? Integer.valueOf(v.intValue()) : null;
    }
}
