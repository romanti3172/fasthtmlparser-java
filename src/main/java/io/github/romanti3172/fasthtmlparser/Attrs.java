package io.github.romanti3172.fasthtmlparser;

/** Attributes are read from the source of the opening tag when they are asked for. */
final class Attrs {
    private Attrs() {
    }

    private static final int COMMENT = Tag.COMMENT.ordinal();

    /** The decoded value of the first attribute with the name (case-insensitive); "" when missing or without a value. */
    static String attrValue(HtmlNode n, String name) {
        return Decode.decode(attrRaw(n, name), true);
    }

    /** Whether one of the space-separated classes equals cls (case-insensitive). */
    static boolean hasClass(HtmlNode n, String cls) {
        if (cls.isEmpty()) return false;
        String value = attrRaw(n, "class");
        if (Decode.needsDecoding(value)) value = Decode.decode(value, true);
        if (value.length() == cls.length()) return Chars.equalFold(value, cls);
        if (cls.length() > value.length()) return false;
        for (int from = 0; from <= value.length() - cls.length(); ) {
            int i = Chars.indexFold(value, cls, from);
            if (i < 0) return false;
            int after = i + cls.length();
            if ((i == 0 || Chars.isSpace(value.charAt(i - 1))) && (after == value.length() || Chars.isSpace(value.charAt(after)))) {
                return true;
            }
            from = i + 1;
        }
        return false;
    }

    /**
     * The raw value of the first attribute with the name, as a browser reads the tag: quoted or unquoted
     * (href=/p?a=1), "" for an attribute without a value or a missing one.
     */
    static String attrRaw(HtmlNode n, String attribute) {
        // a comment has no attributes, whatever its text looks like
        if (attribute.isEmpty() || n.key == COMMENT) return "";

        String src = n.document().source();
        int start = n.start;
        int end = openTagEnd(n); // src[start, end): "<name attributes", without the closing '>'

        // The first place where the name stands as a word of its own. Most tags have none.
        int pos = nextWord(src, end, attribute, start + 1);
        if (pos < 0) return "";

        // That word is the attribute unless it is inside the value of another one (title="see id=5", x= id).
        // Nearly always that is ruled out at once, and the value is read in place.
        boolean firstWord = n.key >= 0 && pos - start == Parser.TAG_NAMES[n.key].length() + 2;
        if (firstWord || isNameForSure(src, start, pos)) {
            Value v = new Value();
            v.read(src, pos + attribute.length(), end);
            return v.start < 0 ? "" : src.substring(v.start, v.start + v.length);
        }

        // Otherwise the attributes are read one by one, up to the one that starts at that word.
        Scanner s = new Scanner(n);
        while (s.next()) {
            // a word the scanner has passed was inside a value: the next one takes its place
            while (pos < s.nameStart) {
                pos = nextWord(src, end, attribute, pos + 1);
                if (pos < 0) return "";
            }
            if (pos == s.nameStart && s.nameLength == attribute.length()) {
                return s.valueStart < 0 ? "" : src.substring(s.valueStart, s.valueStart + s.valueLength);
            }
        }
        return "";
    }

    private static boolean startsName(int c) {
        return Chars.isSpace(c) || c == '"' || c == '\'' || c == '/' || c == '=';
    }

    private static boolean endsName(int c) {
        return Chars.isSpace(c) || c == '=' || c == '/' || c == '>';
    }

    /** The next place in src[start, end) at or after from where the name stands as a word of its own, or -1. */
    private static int nextWord(String src, int end, String attribute, int from) {
        while (from < end) {
            int found = Chars.indexFold(src, attribute, from, end);
            if (found < 0) break;
            int after = found + attribute.length();
            if (startsName(Chars.at(src, found - 1)) && (after == end || endsName(Chars.at(src, after)))) return found;
            from = found + 1;
        }
        return -1;
    }

    /**
     * Whether the word at pos of the opening tag that starts at start is an attribute name, judged by the chars
     * before it alone. False means "not sure".
     */
    private static boolean isNameForSure(String src, int start, int pos) {
        // After whitespace, and not as the unquoted value in "x= word".
        int last = pos - 1;
        if (!Chars.isSpace(src.charAt(last))) return false;
        while (last > start && Chars.isSpace(src.charAt(last))) last--;
        if (src.charAt(last) == '=') return false;
        // Not inside a quoted value: it is enough that the last quote of each kind before the word does not open a value.
        return !opensValue(src, start, lastIndexIn(src, '"', start, last)) && !opensValue(src, start, lastIndexIn(src, '\'', start, last));
    }

    /** The last position of ch in src[start, last]; -1 when there is none. Never looks before the tag. */
    private static int lastIndexIn(String src, char ch, int start, int last) {
        for (int i = last; i >= start; i--) if (src.charAt(i) == ch) return i;
        return -1;
    }

    /** Whether the quote at quote (or -1: none) stands right after an '='. */
    private static boolean opensValue(String src, int start, int quote) {
        if (quote < 0) return false;
        int k = quote - 1; // >= start: the tag starts with '<'
        while (k > start && Chars.isSpace(src.charAt(k))) k--;
        return src.charAt(k) == '=';
    }

    /** Compares the decoded value; decodes only when the raw value has '&amp;' or CR. */
    static boolean attrEquals(String raw, String value) {
        return Decode.needsDecoding(raw) ? Decode.decode(raw, true).equals(value) : raw.equals(value);
    }

    /** Whether the decoded value starts with prefix. */
    static boolean attrStartsWith(String raw, String prefix) {
        return (Decode.needsDecoding(raw) ? Decode.decode(raw, true) : raw).startsWith(prefix);
    }

    /** The position of the '&gt;' that ends the opening tag. */
    static int openTagEnd(HtmlNode n) {
        int end = n.innerStart > 0 && n.innerEnd > 0 ? n.innerStart - 1 : n.end;
        return Math.min(end, n.document().source().length());
    }

    /** The value that follows an attribute name: start (-1: no '='), length, and the position after the value. */
    private static final class Value {
        int start;
        int length;
        int next;

        /** Reads the value that follows an attribute name at pos (before end). */
        void read(String s, int pos, int end) {
            int p = pos;
            while (p < end && Chars.isSpace(s.charAt(p))) p++;
            if (p >= end || s.charAt(p) != '=') {
                start = -1;
                length = 0;
                next = pos;
                return;
            }
            p++;
            while (p < end && Chars.isSpace(s.charAt(p))) p++;
            int q = p < end ? s.charAt(p) : -1;
            if (q == '"' || q == '\'') {
                start = p + 1;
                int close = start;
                while (close < end && s.charAt(close) != q) close++;
                if (close >= end) {
                    length = end - start;
                    next = end;
                } else {
                    length = close - start;
                    next = close + 1;
                }
                return;
            }
            start = p;
            while (p < end && !Chars.isSpace(s.charAt(p))) p++;
            length = p - start;
            next = p;
        }
    }

    /** Reads the attributes of an opening tag one by one: positions in the source. */
    static final class Scanner {
        final String src;
        final int end;
        int pos;
        int nameStart;
        int nameLength;
        int valueStart; // -1: no value
        int valueLength;
        private final Value value = new Value();

        Scanner(HtmlNode n) {
            String src = n.document().source();
            int end = openTagEnd(n);
            int i = n.start + 1; // skip '<' and the name
            while (i < end) {
                int c = src.charAt(i);
                if (Chars.isSpace(c) || c == '/' || c == '>') break;
                i++;
            }
            this.src = src;
            this.end = end;
            this.pos = i;
        }

        /** Moves to the next attribute; false at the end of the tag. */
        boolean next() {
            String s = src;
            for (;;) {
                while (pos < end && (Chars.isSpace(s.charAt(pos)) || s.charAt(pos) == '/')) pos++;
                if (pos >= end) return false;
                nameStart = pos;
                while (pos < end) {
                    int c = s.charAt(pos);
                    if (Chars.isSpace(c) || c == '=' || c == '/' || c == '>') break;
                    pos++;
                }
                nameLength = pos - nameStart;
                if (nameLength == 0) {
                    pos++; // a stray '=': skip it
                    continue;
                }
                value.read(s, pos, end);
                valueStart = value.start;
                valueLength = value.length;
                pos = value.next;
                return true;
            }
        }

        String name() {
            return src.substring(nameStart, nameStart + nameLength);
        }

        String rawValue() {
            return valueStart < 0 ? "" : src.substring(valueStart, valueStart + valueLength);
        }
    }

    /** Whether the element has the attribute, with or without a value. */
    static boolean hasAttribute(HtmlNode n, String name) {
        if (!attrRaw(n, name).isEmpty()) return true;
        if (n.key == COMMENT) return false;
        Scanner s = new Scanner(n);
        while (s.next()) if (Chars.equalFold(s.name(), name)) return true;
        return false;
    }
}
