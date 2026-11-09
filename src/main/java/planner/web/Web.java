package planner.web;

import planner.auth.Accounts;
import planner.domain.Planner;
import planner.domain.Store;
import planner.http.HttpException;
import planner.http.Html;
import planner.http.Request;
import planner.http.Router;

import java.time.Clock;

/** Every page and action in the app, wired onto one router. */
public final class Web {

    private Web() { }

    public static Router routes(Store store, Planner planner, Accounts accounts, Clock clock) {
        Ctx ctx = new Ctx(store, planner, clock);
        Router router = new Router();
        new AuthPages(accounts).routes(router);
        new WeekPages(ctx).routes(router);
        new TaskPages(ctx).routes(router);
        new SetupPages(ctx).routes(router);
        new ImportPages(ctx).routes(router);
        SmartPages smart = new SmartPages(ctx);
        smart.routes(router);
        TaskPages.smart = smart;
        return router;
    }

    /** The page shown for 403, 404, 405 and 500. */
    public static String errorPage(Request request, HttpException e) {
        String title = switch (e.status()) {
            case 404 -> "There's nothing here";
            case 403 -> "That didn't go through";
            case 500 -> "Something went wrong on our side";
            default -> "That didn't work";
        };
        String message = e.status() == 404
                ? "The page may have been deleted, or the link is wrong."
                : e.getMessage();
        String body = Html.t("""
                <section class="error-page">
                  <h1>{{title}}</h1>
                  <p>{{message}}</p>
                  <a class="button" href="/">Go to this week</a>
                </section>""", "title", title, "message", message);
        return Layout.page(request, "", title, body);
    }
}
