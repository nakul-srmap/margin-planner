package planner.web;

import planner.domain.Planner;
import planner.domain.Records.Highlighter;
import planner.domain.Records.Subject;
import planner.domain.Store;
import planner.domain.Text;
import planner.engine.model.SchedulingMode;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What every page needs: the data, the planner and the time. */
record Ctx(Store store, Planner planner, Clock clock) {

    /** Re-plans the week if the student has that turned on; returns a sentence for the message. */
    String autoPlan(long userId) {
        Store.AutoPlan a = store.autoPlan(userId);
        if (!a.on()) {
            return " Make a new plan to fit it in.";
        }
        int n = planner.replan(userId, SchedulingMode.valueOf(a.mode()), now());
        return " Your week was re-planned around it (" + Text.plural(n, "study block", "study blocks") + ").";
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }

    Map<String, Highlighter> colours(long userId) {
        Map<String, Highlighter> out = new HashMap<>();
        for (Subject s : store.subjects(userId)) {
            out.put(s.code(), s.highlighter());
        }
        return out;
    }

    /** Subject options for a select, with an optional "none" choice first. */
    static String[] subjectOptions(List<Subject> subjects, String none) {
        int offset = none == null ? 0 : 2;
        String[] out = new String[subjects.size() * 2 + offset];
        if (none != null) {
            out[0] = "";
            out[1] = none;
        }
        for (int i = 0; i < subjects.size(); i++) {
            out[offset + i * 2] = String.valueOf(subjects.get(i).id());
            out[offset + i * 2 + 1] = subjects.get(i).code() + ", " + subjects.get(i).name();
        }
        return out;
    }

    /** Only ever redirect to a path on this site. */
    static String safe(String path, String fallback) {
        return path != null && path.startsWith("/") && !path.startsWith("//") && !path.contains("\\") ? path : fallback;
    }
}
