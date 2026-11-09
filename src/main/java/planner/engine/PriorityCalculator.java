package planner.engine;

import planner.engine.model.ExamEvent;
import planner.engine.model.ScoreBreakdown;
import planner.engine.model.StudyTask;
import planner.engine.model.Subject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Turns a task plus its academic context into a normalised 0-100 priority score.
 *
 * <p>Each of the five components is computed on its own 0-100 scale and then combined using
 * {@link PriorityWeights}. Because the weights sum to 1.0 the result is already normalised.
 * The returned {@link ScoreBreakdown} keeps the components so the scheduler can tell the
 * student <em>why</em> a task was prioritised.
 */
public class PriorityCalculator {

    /** Tasks further than this many hours out score 0 on urgency. */
    private static final double URGENCY_HORIZON_HOURS = 14 * 24;

    /** Exams further than this many days out score 0 on exam relevance. */
    private static final double EXAM_HORIZON_DAYS = 21.0;

    /** Each missed session adds this much to the overdue component. */
    private static final double MISSED_SESSION_PENALTY = 20.0;

    private final PriorityWeights weights;

    public PriorityCalculator() {
        this(PriorityWeights.defaults());
    }

    public PriorityCalculator(PriorityWeights weights) {
        this.weights = weights;
    }

    public PriorityWeights weights() {
        return weights;
    }

    public ScoreBreakdown score(StudyTask task, Map<String, Subject> subjects,
                                List<ExamEvent> exams, LocalDateTime now) {
        double urgency = deadlineUrgency(task, now);
        double importance = task.priority().weight();
        double examRelevance = examRelevance(task, exams, now.toLocalDate());
        double difficulty = difficulty(task, subjects);
        double overdue = overdueFactor(task, now);

        double total = urgency * weights.deadline()
                + importance * weights.importance()
                + examRelevance * weights.exam()
                + difficulty * weights.difficulty()
                + overdue * weights.overdue();

        total = clamp(total);

        return new ScoreBreakdown(task.id(), urgency, importance, examRelevance, difficulty,
                overdue, total, dominantFactor(urgency, importance, examRelevance, difficulty, overdue));
    }

    /**
     * Urgency rises as the deadline approaches, reaching 100 once the task is due or overdue.
     * A task with no deadline scores 0 so that dated work always outranks it.
     */
    double deadlineUrgency(StudyTask task, LocalDateTime now) {
        if (task.deadline() == null) {
            return 0.0;
        }
        double hoursLeft = task.hoursUntilDeadline(now);
        if (hoursLeft <= 0) {
            return 100.0;
        }
        if (hoursLeft >= URGENCY_HORIZON_HOURS) {
            return 0.0;
        }
        double linear = 100.0 * (1.0 - hoursLeft / URGENCY_HORIZON_HOURS);

        // Slack-awareness: if the remaining work barely fits in the remaining time, treat the
        // task as more urgent than the calendar distance alone suggests.
        double workHours = task.remainingMinutes() / 60.0;
        double slackRatio = workHours <= 0 ? 1.0 : hoursLeft / (workHours * 3.0);
        double slackBoost = slackRatio >= 1.0 ? 0.0 : 100.0 * (1.0 - slackRatio);

        return clamp(Math.max(linear, slackBoost));
    }

    /**
     * How much this task matters for an upcoming exam in the same subject. Scales with both
     * nearness of the paper and its weightage, and ignores exams already sat.
     */
    double examRelevance(StudyTask task, List<ExamEvent> exams, LocalDate today) {
        if (task.subjectCode() == null || exams == null || exams.isEmpty()) {
            return 0.0;
        }
        double best = 0.0;
        for (ExamEvent exam : exams) {
            if (!task.subjectCode().equals(exam.subjectCode()) || !exam.isUpcoming(today)) {
                continue;
            }
            long days = exam.daysUntil(today);
            if (days > EXAM_HORIZON_DAYS) {
                continue;
            }
            double nearness = 100.0 * (1.0 - days / EXAM_HORIZON_DAYS);
            double weighted = nearness * (Math.min(100, exam.weightage()) / 100.0);
            best = Math.max(best, weighted);
        }
        return clamp(best);
    }

    /** Subject difficulty on the 0-100 axis; unknown subjects are treated as mid-difficulty. */
    double difficulty(StudyTask task, Map<String, Subject> subjects) {
        if (task.subjectCode() == null || subjects == null) {
            return 50.0;
        }
        Subject subject = subjects.get(task.subjectCode());
        return subject == null ? 50.0 : subject.difficultyScore();
    }

    /** Penalty for work the student has already let slip. */
    double overdueFactor(StudyTask task, LocalDateTime now) {
        double factor = task.isOverdue(now) ? 100.0 : 0.0;
        factor += task.missedSessions() * MISSED_SESSION_PENALTY;
        return clamp(factor);
    }

    private String dominantFactor(double urgency, double importance, double exam,
                                  double difficulty, double overdue) {
        double wu = urgency * weights.deadline();
        double wi = importance * weights.importance();
        double we = exam * weights.exam();
        double wd = difficulty * weights.difficulty();
        double wo = overdue * weights.overdue();

        double max = Math.max(Math.max(Math.max(wu, wi), Math.max(we, wd)), wo);
        if (max == wo && wo > 0) return "previously missed work";
        if (max == wu) return "an approaching deadline";
        if (max == we) return "an upcoming examination";
        if (max == wi) return "the priority you set";
        return "subject difficulty";
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }
}
