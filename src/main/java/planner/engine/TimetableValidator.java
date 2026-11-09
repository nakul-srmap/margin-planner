package planner.engine;

import planner.engine.model.ClassSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects clashes in a weekly timetable before it is saved.
 *
 * <p>Two kinds of clash matter. A <em>student clash</em> is two entries the student would have
 * to attend at once. A <em>room clash</em> is two entries booked into the same room at the same
 * time, which usually means the timetable was typed in wrongly.
 */
public class TimetableValidator {

    /** One detected clash between two timetable entries. */
    public record Conflict(ClassSession first, ClassSession second, String kind, String message) {}

    /** Every pairwise clash in the given timetable. An empty list means the timetable is valid. */
    public List<Conflict> validate(List<ClassSession> sessions) {
        List<Conflict> conflicts = new ArrayList<>();
        if (sessions == null || sessions.size() < 2) {
            return conflicts;
        }
        for (int i = 0; i < sessions.size(); i++) {
            for (int j = i + 1; j < sessions.size(); j++) {
                ClassSession a = sessions.get(i);
                ClassSession b = sessions.get(j);
                if (!a.conflictsWith(b)) {
                    continue;
                }
                boolean sameRoom = a.room() != null && a.room().equals(b.room());
                conflicts.add(new Conflict(a, b,
                        sameRoom ? "ROOM" : "STUDENT",
                        "%s %s-%s (%s) overlaps %s-%s (%s)%s".formatted(
                                a.day(), a.start(), a.end(), a.subjectCode(),
                                b.start(), b.end(), b.subjectCode(),
                                sameRoom ? " in room " + a.room() : "")));
            }
        }
        return conflicts;
    }

    public boolean isValid(List<ClassSession> sessions) {
        return validate(sessions).isEmpty();
    }

    /** Throws if the timetable contains any clash; used by the service layer before persisting. */
    public void requireValid(List<ClassSession> sessions) {
        List<Conflict> conflicts = validate(sessions);
        if (!conflicts.isEmpty()) {
            StringBuilder sb = new StringBuilder("Timetable contains ")
                    .append(conflicts.size())
                    .append(" conflict(s):");
            conflicts.forEach(c -> sb.append("\n  - ").append(c.message()));
            throw new IllegalStateException(sb.toString());
        }
    }
}
