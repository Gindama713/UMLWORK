package cn.jsuhao.parking.pass;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

record ApiResponse<T>(String code, String message, T data, String requestId) {
    static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "success", data, UUID.randomUUID().toString());
    }
}

final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() { return status; }
    String code() { return code; }
}

@RestControllerAdvice
class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> business(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiResponse<>(
                exception.code(), exception.getMessage(), null, UUID.randomUUID().toString()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> invalidJson(Exception ignored) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(
                "INVALID_ARGUMENT", "invalid JSON or timestamp", null, UUID.randomUUID().toString()));
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse<Void>> invalidParameter(Exception ignored) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(
                "INVALID_ARGUMENT", "missing or invalid request parameter", null, UUID.randomUUID().toString()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception error) {
        LOG.error("Unhandled pass-service error", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiResponse<>(
                "INTERNAL_ERROR", "internal error", null, UUID.randomUUID().toString()));
    }
}
