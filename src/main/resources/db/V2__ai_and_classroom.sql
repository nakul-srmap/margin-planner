-- Tasks brought in from Google Classroom remember where they came from, so a re-sync updates
-- them instead of adding duplicates.
alter table tasks add column external_id text;
alter table tasks add column link text check (link is null or link like 'https://%');
create unique index tasks_external on tasks (user_id, external_id) where external_id is not null;

-- Re-plan the week automatically whenever something is added.
alter table preferences add column auto_plan boolean not null default true;
alter table preferences add column plan_mode text not null default 'BALANCED'
    check (plan_mode in ('BALANCED', 'DEADLINE', 'EXAM'));

-- A student's link to Google Classroom. Only the refresh token is kept; it can be revoked
-- from the Google account at any time, and Disconnect deletes it here.
create table google_accounts (
    user_id       bigint      primary key references users (id) on delete cascade,
    refresh_token text        not null,
    access_token  text,
    expires_at    timestamptz,
    synced_at     timestamptz
);
