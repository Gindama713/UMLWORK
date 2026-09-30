package cn.jsuhao.parking.analytics;

import feign.FeignException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/v1/analytics")
class TrafficController {
    private final TrafficService service;

    TrafficController(TrafficService service) {
        this.service = service;
    }

    @GetMapping("/traffic")
    Envelope<TrafficReport> traffic(@RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to, @RequestParam(defaultValue = "HOUR") String granularity) {
        return Envelope.ok(service.generate(from, to, granularity));
    }

    @GetMapping("/traffic-preview")
    Envelope<TrafficReport> preview(@RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to, @RequestParam(defaultValue = "HOUR") String granularity) {
        return Envelope.ok(service.preview(from, to, granularity));
    }

    @GetMapping("/traffic-reports/{id}")
    Envelope<TrafficReport> saved(@PathVariable String id) {
        return Envelope.ok(service.saved(id));
    }
}

@FeignClient(name = "access-service", url = "${clients.access.url:}")
interface AccessTrafficClient {
    @GetMapping("/internal/v1/access/sessions")
    Envelope<List<TrafficSession>> sessions(@RequestParam("from") String from,
            @RequestParam("to") String to);
}

@org.springframework.stereotype.Service
class TrafficService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final AccessTrafficClient access;
    private final JdbcTemplate jdbc;

    TrafficService(AccessTrafficClient access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    @Transactional
    TrafficReport generate(OffsetDateTime from, OffsetDateTime to, String granularity) {
        TrafficReport draft = preview(from, to, granularity);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO traffic_report(id,range_start,range_end,granularity,total_entries,peak_start)
                VALUES (?,?,?,?,?,?)
                """, id, utc(from), utc(to), granularity, draft.totalEntries(),
                draft.peakBucket() == null ? null : utc(draft.peakBucket().start()));
        for (TrafficBucket bucket : draft.buckets())
            jdbc.update("INSERT INTO traffic_bucket(report_id,bucket_start,entry_count) VALUES (?,?,?)",
                    id, utc(bucket.start()), bucket.count());
        return new TrafficReport(id, from, to, granularity, draft.buckets(),
                draft.peakBucket(), draft.totalEntries());
    }

    TrafficReport preview(OffsetDateTime from, OffsetDateTime to, String granularity) {
        if (from == null || to == null || !to.toInstant().isAfter(from.toInstant())
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || !("HOUR".equals(granularity) || "DAY".equals(granularity)))
            throw new TrafficFailure("INVALID_ARGUMENT", HttpStatus.BAD_REQUEST,
                    "统计范围应为正且不超过 31 天，粒度为 HOUR 或 DAY");
        Envelope<List<TrafficSession>> response;
        try {
            response = access.sessions(from.toString(), to.toString());
        } catch (FeignException error) {
            throw new TrafficFailure("DEPENDENCY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "出入场服务暂不可用");
        }
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new TrafficFailure("DEPENDENCY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "出入场服务响应异常");

        ZonedDateTime cursor = from.atZoneSameInstant(SHANGHAI);
        cursor = "HOUR".equals(granularity)
                ? cursor.truncatedTo(ChronoUnit.HOURS) : cursor.toLocalDate().atStartOfDay(SHANGHAI);
        Map<OffsetDateTime, Integer> counts = new LinkedHashMap<>();
        while (cursor.toInstant().isBefore(to.toInstant())) {
            counts.put(cursor.toOffsetDateTime(), 0);
            cursor = "HOUR".equals(granularity) ? cursor.plusHours(1) : cursor.plusDays(1);
        }
        for (TrafficSession session : response.data()) {
            if (session.entryTime() == null) continue;
            ZonedDateTime local = session.entryTime().atZoneSameInstant(SHANGHAI);
            OffsetDateTime bucket = "HOUR".equals(granularity)
                    ? local.truncatedTo(ChronoUnit.HOURS).toOffsetDateTime()
                    : local.toLocalDate().atStartOfDay(SHANGHAI).toOffsetDateTime();
            counts.computeIfPresent(bucket, (ignored, n) -> n + 1);
        }
        List<TrafficBucket> buckets = counts.entrySet().stream()
                .map(e -> new TrafficBucket(e.getKey(), e.getValue())).toList();
        TrafficBucket peak = buckets.stream().filter(b -> b.count() > 0)
                .max((a, b) -> Integer.compare(a.count(), b.count())).orElse(null);
        int total = buckets.stream().mapToInt(TrafficBucket::count).sum();
        return new TrafficReport(null, from, to, granularity, buckets, peak, total);
    }

    TrafficReport saved(String id) {
        List<TrafficReport> reports = jdbc.query("SELECT * FROM traffic_report WHERE id=?",
                (rs, row) -> {
                    OffsetDateTime from = rs.getObject("range_start", LocalDateTime.class)
                            .atOffset(ZoneOffset.UTC);
                    OffsetDateTime to = rs.getObject("range_end", LocalDateTime.class)
                            .atOffset(ZoneOffset.UTC);
                    List<TrafficBucket> buckets = jdbc.query("""
                            SELECT bucket_start,entry_count FROM traffic_bucket
                            WHERE report_id=? ORDER BY bucket_start
                            """, (brs, i) -> new TrafficBucket(brs.getObject("bucket_start", LocalDateTime.class)
                                    .atOffset(ZoneOffset.UTC)
                                    .atZoneSameInstant(SHANGHAI).toOffsetDateTime(),
                                    brs.getInt("entry_count")), id);
                    TrafficBucket peak = buckets.stream().filter(b -> b.count() > 0)
                            .max((a, b) -> Integer.compare(a.count(), b.count())).orElse(null);
                    return new TrafficReport(id, from, to, rs.getString("granularity"),
                            buckets, peak, rs.getInt("total_entries"));
                }, id);
        if (reports.isEmpty())
            throw new TrafficFailure("NOT_FOUND", HttpStatus.NOT_FOUND, "报表不存在");
        return reports.getFirst();
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return value.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}

record Envelope<T>(String code, String message, T data, String requestId) {
    static <T> Envelope<T> ok(T data) {
        return new Envelope<>("OK", "ok", data, UUID.randomUUID().toString());
    }
}
record TrafficSession(String parkingSessionId, OffsetDateTime entryTime,
                      OffsetDateTime exitTime, String status) {}
record TrafficBucket(OffsetDateTime start, int count) {}
record TrafficReport(String reportId, OffsetDateTime from, OffsetDateTime to, String granularity,
                     List<TrafficBucket> buckets, TrafficBucket peakBucket, int totalEntries) {}

class TrafficFailure extends RuntimeException {
    final String code;
    final HttpStatus status;
    TrafficFailure(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
}

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
