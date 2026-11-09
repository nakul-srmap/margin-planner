package planner.domain;

import planner.db.Db;
import planner.db.Sql;
import planner.domain.Records.BlockStatus;
import planner.domain.Records.Chapter;
import planner.domain.Records.Exam;
import planner.domain.Records.Highlighter;
import planner.domain.Records.Kind;
import planner.domain.Records.PlannedBlock;
import planner.domain.Records.Preferences;
import planner.domain.Records.Priority;
import planner.domain.Records.Subject;
import planner.domain.Records.Task;
import planner.domain.Records.TimetableEntry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Every read and write the pages need, as plain SQL against PostgreSQL.
 *
 * <p>Every statement filters on {@code user_id}, and a statement that touches a child row
 * (a chapter, say) joins back to its owner to check it. A request for someone else's
 * record therefore finds nothing, and the pages answer 404 — which is indistinguishable
 * from the record not existing, so nothing leaks.
 */
public final class Store {

    private final Db db;

    public Store(Db db) {
        this.db = db;
    }

    public Db db() {
        return db;
    }

    /* =============================== Subjects =============================== */

    private static final String SUBJECT_SELECT = """
            select s.id, s.code, s.name, s.difficulty, s.highlighter,
                   coalesce((select max(case when x.total = 0 then 0
                                             else x.done::float / x.total end)
                             from (select count(c.id) as total,
                                          count(c.id) filter (where c.done) as done
                                   from exams e left join chapters c on c.exam_id = e.id
                                   where e.subject_id = s.id
                                   group by e.id) x), 0) as preparedness,
                   (select count(*) from tasks t
                     where t.subject_id = s.id and t.done_at is null) as open_tasks
            from subjects s
            """;

    private static Subject subject(ResultSet rs) throws SQLException {
        return new Subject(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getInt("difficulty"), Highlighter.parse(rs.getString("highlighter")),
                rs.getDouble("preparedness"), rs.getInt("open_tasks"));
    }

    public List<Subject> subjects(long userId) {
        return db.read(c -> Sql.list(c, SUBJECT_SELECT + " where s.user_id = ? order by s.code",
                Store::subject, userId));
    }

    public Optional<Subject> subject(long userId, long id) {
        return db.read(c -> Sql.one(c, SUBJECT_SELECT + " where s.user_id = ? and s.id = ?",
                Store::subject, userId, id));
    }

    public long addSubject(long userId, String code, String name, int difficulty, Highlighter colour) {
        return db.tx(c -> Sql.insert(c, """
                insert into subjects (user_id, code, name, difficulty, highlighter)
                values (?, ?, ?, ?, ?)""", userId, code, name, difficulty, colour.name()));
    }

    public boolean updateSubject(long userId, long id, String code, String name, int difficulty,
                                 Highlighter colour) {
        return db.tx(c -> Sql.update(c, """
                update subjects set code = ?, name = ?, difficulty = ?, highlighter = ?
                where id = ? and user_id = ?""", code, name, difficulty, colour.name(), id, userId)) == 1;
    }

    public boolean deleteSubject(long userId, long id) {
        return db.tx(c -> Sql.update(c, "delete from subjects where id = ? and user_id = ?", id, userId)) == 1;
    }

    /** The next highlighter not yet used, so a new subject is distinguishable by default. */
    public Highlighter nextHighlighter(long userId) {
        List<String> used = db.read(c -> Sql.list(c,
                "select highlighter from subjects where user_id = ?", rs -> rs.getString(1), userId));
        for (Highlighter h : Highlighter.values()) {
            if (!used.contains(h.name())) {
                return h;
            }
        }
        return Highlighter.values()[used.size() % Highlighter.values().length];
    }

    /* =============================== Timetable =============================== */

    private static final String TIMETABLE_SELECT = """
            select t.id, t.subject_id, s.code, s.name, s.highlighter, t.kind, t.label,
                   t.day_of_week, t.start_time, t.end_time, t.room
            from timetable t left join subjects s on s.id = t.subject_id
            """;

    private static TimetableEntry entry(ResultSet rs) throws SQLException {
        return new TimetableEntry(rs.getLong("id"), Sql.longOrNull(rs, "subject_id"),
                rs.getString("code"), rs.getString("name"),
                Highlighter.parse(rs.getString("highlighter")), Kind.valueOf(rs.getString("kind")),
                rs.getString("label"), DayOfWeek.of(rs.getInt("day_of_week")),
                Sql.time(rs, "start_time"), Sql.time(rs, "end_time"), rs.getString("room"));
    }

    public List<TimetableEntry> timetable(long userId) {
        return db.read(c -> Sql.list(c,
                TIMETABLE_SELECT + " where t.user_id = ? order by t.day_of_week, t.start_time",
                Store::entry, userId));
    }

    public long addTimetableEntry(long userId, Long subjectId, Kind kind, String label, DayOfWeek day,
                                  LocalTime start, LocalTime end, String room) {
        return db.tx(c -> {
            if (subjectId != null) {
                requireOwned(c, "subjects", subjectId, userId);
            }
            return Sql.insert(c, """
                    insert into timetable (user_id, subject_id, kind, label, day_of_week,
                                           start_time, end_time, room)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""",
                    userId, subjectId, kind.name(), label, day.getValue(), start, end, room);
        });
    }

    public boolean deleteTimetableEntry(long userId, long id) {
        return db.tx(c -> Sql.update(c, "delete from timetable where id = ? and user_id = ?", id, userId)) == 1;
    }

    /* ================================= Tasks ================================= */

    private static final String TASK_SELECT = """
            select t.id, t.subject_id, s.code, s.highlighter, t.title, t.notes, t.priority,
                   t.due_at, t.estimated_minutes, t.completed_minutes, t.missed_sessions,
                   t.splittable, t.done_at
            from tasks t left join subjects s on s.id = t.subject_id
            """;

    private static Task task(ResultSet rs) throws SQLException {
        return new Task(rs.getLong("id"), Sql.longOrNull(rs, "subject_id"), rs.getString("code"),
                Highlighter.parse(rs.getString("highlighter")), rs.getString("title"),
                rs.getString("notes"), Priority.parse(rs.getString("priority")),
                Sql.dateTime(rs, "due_at"), rs.getInt("estimated_minutes"),
                rs.getInt("completed_minutes"), rs.getInt("missed_sessions"),
                rs.getBoolean("splittable"), Sql.dateTime(rs, "done_at"));
    }

    public List<Task> tasks(long userId) {
        return db.read(c -> Sql.list(c,
                TASK_SELECT + " where t.user_id = ? order by t.done_at nulls first, t.due_at",
                Store::task, userId));
    }

    public List<Task> openTasks(long userId) {
        return db.read(c -> Sql.list(c,
                TASK_SELECT + " where t.user_id = ? and t.done_at is null order by t.due_at",
                Store::task, userId));
    }

    public Optional<Task> task(long userId, long id) {
        return db.read(c -> Sql.one(c, TASK_SELECT + " where t.user_id = ? and t.id = ?",
                Store::task, userId, id));
    }

    public long addTask(long userId, Long subjectId, String title, String notes, Priority priority,
                        LocalDateTime due, int minutes, boolean splittable) {
        return db.tx(c -> {
            if (subjectId != null) {
                requireOwned(c, "subjects", subjectId, userId);
            }
            return Sql.insert(c, """
                    insert into tasks (user_id, subject_id, title, notes, priority, due_at,
                                       estimated_minutes, splittable)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""",
                    userId, subjectId, title, notes, priority.name(), due, minutes, splittable);
        });
    }

    public boolean updateTask(long userId, long id, Long subjectId, String title, String notes,
                              Priority priority, LocalDateTime due, int minutes, int completed,
                              boolean splittable) {
        return db.tx(c -> {
            if (subjectId != null) {
                requireOwned(c, "subjects", subjectId, userId);
            }
            return Sql.update(c, """
                    update tasks set subject_id = ?, title = ?, notes = ?, priority = ?, due_at = ?,
                                     estimated_minutes = ?, completed_minutes = least(?, ?),
                                     splittable = ?
                    where id = ? and user_id = ?""",
                    subjectId, title, notes, priority.name(), due, minutes, completed, minutes,
                    splittable, id, userId) == 1;
        });
    }

    /** Ticks a task off, or reopens it. Ticking off also clears its future planned blocks. */
    public boolean setTaskDone(long userId, long id, boolean done) {
        return db.tx(c -> {
            int changed = Sql.update(c, """
                    update tasks set done_at = case when ? then now() else null end
                    where id = ? and user_id = ?""", done, id, userId);
            if (changed == 1 && done) {
                Sql.update(c, """
                        delete from planned_sessions
                        where task_id = ? and user_id = ? and status = 'PLANNED' and day >= current_date""",
                        id, userId);
            }
            return changed == 1;
        });
    }

    public boolean deleteTask(long userId, long id) {
        return db.tx(c -> Sql.update(c, "delete from tasks where id = ? and user_id = ?", id, userId)) == 1;
    }

    /* ================================= Exams ================================= */

    private static final String EXAM_SELECT = """
            select e.id, e.subject_id, s.code, s.name, s.highlighter, e.title, e.exam_date,
                   e.start_time, e.weightage,
                   (select count(*) from chapters c where c.exam_id = e.id) as chapters,
                   (select count(*) from chapters c where c.exam_id = e.id and c.done) as done
            from exams e join subjects s on s.id = e.subject_id
            """;

    private static Exam exam(ResultSet rs) throws SQLException {
        return new Exam(rs.getLong("id"), rs.getLong("subject_id"), rs.getString("code"),
                rs.getString("name"), Highlighter.parse(rs.getString("highlighter")),
                rs.getString("title"), Sql.date(rs, "exam_date"), Sql.time(rs, "start_time"),
                rs.getInt("weightage"), rs.getInt("chapters"), rs.getInt("done"));
    }

    public List<Exam> exams(long userId) {
        return db.read(c -> Sql.list(c, EXAM_SELECT + " where e.user_id = ? order by e.exam_date",
                Store::exam, userId));
    }

    public List<Exam> upcomingExams(long userId, LocalDate from) {
        return db.read(c -> Sql.list(c,
                EXAM_SELECT + " where e.user_id = ? and e.exam_date >= ? order by e.exam_date",
                Store::exam, userId, from));
    }

    public long addExam(long userId, long subjectId, String title, LocalDate date, LocalTime start,
                        int weightage) {
        return db.tx(c -> {
            requireOwned(c, "subjects", subjectId, userId);
            return Sql.insert(c, """
                    insert into exams (user_id, subject_id, title, exam_date, start_time, weightage)
                    values (?, ?, ?, ?, ?, ?)""", userId, subjectId, title, date, start, weightage);
        });
    }

    public boolean deleteExam(long userId, long id) {
        return db.tx(c -> Sql.update(c, "delete from exams where id = ? and user_id = ?", id, userId)) == 1;
    }

    public List<Chapter> chapters(long userId, long examId) {
        return db.read(c -> Sql.list(c, """
                select c.id, c.exam_id, c.name, c.done
                from chapters c join exams e on e.id = c.exam_id
                where e.user_id = ? and c.exam_id = ?
                order by c.position, c.id""",
                rs -> new Chapter(rs.getLong("id"), rs.getLong("exam_id"), rs.getString("name"),
                        rs.getBoolean("done")), userId, examId));
    }

    public boolean addChapters(long userId, long examId, List<String> names) {
        return db.tx(c -> {
            if (Sql.count(c, "select count(*) from exams where id = ? and user_id = ?", examId, userId) == 0) {
                return false;
            }
            long position = Sql.count(c, "select count(*) from chapters where exam_id = ?", examId);
            for (String name : names) {
                Sql.update(c, "insert into chapters (exam_id, name, position) values (?, ?, ?)",
                        examId, name, position++);
            }
            return true;
        });
    }

    public boolean setChapterDone(long userId, long chapterId, boolean done) {
        return db.tx(c -> Sql.update(c, """
                update chapters set done = ?
                where id = ? and exam_id in (select id from exams where user_id = ?)""",
                done, chapterId, userId)) == 1;
    }

    public boolean deleteChapter(long userId, long chapterId) {
        return db.tx(c -> Sql.update(c, """
                delete from chapters
                where id = ? and exam_id in (select id from exams where user_id = ?)""",
                chapterId, userId)) == 1;
    }

    /* ============================== Preferences ============================== */

    public Preferences preferences(long userId) {
        return db.read(c -> Sql.one(c, "select * from preferences where user_id = ?",
                rs -> new Preferences(Sql.time(rs, "wake_time"), Sql.time(rs, "sleep_time"),
                        rs.getInt("max_daily_minutes"), rs.getInt("min_session_minutes"),
                        rs.getInt("max_session_minutes"), rs.getInt("break_minutes"),
                        rs.getInt("max_hard_per_day"), rs.getBoolean("weekend_available"),
                        Sql.time(rs, "focus_start"), Sql.time(rs, "focus_end")),
                userId)).orElseThrow(() -> new IllegalStateException("No preferences for user " + userId));
    }

    public void savePreferences(long userId, Preferences p) {
        db.txv(c -> Sql.update(c, """
                update preferences set wake_time = ?, sleep_time = ?, max_daily_minutes = ?,
                       min_session_minutes = ?, max_session_minutes = ?, break_minutes = ?,
                       max_hard_per_day = ?, weekend_available = ?, focus_start = ?, focus_end = ?
                where user_id = ?""",
                p.wake(), p.sleep(), p.maxDailyMinutes(), p.minSessionMinutes(),
                p.maxSessionMinutes(), p.breakMinutes(), p.maxHardPerDay(), p.weekendAvailable(),
                p.focusStart(), p.focusEnd(), userId));
    }

    /* ================================ The plan ================================ */

    private static final String BLOCK_SELECT = """
            select p.id, p.task_id, s.code, s.highlighter, p.title, p.day, p.start_time,
                   p.end_time, p.score, p.revision, p.part, p.parts, p.note, p.status
            from planned_sessions p left join subjects s on s.id = p.subject_id
            """;

    private static PlannedBlock block(ResultSet rs) throws SQLException {
        return new PlannedBlock(rs.getLong("id"), Sql.longOrNull(rs, "task_id"), rs.getString("code"),
                Highlighter.parse(rs.getString("highlighter")), rs.getString("title"),
                Sql.date(rs, "day"), Sql.time(rs, "start_time"), Sql.time(rs, "end_time"),
                rs.getDouble("score"), rs.getBoolean("revision"), rs.getInt("part"),
                rs.getInt("parts"), rs.getString("note"), BlockStatus.valueOf(rs.getString("status")));
    }

    public List<PlannedBlock> plan(long userId, LocalDate from, LocalDate to) {
        return db.read(c -> Sql.list(c,
                BLOCK_SELECT + " where p.user_id = ? and p.day between ? and ? order by p.day, p.start_time",
                Store::block, userId, from, to));
    }

    /** A block the student should say they did or missed: in the past, still marked planned. */
    public List<PlannedBlock> unresolved(long userId, LocalDateTime now) {
        return db.read(c -> Sql.list(c, BLOCK_SELECT + """
                 where p.user_id = ? and p.status = 'PLANNED'
                   and (p.day < ? or (p.day = ? and p.end_time <= ?))
                 order by p.day, p.start_time""",
                Store::block, userId, now.toLocalDate(), now.toLocalDate(), now.toLocalTime()));
    }

    public record NewBlock(Long taskId, Long subjectId, String title, LocalDate day, LocalTime start,
                           LocalTime end, double score, boolean revision, int part, int parts,
                           String note) { }

    /**
     * Replaces every planned block that starts after {@code from} with the new plan, in
     * one transaction: the student never sees half an old plan and half a new one.
     * Blocks already marked done or missed are history and are kept.
     */
    public int replacePlan(long userId, LocalDateTime from, List<NewBlock> blocks) {
        return db.tx(c -> {
            // Only blocks that haven't started yet are replaced. Earlier ones are kept so the
            // student can still say whether they did them.
            Sql.update(c, """
                    delete from planned_sessions
                    where user_id = ? and status = 'PLANNED'
                      and (day > ? or (day = ? and start_time >= ?))""",
                    userId, from.toLocalDate(), from.toLocalDate(), from.toLocalTime());
            for (NewBlock b : blocks) {
                Sql.update(c, """
                        insert into planned_sessions (user_id, task_id, subject_id, title, day,
                               start_time, end_time, score, revision, part, parts, note)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        userId, b.taskId(), b.subjectId(), b.title(), b.day(), b.start(), b.end(),
                        b.score(), b.revision(), b.part(), b.parts(), b.note());
            }
            return blocks.size();
        });
    }

    /**
     * Records that a planned block happened or didn't. Done adds its minutes to the task;
     * missed adds one to the task's missed count, which raises its priority next time the
     * plan is made.
     */
    public boolean resolveBlock(long userId, long blockId, BlockStatus status) {
        return db.tx(c -> {
            Optional<PlannedBlock> block = Sql.one(c, BLOCK_SELECT
                    + " where p.user_id = ? and p.id = ? and p.status = 'PLANNED' for update of p",
                    Store::block, userId, blockId);
            if (block.isEmpty()) {
                return false;
            }
            Sql.update(c, "update planned_sessions set status = ? where id = ?", status.name(), blockId);
            Long taskId = block.get().taskId();
            if (taskId != null) {
                if (status == BlockStatus.DONE) {
                    Sql.update(c, """
                            update tasks set completed_minutes = least(estimated_minutes,
                                                                       completed_minutes + ?)
                            where id = ? and user_id = ?""", block.get().minutes(), taskId, userId);
                } else if (status == BlockStatus.MISSED) {
                    Sql.update(c, "update tasks set missed_sessions = missed_sessions + 1 where id = ? and user_id = ?",
                            taskId, userId);
                }
            }
            return true;
        });
    }

    /* ================================ Helpers ================================ */

    /* ======================= Auto re-planning, Classroom ======================= */

    public record AutoPlan(boolean on, String mode) { }

    public AutoPlan autoPlan(long userId) {
        return db.read(c -> Sql.one(c, "select auto_plan, plan_mode from preferences where user_id = ?",
                rs -> new AutoPlan(rs.getBoolean(1), rs.getString(2)), userId)).orElse(new AutoPlan(true, "BALANCED"));
    }

    public void setAutoPlan(long userId, boolean on, String mode) {
        db.txv(c -> Sql.update(c, "update preferences set auto_plan = ?, plan_mode = ? where user_id = ?", on, mode, userId));
    }

    /** Adds or refreshes a task that came from elsewhere. Returns true when it was new. */
    public boolean upsertExternalTask(long userId, String externalId, Long subjectId, String title, String notes,
                                      LocalDateTime due, int minutes, String link, boolean turnedIn) {
        return db.tx(c -> {
            Optional<Long> existing = Sql.one(c, "select id from tasks where user_id = ? and external_id = ?",
                    rs -> rs.getLong(1), userId, externalId);
            if (existing.isPresent()) {
                Sql.update(c, """
                        update tasks set title = ?, due_at = ?, link = ?,
                               subject_id = coalesce(subject_id, ?),
                               done_at = case when ? and done_at is null then now() else done_at end
                        where id = ? and user_id = ?""",
                        title, due, link, subjectId, turnedIn, existing.get(), userId);
                return false;
            }
            Sql.insert(c, """
                    insert into tasks (user_id, subject_id, title, notes, priority, due_at, estimated_minutes,
                                       splittable, external_id, link, done_at)
                    values (?, ?, ?, ?, 'MEDIUM', ?, ?, true, ?, ?, case when ? then now() else null end)""",
                    userId, subjectId, title, notes, due, minutes, externalId, link, turnedIn);
            return true;
        });
    }

    public record GoogleAccount(String refreshToken, String accessToken, java.time.Instant expiresAt, java.time.Instant syncedAt) { }

    public Optional<GoogleAccount> googleAccount(long userId) {
        return db.read(c -> Sql.one(c, "select refresh_token, access_token, expires_at, synced_at from google_accounts where user_id = ?",
                rs -> new GoogleAccount(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()), userId));
    }

    public void saveGoogleTokens(long userId, String refresh, String access, java.time.Instant expires) {
        db.txv(c -> Sql.update(c, """
                insert into google_accounts (user_id, refresh_token, access_token, expires_at) values (?, ?, ?, ?)
                on conflict (user_id) do update set
                    refresh_token = coalesce(excluded.refresh_token, google_accounts.refresh_token),
                    access_token = excluded.access_token, expires_at = excluded.expires_at""",
                userId, refresh, access, java.sql.Timestamp.from(expires)));
    }

    public void markGoogleSynced(long userId) {
        db.txv(c -> Sql.update(c, "update google_accounts set synced_at = now() where user_id = ?", userId));
    }

    public void deleteGoogleAccount(long userId) {
        db.txv(c -> Sql.update(c, "delete from google_accounts where user_id = ?", userId));
    }

    private static void requireOwned(java.sql.Connection c, String table, long id, long userId)
            throws SQLException {
        // The table name is one of our own constants, never user input.
        if (Sql.count(c, "select count(*) from " + table + " where id = ? and user_id = ?", id, userId) == 0) {
            throw new planner.http.HttpException(404, "That subject doesn't exist.");
        }
    }

    public boolean hasAnyData(long userId) {
        return db.read(c -> Sql.count(c, """
                select (select count(*) from subjects where user_id = ?)
                     + (select count(*) from tasks where user_id = ?)""", userId, userId) > 0);
    }
}
