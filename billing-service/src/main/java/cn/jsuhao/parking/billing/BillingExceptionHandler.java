package cn.jsuhao.parking.billing;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class BillingExceptionHandler {
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiResponse<Void>> known(ApiFailure ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status()).body(new ApiResponse<>(
                ex.code(), ex.getMessage(), null, ApiResponse.requestId(request)));
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<Void>> invalid(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiResponse<>(
                "INVALID_ARGUMENT", ex.getMessage(), null, ApiResponse.requestId(request)));
    }
}

final class ApiFailure extends RuntimeException {
    private final String code;
    private final int status;

    ApiFailure(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}
