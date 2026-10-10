package cn.jsuhao.parking.analytics;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class TrafficErrors {
    private static final Logger log = LoggerFactory.getLogger(TrafficErrors.class);

    @ExceptionHandler(TrafficFailure.class)
    ResponseEntity<Envelope<Void>> business(TrafficFailure error) {
        return ResponseEntity.status(error.status).body(new Envelope<>(error.code,
                error.getMessage(), null, UUID.randomUUID().toString()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Envelope<Void>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new Envelope<>("INVALID_ARGUMENT",
                "请求参数无效", null, UUID.randomUUID().toString()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Envelope<Void>> database(DataAccessException error) {
        log.error("Analytics database failure", error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Envelope<>(
                "DEPENDENCY_UNAVAILABLE", "报表数据库不可用", null, UUID.randomUUID().toString()));
    }
}
