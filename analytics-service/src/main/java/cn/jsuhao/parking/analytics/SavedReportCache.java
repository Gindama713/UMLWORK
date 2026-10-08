package cn.jsuhao.parking.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class SavedReportCache {
    private static final Logger log = LoggerFactory.getLogger(SavedReportCache.class);
    private static final String PREFIX = "parking:analytics:report:v1:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    private final Duration ttl;

    SavedReportCache(StringRedisTemplate redis, @Value("${parking.analytics.redis.ttl}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Redis cache TTL must be positive");
        this.redis = redis;
        this.ttl = ttl;
    }

    TrafficReport find(String id) {
        try {
            String value = redis.opsForValue().get(PREFIX + id);
            return value == null ? null : json.readValue(value, TrafficReport.class);
        } catch (RuntimeException | JsonProcessingException error) {
            log.warn("Redis report cache read skipped: {}", error.toString());
            return null;
        }
    }

    void put(TrafficReport report) {
        try {
            redis.opsForValue().set(PREFIX + report.reportId(), json.writeValueAsString(report), ttl);
        } catch (RuntimeException | JsonProcessingException error) {
            log.warn("Redis report cache write skipped: {}", error.toString());
        }
    }
}
