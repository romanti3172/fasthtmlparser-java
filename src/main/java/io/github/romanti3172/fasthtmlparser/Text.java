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

    /** Removes tags, comments and invisible content, collapses whitespace runs into one ' ' and trims. */
    private static String stripTags(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
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
                if (last < 0) return out.toString();
                i = last + 1;
                continue;
            }
            if (Chars.isUnicodeSpace(c)) {
                if (run >= 0) {
                    out.append(s, run, i);
                    run = -1;
                }
                if (out.length() > 0) pendingSpace = true;
                i++;
                continue;
            }
            if (run < 0) {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                run = i;
            }
            i++;
        }
        if (run == 0) return s;
        if (run > 0) out.append(s, run, n);
        return out.toString();
    }

    /** The visible text of an HTML fragment. */
    static String clearTags(String s) {
        if (s.isEmpty()) return "";
        String text = stripTags(s);
        return text.indexOf('&') >= 0 ? Decode.decode(text, false) : text;
    }

    /** Whether the visible text of the body (value) equals expected, comparing on the fly; n gives the full text. */
    static boolean isText(String value, String expected, HtmlNode n) {
        if (value.isEmpty() || expected.isEmpty()) return false;
        int matched = 0;
        boolean prevSpace = true; // left trim
        boolean pendingSpace = false; // a collapsed space counts only if more text follows (right trim)
        for (int i = 0; i < value.length(); ) {
            int c = value.charAt(i);
            if (c == '<' && Parser.isMarkupStart(value, i)) {
                int last = skipMarkup(value, i);
                if (last < 0) break;
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
