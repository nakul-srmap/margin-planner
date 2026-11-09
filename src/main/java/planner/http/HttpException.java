package planner.http;

/** Aborts a request with a status and a message the student can read. */
public class HttpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static HttpException notFound() {
        return new HttpException(404, "That page doesn't exist, or it belongs to another account.");
    }
}
