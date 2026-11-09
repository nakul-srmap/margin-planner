package planner.web;

import planner.domain.Records.Preferences;
import planner.domain.Text;
import planner.http.Html;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Seven ruled columns with classes shaded in pencil and study drawn in highlighter.
 * Pure markup: block details open with the Popover API, so it works without JavaScript.
 */
final class WeekGrid {

    private WeekGrid() { }

    /**
     * One thing on the grid. {@code slip} is the body of the note that opens when the block
     * is clicked, or null for a block that doesn't open.
     */
    record Cell(LocalDate date, LocalTime start, LocalTime end, boolean fixed, String title,
                String meta, String highlighter, String extraClass, String slip) {

        int minutes() {
            return (int) Duration.between(start, end).toMinutes();
        }
    }

    static String render(LocalDate from, Preferences prefs, List<Cell> cells, LocalDateTime now, boolean drawing) {
        int firstHour = prefs.wake().getHour();
        int lastHour = prefs.sleep().getMinute() > 0 ? prefs.sleep().getHour() + 1 : prefs.sleep().getHour();
        if (lastHour <= firstHour) {
            lastHour = 24;   // sleep after midnight: show to the end of the day
        }
        for (Cell c : cells) {
            firstHour = Math.min(firstHour, c.start().getHour());
            int end = c.end().getMinute() > 0 ? c.end().getHour() + 1 : c.end().getHour();
            lastHour = Math.max(lastHour, end == 0 ? 24 : end);
        }
        int rows = lastHour - firstHour;
        int origin = firstHour * 60;

        StringBuilder heads = new StringBuilder("<div class=\"week-corner\"></div>");
        StringBuilder cols = new StringBuilder();
        StringBuilder slips = new StringBuilder();

        StringBuilder hours = new StringBuilder("<div class=\"week-hours\" aria-hidden=\"true\">");
        for (int h = 1; h < rows; h++) {
            hours.append("<span style=\"top:calc(").append(h).append(" * var(--hour))\">")
                    .append(String.format("%02d:00", firstHour + h)).append("</span>");
        }
        hours.append("</div>");
        cols.append(hours);

        int index = 0;
        for (int d = 0; d < 7; d++) {
            LocalDate date = from.plusDays(d);
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            int study = 0;
            StringBuilder blocks = new StringBuilder();

            for (Cell c : cells) {
                if (!c.date().equals(date)) {
                    continue;
                }
                if (!c.fixed()) {
                    study += c.minutes();
                }
                String id = "slip-" + date + "-" + c.start().toString().replace(":", "") + "-" + (index);
                int from_ = c.start().getHour() * 60 + c.start().getMinute() - origin;
                int to = (c.end().equals(LocalTime.MIDNIGHT) ? 24 * 60 : c.end().getHour() * 60 + c.end().getMinute()) - origin;
                String style = "--from:" + from_ + ";--to:" + to + ";--i:" + index
                        + (c.highlighter() == null ? (c.fixed() ? ";--c:var(--pencil)" : "") : ";--c:var(--hl-" + Html.esc(c.highlighter()) + ")");
                String inner = "<span class=\"t\">" + Html.esc(c.title()) + "</span>"
                        + "<span class=\"m\">" + Html.esc(Text.time(c.start()) + " to " + Text.time(c.end())
                        + (c.meta() == null || c.meta().isEmpty() ? "" : ", " + c.meta())) + "</span>";
                String cls = "block " + (c.fixed() ? "fixed" : "study") + (c.minutes() < 45 ? " short" : "")
                        + (c.extraClass() == null ? "" : " " + c.extraClass());
                if (c.slip() != null) {
                    blocks.append("<button type=\"button\" class=\"").append(cls).append("\" style=\"").append(style)
                            .append("\" popovertarget=\"").append(id).append("\">").append(inner).append("</button>");
                    slips.append("<div id=\"").append(id).append("\" popover class=\"slip\">")
                            .append("<button type=\"button\" class=\"link-button close\" popovertarget=\"").append(id)
                            .append("\" popovertargetaction=\"hide\">Close</button>")
                            .append(c.slip()).append("</div>");
                } else {
                    blocks.append("<div class=\"").append(cls).append("\" style=\"").append(style).append("\">")
                            .append(inner).append("</div>");
                }
                index++;
            }

            if (date.equals(now.toLocalDate())) {
                int at = now.getHour() * 60 + now.getMinute() - origin;
                if (at >= 0 && at <= rows * 60) {
                    blocks.append("<div class=\"now-line\" style=\"--at:").append(at).append("\" title=\"Now\"></div>");
                }
            }

            boolean today = date.equals(now.toLocalDate());
            heads.append("<div class=\"week-day-head").append(today ? " today" : "").append("\">")
                    .append(Text.shortDay(date.getDayOfWeek())).append(" <b>").append(date.getDayOfMonth()).append("</b>")
                    .append(study > 0 ? "<span class=\"load\">" + Text.duration(study) + "</span>" : "")
                    .append("</div>");
            cols.append("<div class=\"week-col").append(weekend ? " weekend" : "")
                    .append(weekend && !prefs.weekendAvailable() ? " off" : "")
                    .append("\" aria-label=\"").append(Text.dayName(date.getDayOfWeek())).append("\">")
                    .append(blocks).append("</div>");
        }

        return Html.t("""
                <div class="week{{{drawing}}}" style="--rows:{{rows}}">
                  <div class="week-inner">{{{heads}}}{{{cols}}}</div>
                </div>
                <p class="legend"><span><i class="l-fixed"></i>Classes and commitments</span><span><i class="l-study"></i>Study, in each subject's colour</span><span>Select a block to see why it's there.</span></p>
                {{{slips}}}""",
                "drawing", drawing ? " drawing" : "", "rows", rows, "heads", heads.toString(),
                "cols", cols.toString(), "slips", slips.toString());
    }
}
