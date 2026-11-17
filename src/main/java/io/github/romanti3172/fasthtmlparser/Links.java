package io.github.romanti3172.fasthtmlparser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Links: href / src made absolute against the page URL, written as .NET's System.Uri writes them.
 *
 * <p>Resolution follows RFC 3986 the way the Go port does it (net/url: Parse + ResolveReference), byte for byte:
 * URLs are handled as "byte strings" (one char per UTF-8 byte), so percent-escapes of non-ASCII text work as in Go.
 */
final class Links {
    private Links() {
    }

    /** A parsed URL; every string is a byte string (chars 0..255, the UTF-8 bytes). */
    static final class Url implements Cloneable {
        String scheme = "";
        String opaque = "";
        String user; // the userinfo as written back (escaped), or null
        String host = "";
        String path = ""; // decoded
        String rawPath = ""; // the path as written, when it differs from the default escaping of path
        boolean forceQuery;
        String rawQuery = "";
        String fragment = ""; // decoded
        String rawFragment = "";

        @Override
        protected Url clone() {
            try {
                return (Url) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** A malformed URL. */
    private static final class UrlException extends Exception {
        private static final long serialVersionUID = 1L;

        UrlException(String message) {
            super(message, null, false, false);
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x80) return false;
        return true;
    }

    /** The UTF-8 bytes of s as a byte string; a lone surrogate is written as U+FFFD, as JavaScript's TextEncoder does. */
    static String toBytes(String s) {
        if (isAscii(s)) return s;
        StringBuilder out = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (Character.isHighSurrogate((char) c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                c = Character.toCodePoint((char) c, s.charAt(++i));
            } else if (Character.isSurrogate((char) c)) {
                c = 0xfffd;
            }
            if (c < 0x80) {
                out.append((char) c);
            } else if (c < 0x800) {
                out.append((char) (0xc0 | (c >> 6))).append((char) (0x80 | (c & 0x3f)));
            } else if (c < 0x10000) {
                out.append((char) (0xe0 | (c >> 12))).append((char) (0x80 | ((c >> 6) & 0x3f))).append((char) (0x80 | (c & 0x3f)));
            } else {
                out.append((char) (0xf0 | (c >> 18))).append((char) (0x80 | ((c >> 12) & 0x3f)))
                    .append((char) (0x80 | ((c >> 6) & 0x3f))).append((char) (0x80 | (c & 0x3f)));
            }
        }
        return out.toString();
    }

    /** A byte string read as UTF-8; invalid sequences become U+FFFD. */
    static String fromBytes(String b) {
        if (isAscii(b)) return b;
        byte[] bytes = new byte[b.length()];
        for (int i = 0; i < b.length(); i++) bytes[i] = (byte) b.charAt(i);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean isValidUtf8(String b) {
        byte[] bytes = new byte[b.length()];
        for (int i = 0; i < b.length(); i++) bytes[i] = (byte) b.charAt(i);
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    // ----- net/url escaping -----

    private static final int MODE_PATH = 0;
    private static final int MODE_HOST = 1;
    private static final int MODE_USER_PASSWORD = 2;
    private static final int MODE_FRAGMENT = 3;

    private static boolean isAlnumByte(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean shouldEscape(int c, int mode) {
        if (isAlnumByte(c)) return false;
        if (mode == MODE_HOST) {
            switch (c) {
                case '!': case '$': case '&': case '\'': case '(': case ')': case '*': case '+': case ',': case ';':
                case '=': case ':': case '[': case ']': case '<': case '>': case '"':
                    return false;
                default:
                    break;
            }
        }
        switch (c) {
            case '-': case '_': case '.': case '~':
                return false;
            case '$': case '&': case '+': case ',': case '/': case ':': case ';': case '=': case '?': case '@':
                switch (mode) {
                    case MODE_PATH:
                        return c == '?';
                    case MODE_USER_PASSWORD:
                        return c == '@' || c == '/' || c == '?' || c == ':';
                    case MODE_FRAGMENT:
                        return false;
                    default:
                        break;
                }
                break;
            default:
                break;
        }
        if (mode == MODE_FRAGMENT && (c == '!' || c == '(' || c == ')' || c == '*')) return false;
        return true;
    }

    private static int unhex(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return c - 'A' + 10;
    }

    private static String unescape(String s, int mode) throws UrlException {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int c = s.charAt(i);
            if (c == '%') {
                n++;
                if (i + 2 >= s.length() || !Chars.isHexChar(s.charAt(i + 1)) || !Chars.isHexChar(s.charAt(i + 2))) {
                    throw new UrlException("invalid URL escape");
                }
                // in the host, %-encoding is only for non-ASCII bytes (and %25)
                if (mode == MODE_HOST && unhex(s.charAt(i + 1)) < 8 && !s.startsWith("%25", i)) {
                    throw new UrlException("invalid URL escape");
                }
                i += 3;
            } else {
                if (mode == MODE_HOST && c < 0x80 && shouldEscape(c, mode)) throw new UrlException("invalid character in host name");
                i++;
            }
        }
        if (n == 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '%') {
                out.append((char) ((unhex(s.charAt(i + 1)) << 4) | unhex(s.charAt(i + 2))));
                i += 3;
            } else {
                out.append(s.charAt(i++));
            }
        }
        return out.toString();
    }

    private static final String UPPER_HEX = "0123456789ABCDEF";

    private static String escape(String s, int mode) {
        StringBuilder out = null;
        int from = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (shouldEscape(c, mode)) {
                if (out == null) out = new StringBuilder(s.length() + 16);
                out.append(s, from, i).append('%').append(UPPER_HEX.charAt((c >> 4) & 15)).append(UPPER_HEX.charAt(c & 15));
                from = i + 1;
            }
        }
        return out == null ? s : out.append(s, from, s.length()).toString();
    }

    private static boolean validEncoded(String s, int mode) {
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            switch (c) {
                case '!': case '$': case '&': case '\'': case '(': case ')': case '*': case '+': case ',': case ';':
                case '=': case ':': case '@': case '[': case ']': case '%':
                    break;
                default:
                    if (shouldEscape(c, mode)) return false;
            }
        }
        return true;
    }

    // ----- parsing -----

    /** The scheme and the rest of raw. */
    private static String[] getScheme(String raw) throws UrlException {
        for (int i = 0; i < raw.length(); i++) {
            int c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) continue;
            if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.') {
                if (i == 0) return new String[] {"", raw};
                continue;
            }
            if (c == ':') {
                if (i == 0) throw new UrlException("missing protocol scheme");
                return new String[] {raw.substring(0, i), raw.substring(i + 1)};
            }
            return new String[] {"", raw};
        }
        return new String[] {"", raw};
    }

    private static void setPath(Url u, String p) throws UrlException {
        u.path = unescape(p, MODE_PATH);
        u.rawPath = escape(u.path, MODE_PATH).equals(p) ? "" : p;
    }

    private static String escapedPath(Url u) {
        if (!u.rawPath.isEmpty() && validEncoded(u.rawPath, MODE_PATH)) {
            try {
                if (unescape(u.rawPath, MODE_PATH).equals(u.path)) return u.rawPath;
            } catch (UrlException e) {
                // fall through
            }
        }
        if (u.path.equals("*")) return "*";
        return escape(u.path, MODE_PATH);
    }

    private static String escapedFragment(Url u) {
        if (!u.rawFragment.isEmpty() && validEncoded(u.rawFragment, MODE_FRAGMENT)) {
            try {
                if (unescape(u.rawFragment, MODE_FRAGMENT).equals(u.fragment)) return u.rawFragment;
            } catch (UrlException e) {
                // fall through
            }
        }
        return escape(u.fragment, MODE_FRAGMENT);
    }

    private static boolean validOptionalPort(String port) {
        if (port.isEmpty()) return true;
        if (port.charAt(0) != ':') return false;
        for (int i = 1; i < port.length(); i++) {
            int c = port.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String parseHost(String host) throws UrlException {
        if (host.startsWith("[")) {
            int i = host.lastIndexOf(']');
            if (i < 0) throw new UrlException("missing ']' in host");
            if (!validOptionalPort(host.substring(i + 1))) throw new UrlException("invalid port");
        } else {
            int i = host.lastIndexOf(':');
            if (i >= 0 && !validOptionalPort(host.substring(i))) throw new UrlException("invalid port");
        }
        return unescape(host, MODE_HOST);
    }

    private static boolean validUserinfo(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isAlnumByte(c)) continue;
            if ("-._:~!$&'()*+,;=%@".indexOf(c) < 0) return false;
        }
        return true;
    }

    private static void parseAuthority(Url u, String authority) throws UrlException {
        int i = authority.lastIndexOf('@');
        u.host = parseHost(i < 0 ? authority : authority.substring(i + 1));
        if (i < 0) return;
        String userinfo = authority.substring(0, i);
        if (!validUserinfo(userinfo)) throw new UrlException("invalid userinfo");
        int colon = userinfo.indexOf(':');
        if (colon < 0) {
            u.user = escape(unescape(userinfo, MODE_USER_PASSWORD), MODE_USER_PASSWORD);
        } else {
            String name = unescape(userinfo.substring(0, colon), MODE_USER_PASSWORD);
            String password = unescape(userinfo.substring(colon + 1), MODE_USER_PASSWORD);
            u.user = escape(name, MODE_USER_PASSWORD) + ":" + escape(password, MODE_USER_PASSWORD);
        }
    }

    private static boolean hasCtlByte(String s) {
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    /** net/url Parse on a byte string. */
    private static Url parseUrl(String rawUrl) throws UrlException {
        int hash = rawUrl.indexOf('#');
        String rest0 = hash < 0 ? rawUrl : rawUrl.substring(0, hash);
        Url u = parseNoFragment(rest0);
        if (hash >= 0) {
            String frag = rawUrl.substring(hash + 1);
            u.fragment = unescape(frag, MODE_FRAGMENT);
            u.rawFragment = escape(u.fragment, MODE_FRAGMENT).equals(frag) ? "" : frag;
        }
        return u;
    }

    private static Url parseNoFragment(String raw) throws UrlException {
        if (hasCtlByte(raw)) throw new UrlException("invalid control character in URL");
        Url u = new Url();
        if (raw.equals("*")) {
            u.path = "*";
            return u;
        }
        String[] schemeRest = getScheme(raw);
        u.scheme = Chars.lower(schemeRest[0]);
        String rest = schemeRest[1];

        if (rest.endsWith("?") && rest.indexOf('?') == rest.length() - 1) {
            u.forceQuery = true;
            rest = rest.substring(0, rest.length() - 1);
        } else {
            int q = rest.indexOf('?');
            if (q >= 0) {
                u.rawQuery = rest.substring(q + 1);
                rest = rest.substring(0, q);
            }
        }

        if (!rest.startsWith("/")) {
            if (!u.scheme.isEmpty()) {
                // rootless paths are opaque (RFC 3986)
                u.opaque = rest;
                return u;
            }
            // a relative-path reference: the first segment cannot contain ':'
            int slash = rest.indexOf('/');
            if ((slash < 0 ? rest : rest.substring(0, slash)).indexOf(':') >= 0) {
                throw new UrlException("first path segment in URL cannot contain colon");
            }
        }

        if ((!u.scheme.isEmpty() || !rest.startsWith("///")) && rest.startsWith("//")) {
            String authority = rest.substring(2);
            rest = "";
            int slash = authority.indexOf('/');
            if (slash >= 0) {
                rest = authority.substring(slash);
                authority = authority.substring(0, slash);
            }
            parseAuthority(u, authority);
        }
        setPath(u, rest);
        return u;
    }

    private static String resolvePath(String base, String ref) {
        String full;
        if (ref.isEmpty()) full = base;
        else if (ref.charAt(0) != '/') full = base.substring(0, base.lastIndexOf('/') + 1) + ref;
        else full = ref;
        if (full.isEmpty()) return "";

        StringBuilder dst = new StringBuilder("/");
        boolean first = true;
        String remaining = full;
        boolean found = true;
        String elem = "";
        while (found) {
            int slash = remaining.indexOf('/');
            found = slash >= 0;
            elem = found ? remaining.substring(0, slash) : remaining;
            remaining = found ? remaining.substring(slash + 1) : "";
            if (elem.equals(".")) {
                first = false;
                continue;
            }
            if (elem.equals("..")) {
                String str = dst.substring(1);
                int index = str.lastIndexOf('/');
                dst.setLength(0);
                dst.append('/');
                if (index == -1) first = true;
                else dst.append(str, 0, index);
            } else {
                if (!first) dst.append('/');
                dst.append(elem);
                first = false;
            }
        }
        if (elem.equals(".") || elem.equals("..")) dst.append('/');
        String out = dst.toString();
        if (out.length() > 1 && out.charAt(1) == '/') out = out.substring(1);
        return out;
    }

    /** net/url ResolveReference. */
    private static Url resolveReference(Url base, Url ref) throws UrlException {
        Url url = ref.clone();
        if (ref.scheme.isEmpty()) url.scheme = base.scheme;
        if (!ref.scheme.isEmpty() || !ref.host.isEmpty() || ref.user != null) {
            setPath(url, resolvePath(escapedPath(ref), ""));
            return url;
        }
        if (!ref.opaque.isEmpty()) {
            url.user = null;
            url.host = "";
            url.path = "";
            return url;
        }
        if (ref.path.isEmpty() && !ref.forceQuery && ref.rawQuery.isEmpty()) {
            url.rawQuery = base.rawQuery;
            if (ref.fragment.isEmpty()) {
                url.fragment = base.fragment;
                url.rawFragment = base.rawFragment;
            }
        }
        if (ref.path.isEmpty() && !base.opaque.isEmpty()) {
            url.opaque = base.opaque;
            url.user = null;
            url.host = "";
            url.path = "";
            return url;
        }
        url.host = base.host;
        url.user = base.user;
        setPath(url, resolvePath(escapedPath(base), escapedPath(ref)));
        return url;
    }

    private static final Map<String, String> DEFAULT_PORTS = Map.of("http", "80", "https", "443", "ftp", "21", "ws", "80", "wss", "443");

    /** The host and the port. */
    private static String[] splitHostPort(String hostPort) {
        String host = hostPort;
        String port = "";
        int colon = host.lastIndexOf(':');
        if (colon != -1 && validOptionalPort(host.substring(colon))) {
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        if (host.startsWith("[") && host.endsWith("]") && host.length() >= 2) host = host.substring(1, host.length() - 1);
        return new String[] {host, port};
    }

    private static final String RESERVED_FOR_DISPLAY = "%/?#[]@:!$&'()*+,;=";

    /**
     * Decodes %XX escapes except those of reserved and control characters; escaped bytes of non-ASCII text are decoded
     * when they form valid UTF-8 (byte string in, byte string out).
     */
    private static String unescapeForDisplay(String s) {
        if (s.indexOf('%') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) != '%' || i + 2 >= s.length() || !Chars.isHexChar(s.charAt(i + 1)) || !Chars.isHexChar(s.charAt(i + 2))) {
                out.append(s.charAt(i++));
                continue;
            }
            int c = (unhex(s.charAt(i + 1)) << 4) | unhex(s.charAt(i + 2));
            if (c < 0x80) {
                if (c < 0x20 || c == 0x7f || RESERVED_FOR_DISPLAY.indexOf(c) >= 0) out.append(s, i, i + 3);
                else out.append((char) c);
                i += 3;
                continue;
            }
            // a run of escaped bytes >= 0x80: decoded together when it is valid UTF-8
            int j = i;
            StringBuilder run = new StringBuilder();
            while (j + 2 < s.length() && s.charAt(j) == '%' && Chars.isHexChar(s.charAt(j + 1)) && Chars.isHexChar(s.charAt(j + 2))) {
                int v = (unhex(s.charAt(j + 1)) << 4) | unhex(s.charAt(j + 2));
                if (v < 0x80) break;
                run.append((char) v);
                j += 3;
            }
            String r = run.toString();
            if (isValidUtf8(r)) out.append(r);
            else out.append(s, i, j);
            i = j;
        }
        return out.toString();
    }

    /**
     * Writes a resolved URL as the .NET version does (System.Uri.ToString): the scheme and host in lower case,
     * no default port, an empty path as "/", and percent-escapes decoded except those of the characters that delimit
     * URL parts ("%2F" stays) and of control characters; non-ASCII text is written as is.
     */
    private static String displayString(Url u) {
        StringBuilder b = new StringBuilder();
        b.append(Chars.lower(u.scheme)).append(':');
        if (!u.opaque.isEmpty()) {
            b.append(u.opaque);
        } else {
            if (!u.host.isEmpty() || u.user != null) {
                b.append("//");
                if (u.user != null) b.append(u.user).append('@');
                String[] hostPort = splitHostPort(u.host);
                b.append(toBytes(Chars.lower(fromBytes(hostPort[0]))));
                String port = hostPort[1];
                if (!port.isEmpty() && !port.equals(DEFAULT_PORTS.get(Chars.lower(u.scheme)))) b.append(':').append(port);
            }
            String path = escapedPath(u);
            if (path.isEmpty() && !u.host.isEmpty()) path = "/";
            b.append(unescapeForDisplay(path));
        }
        if (!u.rawQuery.isEmpty() || u.forceQuery) b.append('?').append(unescapeForDisplay(u.rawQuery));
        if (!u.fragment.isEmpty()) b.append('#').append(unescapeForDisplay(escapedFragment(u)));
        return fromBytes(b.toString());
    }

    /** Parses a page URL with its own scheme and host; null for anything else. */
    static Url absoluteUrl(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            Url u = parseUrl(toBytes(s));
            if (u.scheme.isEmpty() || (u.host.isEmpty() && u.opaque.isEmpty() && !Chars.equalFold(u.scheme, "file"))) return null;
            return u;
        } catch (UrlException e) {
            return null;
        }
    }

    /** A link with its own scheme ("https://…", "mailto:…", "file:…"). "/path" and "//host/path" are relative. */
    private static boolean isAbsoluteLink(String link) {
        try {
            return !parseUrl(toBytes(Chars.trimSpace(link))).scheme.isEmpty();
        } catch (UrlException e) {
            return false;
        }
    }

    /**
     * The raw attribute value with its character references decoded and made absolute against the page.
     * Percent-escapes are part of the URL and are kept. An empty link gives the page URL.
     */
    static String correctLink(String raw, String pageUrl, Url base) {
        if (raw.isEmpty()) return pageUrl;
        String link = Decode.decode(raw, true);
        if (base == null || isAbsoluteLink(link)) return link;
        try {
            Url ref = parseUrl(toBytes(Chars.trimSpace(link)));
            return displayString(resolveReference(base, ref));
        } catch (UrlException e) {
            return link;
        }
    }

    /** Everything after the first ':' of a link with the scheme (mailto, tel), or "". */
    static String afterScheme(String rawHref, String scheme) {
        String href = Chars.trimSpace(Decode.decode(rawHref, true));
        if (href.length() < scheme.length() || !Chars.equalFold(href.substring(0, scheme.length()), scheme)) return "";
        int i = href.indexOf(':');
        return i >= 0 ? href.substring(i + 1) : "";
    }
}
