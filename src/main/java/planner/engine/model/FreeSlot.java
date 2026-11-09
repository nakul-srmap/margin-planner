package planner.engine.model;

import java.time.LocalDate;
import java.time.LocalTime;

/** A contiguous block of unoccupied time on a specific date. */
public record FreeSlot(LocalDate date, LocalTime start, LocalTime end, boolean preferred) {

    public FreeSlot {
        if (date == null || start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("Invalid free slot: " + date + " " + start + "-" + end);
        }
    }

    public int minutes() {
        return (end.toSecondOfDay() - start.toSecondOfDay()) / 60;
    }

    /** The slot left over after consuming {@code usedMinutes} from the front, or null if exhausted. */
    public FreeSlot consume(int usedMinutes) {
        LocalTime newStart = start.plusMinutes(usedMinutes);
        if (!newStart.isBefore(end)) {
            return null;
        }
        return new FreeSlot(date, newStart, end, preferred);
    }

    @Override
    public String toString() {
        return "%s %s-%s (%d min%s)".formatted(date, start, end, minutes(), preferred ? ", preferred" : "");
    }
}
