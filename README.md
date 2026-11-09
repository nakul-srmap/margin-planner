# Margin: a smart academic planner

Margin reads your timetable, deadlines and exams, then fits study into the gaps in your week.
Every study block it places says why it is there.

Pure Java 17+ and PostgreSQL. No Maven, no Gradle, no Node. The only library is the
PostgreSQL JDBC driver, which is already in `lib/`.

## Run it on Windows

You need two things: a JDK (17 or newer) and PostgreSQL.

**1. JDK.** You already have one if `java -version` works and `javac -version` works.
If `javac` is missing, install a JDK (not a JRE):

```powershell
winget install --id Microsoft.OpenJDK.21 -e
```

**2. PostgreSQL.** Install it with the installer window, so you can choose the password:

```powershell
winget install --id PostgreSQL.PostgreSQL.17 -e --interactive
```

During setup, set a password for the `postgres` user and keep port `5432`. You can untick
pgAdmin and Stack Builder if you don't want them.

**3. Tell Margin the password.** Settings are read from `C:\Users\<you>\.margin\planner.properties`,
which stays put when you update Margin, and from `planner.properties` next to `Build.java`
(that one wins if both set something). Create the first one once:

```powershell
mkdir $HOME\.margin -Force
copy planner.properties.example $HOME\.margin\planner.properties
notepad $HOME\.margin\planner.properties
```

Change `db.password=change-me` to the password you chose (and `5432` to your port if it differs), and save.

**4. Start it.**

```powershell
java Build.java
```

The first start compiles the code, creates the `planner` database and its tables, then prints
`Margin is running at http://localhost:8080`. Open that address, create an account, and either
add your own subjects or press **Load a sample week** to look around.

Press `Ctrl+C` to stop.

### Other commands

| Command | What it does |
|---|---|
| `java Build.java` | Compile and start (same as `run`) |
| `java Build.java test` | Run the engine checks and the web tests (needs PostgreSQL; uses a separate `planner_test` database) |
| `java Build.java build` | Compile only |
| `java Build.java clean` | Delete compiled files |

Instead of `planner.properties` you can set `DB_URL`, `DB_USER`, `DB_PASSWORD` and `PORT`
as environment variables.

### If something goes wrong

- **"Could not connect to PostgreSQL ... password authentication failed"**: the password in
  `planner.properties` doesn't match the one you chose when installing PostgreSQL.
- **"Connection refused"**: PostgreSQL isn't running. Open *Services*, find
  `postgresql-x64-17` and start it.
- **"No Java compiler found"**: you are running a JRE. Install a JDK as in step 1.
- **Port 8080 in use**: set `port=8081` in `planner.properties`.

## AI quick add (Gemini)

Type something like *"DAA assignment due Friday, about 3 hours. DBMS mid-sem on the 20th"* into
**Quick add with AI** on the week or task page. Gemini reads it, works out the dates, estimates
the time each item needs, and shows you a list to confirm. Margin then fits it into your free time.
You can also paste your Google Classroom to-do list into the same box.

1. Get a free key at https://aistudio.google.com/apikey (sign in with any Google account).
2. Put it in `planner.properties`: `gemini.api.key=YOUR_KEY`
3. Restart Margin.

Gemini only reads and estimates. Where study goes is still decided by Margin's own scheduler,
so it never lands on a class.

## Automatic planning

By default Margin re-plans your week whenever you add or change a task, exam, class or setting,
and after a Classroom sync. Turn it off or pick the planning style under **Settings → Automatic planning**.

## Google Classroom

**Connect Google Classroom** on the Tasks page brings in every assignment with a due date from your
active classes (read-only), matches each class to a subject by its code or name, and marks work
you've turned in as done. **Sync now** updates them without making duplicates.

Google requires your own sign-in client for this. It's free and takes about 10 minutes:

1. Open https://console.cloud.google.com with a personal Gmail account and create a project.
2. **APIs & Services → Library**: search for **Google Classroom API** and click **Enable**.
3. **Google Auth Platform** (or **OAuth consent screen**): choose **External**, fill in the app
   name and your email. Under **Audience → Test users**, add the account your Classroom uses
   (for example your `@srmap.edu.in` address).
4. **Data access**: add the scopes `.../auth/classroom.courses.readonly` and
   `.../auth/classroom.coursework.me.readonly`.
5. **Clients → Create client → Web application**. Under **Authorized redirect URIs** add
   `http://localhost:8080/classroom/callback` (change the port if you changed it).
6. Copy the client ID and secret into `planner.properties`:
   `google.client.id=...` and `google.client.secret=...`, then restart Margin.

Notes:
- While the Google app is in "Testing", Google ends the connection after 7 days. Click Connect again when that happens.
- If Google says **"Access blocked"** for your college account, your college's Google admin
  hasn't allowed outside apps. Copy your Classroom to-do list into **Quick add with AI** instead.

## What it does

- **This week**: your timetable in pencil grey, planned study in each subject's highlighter
  colour, a red line for now. Select a block to read why it was placed there, and mark it done
  or missed. Blocks that have passed are listed so you can say how they went.
- **Plan**: preview the next seven days in one of three styles (Balanced, Deadlines first,
  Exam revision), see which tasks won't fit and why, read the ranking, then save. Saving
  replaces only blocks that haven't started.
- **Tasks**, **Exams** (with a chapter checklist), **Timetable** (overlaps are refused),
  **Subjects** (difficulty and colour), **Settings** (wake and sleep times, focus hours, daily
  limit, session lengths, breaks, weekends).

## How the planner decides

Each open task gets a score out of 100:

| Factor | Weight |
|---|---|
| How close the deadline is | 35% |
| The priority you set | 25% |
| How close its subject's exam is | 20% |
| How hard the subject is | 10% |
| Sessions already missed, or overdue | 10% |

Tasks are placed in order of score into free time: inside your waking hours, never over a class
or commitment, within the daily limit, with breaks between sessions, and with at most a set
number of hard-subject sessions per day. Long tasks are split into parts if you allow it.
The three styles differ in where blocks go: the first free gap (Deadlines first), the lightest
day inside your focus hours (Balanced), or revision blocks spread over the days before each
exam (Exam revision). Anything that can't be fully placed before its deadline is reported,
with the reason.

## How it's built

```
Browser ── HTML forms, no client framework
   │
planner.http   JDK HttpServer, router, sessions, CSRF tokens, security headers
planner.web    pages rendered from Java text blocks (WeekGrid draws the timetable)
planner.domain Store (all SQL, every query scoped to the signed-in user), Planner
planner.engine scheduling engine: PriorityCalculator, FreeTimeFinder, three strategies
planner.db     small connection pool, migrations in src/main/resources/db
   │
PostgreSQL
```

- Passwords are hashed with PBKDF2-SHA256 (600,000 rounds). Sessions are stored in the
  database in an HttpOnly cookie.
- Every form carries a CSRF token. Another account's records return 404.
- The interface works without JavaScript. `app.js` adds the extras: instant ticking with confetti, toasts, task filtering, light/dark theme (press `d`), and keyboard shortcuts (press `?`).
- The Recursive typeface is bundled (SIL Open Font License), so nothing loads from the internet.

`java Build.java test` runs 81 engine checks and 66 web, import, AI and Classroom checks against a real
PostgreSQL database (Gemini and Google are not called in tests).
