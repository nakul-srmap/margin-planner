package planner.web;

import planner.http.Html;
import planner.http.Request;

/** The page frame: masthead, navigation, flash message and footer. */
final class Layout {

    private Layout() { }

    private static final String[][] NAV = {
            {"/", "This week", "<rect x='3' y='5' width='18' height='16' rx='3'/><path d='M3 10h18M8 3v4M16 3v4'/>"},
            {"/plan", "Plan", "<path d='M12 3l2.2 5.3L20 9l-4.4 3.8L17 19l-5-3.1L7 19l1.4-6.2L4 9l5.8-.7z'/>"},
            {"/tasks", "Tasks", "<rect x='3' y='3' width='18' height='18' rx='4'/><path d='M8 12l3 3 5-6'/>"},
            {"/exams", "Exams", "<path d='M4 21V4h11l-1 4 5 1-2 7h-9'/><path d='M4 4v17'/>"},
            {"/timetable", "Timetable", "<rect x='3' y='3' width='18' height='18' rx='3'/><path d='M3 9h18M3 15h18M9 3v18M15 3v18'/>"},
            {"/subjects", "Subjects", "<path d='M4 5a2 2 0 012-2h13v15H6a2 2 0 00-2 2z'/><path d='M4 20a2 2 0 002 1h13v-3'/>"},
            {"/settings", "Settings", "<path d='M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0'/><circle cx='16' cy='6' r='2'/><circle cx='10' cy='12' r='2'/><circle cx='18' cy='18' r='2'/>"},
    };

    private static String icon(String paths) {
        return "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' aria-hidden='true'>"
                + paths + "</svg>";
    }

    private static final String THEME_BUTTON = "<button class=\"icon-button\" type=\"button\" data-theme-toggle aria-label=\"Switch light or dark theme\" title=\"Switch theme (d)\">"
            + icon("<path d='M12 3a6 6 0 009 9 9 9 0 11-9-9z'/>") + "</button>";

    static String page(Request request, String active, String title, String body) {
        StringBuilder nav = new StringBuilder();
        String account = "";
        if (request.signedIn()) {
            for (String[] item : NAV) {
                nav.append("<a href=\"").append(item[0]).append('"')
                        .append(item[0].equals(active) ? " aria-current=\"page\"" : "")
                        .append(" title=\"").append(item[1]).append("\">").append(icon(item[2]))
                        .append("<span>").append(item[1]).append("</span></a>");
            }
            account = Html.t("""
                    <div class="account">
                      {{{theme}}}
                      <span class="avatar" aria-hidden="true">{{initial}}</span>
                      <span>{{name}}</span>
                      <form method="post" action="/signout">{{{csrf}}}<button class="link-button" type="submit">Sign out</button></form>
                    </div>""", "theme", THEME_BUTTON, "initial", request.session().firstName().substring(0, 1).toUpperCase(),
                    "name", request.session().firstName(), "csrf", Html.csrf(request));
        } else {
            account = "<div class=\"account\">" + THEME_BUTTON + "</div>";
        }
        String flash = request.flash() == null ? ""
                : "<div class=\"toast\" role=\"status\">" + Html.esc(request.flash()) + "</div>";

        return Html.t("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>{{title}} | Margin</title>
                  <link rel="icon" href="/favicon.svg" type="image/svg+xml">
                  <link rel="preload" href="/recursive.woff2" as="font" type="font/woff2" crossorigin>
                  <script src="/theme.js"></script>
                  <link rel="stylesheet" href="/app.css">
                  <script src="/app.js" defer></script>
                </head>
                <body>
                  <header class="masthead">
                    <div class="masthead-inner">
                      <a class="wordmark" href="/"><i aria-hidden="true"></i><span>Margin</span></a>
                      <nav class="nav" aria-label="Main">{{{nav}}}</nav>
                      {{{account}}}
                    </div>
                  </header>
                  <div class="toasts">{{{flash}}}</div>
                  <main id="main" class="rise">
                {{{body}}}
                  </main>
                  <footer class="colophon">Margin plans study around your classes. Your data stays in your own database.</footer>
                </body>
                </html>""",
                "title", title, "nav", nav.toString(), "account", account, "flash", flash, "body", body);
    }

    /** A page heading with an optional sub-line and an optional block of actions on the right. */
    static String head(String title, String sub, String actions) {
        return Html.t("""
                <div class="page-head">
                  <div>
                    <h1>{{title}}</h1>{{{sub}}}
                  </div>{{{actions}}}
                </div>""",
                "title", title,
                "sub", sub == null ? "" : "<p class=\"sub\">" + Html.esc(sub) + "</p>",
                "actions", actions == null ? "" : "<div class=\"actions\">" + actions + "</div>");
    }

    /** A one-button form, used for every action that changes data. */
    static String button(Request request, String action, String label, String cls, String confirm, String... hidden) {
        StringBuilder fields = new StringBuilder(Html.csrf(request));
        for (int i = 0; i < hidden.length; i += 2) {
            fields.append("<input type=\"hidden\" name=\"").append(Html.esc(hidden[i]))
                    .append("\" value=\"").append(Html.esc(hidden[i + 1])).append("\">");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/sessions/(\\d+)/(done|missed)$").matcher(action);
        String async = m.matches() ? " data-async=\"session\"" : "";
        return Html.t("""
                <form method="post" action="{{action}}"{{{async}}}{{{confirm}}}>{{{fields}}}<button class="{{cls}}" type="submit">{{label}}</button></form>""",
                "action", action, "async", async, "fields", fields.toString(), "cls", cls, "label", label,
                "confirm", confirm == null ? "" : " data-confirm=\"" + Html.esc(confirm) + "\"");
    }

    static String hl(String code, Object highlighter) {
        if (code == null) {
            return "";
        }
        return "<span class=\"hl hl-" + Html.esc(highlighter) + "\">" + Html.esc(code) + "</span>";
    }
}
