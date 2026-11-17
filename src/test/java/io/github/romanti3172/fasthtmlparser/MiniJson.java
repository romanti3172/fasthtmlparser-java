package io.github.romanti3172.fasthtmlparser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader for the test data: objects, arrays, strings, numbers (Long or Double), true, false, null. */
final class MiniJson {
    private final String s;
    private int p;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        MiniJson j = new MiniJson(text);
        j.space();
        Object v = j.value();
        j.space();
        if (j.p != j.s.length()) throw new IllegalArgumentException("trailing data at " + j.p);
        return v;
    }

    private void space() {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
    }

    private Object value() {
        char c = s.charAt(p);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': p += 4; return Boolean.TRUE;
            case 'f': p += 5; return Boolean.FALSE;
            case 'n': p += 4; return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        p++;
        space();
        if (s.charAt(p) == '}') {
            p++;
            return m;
        }
        for (;;) {
            space();
            String key = string();
            space();
            p++; // ':'
            space();
            m.put(key, value());
            space();
            if (s.charAt(p++) == '}') return m;
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        p++;
        space();
        if (s.charAt(p) == ']') {
            p++;
            return a;
        }
        for (;;) {
            space();
            a.add(value());
            space();
            if (s.charAt(p++) == ']') return a;
        }
    }

    private String string() {
        p++; // '"'
        StringBuilder b = new StringBuilder();
        for (;;) {
            char c = s.charAt(p++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(p++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    b.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                    p += 4;
                    break;
                default: b.append(e);
            }
        }
    }

    private Object number() {
        int start = p;
        while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        String t = s.substring(start, p);
        if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.parseLong(t);
        return Double.parseDouble(t);
    }
}
