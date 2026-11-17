package io.github.romanti3172.fasthtmlparser;

import java.util.HashMap;
import java.util.Map;

/**
 * HTML5 character reference decoder (WHATWG "character reference state"): all 2125 named references, the legacy
 * names without ';', numeric references with or without ';', and the spec replacements (0, surrogates,
 * &gt; U+10FFFF → U+FFFD; 0x80–0x9F → Windows-1252).
 */
final class Decode {
    private Decode() {
    }

    /** The entity table, built on first use. */
    private static final class Table {
        static final Map<String, Integer> INDEX = new HashMap<>(4096);
        static final String[] VALUES;
        static final boolean[] LEGACY;

        static {
            String[] names = Entities.NAMES.split(" ");
            VALUES = new String[names.length];
            LEGACY = new boolean[names.length];
            String values = Entities.VALUES;
            for (int i = 0; i < names.length; i++) {
                String name = names[i];
                boolean legacy = name.charAt(0) == '+';
                if (legacy) name = name.substring(1);
                char second = values.charAt(2 * i + 1);
                VALUES[i] = second == 0 ? values.substring(2 * i, 2 * i + 1) : values.substring(2 * i, 2 * i + 2);
                LEGACY[i] = legacy;
                INDEX.put(name, i);
            }
        }
    }

    /** Whether a value read from the page differs from its source: it has a character reference or a CR. */
    static boolean needsDecoding(String s) {
        return s.indexOf('&') >= 0 || s.indexOf('\r') >= 0;
    }

    /** HTML input stream preprocessing: CR LF and a lone CR become LF. */
    static String normalizeNewlines(String s) {
        int cr = s.indexOf('\r');
        if (cr < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        b.append(s, 0, cr);
        for (int i = cr; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                b.append('\n');
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * Decodes the character references in s; CR LF and a lone CR become LF. With inAttribute, a legacy name
     * without ';' followed by '=' or an alphanumeric is left as is (so "?a=1&amp;copy=2" stays intact).
     * Without '&amp;' and CR the input is returned as is.
     */
    static String decode(String s, boolean inAttribute) {
        // Line breaks first, as a browser reads the page; a CR written as "&#13;" is decoded after that and stays.
        if (s.indexOf('\r') >= 0) s = normalizeNewlines(s);
        int amp = s.indexOf('&');
        if (amp < 0) return s;

        StringBuilder out = new StringBuilder(s.length());
        int pos = 0;
        while (amp >= 0) {
            out.append(s, pos, amp);
            int consumed = 0;
            if (amp + 1 < s.length() && s.charAt(amp + 1) == '#') {
                long r = matchNumeric(s, amp + 1);
                consumed = (int) (r >>> 32);
                if (consumed > 0) out.appendCodePoint((int) r);
            } else {
                long r = matchNamed(s, amp + 1, inAttribute);
                consumed = (int) (r >>> 32);
                if (consumed > 0) out.append(Table.VALUES[(int) r]);
            }
            if (consumed == 0) out.append('&'); // not a reference: keep '&'
            pos = amp + 1 + consumed;
            amp = s.indexOf('&', pos);
        }
        return out.append(s, pos, s.length()).toString();
    }

    /**
     * s[at] is '#'. Returns the number of chars consumed (0: not a reference) in the high 32 bits and the
     * code point in the low 32 bits.
     */
    private static long matchNumeric(String s, int at) {
        int i = at + 1;
        boolean hex = i < s.length() && (s.charAt(i) | 0x20) == 'x';
        if (hex) i++;
        int digits = i;
        int value = 0;
        for (; i < s.length(); i++) {
            int c = s.charAt(i);
            int d;
            if (c >= '0' && c <= '9') d = c - '0';
            else if (hex && (c | 0x20) >= 'a' && (c | 0x20) <= 'f') d = (c | 0x20) - 'a' + 10;
            else break;
            value = hex ? value * 16 + d : value * 10 + d;
            if (value > 0x10ffff) value = 0x110000; // saturate on long digit runs
        }
        if (i == digits) return 0; // "&#" / "&#x" without digits is plain text
        if (i < s.length() && s.charAt(i) == ';') i++;
        if (value == 0 || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) {
            value = 0xfffd;
        } else if (value >= 0x80 && value <= 0x9f) {
            int mapped = WINDOWS_1252[value - 0x80];
            if (mapped != 0) value = mapped;
        }
        return ((long) (i - at) << 32) | value;
    }

    /**
     * s[at] is the char right after '&amp;'. Returns the number of chars consumed (0: not a reference) in the high
     * 32 bits and the entity index in the low 32 bits.
     */
    private static long matchNamed(String s, int at, boolean inAttribute) {
        int limit = Math.min(s.length() - at, Entities.MAX_NAME + 1);
        int n = 0;
        while (n < limit && Chars.isAlnum(s.charAt(at + n))) n++;
        if (n < 2) return 0;
        Map<String, Integer> t = Table.INDEX;
        // Full match: "&name;".
        if (at + n < s.length() && s.charAt(at + n) == ';') {
            Integer e = t.get(s.substring(at, at + n));
            if (e != null) return ((long) (n + 1) << 32) | e;
        }
        // Longest legacy prefix without ';': "&copy2024" → "©2024", "&notit;" → "¬it;".
        for (int k = Math.min(n, Entities.MAX_LEGACY_NAME); k >= 2; k--) {
            Integer e = t.get(s.substring(at, at + k));
            if (e != null && Table.LEGACY[e]) {
                if (inAttribute && at + k < s.length()) {
                    int c = s.charAt(at + k);
                    if (c == '=' || Chars.isAlnum(c)) break;
                }
                return ((long) k << 32) | e;
            }
        }
        return 0;
    }

    /**
     * Decodes one character reference; s[at] is the char right after '&amp;'. Returns the decoded text, or null when
     * there is no reference; consumed[0] gets the number of chars consumed after '&amp;'.
     */
    static String decodeReference(String s, int at, int[] consumed) {
        if (at < s.length() && s.charAt(at) == '#') {
            long r = matchNumeric(s, at);
            int n = (int) (r >>> 32);
            if (n == 0) return null;
            consumed[0] = n;
            return new String(Character.toChars((int) r));
        }
        long r = matchNamed(s, at, false);
        int n = (int) (r >>> 32);
        if (n == 0) return null;
        consumed[0] = n;
        return Table.VALUES[(int) r];
    }

    // Replacements for numeric references 0x80..0x9F; 0 = keep the code point as is.
    private static final int[] WINDOWS_1252 = {
        0x20ac, 0, 0x201a, 0x0192, 0x201e, 0x2026, 0x2020, 0x2021, 0x02c6, 0x2030, 0x0160, 0x2039, 0x0152, 0, 0x017d, 0,
        0, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014, 0x02dc, 0x2122, 0x0161, 0x203a, 0x0153, 0, 0x017e, 0x0178,
    };
}
