package cn.jsuhao.parking.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analytics_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.cloud.nacos.discovery.enabled=false"
})
class TrafficServiceTest {
    @Autowired TrafficService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AccessTrafficClient access;
    @MockitoBean SavedReportCache cache;

    @Test
    void reportCountsEntriesAndSavedSnapshotMatches() {
        var first = OffsetDateTime.parse("2026-10-01T08:05:00+08:00");
        var second = OffsetDateTime.parse("2026-10-01T08:45:00+08:00");
        var third = OffsetDateTime.parse("2026-10-01T09:01:00+08:00");
        when(access.sessions(anyString(), anyString())).thenReturn(Envelope.ok(List.of(
                new TrafficSession("s1", first, null, "PARKED"),
                new TrafficSession("s2", second, null, "CLOSED"),
                new TrafficSession("s3", third, null, "PARKED"))));
        var from = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
        var to = OffsetDateTime.parse("2026-10-01T10:00:00+08:00");
        var preview = service.preview(from, to, "HOUR");
        assertThrows(TrafficFailure.class, () -> service.preview(from, from.plusDays(31).plusSeconds(1), "DAY"));
        assertNull(preview.reportId());
        assertEquals(3, preview.totalEntries());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM traffic_report", Integer.class));
        var report = service.generate(from,
                to, "HOUR");
        assertEquals(3, report.totalEntries());
        assertEquals(2, report.peakBucket().count());
        assertEquals(2, report.buckets().size());
        assertEquals(report.buckets(), service.saved(report.reportId()).buckets());
        verify(cache).put(argThat(saved -> report.reportId().equals(saved.reportId())));
    }

    @Test
    void savedReportCacheHitDoesNotRequireMysqlRow() {
        var from = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
        var cached = new TrafficReport("cached-only", from, from.plusHours(1), "HOUR",
                List.of(new TrafficBucket(from, 2)), new TrafficBucket(from, 2), 2);
        when(cache.find("cached-only")).thenReturn(cached);
        assertEquals(cached, service.saved("cached-only"));
    }
}
