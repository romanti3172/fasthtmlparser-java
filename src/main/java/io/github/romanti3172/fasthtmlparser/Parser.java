package io.github.romanti3172.fasthtmlparser;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A page is scanned once in fast mode: tags are matched to their end tags and nothing else. Well-formed markup needs
 * nothing more. Where the HTML5 optional-end-tag rules matter ({@code <li>one<li>two</ul>}), tags are left open inside
 * an element: when its end tag (or the end of the document) finds them on the stack and one of them is a rule target
 * (li, p, td, tr, option, ...), only that part is scanned again in rules mode, from the outermost such tag up to that
 * end tag. Everything before it was parsed right and is kept.
 *
 * <p>Port of ParsingCore.cs from z3l.FastHtmlParser; positions are UTF-16 indexes into the source.
 */
final class Parser {
    private Parser() {
    }

    /** Nesting deeper than this is flattened, as browsers do (Blink: 512). */
    private static final int MAX_DEPTH = 512;
    /** The parts scanned again with the optional-end-tag rules may add up to this many times the source length. */
    private static final int REPAIR_BUDGET_FACTOR = 8;

    private static final int TAG_COUNT = Tag.VALUES.length;
    private static final int COMMENT = Tag.COMMENT.ordinal();
    private static final int P = Tag.P.ordinal();

    // ----- tables -----

    // Tag names by a hash of their lower-case chars (open addressing): a name is looked up right in the source,
    // without cutting it out as a string first.
    private static final int TABLE_MASK = 1023;
    private static final short[] TAG_TABLE = new short[TABLE_MASK + 1];
    static final String[] TAG_NAMES = new String[TAG_COUNT];

    static {
        Arrays.fill(TAG_TABLE, (short) -1);
        for (int t = 0; t < TAG_COUNT; t++) {
            String name = Tag.VALUES[t].tagName();
            TAG_NAMES[t] = name;
            int slot = hashRange(name, 0, name.length());
            while (TAG_TABLE[slot] >= 0) slot = (slot + 1) & TABLE_MASK;
            TAG_TABLE[slot] = (short) t;
        }
    }

    private static int hashRange(String s, int from, int to) {
        int h = 0;
        for (int i = from; i < to; i++) {
            int c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') c += 32;
            h = h * 31 + c;
        }
        return (h ^ (h >>> 10)) & TABLE_MASK;
    }

    /** Finds the tag name s[from, to) case-insensitively (ASCII); -1 for a name that is not a known tag. */
    static int lookupTagAt(String s, int from, int to) {
        int length = to - from;
        if (length <= 0 || length > 32) return -1;
        for (int slot = hashRange(s, from, to); ; slot = (slot + 1) & TABLE_MASK) {
            int t = TAG_TABLE[slot];
            if (t < 0) return -1;
            String name = TAG_NAMES[t];
            if (name.length() != length) continue;
            int k = 0;
            for (; k < length; k++) {
                int c = s.charAt(from + k);
                if (c >= 'A' && c <= 'Z') c += 32;
                if (c != name.charAt(k)) break;
            }
            if (k == length) return t;
        }
    }

    /** Finds a tag name case-insensitively (ASCII); -1 for a name that is not a known tag. */
    static int lookupTag(String name) {
        return lookupTagAt(name, 0, name.length());
    }

    private static boolean[] tagSet(Tag... tags) {
        boolean[] set = new boolean[TAG_COUNT];
        for (Tag t : tags) set[t.ordinal()] = true;
        return set;
    }

    private static final class ImpliedEndRule {
        final boolean[] targets = new boolean[TAG_COUNT];
        final boolean[] stops = new boolean[TAG_COUNT];
        final int[] targetKeys;

        ImpliedEndRule(Tag[] targets, Tag[]... stops) {
            targetKeys = new int[targets.length];
            for (int i = 0; i < targets.length; i++) {
                this.targets[targets[i].ordinal()] = true;
                targetKeys[i] = targets[i].ordinal();
            }
            for (Tag[] list : stops) for (Tag s : list) this.stops[s.ordinal()] = true;
        }

        /** Whether a target is open at all: in well-formed markup it is not. */
        boolean anyOpen(int[] openByKey) {
            for (int t : targetKeys) if (openByKey[t] > 0) return true;
            return false;
        }
    }

    private static Tag[] tags(Tag... t) {
        return t;
    }

    // Elements that limit the scope of the rules (HTML5 "has an element in scope").
    private static final Tag[] SCOPE_STOPS = tags(Tag.HTML, Tag.BODY, Tag.TABLE, Tag.CAPTION, Tag.TD, Tag.TH,
        Tag.TEMPLATE, Tag.OBJECT, Tag.APPLET, Tag.MARQUEE, Tag.BUTTON);

    // <p> is ended by the start of a block element (HTML5 "close a p element").
    private static final ImpliedEndRule P_RULE = new ImpliedEndRule(tags(Tag.P), SCOPE_STOPS);

    private static final boolean[] CLOSES_P = tagSet(Tag.ADDRESS, Tag.ARTICLE, Tag.ASIDE, Tag.BLOCKQUOTE, Tag.CENTER,
        Tag.DETAILS, Tag.DIALOG, Tag.DIR, Tag.DIV, Tag.DL, Tag.FIELDSET, Tag.FIGCAPTION, Tag.FIGURE,
        Tag.FOOTER, Tag.FORM, Tag.H1, Tag.H2, Tag.H3, Tag.H4, Tag.H5, Tag.H6,
        Tag.HEADER, Tag.HGROUP, Tag.HR, Tag.LISTING, Tag.MAIN, Tag.MENU, Tag.NAV,
        Tag.OL, Tag.P, Tag.PRE, Tag.SEARCH, Tag.SECTION, Tag.SUMMARY, Tag.TABLE,
        Tag.UL, Tag.LI, Tag.DD, Tag.DT, Tag.PLAINTEXT, Tag.XMP);

    private static final ImpliedEndRule[] IMPLIED_END_RULES = new ImpliedEndRule[TAG_COUNT];
    private static final boolean[] IS_RULE_TARGET = new boolean[TAG_COUNT];

    static {
        ImpliedEndRule[] rules = IMPLIED_END_RULES;
        rules[Tag.LI.ordinal()] = new ImpliedEndRule(tags(Tag.LI), SCOPE_STOPS, tags(Tag.UL, Tag.OL, Tag.MENU));

        ImpliedEndRule dtdd = new ImpliedEndRule(tags(Tag.DT, Tag.DD), SCOPE_STOPS, tags(Tag.DL));
        rules[Tag.DT.ordinal()] = dtdd;
        rules[Tag.DD.ordinal()] = dtdd;

        Tag[] tableStops = tags(Tag.HTML, Tag.BODY, Tag.TEMPLATE, Tag.TABLE);
        ImpliedEndRule cell = new ImpliedEndRule(tags(Tag.TD, Tag.TH), tableStops, tags(Tag.TR, Tag.TBODY, Tag.THEAD, Tag.TFOOT));
        rules[Tag.TD.ordinal()] = cell;
        rules[Tag.TH.ordinal()] = cell;
        rules[Tag.TR.ordinal()] = new ImpliedEndRule(tags(Tag.TR, Tag.TD, Tag.TH), tableStops, tags(Tag.TBODY, Tag.THEAD, Tag.TFOOT));
        ImpliedEndRule section = new ImpliedEndRule(
            tags(Tag.TBODY, Tag.THEAD, Tag.TFOOT, Tag.TR, Tag.TD, Tag.TH, Tag.CAPTION), tableStops);
        rules[Tag.TBODY.ordinal()] = section;
        rules[Tag.THEAD.ordinal()] = section;
        rules[Tag.TFOOT.ordinal()] = section;

        Tag[] selectStops = tags(Tag.HTML, Tag.BODY, Tag.TEMPLATE, Tag.SELECT, Tag.DATALIST);
        rules[Tag.OPTION.ordinal()] = new ImpliedEndRule(tags(Tag.OPTION), selectStops, tags(Tag.OPTGROUP));
        rules[Tag.OPTGROUP.ordinal()] = new ImpliedEndRule(tags(Tag.OPTION, Tag.OPTGROUP), selectStops);

        for (ImpliedEndRule r : rules) if (r != null) for (int k : r.targetKeys) IS_RULE_TARGET[k] = true;
    }

    // HTML5 void elements (incl. obsolete ones the parsing rules also treat as void) and declarations.
    private static final boolean[] VOID_TAGS = tagSet(Tag.AREA, Tag.BASE, Tag.BR, Tag.COL, Tag.EMBED, Tag.HR, Tag.IMG,
        Tag.INPUT, Tag.LINK, Tag.META, Tag.SOURCE, Tag.TRACK, Tag.WBR,
        Tag.BASEFONT, Tag.BGSOUND, Tag.FRAME, Tag.KEYGEN, Tag.PARAM, Tag.ISINDEX,
        Tag.DOCTYPE, Tag.XML_DECLARATION);

    /** "&lt;/script", "&lt;/style", ... for the elements whose content is raw text (HTML5 RAWTEXT / RCDATA); null for others. */
    static final String[] RAW_TEXT_ENDS = new String[TAG_COUNT];

    static {
        for (Tag t : tags(Tag.SCRIPT, Tag.STYLE, Tag.TEXTAREA, Tag.TITLE, Tag.XMP, Tag.IFRAME, Tag.NOEMBED, Tag.NOFRAMES)) {
            RAW_TEXT_ENDS[t.ordinal()] = "</" + t.tagName();
        }
    }

    /** Whether the content of the tag is raw text (script, style, textarea, title, ...). */
    static boolean isRawText(int key) {
        return key >= 0 && RAW_TEXT_ENDS[key] != null;
    }

    // ----- helpers -----

    /** As in HTML5, '&lt;' starts markup only before an ASCII letter, '/', '!' or '?'. */
    static boolean isMarkupStart(String s, int i, int limit) {
        if (i + 1 >= limit) return false;
        int c = s.charAt(i + 1);
        int l = c | 0x20;
        return (l >= 'a' && l <= 'z') || c == '/' || c == '!' || c == '?';
    }

    static boolean isMarkupStart(String s, int i) {
        return isMarkupStart(s, i, s.length());
    }

    /** The position of the '&lt;' of the end tag (e.g. "&lt;/script") in s[from, limit), or limit if there is none. */
    static int findRawTextEnd(String s, int from, String endTag, int limit) {
        while (from < limit) {
            int pos = Chars.indexFold(s, endTag, from, limit);
            if (pos < 0) break;
            int after = pos + endTag.length();
            // "</script>", "</script >", "</script/>" end it; "</scripts" does not.
            if (after >= limit) return pos;
            int c = s.charAt(after);
            if (c == '>' || c == '/' || Chars.isSpace(c)) return pos;
            from = after;
        }
        return limit;
    }

    static int findRawTextEnd(String s, int from, String endTag) {
        return findRawTextEnd(s, from, endTag, s.length());
    }

    /**
     * The position of the '&gt;' that ends a comment whose content starts at contentStart (in s[0, limit)). As in HTML5:
     * "&lt;!--&gt;" and "&lt;!---&gt;" are empty comments, "--&gt;" or "--!&gt;" end a comment, otherwise it runs to the end.
     */
    static int findCommentEnd(String s, int contentStart, int limit) {
        if (contentStart < limit && s.charAt(contentStart) == '>') return contentStart;
        if (contentStart + 1 < limit && s.charAt(contentStart) == '-' && s.charAt(contentStart + 1) == '>') {
            return contentStart + 1;
        }
        int from = contentStart;
        while (from < limit) {
            int dashes = s.indexOf("--", from);
            if (dashes < 0 || dashes + 2 > limit) break;
            int pos = dashes + 2;
            if (pos < limit && s.charAt(pos) == '>') return pos;
            if (pos + 1 < limit && s.charAt(pos) == '!' && s.charAt(pos + 1) == '>') return pos + 1;
            from = dashes + 1;
        }
        return limit - 1;
    }

    static int findCommentEnd(String s, int contentStart) {
        return findCommentEnd(s, contentStart, s.length());
    }

    /** An XML document (RSS, Atom, sitemap, XHTML) is parsed by XML rules: elements end only with an end tag or "/&gt;". */
    private static boolean isXml(String src) {
        int i = 0;
        while (i < src.length()) {
            int c = src.charAt(i);
            if (!Chars.isSpace(c) && c != 0xfeff) break; // whitespace and a BOM
            i++;
        }
        return Chars.startsWithAt(src, i, "<?") && src.length() - i >= 5 && Chars.startsWithFold(src, i, "<?xml");
    }

    /** A custom tag name: the lower-case name, one string per document, and how many such tags are in the open list. */
    private static final class CustomName {
        final String name;
        int open;

        CustomName(String name) {
            this.name = name;
        }
    }

    /** Buffers reused between the parses on one thread: only the result is allocated per document. */
    private static final class Scratch {
        int size;
        int[] key = new int[0]; // Tag, or -1 for a custom name
        int[] depth = new int[0];
        int[] prevOpen = new int[0]; // the open tags as a linked list in document order
        boolean[] selfClosed = new boolean[0]; // <x/>: stays in the open list (a later </x> ends it), but nests nothing
        int[] stack = new int[64]; // indexes of the open tags that contain the following ones
        final int[] openByKey = new int[TAG_COUNT]; // per Tag, how many are on the stack: kept only in rules mode
        final int[] openInList = new int[TAG_COUNT]; // per Tag, how many are in the open list

        void grow(int needed) {
            int n = Math.max(needed, Math.max(size * 2, 256));
            key = Arrays.copyOf(key, n);
            depth = Arrays.copyOf(depth, n);
            prevOpen = Arrays.copyOf(prevOpen, n);
            selfClosed = Arrays.copyOf(selfClosed, n);
            size = n;
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** Parses src into the flat tag list of doc. */
    static HtmlNode[] parseTags(HtmlDocument doc, String src) {
        if (src.indexOf('<') < 0) return new HtmlNode[0];
        Scratch sc = SCRATCH.get();
        Arrays.fill(sc.openInList, 0);
        Arrays.fill(sc.openByKey, 0);

        State st = new State(doc, src, sc);
        st.scan(false, 0, src.length());

        // The end of the document ends every tag still open (a truncated page keeps its text),
        // right before an unfinished tag if the source stops inside one.
        int regionEnd = st.tagStarted ? st.tagStart - 1 : src.length();
        if (!st.isXml) {
            int s = st.firstMisnested(0, st.stackTop);
            if (s >= 0) st.repair(s, regionEnd);
        }
        st.truncateOpenList(0, regionEnd - 1);

        HtmlNode[] nodes = Arrays.copyOf(st.nodes, st.count);
        for (HtmlNode n : nodes) if (n.innerEnd == 0) n.innerStart = 0; // not closed: no body
        return nodes;
    }

    private static final class State {
        final HtmlDocument doc;
        final String src;
        final boolean isXml;
        final Scratch sc;
        HtmlNode[] nodes = new HtmlNode[64];
        Map<String, CustomName> custom;
        int count;
        int stackTop;
        int lastOpen = -1;
        int openP; // <p> tags on the stack, kept in both modes
        long budget; // chars that may still be scanned again by repair
        boolean tagStarted; // scan results
        int tagStart;
        // the result of tagNameAt: the name is src[nameFrom, nameTo)
        int nameFrom;
        int nameTo;

        State(HtmlDocument doc, String src, Scratch sc) {
            this.doc = doc;
            this.src = src;
            this.sc = sc;
            this.isXml = Parser.isXml(src);
            this.budget = (long) REPAIR_BUDGET_FACTOR * src.length();
        }

        void setNode(int k, HtmlNode n) {
            if (k >= nodes.length) nodes = Arrays.copyOf(nodes, Math.max(k + 1, nodes.length * 2));
            nodes[k] = n;
        }

        /** Finds the name in the inside of an opening tag src[from, to) (trimmed; "!--" for a comment). */
        void tagNameAt(int from, int to) {
            while (to > from && Chars.isUnicodeSpace(src.charAt(to - 1))) to--;
            while (from < to && Chars.isUnicodeSpace(src.charAt(from))) from++;
            nameFrom = from;
            if (Chars.startsWithAt(src, from, "!--", to)) {
                nameTo = from + 3;
                return;
            }
            // The name ends at whitespace or '/': <br/> is "br", as <br /> is.
            int i = from;
            while (i < to) {
                int c = src.charAt(i);
                if (Chars.isSpace(c) || c == '/') break;
                i++;
            }
            nameTo = i;
        }

        /** Parses src[from, to) (from is outside a tag), adding tags at count. Tags still open at to stay on the stack. */
        void scan(boolean rules, int from, int to) {
            String src = this.src;
            Scratch sc = this.sc;
            boolean tagStarted = false;
            int tagStart = 0;
            int i = from;
            while (i < to) {
                if (!tagStarted) {
                    // Outside a tag only '<' matters: jump to it.
                    int lt = src.indexOf('<', i);
                    if (lt < 0 || lt >= to) break;
                    i = lt;

                    if (Chars.startsWithAt(src, i + 1, "!--", to)) {
                        // <!-- ... -->: one "!--" tag; its content is not markup, it does not nest anything.
                        int commentEnd = findCommentEnd(src, i + 4, to);
                        int parent = -1;
                        int depth = 0;
                        if (stackTop > 0) {
                            parent = sc.stack[stackTop - 1];
                            depth = sc.depth[parent] + 1;
                        }
                        int k = count;
                        if (k >= sc.size) sc.grow(k + 1);
                        setNode(k, new HtmlNode(doc, k, parent, COMMENT, "", i, commentEnd, commentEnd + 1, depth));
                        sc.key[k] = COMMENT;
                        sc.depth[k] = depth;
                        sc.prevOpen[k] = -1;
                        sc.selfClosed[k] = false;
                        count++;
                        i = commentEnd + 1;
                        continue;
                    }

                    // As in HTML5, '<' starts markup only before a letter, '/', '!' or '?': "a < b" is text.
                    if (!isMarkupStart(src, i, to)) {
                        i++;
                        continue;
                    }

                    tagStarted = true;
                    tagStart = i + 1;
                    i++;
                    continue;
                }

                // Inside a tag only '<', '=' and '>' matter.
                int c = 0;
                while (i < to) {
                    c = src.charAt(i);
                    if (c == '<' || c == '=' || c == '>') break;
                    i++;
                }
                if (i >= to) break;

                if (c == '<') {
                    // An unfinished tag: start over at this '<' (it may also start a comment).
                    tagStarted = false;
                    continue;
                }
                if (c == '=') {
                    // A quoted value may contain '<' and '>' (data-x="<p>...</p>"): jump over it.
                    int q = i + 1;
                    while (q < to && Chars.isSpace(src.charAt(q))) q++;
                    if (q < to) {
                        int quote = src.charAt(q);
                        if (quote == '"' || quote == '\'') {
                            int closeQuote = src.indexOf(quote, q + 1);
                            if (closeQuote >= 0 && closeQuote < to) i = closeQuote;
                        }
                    }
                } else {
                    // '>'
                    if (src.charAt(tagStart) == '/') endTag(rules, tagStart, i);
                    else i = startTag(rules, tagStart, i, to);
                    tagStarted = false;
                }
                i++;
            }
            this.tagStarted = tagStarted;
            this.tagStart = tagStart;
        }

        /** Matches the end tag src[tagStart-1 .. gt] to the innermost open tag with that name. */
        void endTag(boolean rules, int tagStart, int gt) {
            int from = tagStart + 1;
            int to = gt;
            while (to > from && Chars.isUnicodeSpace(src.charAt(to - 1))) to--;
            while (from < to && Chars.isUnicodeSpace(src.charAt(from))) from++;
            if (from == to) return;
            // Resolve the name once, then compare ints instead of a lookup per candidate.
            int key = lookupTagAt(src, from, to);
            String name = key >= 0 ? "" : src.substring(from, to); // only custom names are compared as strings
            // The rules may have ended the matched tag itself (<li><div><li>x</div>: the second <li> ends the first one
            // together with its <div>, as in a browser): the end tag is matched again, to what is open now.
            // Once: the part is not scanned a second time.
            if (matchEnd(rules, key, name, tagStart, gt, true)) matchEnd(rules, key, name, tagStart, gt, false);
        }

        /**
         * Ends the innermost open tag with the end tag's name. Returns true when it scanned a part again
         * with the optional-end-tag rules instead, and the end tag is to be matched once more.
         */
        boolean matchEnd(boolean rules, int key, String name, int tagStart, int gt, boolean canRepair) {
            Scratch sc = this.sc;
            // A name that no open tag has is a stray end tag: it is skipped without scanning the open tags.
            CustomName closing = null;
            if (key >= 0) {
                if (sc.openInList[key] <= 0) return false;
            } else {
                if (custom == null) return false;
                closing = custom.get(Chars.lowerKey(name));
                if (closing == null || closing.open <= 0) return false;
            }

            for (int j = lastOpen; j >= 0; j = sc.prevOpen[j]) {
                if (key >= 0) {
                    if (sc.key[j] != key) continue;
                } else if (sc.key[j] >= 0 || !nodes[j].custom.equals(closing.name)) {
                    continue;
                }

                int innerEnd = tagStart - 2;

                // Tags left open inside it: if the optional-end-tag rules apply to one of them,
                // scan its part again with the rules (fast mode only; rules mode has applied them).
                if (canRepair && !rules && !isXml && stackTop > 0 && sc.stack[stackTop - 1] > j) {
                    int above = stackTop - 1;
                    while (above > 0 && sc.stack[above - 1] > j) above--;
                    int target = firstMisnested(above, stackTop);
                    if (target >= 0) {
                        repair(target, tagStart - 1);
                        return true;
                    }
                }

                HtmlNode node = nodes[j];
                node.innerEnd = innerEnd;
                node.end = gt;

                while (stackTop > 0 && sc.stack[stackTop - 1] >= j) {
                    stackTop--;
                    int popped = sc.key[sc.stack[stackTop]];
                    if (popped == P) openP--;
                    if (rules && popped >= 0) sc.openByKey[popped]--;
                }

                // It leaves the open list with everything opened after it; the tags opened inside it and
                // never closed end right before this end tag, as in a browser.
                truncateOpenList(j, innerEnd);
                return false;
            }
            return false;
        }

        /** Adds the tag src[tagStart-1 .. gt]; returns the position to continue from (gt, or before a raw-text end tag). */
        int startTag(boolean rules, int tagStart, int gt, int to) {
            Scratch sc = this.sc;
            tagNameAt(tagStart, gt);
            int key = lookupTagAt(src, nameFrom, nameTo);
            // Custom names (svg elements, web components, ...): one string per distinct name per document.
            String customName = key < 0 ? internName(src.substring(nameFrom, nameTo)) : "";

            if (!isXml && key >= 0) {
                // Optional end tags (HTML5): <li> ends an open <li>, <td> an open <td>, ...
                ImpliedEndRule rule = IMPLIED_END_RULES[key];
                if (rule != null) {
                    if (rules) {
                        if (rule.anyOpen(sc.openByKey)) closeInScope(rule, tagStart - 2, true);
                    } else if (stackTop > 0) {
                        // The common case of a left-out end tag is caught right here: the innermost open tag is a target.
                        int top = sc.key[sc.stack[stackTop - 1]];
                        if (top >= 0 && rule.targets[top]) applyRuleInFastMode(rule, tagStart);
                    }
                }

                // A block element ends an open <p>. Usually no <p> is open and this is one compare.
                if (openP > 0 && CLOSES_P[key]) {
                    if (rules) closeInScope(P_RULE, tagStart - 2, true);
                    else applyRuleInFastMode(P_RULE, tagStart);
                }
            }

            // the innermost open tag is the parent
            int parent = -1;
            int depth = 0;
            if (stackTop > 0) {
                parent = sc.stack[stackTop - 1];
                depth = sc.depth[parent] + 1;
            }

            int index = count;
            if (index >= sc.size) sc.grow(index + 1);
            setNode(index, new HtmlNode(doc, index, parent, key, customName, tagStart - 1, gt, gt + 1, depth));

            // Void elements (<br>, <img>, <meta>, ...) and declarations never contain anything.
            boolean isVoid = !isXml && key >= 0 && VOID_TAGS[key];
            boolean selfClosing = isVoid;
            if (!selfClosing) {
                int e = gt;
                while (e > tagStart && Chars.isSpace(src.charAt(e - 1))) e--;
                selfClosing = e > tagStart && src.charAt(e - 1) == '/';
            }

            sc.key[index] = key;
            sc.depth[index] = depth;
            if (isVoid) {
                sc.prevOpen[index] = -1;
                sc.selfClosed[index] = false;
            } else {
                sc.prevOpen[index] = lastOpen;
                sc.selfClosed[index] = selfClosing;
                lastOpen = index;
                if (key >= 0) sc.openInList[key]++;
                else adjustCustom(customName, 1);
            }
            count++;

            if (!selfClosing) {
                // Deeper than MAX_DEPTH the tag is not nested: it stays in the open list for its end tag.
                if (stackTop < MAX_DEPTH) {
                    if (stackTop == sc.stack.length) sc.stack = Arrays.copyOf(sc.stack, sc.stack.length * 2);
                    sc.stack[stackTop++] = index;
                    if (key == P) openP++;
                    if (rules && key >= 0) sc.openByKey[key]++;
                }

                // <script>, <style>, <textarea>, <title>, ...: the content is text, not markup;
                // continue right before the matching end tag.
                if (!isXml && isRawText(key)) {
                    return findRawTextEnd(src, gt + 1, RAW_TEXT_ENDS[key], to) - 1;
                }
            }
            return gt;
        }

        // ----- implied end tags -----

        /** Among the tags left open at stack positions [from, to), the outermost one that a rule should have ended, or -1. */
        int firstMisnested(int from, int to) {
            Scratch sc = this.sc;
            for (int s = from; s < to - 1; s++) {
                int key = sc.key[sc.stack[s]];
                if (key < 0 || !IS_RULE_TARGET[key]) continue;
                for (int inner = s + 1; inner < to; inner++) {
                    int innerKey = sc.key[sc.stack[inner]];
                    if (innerKey >= 0) {
                        ImpliedEndRule rule = IMPLIED_END_RULES[innerKey];
                        if (rule != null && rule.targets[key]) return s;
                    }
                }
            }
            return -1;
        }

        /**
         * Scans again with the optional-end-tag rules from the tag at stack position stackPos up to regionEnd:
         * the tags from it on are replaced, the ones before it are kept.
         */
        void repair(int stackPos, int regionEnd) {
            Scratch sc = this.sc;
            int first = sc.stack[stackPos];
            int from = nodes[first].start;

            // Past the budget the part is left as it is, so that parsing stays linear on any markup.
            int cost = regionEnd - from;
            if (cost > budget) return;
            budget -= cost;

            truncateOpenList(first, -1);
            stackTop = stackPos;
            count = first;

            Arrays.fill(sc.openByKey, 0);
            openP = 0;
            for (int s = 0; s < stackPos; s++) {
                int key = sc.key[sc.stack[s]];
                if (key < 0) continue;
                sc.openByKey[key]++;
                if (key == P) openP++;
            }

            scan(true, from, regionEnd);
        }

        /**
         * Removes the tags from index first on from the open list (they are its tail).
         * With end &gt;= 0 the ones that have no end tag of their own end there.
         */
        void truncateOpenList(int first, int end) {
            Scratch sc = this.sc;
            int k = lastOpen;
            while (k >= first) {
                HtmlNode node = nodes[k];
                int key = sc.key[k];
                if (key >= 0) sc.openInList[key]--;
                else adjustCustom(node.custom, -1);

                if (end >= 0 && !sc.selfClosed[k] && node.innerEnd == 0) {
                    node.innerEnd = end;
                    node.end = end;
                }
                k = sc.prevOpen[k];
            }
            lastOpen = k;
        }

        void adjustCustom(String name, int delta) {
            if (custom == null || name.isEmpty()) return;
            CustomName c = custom.get(name);
            if (c != null) c.open += delta;
        }

        /** The lower-case name as one string per document. */
        String internName(String name) {
            if (name.isEmpty()) return "";
            if (custom == null) custom = new HashMap<>();
            String lower = Chars.lowerKey(name);
            CustomName c = custom.get(lower);
            if (c != null) return c.name;
            custom.put(lower, new CustomName(lower));
            return lower;
        }

        /** The stack position of the outermost target of rule below the first stop tag, or -1. */
        int scopeStart(ImpliedEndRule rule) {
            int from = -1;
            for (int s = stackTop - 1; s >= 0; s--) {
                int k = sc.key[sc.stack[s]];
                if (k < 0) continue;
                if (rule.targets[k]) from = s;
                else if (rule.stops[k]) break;
            }
            return from;
        }

        /**
         * Applies rule for the tag that starts at tagStart. If a tag it ends was itself misnested,
         * that part is scanned again with the rules first.
         */
        void applyRuleInFastMode(ImpliedEndRule rule, int tagStart) {
            int from = scopeStart(rule);
            if (from < 0) return;
            int m = firstMisnested(from, stackTop);
            if (m >= 0) repair(m, tagStart - 1);
            closeInScope(rule, tagStart - 2, false);
        }

        /** Ends the outermost target found below the first stop tag together with everything opened inside it. */
        void closeInScope(ImpliedEndRule rule, int end, boolean byKey) {
            int from = scopeStart(rule);
            if (from < 0) return;
            int first = sc.stack[from];
            while (stackTop > from) {
                stackTop--;
                int key = sc.key[sc.stack[stackTop]];
                if (key == P) openP--;
                if (byKey && key >= 0) sc.openByKey[key]--;
            }
            truncateOpenList(first, end);
        }
    }
}
