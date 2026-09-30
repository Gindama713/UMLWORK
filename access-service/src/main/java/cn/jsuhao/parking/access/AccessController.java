package cn.jsuhao.parking.access;

import static cn.jsuhao.parking.access.ApiModels.*;

import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestController
@RequestMapping
class AccessController {
    private final AccessService service;

    AccessController(AccessService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/access/entries")
    Envelope<SessionView> enter(@RequestBody EntryRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return Envelope.ok(service.enter(request, key));
    }

    @GetMapping("/api/v1/access/locate")
    Envelope<SessionView> locate(@RequestParam String plateNumber) {
        return Envelope.ok(service.locate(plateNumber));
    }

    @PostMapping("/api/v1/access/{sessionId}/exit-requests")
    Envelope<ExitView> requestExit(@PathVariable String sessionId, @RequestBody ExitRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return Envelope.ok(service.requestExit(sessionId, request, key));
    }

    @PostMapping("/api/v1/access/{sessionId}/complete-exit")
    Envelope<CompleteView> complete(@PathVariable String sessionId, @RequestBody CompleteRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return Envelope.ok(service.completeExit(sessionId, request, key));
    }

    @GetMapping("/internal/v1/access/sessions")
    Envelope<List<TrafficSession>> traffic(@RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to) {
        return Envelope.ok(service.traffic(from, to));
    }
}

@RestControllerAdvice
class AccessErrors {
    private static final Logger log = LoggerFactory.getLogger(AccessErrors.class);
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<Envelope<Void>> business(ApiFailure error) {
        return ResponseEntity.status(error.status())
                .body(new Envelope<>(error.code(), error.getMessage(), null,
                        java.util.UUID.randomUUID().toString()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Envelope<Void>> badRequest(Exception error) {
        return ResponseEntity.badRequest().body(new Envelope<>("INVALID_ARGUMENT",
                "请求格式或参数无效", null, java.util.UUID.randomUUID().toString()));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<Envelope<Void>> notFound(Exception error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Envelope<>("NOT_FOUND",
                "接口不存在", null, java.util.UUID.randomUUID().toString()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Envelope<Void>> database(DataAccessException error) {
        log.error("Access database failure", error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Envelope<>(
                "DEPENDENCY_UNAVAILABLE", "停车记录数据库不可用", null,
                java.util.UUID.randomUUID().toString()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Envelope<Void>> unexpected(Exception error) {
        log.error("Unexpected access failure", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Envelope<>(
                "INTERNAL_ERROR", "服务器内部错误", null,
                java.util.UUID.randomUUID().toString()));
    }
}
