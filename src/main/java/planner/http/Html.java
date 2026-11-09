package planner.http;

import java.util.HashMap;
import java.util.Map;

/**
 * HTML escaping and a deliberately tiny template syntax.
 *
 * <pre>
 *   {{name}}     the value, HTML-escaped
 *   {{{name}}}   the value as-is, for markup that was already built safely
 * </pre>
 *
 * Escaping is the default, so forgetting is safe. A placeholder with no matching value
 * throws instead of rendering blank, so a typo fails loudly in development rather than
 * silently in front of a student.
 */
public final class Html {

    private Html() { }

    public static String esc(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Renders a template with name/value pairs: {@code t(tpl, "title", t, "body", b)}. */
    public static String t(String template, Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Template values must come in name/value pairs");
        }
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }

        StringBuilder out = new StringBuilder(template.length() + 256);
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);

            boolean raw = template.startsWith("{{{", open);
            int start = open + (raw ? 3 : 2);
            int close = template.indexOf(raw ? "}}}" : "}}", start);
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed placeholder near: "
                        + template.substring(open, Math.min(template.length(), open + 30)));
            }
            String name = template.substring(start, close).trim();
            if (!values.containsKey(name)) {
                throw new IllegalArgumentException("No value for template placeholder '" + name + "'");
            }
            Object value = values.get(name);
            out.append(raw ? (value == null ? "" : value.toString()) : esc(value));
            i = close + (raw ? 3 : 2);
        }
        return out.toString();
    }

    public static String when(boolean condition, String markup) {
        return condition ? markup : "";
    }

    public static String checked(boolean condition) {
        return condition ? " checked" : "";
    }

    public static String selected(boolean condition) {
        return condition ? " selected" : "";
    }

    /** The hidden field every POST form must carry. */
    public static String csrf(Request request) {
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + esc(request.csrf()) + "\">";
    }
}
