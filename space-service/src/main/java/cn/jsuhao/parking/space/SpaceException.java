package cn.jsuhao.parking.space;

import org.springframework.http.HttpStatus;

public class SpaceException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public SpaceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }

    public static SpaceException invalid(String message) {
        return new SpaceException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    public static SpaceException notFound(String message) {
        return new SpaceException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static SpaceException unavailable(String message) {
        return new SpaceException(HttpStatus.CONFLICT, "SPACE_UNAVAILABLE", message);
    }

    public static SpaceException conflict(String message) {
        return new SpaceException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
    }
}
