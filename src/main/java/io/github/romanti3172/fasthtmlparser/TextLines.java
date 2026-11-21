package io.github.romanti3172.fasthtmlparser;

/** Text with the visible line structure kept ({@link HtmlNode#textLineBreaks()}). */
final class TextLines {
    private TextLines() {
    }

    private static final byte BREAK_NONE = 0; // inline tag (b, span, a, ...): removed, words are not split
    private static final byte BREAK_SPACE = 1; // table cell: word separator
    private static final byte BREAK_LINE = 2; // <br> and block elements (div, li, tr, ...): new line
    private static final byte BREAK_PARAGRAPH = 3; // p, h1-h6: new paragraph (blank line)
    private static final byte BREAK_SKIP_CONTENT = 4; // script, style, noscript: contents are not text

    private static final byte[] KINDS = new byte[Tag.VALUES.length];
    private static final int BR = Tag.BR.ordinal();

    static {
        for (Tag t : new Tag[] {Tag.P, Tag.H1, Tag.H2, Tag.H3, Tag.H4, Tag.H5, Tag.H6}) KINDS[t.ordinal()] = BREAK_PARAGRAPH;
        for (Tag t : new Tag[] {
            Tag.BR, Tag.DIV, Tag.LI, Tag.UL, Tag.OL, Tag.DL, Tag.DT, Tag.DD,
            Tag.TABLE, Tag.CAPTION, Tag.TR, Tag.THEAD, Tag.TBODY, Tag.TFOOT,
            Tag.SECTION, Tag.ARTICLE, Tag.ASIDE, Tag.NAV, Tag.HEADER, Tag.FOOTER, Tag.MAIN,
            Tag.ADDRESS, Tag.BLOCKQUOTE, Tag.PRE, Tag.HR, Tag.FIGURE, Tag.FIGCAPTION,
            Tag.FORM, Tag.FIELDSET, Tag.LEGEND, Tag.DETAILS, Tag.SUMMARY, Tag.DIALOG,
            Tag.OPTION, Tag.HTML, Tag.HEAD, Tag.BODY, Tag.TITLE,
            Tag.HGROUP, Tag.SEARCH, Tag.MENU, Tag.CENTER, Tag.DIR, Tag.LISTING,
            Tag.XMP, Tag.PLAINTEXT, Tag.MULTICOL, Tag.FRAMESET, Tag.MARQUEE,
        }) {
            KINDS[t.ordinal()] = BREAK_LINE;
        }
        KINDS[Tag.TD.ordinal()] = BREAK_SPACE;
        KINDS[Tag.TH.ordinal()] = BREAK_SPACE;
        for (Tag t : new Tag[] {Tag.SCRIPT, Tag.STYLE, Tag.NOSCRIPT, Tag.TEMPLATE, Tag.NOFRAMES, Tag.NOEMBED}) {
            KINDS[t.ordinal()] = BREAK_SKIP_CONTENT;
        }
    }

    /**
     * Whether the tag (an ordinal of {@link Tag}) separates words in the one-line text of {@code text()}, as in jsoup's
     * {@code text()}: {@code <br>}, block elements, p/h1-h6 and table cells. The same tags as the line breaks here.
     */
    static boolean separatesWords(int tag) {
        byte k = KINDS[tag];
        return k == BREAK_LINE || k == BREAK_PARAGRAPH || k == BREAK_SPACE;
    }

    /** Writes text, collapsing whitespace and turning requested breaks into trimmed lines. */
    private static final class LineWriter {
        final StringBuilder out = new StringBuilder();
        int pendingBreaks; // new lines requested since the last text char
        boolean pendingSpace;

        /** Writes s[from, to). */
        void chars(String s, int from, int to) {
            for (int i = from; i < to; i++) {
                int c = s.charAt(i);
                if (Chars.isUnicodeSpace(c)) {
                    pendingSpace = true;
                    continue;
                }
                // a run of text chars at once
                int j = i + 1;
                while (j < to && !Chars.isUnicodeSpace(s.charAt(j))) j++;
                text(s, i, j);
                i = j - 1;
            }
        }

        private void text(String s, int from, int to) {
            if (out.length() > 0) {
                // nothing before the first text char: leading breaks and spaces are trimmed
                if (pendingBreaks > 0) out.append(pendingBreaks >= 2 ? "\n\n" : "\n"); // at most one blank line
                else if (pendingSpace) out.append(' ');
            }
            pendingBreaks = 0;
            pendingSpace = false;
            out.append(s, from, to);
        }

        /** A block boundary: at least count new lines before the next text. */
        void lineBreak(int count) {
            pendingBreaks = Math.max(pendingBreaks, count);
        }
    }

    /** The text of an HTML fragment with the visible line structure kept. Lines are joined with "\n". */
    static String clearTagsLineBreaks(String value) {
        if (value.isEmpty()) return "";
        if (value.indexOf('\r') >= 0) value = Decode.normalizeNewlines(value); // values are read as a browser reads the page
        LineWriter w = new LineWriter();
        int[] consumed = new int[1];
        int n = value.length();
        int i = 0;
        while (i < n) {
            int c = value.charAt(i);

            if (c == '&') {
                // '&': decoded chars go through the same whitespace/text logic (&nbsp; collapses, &lt; is plain text)
                String decoded = Decode.decodeReference(value, i + 1, consumed);
                if (decoded != null) {
                    w.chars(decoded, 0, decoded.length());
                    i += 1 + consumed[0];
                } else {
                    w.chars("&", 0, 1);
                    i++;
                }
                continue;
            }

            if (c != '<' || !Parser.isMarkupStart(value, i)) {
                // plain text up to the next '&' or '<'
                int j = i + 1;
                while (j < n) {
                    int d = value.charAt(j);
                    if (d == '&' || d == '<') break;
                    j++;
                }
                w.chars(value, i, j);
                i = j;
                continue;
            }

            if (value.startsWith("<!--", i)) {
                int end = value.indexOf("-->", i + 4);
                if (end < 0) break;
                i = end + 3;
                continue;
            }

            int closeTag = value.indexOf('>', i);
            if (closeTag < 0) break; // unterminated tag: the rest is markup
            String tag = value.substring(i + 1, closeTag);
            i = closeTag + 1;

            boolean closing = !tag.isEmpty() && tag.charAt(0) == '/';
            String name = closing ? tag.substring(1) : tag;
            for (int k = 0; k < name.length(); k++) {
                int d = name.charAt(k);
                if (d == '/' || (Chars.isSpace(d) && d != 11)) {
                    // " \t\r\n\f/"
                    name = name.substring(0, k);
                    break;
                }
            }
            int known = Parser.lookupTag(name);
            if (known < 0) continue; // unknown tag: just removed

            switch (KINDS[known]) {
                case BREAK_LINE:
                    if (known == BR) w.pendingBreaks++; // every <br> counts
                    else w.lineBreak(1);
                    break;
                case BREAK_PARAGRAPH:
                    w.lineBreak(2);
                    break;
                case BREAK_SPACE:
                    w.chars(" ", 0, 1);
                    break;
                case BREAK_SKIP_CONTENT:
                    if (!closing && !Chars.trimRightAscii(tag).endsWith("/")) {
                        // jump to the closing tag; it is removed on the next iteration
                        int end = Chars.indexFold(value, "</" + Parser.TAG_NAMES[known], i);
                        i = end < 0 ? n : end;
                    }
                    break;
                default:
                    break;
            }
        }
        return w.out.toString();
    }
}
