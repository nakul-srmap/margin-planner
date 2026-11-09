package planner.web;

import planner.http.Html;
import planner.http.Request;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A form being shown or checked. On GET it holds the defaults; on POST it holds what
 * the student typed, plus one message per field that needs fixing.
 */
final class Form {

    private final Map<String, String> values;
    private final Map<String, String> errors = new LinkedHashMap<>();
    private final boolean submitted;

    private Form(Map<String, String> values, boolean submitted) {
        this.values = values;
        this.submitted = submitted;
    }

    static Form blank() {
        return new Form(new HashMap<>(), false);
    }

    static Form of(Map<String, String> defaults) {
        return new Form(new HashMap<>(defaults), false);
    }

    static Form submitted(Request request) {
        Map<String, String> v = new HashMap<>();
        request.formValues().forEach((k, val) -> v.put(k, val == null ? "" : val.trim()));
        return new Form(v, true);
    }

    Form set(String name, Object value) {
        values.put(name, value == null ? "" : value.toString());
        return this;
    }

    String v(String name) {
        return values.getOrDefault(name, "");
    }

    boolean has(String name) {
        return !v(name).isEmpty();
    }

    boolean submittedForm() {
        return submitted;
    }

    boolean ok() {
        return errors.isEmpty();
    }

    void fail(String name, String message) {
        errors.putIfAbsent(name, message);
    }

    String error(String name) {
        return errors.get(name);
    }

    /* ---------------- reading typed values ---------------- */

    String required(String name, int max, String message) {
        String value = v(name);
        if (value.isEmpty()) {
            fail(name, message);
        } else if (value.length() > max) {
            fail(name, "Keep this under " + max + " characters.");
        }
        return value;
    }

    String optional(String name, int max) {
        String value = v(name);
        if (value.length() > max) {
            fail(name, "Keep this under " + max + " characters.");
        }
        return value.isEmpty() ? null : value;
    }

    int integer(String name, int min, int max, String message) {
        try {
            int n = Integer.parseInt(v(name));
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        fail(name, message);
        return min;
    }

    LocalDate date(String name, String message) {
        try {
            return LocalDate.parse(v(name));
        } catch (DateTimeParseException e) {
            fail(name, message);
            return LocalDate.now();
        }
    }

    LocalTime time(String name, String message) {
        try {
            return LocalTime.parse(v(name));
        } catch (DateTimeParseException e) {
            fail(name, message);
            return LocalTime.MIDNIGHT;
        }
    }

    Long id(String name) {
        try {
            return has(name) ? Long.valueOf(v(name)) : null;
        } catch (NumberFormatException e) {
            fail(name, "Choose one from the list.");
            return null;
        }
    }

    boolean bool(String name) {
        return has(name);
    }

    /* ---------------- drawing fields ---------------- */

    /** Shown above a form that came back with problems. */
    String summary() {
        if (errors.isEmpty()) {
            return "";
        }
        return "<p class=\"form-error\" role=\"alert\">"
                + (errors.size() == 1 ? "One thing needs fixing below." : errors.size() + " things need fixing below.")
                + "</p>";
    }

    /** A labelled input. {@code extra} is raw attributes such as {@code required min="5"}. */
    String input(String name, String label, String type, String hint, String extra) {
        return Html.t("""
                <label class="field{{{wide}}}"><span class="label">{{label}}{{{hint}}}</span>
                  <input type="{{type}}" name="{{name}}" value="{{value}}"{{{invalid}}} {{{extra}}}>{{{error}}}
                </label>""",
                "wide", "", "label", label, "hint", hintMarkup(hint), "type", type, "name", name,
                "value", v(name), "invalid", invalid(name), "extra", extra, "error", errorMarkup(name));
    }

    String textarea(String name, String label, String hint, String extra) {
        return Html.t("""
                <label class="field wide"><span class="label">{{label}}{{{hint}}}</span>
                  <textarea name="{{name}}"{{{invalid}}} {{{extra}}}>{{value}}</textarea>{{{error}}}
                </label>""",
                "label", label, "hint", hintMarkup(hint), "name", name, "invalid", invalid(name),
                "extra", extra, "value", v(name), "error", errorMarkup(name));
    }

    /** {@code options} alternates value, label. */
    String select(String name, String label, String hint, String... options) {
        StringBuilder opts = new StringBuilder();
        for (int i = 0; i < options.length; i += 2) {
            opts.append("<option value=\"").append(Html.esc(options[i])).append('"')
                    .append(Html.selected(options[i].equals(v(name)))).append('>')
                    .append(Html.esc(options[i + 1])).append("</option>");
        }
        return Html.t("""
                <label class="field"><span class="label">{{label}}{{{hint}}}</span>
                  <select name="{{name}}"{{{invalid}}}>{{{options}}}</select>{{{error}}}
                </label>""",
                "label", label, "hint", hintMarkup(hint), "name", name, "invalid", invalid(name),
                "options", opts.toString(), "error", errorMarkup(name));
    }

    String checkbox(String name, String label) {
        return "<label class=\"check\"><input type=\"checkbox\" name=\"" + Html.esc(name) + "\" value=\"1\""
                + Html.checked(has(name)) + "> " + Html.esc(label) + "</label>";
    }

    private String invalid(String name) {
        return errors.containsKey(name) ? " aria-invalid=\"true\"" : "";
    }

    private String errorMarkup(String name) {
        String e = errors.get(name);
        return e == null ? "" : "<span class=\"error\">" + Html.esc(e) + "</span>";
    }

    private static String hintMarkup(String hint) {
        return hint == null || hint.isEmpty() ? "" : " <span class=\"hint\">" + Html.esc(hint) + "</span>";
    }
}
