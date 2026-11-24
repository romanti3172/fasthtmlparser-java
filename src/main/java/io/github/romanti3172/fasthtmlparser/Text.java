package io.github.romanti3172.fasthtmlparser;

/** Visible text: tags, comments and invisible content removed, whitespace collapsed, entities decoded. */
final class Text {
    private Text() {
    }

    /**
     * s[i] is '&lt;'. Returns the index of the last char of what is not text there: a comment, a tag,
     * or a tag together with invisible content (script, style, ...) up to its end tag. -1: an unterminated tag.
     */
    static int skipMarkup(String s, int i) {
        if (Chars.startsWithAt(s, i + 1, "!--")) return Parser.findCommentEnd(s, i + 4);
        int tagEnd = s.indexOf('>', i + 1);
        if (tagEnd < 0) return -1;
        String endTag = invisibleContentEnd(s, i + 1, tagEnd);
        if (endTag != null) return Parser.findRawTextEnd(s, tagEnd + 1, endTag) - 1;
        return tagEnd;
    }

    /**
     * For the inside of an opening tag s[from, to), the end tag ("&lt;/script") when the element's content
     * is never visible text (script, style, noscript, template, noframes, noembed), otherwise null.
     */
    private static String invisibleContentEnd(String s, int from, int to) {
        if (from >= to) return null;
        int first = s.charAt(from) | 0x20;
        if (first != 's' && first != 't' && first != 'n') return null;
        int n = 1;
        while (from + n < to && n <= 8) {
            int c = s.charAt(from + n) | 0x20;
            if (c < 'a' || c > 'z') break;
            n++;
        }
        if (from + n < to) {
            int c = s.charAt(from + n);
            if (c != '/' && !Chars.isSpace(c)) return null;
        }
        String name = s.substring(from, from + n);
        String end;
        if (Chars.equalFold(name, "style")) end = "</style";
        else if (Chars.equalFold(name, "script")) end = "</script";
        else if (Chars.equalFold(name, "noembed")) end = "</noembed";
        else if (Chars.equalFold(name, "noscript")) end = "</noscript";
        else if (Chars.equalFold(name, "template")) end = "</template";
        else if (Chars.equalFold(name, "noframes")) end = "</noframes";
        else return null;
        int e = to;
        while (e > from && Chars.isSpace(s.charAt(e - 1))) e--;
        if (s.charAt(e - 1) == '/') return null; // <script src="x" />: no content
        return end;
    }

    /**
     * Whether the markup at s[i] ('<') is a tag that separates words in the one-line text, as in jsoup's {@code text()}:
     * {@code <br>}, a block element or a table cell, opening or closing. Comments, declarations, inline and unknown
     * tags do not.
     */
    static boolean separatesWords(String s, int i) {
        int start = i + 1;
        if (start < s.length() && s.charAt(start) == '/') start++;
        int end = start;
        while (end < s.length() && isAsciiLetterOrDigit(s.charAt(end))) end++;
        if (end == start) return false;
        int tag = Parser.lookupTagAt(s, start, end);
        return tag >= 0 && TextLines.separatesWords(tag);
    }

    /** Whether the HTML has a "<pre" (any case). */
    private static boolean hasPreTag(String s) {
        for (int i = s.indexOf('<'); i >= 0; i = s.indexOf('<', i + 1)) {
            if (s.regionMatches(true, i + 1, "pre", 0, 3)) return true;
        }
        return false;
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }

    /** +1 for an opening pre, listing or plaintext at s[i], -1 for a closing one, 0 otherwise: their text keeps its whitespace. */
    private static int preTag(String s, int i) {
        int start = i + 1, sign = 1;
        if (start < s.length() && s.charAt(start) == '/') {
            start++;
            sign = -1;
        }
        if (start >= s.length() || ((s.charAt(start) | 0x20) != 'p' && (s.charAt(start) | 0x20) != 'l')) return 0;
        int end = start;
        while (end < s.length() && Character.isLetter(s.charAt(end)) && s.charAt(end) < 128) end++;
        String name = s.substring(start, end);
        return name.equalsIgnoreCase("pre") || name.equalsIgnoreCase("listing") || name.equalsIgnoreCase("plaintext") ? sign : 0;
    }

    /** Whether trimmed text has whitespace to collapse: a line break, tab or other space than ' ', or two spaces in a row. */
    static boolean needsCollapse(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                if (i + 1 < s.length() && Chars.isUnicodeSpace(s.charAt(i + 1))) return true;
            } else if (Chars.isUnicodeSpace(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes tags, comments and invisible content, collapses whitespace runs into one ' ' and trims: the visible text
     * in one line, as jsoup's {@code text()}. {@code <br>}, block elements and table cells separate words ("a<br>b", a
     * row of cells: "a b"), inline tags do not ("a<b>b</b>": "ab"). Inside {@code <pre>} (inPre: the text itself is in
     * one) whitespace is kept as written.
     */
    private static String stripTags(String s, boolean inPre) {
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        int preDepth = inPre ? 1 : 0;
        int run = -1; // start of the current run of text chars
        int i = 0;
        int n = s.length();
        while (i < n) {
            int c = s.charAt(i);
            if (c == '<' && Parser.isMarkupStart(s, i)) {
                if (run >= 0) {
                    out.append(s, run, i);
                    run = -1;
                }
                int last = skipMarkup(s, i);
                if (last < 0) break;
                if (!inPre) preDepth = Math.max(0, preDepth + preTag(s, i));
                // only after a word: next to whitespace the tag changes nothing (and costs no lookup)
                if (out.length() > 0 && !pendingSpace && !Chars.isUnicodeSpace(out.charAt(out.length() - 1)) && separatesWords(s, i)) {
                    pendingSpace = true;
                }
                i = last + 1;
                continue;
            }
            if (Chars.isUnicodeSpace(c) && preDepth == 0) {
                if (run >= 0) {
                    out.append(s, run, i);
                    run = -1;
                }
                if (out.length() > 0) pendingSpace = true;
                i++;
                continue;
            }
            if (run < 0) {
                if (out.length() == 0 && Chars.isUnicodeSpace(c)) { // inside <pre>: not at the start
                    i++;
                    continue;
                }
                // not after whitespace kept as written inside <pre>
                if (pendingSpace) {
                    if (!Chars.isUnicodeSpace(out.charAt(out.length() - 1))) out.append(' ');
                    pendingSpace = false;
                }
                run = i;
            }
            i++;
        }
        if (run == 0 && preDepth == 0) return s;
        if (run >= 0) out.append(s, run, n);
        // Right trim (inside <pre> there may be trailing whitespace).
        int end = out.length();
        while (end > 0 && Chars.isUnicodeSpace(out.charAt(end - 1))) end--;
        out.setLength(end);
        return out.toString();
    }

    /** The visible text of an HTML fragment in one line. */
    static String clearTags(String s) {
        return clearTags(s, false);
    }

    static String clearTags(String s, boolean inPre) {
        if (s.isEmpty()) return "";
        String text = stripTags(s, inPre);
        return text.indexOf('&') >= 0 ? Decode.decode(text, false) : text;
    }

    /** Whether the visible text of the body (value) equals expected, comparing on the fly; n gives the full text. */
    static boolean isText(String value, String expected, HtmlNode n) {
        if (value.isEmpty() || expected.isEmpty()) return false;
        // <pre> keeps its whitespace in text(), raw text is as written: compare with text() itself (rare, the slow way)
        if (Parser.isRawText(n.key) || n.document().hasPre() && (hasPreTag(value) || n.inPre())) {
            return n.text().equals(expected);
        }
        int matched = 0;
        boolean prevSpace = true; // left trim
        boolean pendingSpace = false; // a collapsed space counts only if more text follows (right trim)
        for (int i = 0; i < value.length(); ) {
            int c = value.charAt(i);
            if (c == '<' && Parser.isMarkupStart(value, i)) {
                int last = skipMarkup(value, i);
                if (last < 0) break;
                // <br>, blocks and cells separate words, as in text()
                if (!prevSpace && separatesWords(value, i)) {
                    pendingSpace = true;
                    prevSpace = true;
                }
                i = last + 1;
                continue;
            }
            if (Chars.isUnicodeSpace(c)) {
                if (!prevSpace) {
                    pendingSpace = true;
                    prevSpace = true;
                }
                i++;
                continue;
            }
            // Text with entities must be decoded before comparing: take the full path.
            if (c == '&') return n.text().equals(expected);
            if (pendingSpace) {
                if (matched >= expected.length() || expected.charAt(matched) != ' ') return false;
                matched++;
                pendingSpace = false;
            }
            if (matched >= expected.length() || expected.charAt(matched) != c) return false;
            matched++;
            prevSpace = false;
            i++;
        }
        return matched == expected.length();
    }
}
