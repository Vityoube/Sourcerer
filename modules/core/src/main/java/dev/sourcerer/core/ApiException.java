package dev.sourcerer.core;

/** Error carrying an HTTP status. Stackless: cheap to throw on hot paths (404s etc.). */
public final class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    public int status() { return status; }

    public static ApiException badRequest(String m) { return new ApiException(400, m); }
    public static ApiException unauthorized(String m) { return new ApiException(401, m); }
    public static ApiException forbidden(String m) { return new ApiException(403, m); }
    public static ApiException notFound(String m) { return new ApiException(404, m); }
    public static ApiException conflict(String m) { return new ApiException(409, m); }
}
