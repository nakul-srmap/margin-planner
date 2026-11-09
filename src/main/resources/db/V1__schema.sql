-- Smart Academic Planner, schema version 1. PostgreSQL 13+.
--
-- Every table that holds a student's data carries user_id, and every query the
-- application runs filters on it. Constraints live here as well as in the Java code,
-- so the database refuses bad data even if a bug lets it through.

create table users (
    id            bigserial primary key,
    name          text        not null check (length(name) between 1 and 80),
    email         text        not null check (position('@' in email) > 1),
    password_hash text        not null,
    created_at    timestamptz not null default now()
);
create unique index users_email_key on users (lower(email));

create table sessions (
    id         text        primary key,            -- 256-bit random, URL-safe base64
    user_id    bigint      not null references users (id) on delete cascade,
    csrf       text        not null,
    expires_at timestamptz not null
);
create index sessions_user_idx on sessions (user_id);

-- One row per user: the constraints every generated plan must respect.
create table preferences (
    user_id             bigint   primary key references users (id) on delete cascade,
    wake_time           time     not null default '07:00',
    sleep_time          time     not null default '23:00',
    max_daily_minutes   int      not null default 300 check (max_daily_minutes between 30 and 900),
    min_session_minutes int      not null default 30  check (min_session_minutes between 10 and 120),
    max_session_minutes int      not null default 90  check (max_session_minutes between 20 and 240),
    break_minutes       int      not null default 10  check (break_minutes between 0 and 60),
    max_hard_per_day    int      not null default 2   check (max_hard_per_day between 1 and 8),
    weekend_available   boolean  not null default true,
    focus_start         time              default '17:00',
    focus_end           time              default '21:00',
    check (sleep_time > wake_time),
    check (min_session_minutes <= max_session_minutes),
    check (focus_start is null or focus_end is null or focus_end > focus_start)
);

create table subjects (
    id           bigserial primary key,
    user_id      bigint not null references users (id) on delete cascade,
    code         text   not null check (length(code) between 1 and 12),
    name         text   not null check (length(name) between 1 and 100),
    difficulty   int    not null default 3 check (difficulty between 1 and 5),
    -- Which highlighter this subject is drawn in. Fixed set, so colours stay legible.
    highlighter  text   not null default 'yellow'
                 check (highlighter in ('yellow', 'pink', 'green', 'blue', 'orange', 'violet')),
    unique (user_id, code)
);

-- Fixed weekly blocks: classes, and anything else the student is never free for.
create table timetable (
    id          bigserial primary key,
    user_id     bigint not null references users (id) on delete cascade,
    subject_id  bigint references subjects (id) on delete cascade,
    kind        text   not null check (kind in ('LECTURE', 'LAB', 'TUTORIAL', 'OTHER')),
    label       text   check (label is null or length(label) <= 60),
    day_of_week int    not null check (day_of_week between 1 and 7),   -- 1 = Monday
    start_time  time   not null,
    end_time    time   not null,
    room        text   check (room is null or length(room) <= 40),
    check (end_time > start_time),
    -- A class names its subject; an 'OTHER' block names itself.
    check ((kind = 'OTHER' and label is not null) or (kind <> 'OTHER' and subject_id is not null))
);
create index timetable_user_idx on timetable (user_id, day_of_week);

create table tasks (
    id                bigserial primary key,
    user_id           bigint    not null references users (id) on delete cascade,
    subject_id        bigint    references subjects (id) on delete set null,
    title             text      not null check (length(title) between 1 and 160),
    notes             text      check (notes is null or length(notes) <= 2000),
    priority          text      not null default 'MEDIUM'
                      check (priority in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    due_at            timestamp not null,
    estimated_minutes int       not null check (estimated_minutes between 5 and 6000),
    completed_minutes int       not null default 0 check (completed_minutes >= 0),
    missed_sessions   int       not null default 0 check (missed_sessions >= 0),
    splittable        boolean   not null default true,
    done_at           timestamp,
    created_at        timestamp not null default now()
);
create index tasks_user_open_idx on tasks (user_id, due_at) where done_at is null;

create table exams (
    id         bigserial primary key,
    user_id    bigint not null references users (id) on delete cascade,
    subject_id bigint not null references subjects (id) on delete cascade,
    title      text   not null check (length(title) between 1 and 120),
    exam_date  date   not null,
    start_time time   not null default '10:00',
    weightage  int    not null default 30 check (weightage between 1 and 100)
);
create index exams_user_idx on exams (user_id, exam_date);

create table chapters (
    id       bigserial primary key,
    exam_id  bigint  not null references exams (id) on delete cascade,
    name     text    not null check (length(name) between 1 and 140),
    done     boolean not null default false,
    position int     not null default 0
);
create index chapters_exam_idx on chapters (exam_id, position);

-- A saved plan. The reason each block was placed is stored with it, so the student
-- sees the same explanation tomorrow that they saw when they made the plan.
create table planned_sessions (
    id         bigserial primary key,
    user_id    bigint  not null references users (id) on delete cascade,
    task_id    bigint  references tasks (id) on delete cascade,
    subject_id bigint  references subjects (id) on delete set null,
    title      text    not null,
    day        date    not null,
    start_time time    not null,
    end_time   time    not null,
    score      numeric(5, 1) not null default 0,
    revision   boolean not null default false,
    part       int     not null default 1,
    parts      int     not null default 1,
    note       text,
    status     text    not null default 'PLANNED' check (status in ('PLANNED', 'DONE', 'MISSED')),
    check (end_time > start_time)
);
create index planned_user_day_idx on planned_sessions (user_id, day);
