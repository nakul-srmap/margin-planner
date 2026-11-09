package planner.auth;

/** A signed-in browser: who it is, and the token its forms must carry. */
public record Session(String id, long userId, String csrf, String name, String email) {

    public String firstName() {
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }
}
