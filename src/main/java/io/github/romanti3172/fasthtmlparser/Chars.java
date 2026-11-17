package io.github.romanti3172.fasthtmlparser;

import java.util.Locale;

/**
 * Character helpers shared by the parser, the text readers and the query engines.
 * Positions are UTF-16 code unit indexes, as in Java strings (and .NET).
 */
final class Chars {
    private Chars() {
    }

    /** ASCII whitespace as the parser sees it: space, \t, \n, \r, \f, \v. */
    static boolean isSpace(int c) {
        return c == 32 || (c >= 9 && c <= 13);
    }

    /** Whitespace as .NET char.IsWhiteSpace sees it (all such chars are in the BMP). */
    static boolean isUnicodeSpace(int c) {
        if (c < 0x80) return c == 32 || (c >= 9 && c <= 13);
        return c == 0x85 || c == 0xa0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029
            || c == 0x202f || c == 0x205f || c == 0x3000;
    }

    /** Trims Unicode whitespace (char.IsWhiteSpace) from both ends. */
    static String trimSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isUnicodeSpace(s.charAt(start))) start++;
        while (end > start && isUnicodeSpace(s.charAt(end - 1))) end--;
        return start == 0 && end == s.length() ? s : s.substring(start, end);
    }

    /** Trims ASCII whitespace from the end. */
    static String trimRightAscii(String s) {
        int end = s.length();
        while (end > 0 && isSpace(s.charAt(end - 1))) end--;
        return end == s.length() ? s : s.substring(0, end);
    }

    static boolean isLowerAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || c >= 0x80) return false;
        }
        return true;
    }

    /** The lower-case name: the same string when it is already lower-case ASCII. */
    static String lowerKey(String s) {
        return isLowerAscii(s) ? s : s.toLowerCase(Locale.ROOT);
    }

    /** Lower case as JavaScript and .NET's ToLowerInvariant write it. */
    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    /** Case-insensitive comparison (ASCII fast path, Unicode lower-case otherwise). */
    static boolean equalFold(String a, String b) {
        if (a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) {
            int x = a.charAt(i);
            int y = b.charAt(i);
            if (x == y) continue;
            if ((x | y) >= 0x80) return lower(a).equals(lower(b));
            int lx = x | 0x20;
            if (lx != (y | 0x20) || lx < 'a' || lx > 'z') return false;
        }
        return true;
    }

    /** Whether s[at..] starts with the ASCII string sub, ignoring ASCII case. */
    static boolean startsWithFold(String s, int at, String sub) {
        if (at < 0 || at + sub.length() > s.length()) return false;
        for (int k = 0; k < sub.length(); k++) {
            int x = s.charAt(at + k);
            int y = sub.charAt(k);
            if (x == y) continue;
            int lx = x | 0x20;
            if (lx != (y | 0x20) || lx < 'a' || lx > 'z') return false;
        }
        return true;
    }

    /** indexOf with ASCII case folding; sub must be ASCII. Searches s[from, to). */
    static int indexFold(String s, String sub, int from, int to) {
        int n = sub.length();
        if (n == 0) return from;
        int c = sub.charAt(0);
        boolean isLetter = (c | 0x20) >= 'a' && (c | 0x20) <= 'z';
        int lower = isLetter ? c | 0x20 : c;
        int upper = isLetter ? c & ~0x20 : c;
        int last = to - n;
        for (int i = from; i <= last; i++) {
            int x = s.charAt(i);
            if ((x == lower || x == upper) && startsWithFold(s, i, sub)) return i;
        }
        return -1;
    }

    static int indexFold(String s, String sub, int from) {
        return indexFold(s, sub, from, s.length());
    }

    /** Whether s[at..] starts with sub (case-sensitive) and the match ends before limit. */
    static boolean startsWithAt(String s, int at, String sub, int limit) {
        return at >= 0 && at + sub.length() <= limit && s.startsWith(sub, at);
    }

    static boolean startsWithAt(String s, int at, String sub) {
        return startsWithAt(s, at, sub, s.length());
    }

    static boolean isHexChar(int c) {
        return (c >= '0' && c <= '9') || ((c | 0x20) >= 'a' && (c | 0x20) <= 'f');
    }

    static boolean isAlnum(int c) {
        return (c >= '0' && c <= '9') || ((c | 0x20) >= 'a' && (c | 0x20) <= 'z');
    }

    /** s[from, to) as JavaScript's slice gives it for non-negative bounds: clamped to the string, "" when from &gt;= to. */
    static String slice(String s, int from, int to) {
        if (to > s.length()) to = s.length();
        if (from < 0) from = 0;
        return from >= to ? "" : s.substring(from, to);
    }

    /** s.charAt(i), or -1 outside the string: it matches no char. */
    static int at(String s, int i) {
        return i >= 0 && i < s.length() ? s.charAt(i) : -1;
    }
}
