package cn.jsuhao.parking.analytics;

import feign.FeignException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TrafficService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final AccessTrafficClient access;
    private final JdbcTemplate jdbc;
    private final SavedReportCache cache;

    TrafficService(AccessTrafficClient access, JdbcTemplate jdbc, SavedReportCache cache) {
        this.access = access;
        this.jdbc = jdbc;
        this.cache = cache;
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
        TrafficReport cached = cache.find(id);
        if (cached != null) return cached;
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
        TrafficReport report = reports.getFirst();
        cache.put(report);
        return report;
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return value.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
