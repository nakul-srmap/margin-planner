package planner.engine;

import planner.engine.model.ClassSession;
import planner.engine.model.Commitment;
import planner.engine.model.FreeSlot;
import planner.engine.model.TimeWindow;
import planner.engine.model.UserPreferences;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Works out when the student is actually free.
 *
 * <p>For each date in the planning horizon it starts from the waking window, subtracts every
 * class and personal commitment, and returns the gaps that are long enough to be worth using.
 * Gaps are further split along the student's preferred study windows so the scheduler can
 * favour the hours they actually want to work in.
 *
 * <p>This is the component that guarantees the engine never books study time on top of a
 * lecture: anything it does not return simply cannot be allocated.
 */
public class FreeTimeFinder {

    private final UserPreferences preferences;

    public FreeTimeFinder(UserPreferences preferences) {
        this.preferences = preferences;
    }

    /**
     * @param from inclusive first date of the horizon
     * @param to   inclusive last date of the horizon
     * @param now  current time; slots already in the past on the first day are excluded
     */
    public List<FreeSlot> find(LocalDate from, LocalDate to, List<ClassSession> classes,
                               List<Commitment> commitments, LocalDateTime now) {
        List<FreeSlot> slots = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            slots.addAll(findForDate(date, classes, commitments, now));
        }
        return slots;
    }

    /** The free slots on a single date, earliest first. */
    public List<FreeSlot> findForDate(LocalDate date, List<ClassSession> classes,
                                      List<Commitment> commitments, LocalDateTime now) {
        if (isUnavailableWeekend(date)) {
            return List.of();
        }

        LocalTime dayStart = preferences.wakeTime();
        LocalTime dayEnd = preferences.sleepTime();

        // Never schedule in the past.
        if (date.equals(now.toLocalDate())) {
            LocalTime roundedNow = now.toLocalTime().withSecond(0).withNano(0);
            if (roundedNow.isAfter(dayStart)) {
                dayStart = roundedNow;
            }
        }
        if (!dayStart.isBefore(dayEnd)) {
            return List.of();
        }

        List<TimeWindow> busy = collectBusyWindows(date, classes, commitments);
        List<TimeWindow> gaps = subtract(new TimeWindow(dayStart, dayEnd), busy);

        List<FreeSlot> result = new ArrayList<>();
        for (TimeWindow gap : gaps) {
            for (TimeWindow piece : splitByPreference(gap)) {
                if (piece.minutes() >= preferences.minSessionMinutes()) {
                    result.add(new FreeSlot(date, piece.start(), piece.end(), isPreferred(piece)));
                }
            }
        }
        result.sort(Comparator.comparing(FreeSlot::start));
        return result;
    }

    /** Total free minutes on a date — backs the "how many free hours tomorrow?" assistant intent. */
    public int freeMinutesOn(LocalDate date, List<ClassSession> classes,
                             List<Commitment> commitments, LocalDateTime now) {
        return findForDate(date, classes, commitments, now).stream()
                .mapToInt(FreeSlot::minutes)
                .sum();
    }

    private boolean isUnavailableWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        return weekend && !preferences.weekendAvailable();
    }

    private List<TimeWindow> collectBusyWindows(LocalDate date, List<ClassSession> classes,
                                                List<Commitment> commitments) {
        List<TimeWindow> busy = new ArrayList<>();
        if (classes != null) {
            for (ClassSession session : classes) {
                if (session.day() == date.getDayOfWeek()) {
                    busy.add(session.window());
                }
            }
        }
        if (commitments != null) {
            for (Commitment commitment : commitments) {
                if (commitment.appliesTo(date)) {
                    busy.add(commitment.window());
                }
            }
        }
        return merge(busy);
    }

    /** Collapses overlapping or touching busy windows into a minimal non-overlapping set. */
    static List<TimeWindow> merge(List<TimeWindow> windows) {
        if (windows.isEmpty()) {
            return List.of();
        }
        List<TimeWindow> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparing(TimeWindow::start));

        List<TimeWindow> merged = new ArrayList<>();
        LocalTime start = sorted.get(0).start();
        LocalTime end = sorted.get(0).end();

        for (int i = 1; i < sorted.size(); i++) {
            TimeWindow next = sorted.get(i);
            if (!next.start().isAfter(end)) {
                if (next.end().isAfter(end)) {
                    end = next.end();
                }
            } else {
                merged.add(new TimeWindow(start, end));
                start = next.start();
                end = next.end();
            }
        }
        merged.add(new TimeWindow(start, end));
        return merged;
    }

    /** The parts of {@code span} not covered by any of the {@code busy} windows. */
    static List<TimeWindow> subtract(TimeWindow span, List<TimeWindow> busy) {
        List<TimeWindow> free = new ArrayList<>();
        LocalTime cursor = span.start();

        for (TimeWindow block : busy) {
            if (!block.end().isAfter(cursor)) {
                continue;
            }
            if (!block.start().isBefore(span.end())) {
                break;
            }
            if (block.start().isAfter(cursor)) {
                free.add(new TimeWindow(cursor, block.start()));
            }
            if (block.end().isAfter(cursor)) {
                cursor = block.end();
            }
            if (!cursor.isBefore(span.end())) {
                return free;
            }
        }
        if (cursor.isBefore(span.end())) {
            free.add(new TimeWindow(cursor, span.end()));
        }
        return free;
    }

    /**
     * Slices a gap at the edges of the student's preferred windows, so that a gap straddling
     * a preference boundary yields one preferred piece and one ordinary piece rather than a
     * single ambiguous block.
     */
    private List<TimeWindow> splitByPreference(TimeWindow gap) {
        List<TimeWindow> preferred = preferences.preferredWindows();
        if (preferred == null || preferred.isEmpty()) {
            return List.of(gap);
        }
        TreeSet<LocalTime> cuts = new TreeSet<>();
        cuts.add(gap.start());
        cuts.add(gap.end());
        for (TimeWindow window : preferred) {
            if (gap.contains(window.start())) {
                cuts.add(window.start());
            }
            if (window.end().isAfter(gap.start()) && window.end().isBefore(gap.end())) {
                cuts.add(window.end());
            }
        }
        List<LocalTime> ordered = new ArrayList<>(cuts);
        List<TimeWindow> pieces = new ArrayList<>();
        for (int i = 0; i < ordered.size() - 1; i++) {
            pieces.add(new TimeWindow(ordered.get(i), ordered.get(i + 1)));
        }
        return pieces;
    }

    private boolean isPreferred(TimeWindow piece) {
        for (TimeWindow window : preferences.preferredWindows()) {
            if (window.overlaps(piece)) {
                return true;
            }
        }
        return false;
    }
}
