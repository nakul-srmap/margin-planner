package planner.engine.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The complete result of one scheduling run. */
public class GeneratedSchedule {

    private final SchedulingMode mode;
    private final LocalDate from;
    private final LocalDate to;
    private final List<ScheduledSession> sessions = new ArrayList<>();
    private final List<AtRiskTask> atRisk = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<ScoreBreakdown> scores = new ArrayList<>();

    public GeneratedSchedule(SchedulingMode mode, LocalDate from, LocalDate to) {
        this.mode = mode;
        this.from = from;
        this.to = to;
    }

    public SchedulingMode mode() { return mode; }

    public LocalDate from() { return from; }

    public LocalDate to() { return to; }

    public List<ScheduledSession> sessions() { return sessions; }

    public List<AtRiskTask> atRisk() { return atRisk; }

    public List<String> warnings() { return warnings; }

    public List<ScoreBreakdown> scores() { return scores; }

    public void addSession(ScheduledSession session) { sessions.add(session); }

    public void addAtRisk(AtRiskTask task) { atRisk.add(task); }

    public void addWarning(String warning) { warnings.add(warning); }

    public void addScore(ScoreBreakdown score) { scores.add(score); }

    public int totalMinutes() {
        return sessions.stream().mapToInt(ScheduledSession::minutes).sum();
    }

    /** Minutes of study booked per date, ordered by date — the data behind the workload heatmap. */
    public Map<LocalDate, Integer> dailyLoad() {
        return sessions.stream()
                .sorted(Comparator.comparing(ScheduledSession::date))
                .collect(Collectors.groupingBy(
                        ScheduledSession::date,
                        LinkedHashMap::new,
                        Collectors.summingInt(ScheduledSession::minutes)));
    }

    /** Minutes booked per subject — the data behind the subject balance chart. */
    public Map<String, Integer> subjectLoad() {
        return sessions.stream()
                .collect(Collectors.groupingBy(
                        s -> s.subjectCode() == null ? "GENERAL" : s.subjectCode(),
                        LinkedHashMap::new,
                        Collectors.summingInt(ScheduledSession::minutes)));
    }

    public List<ScheduledSession> sessionsOn(LocalDate date) {
        return sessions.stream()
                .filter(s -> s.date().equals(date))
                .sorted(Comparator.comparing(ScheduledSession::start))
                .toList();
    }

    /** A short natural-language summary for the dashboard and the AI assistant. */
    public String summary() {
        int hours = totalMinutes() / 60;
        int minutes = totalMinutes() % 60;
        StringBuilder sb = new StringBuilder();
        sb.append("%s plan: %d sessions totalling %dh %02dm across %d day(s)."
                .formatted(mode.displayName(), sessions.size(), hours, minutes, dailyLoad().size()));
        if (!atRisk.isEmpty()) {
            sb.append(" %d task(s) are at risk of missing their deadline.".formatted(atRisk.size()));
        }
        return sb.toString();
    }
}
