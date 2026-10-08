package cn.jsuhao.parking.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SavedReportCacheTest {
    @Test
    @SuppressWarnings("unchecked")
    void preservesReportSnapshotAndTreatsRedisFailureAsCacheMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        SavedReportCache cache = new SavedReportCache(redis, Duration.ofMinutes(5));
        OffsetDateTime start = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
        TrafficBucket bucket = new TrafficBucket(start, 2);
        TrafficReport report = new TrafficReport("report-1", start, start.plusHours(1), "HOUR",
                List.of(bucket), bucket, 2);

        cache.put(report);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        String key = "parking:analytics:report:v1:report-1";
        verify(values).set(eq(key), payload.capture(), eq(Duration.ofMinutes(5)));
        when(values.get(key)).thenReturn(payload.getValue());
        assertEquals(report, cache.find("report-1"));

        when(values.get(key)).thenThrow(new RedisConnectionFailureException("offline"));
        assertNull(cache.find("report-1"));
    }
}
