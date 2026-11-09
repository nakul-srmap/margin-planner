package planner.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Dates, times and durations in plain English, the same way on every page. */
public final class Text {

    private static final Locale EN = Locale.ENGLISH;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", EN);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", EN);
    private static final DateTimeFormatter WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", EN);

    private Text() { }

    public static String dayName(DayOfWeek day) {
        return day.getDisplayName(TextStyle.FULL, EN);
    }

    public static String shortDay(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, EN);
    }

    public static String time(LocalTime time) {
        return time == null ? "" : time.format(TIME);
    }

    public static String date(LocalDate date) {
        return date.format(WEEKDAY_DAY_MONTH);
    }

    /** 135 → "2 h 15 min". */
    public static String duration(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) {
            return m + " min";
        }
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    public static String plural(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** "today", "tomorrow", "Friday", or "13 Nov" — the shortest unambiguous form. */
    public static String day(LocalDate date, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, date);
        if (days == 0) return "today";
        if (days == 1) return "tomorrow";
        if (days == -1) return "yesterday";
        if (days > 1 && days < 7) return dayName(date.getDayOfWeek());
        return date.format(DAY_MONTH);
    }

    /** "today at 23:59", or "was due Monday" once it has passed. */
    public static String due(LocalDateTime due, LocalDateTime now) {
        String day = day(due.toLocalDate(), now.toLocalDate());
        if (due.isBefore(now)) {
            return due.toLocalDate().equals(now.toLocalDate())
                    ? "was due at " + time(due.toLocalTime())
                    : "was due " + day;
        }
        return day + " at " + time(due.toLocalTime());
    }

    /** "in 4 days", "tomorrow", "today". */
    public static String countdown(LocalDate date, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, date);
        if (days == 0) return "today";
        if (days == 1) return "tomorrow";
        if (days < 0) return plural(-days, "day", "days") + " ago";
        return "in " + plural(days, "day", "days");
    }

    /** "9 to 15 November", or "28 October to 3 November" across a month boundary. */
    public static String week(LocalDate monday) {
        LocalDate sunday = monday.plusDays(6);
        String endMonth = sunday.getMonth().getDisplayName(TextStyle.FULL, EN);
        if (monday.getMonth() == sunday.getMonth()) {
            return monday.getDayOfMonth() + " to " + sunday.getDayOfMonth() + " " + endMonth;
        }
        return monday.getDayOfMonth() + " " + monday.getMonth().getDisplayName(TextStyle.FULL, EN)
                + " to " + sunday.getDayOfMonth() + " " + endMonth;
    }
}
