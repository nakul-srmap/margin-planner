package planner.portal;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a class timetable out of a portal page. It looks for a grid with day names down the
 * side and time ranges across the top (or the other way round), and for course codes such
 * as CSE 301 or MAT201L in the cells. Course names are picked up from any table that lists
 * a code next to a longer title.
 */
public final class TimetableParser {

    /** One class found on the page. */
    public record Found(String code, String name, boolean lab, DayOfWeek day, LocalTime start, LocalTime end, String room) { }

    private static final Pattern TABLE = Pattern.compile("(?is)<table\\b.*?</table>");
    private static final Pattern ROW = Pattern.compile("(?is)<tr\\b.*?</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<t([dh])\\b([^>]*)>(.*?)</t\\1>");
    private static final Pattern COLSPAN = Pattern.compile("(?i)colspan\\s*=\\s*[\"']?(\\d+)");
    private static final Pattern RANGE = Pattern.compile(
            "(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?\\s*(?:-|–|to)\\s*(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile("\\b([A-Z]{2,4})\\s?-?\\s?(\\d{3,4}[A-Z]?)\\b");
    private static final Pattern BRACKET = Pattern.compile("^\\s*\\(([^)]{1,30})\\)");
    private static final Pattern ROOM = Pattern.compile("(?i)\\b(?:room|rm|lh|lt|lab|cr)[\\s.:-]*([A-Z]?-?\\d{1,4}[A-Z]?)\\b");

    private TimetableParser() { }

    public static List<Found> parse(String html) {
        List<List<String>> best = null;
        List<Found> out = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();

        for (Matcher t = TABLE.matcher(html); t.find(); ) {
            List<List<String>> grid = grid(t.group());
            collectNames(grid, names);
            List<Found> found = readGrid(grid);
            if (found.size() > out.size()) {
                out = found;
                best = grid;
            }
        }
        if (best == null) {
            return List.of();
        }
        List<Found> named = new ArrayList<>();
        for (Found f : merge(out)) {
            named.add(new Found(f.code(), names.getOrDefault(f.code(), f.name()), f.lab(), f.day(), f.start(), f.end(), f.room()));
        }
        return named;
    }

    /** Rows of plain-text cells, with colspan expanded so columns line up. */
    static List<List<String>> grid(String table) {
        List<List<String>> rows = new ArrayList<>();
        for (Matcher r = ROW.matcher(table); r.find(); ) {
            List<String> row = new ArrayList<>();
            for (Matcher c = CELL.matcher(r.group()); c.find(); ) {
                Matcher span = COLSPAN.matcher(c.group(2));
                int n = span.find() ? Math.min(12, Integer.parseInt(span.group(1))) : 1;
                String text = text(c.group(3));
                for (int i = 0; i < n; i++) {
                    row.add(text);
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<Found> readGrid(List<List<String>> g) {
        List<Found> out = new ArrayList<>();
        // Days down the side, times across the top.
        int header = -1;
        for (int i = 0; i < g.size() && header < 0; i++) {
            if (g.get(i).stream().filter(s -> RANGE.matcher(s).find()).count() >= 2) {
                header = i;
            }
        }
        if (header >= 0) {
            // Pair the cells after the day name with the time ranges in order. Matching by column
            // index breaks when the corner cell spans two header rows, as on SRM's portal.
            List<LocalTime[]> spans = new ArrayList<>();
            for (String h : g.get(header)) {
                LocalTime[] span = range(h);
                if (span != null) {
                    spans.add(span);
                }
            }
            for (int i = header + 1; i < g.size(); i++) {
                List<String> row = g.get(i);
                DayOfWeek day = row.isEmpty() ? null : day(row.get(0));
                if (day == null) {
                    continue;
                }
                for (int k = 0; k + 1 < row.size() && k < spans.size(); k++) {
                    cell(row.get(k + 1), day, spans.get(k), out);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        // Times down the side, days across the top.
        int dayRow = -1;
        for (int i = 0; i < g.size() && dayRow < 0; i++) {
            if (g.get(i).stream().filter(s -> day(s) != null).count() >= 3) {
                dayRow = i;
            }
        }
        if (dayRow >= 0) {
            List<String> days = g.get(dayRow);
            for (int i = dayRow + 1; i < g.size(); i++) {
                List<String> row = g.get(i);
                LocalTime[] span = row.isEmpty() ? null : range(row.get(0));
                if (span == null) {
                    continue;
                }
                for (int c = 1; c < row.size() && c < days.size(); c++) {
                    DayOfWeek day = day(days.get(c));
                    if (day != null) {
                        cell(row.get(c), day, span, out);
                    }
                }
            }
        }
        return out;
    }

    private static void cell(String text, DayOfWeek day, LocalTime[] span, List<Found> out) {
        Matcher m = CODE.matcher(text);
        if (!m.find()) {
            return;
        }
        String code = m.group(1) + m.group(2);
        boolean lab = text.toUpperCase(Locale.ROOT).contains("LAB") || code.endsWith("L") || code.endsWith("P");
        String room = null;
        Matcher br = BRACKET.matcher(text.substring(m.end()));
        if (br.find()) {
            room = br.group(1).trim();
        } else {
            Matcher r = ROOM.matcher(text);
            room = r.find() ? r.group(1) : null;
        }
        out.add(new Found(code, code, lab, day, span[0], span[1], room));
    }

    /** Joins back-to-back slots of the same course on the same day into one class. */
    static List<Found> merge(List<Found> found) {
        List<Found> sorted = new ArrayList<>(found);
        sorted.sort((a, b) -> a.day() != b.day() ? a.day().compareTo(b.day()) : a.start().compareTo(b.start()));
        List<Found> out = new ArrayList<>();
        for (Found f : sorted) {
            Found last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.day() == f.day() && last.code().equals(f.code())
                    && !f.start().isAfter(last.end().plusMinutes(15)) && !f.start().isBefore(last.start())) {
                out.set(out.size() - 1, new Found(last.code(), last.name(), last.lab() || f.lab(), last.day(),
                        last.start(), f.end().isAfter(last.end()) ? f.end() : last.end(), last.room() != null ? last.room() : f.room()));
            } else if (last == null || !(last.day() == f.day() && last.start().equals(f.start()))) {
                out.add(f);
            }
        }
        return out;
    }

    /** A row like "CSE301 | Design and Analysis of Algorithms | 4" gives the course's name. */
    private static void collectNames(List<List<String>> g, Map<String, String> names) {
        for (List<String> row : g) {
            for (int c = 0; c + 1 < row.size(); c++) {
                Matcher m = CODE.matcher(row.get(c));
                if (m.matches() || (m.find() && row.get(c).length() <= 12)) {
                    String name = row.get(c + 1);
                    if (name.length() >= 6 && name.length() <= 100 && name.matches(".*[A-Za-z]{3}.*")
                            && !CODE.matcher(name).find() && !RANGE.matcher(name).find() && day(name) == null) {
                        names.putIfAbsent(m.group(1) + m.group(2), tidy(name));
                    }
                }
            }
        }
    }

    /** "OBJECT ORIENTED PROGRAMMING WITH C++" becomes "Object Oriented Programming with C++". */
    static String tidy(String name) {
        if (name.matches(".*[a-z].*")) {
            return name;
        }
        StringBuilder out = new StringBuilder();
        for (String w : name.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) continue;
            boolean small = out.length() > 0 && List.of("and", "of", "in", "with", "for", "to", "the", "a", "an", "on").contains(w);
            boolean roman = w.matches("i{1,3}|iv|vi{0,3}|ix|x");
            out.append(out.length() == 0 ? "" : " ")
                    .append(roman ? w.toUpperCase(Locale.ROOT) : small ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return out.toString();
    }

    static DayOfWeek day(String s) {
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (t.length() < 3 || t.length() > 12) {
            return null;
        }
        for (DayOfWeek d : DayOfWeek.values()) {
            if (t.startsWith(d.name().substring(0, 3))) {
                return d;
            }
        }
        return null;
    }

    static LocalTime[] range(String s) {
        Matcher m = RANGE.matcher(s);
        if (!m.find()) {
            return null;
        }
        LocalTime a = time(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) != null ? m.group(3) : m.group(6));
        LocalTime b = time(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6));
        if (a == null || b == null) {
            return null;
        }
        if (!b.isAfter(a) && b.getHour() < 12) {
            b = b.plusHours(12);
        }
        return b.isAfter(a) ? new LocalTime[] {a, b} : null;
    }

    /** College timetables often write 1:00 for 13:00; anything before 7 is taken as afternoon. */
    private static LocalTime time(int h, int m, String ampm) {
        if (h > 23 || m > 59) {
            return null;
        }
        if (ampm != null) {
            boolean pm = ampm.equalsIgnoreCase("pm");
            h = h % 12 + (pm ? 12 : 0);
        } else if (h < 7) {
            h += 12;
        }
        return LocalTime.of(h, m);
    }

    private static String text(String html) {
        return html.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#160;", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
