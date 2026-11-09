package planner.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader and string quoter, enough for the Gemini and Classroom APIs. */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Objects become Map, arrays List, numbers Double, plus String, Boolean and null. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.space();
        Object v = p.value();
        p.space();
        if (p.i != p.s.length()) {
            throw p.error("unexpected text after the value");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static String str(Object o) {
        return o == null ? null : o instanceof String t ? t : String.valueOf(o instanceof Double d && d == Math.rint(d) ? (Object) d.longValue() : o);
    }

    public static Integer num(Object o) {
        if (o instanceof Double d) return (int) Math.round(d);
        if (o instanceof String t) {
            try { return (int) Math.round(Double.parseDouble(t.trim())); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Follows a path of object keys: at(json, "candidates", 0, "content"). */
    public static Object at(Object o, Object... path) {
        for (Object key : path) {
            if (key instanceof Integer n) {
                List<Object> l = arr(o);
                o = n < l.size() ? l.get(n) : null;
            } else {
                o = obj(o).get(key);
            }
        }
        return o;
    }

    public static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        space();
        if (peek() == '}') { i++; return m; }
        while (true) {
            space();
            String k = string();
            space();
            expect(':');
            space();
            m.put(k, value());
            space();
            if (peek() == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        space();
        if (peek() == ']') { i++; return l; }
        while (true) {
            space();
            l.add(value());
            space();
            if (peek() == ',') { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        throw error("unterminated string");
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected character '" + s.charAt(i) + "'");
        return Double.valueOf(s.substring(start, i));
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw error("expected " + w);
        i += w.length();
        return v;
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) throw error("expected '" + c + "'");
        i++;
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Bad JSON at " + i + ": " + what);
    }
}
