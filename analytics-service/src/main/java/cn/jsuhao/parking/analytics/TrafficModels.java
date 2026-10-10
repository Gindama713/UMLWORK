package cn.jsuhao.parking.analytics;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

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
