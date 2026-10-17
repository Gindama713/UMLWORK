package cn.jsuhao.parking.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TrafficInsightServiceTest {
    @Test
    void usesOnlySavedAggregateAndNeverTreatsProviderFailureAsInsight() throws Exception {
        TrafficService reports = mock(TrafficService.class);
        OffsetDateTime start = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
        when(reports.saved("report-1")).thenReturn(new TrafficReport("report-1", start,
                start.plusHours(2), "HOUR", List.of(new TrafficBucket(start, 2),
                        new TrafficBucket(start.plusHours(1), 1)), new TrafficBucket(start, 2), 3));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> sent = new AtomicReference<>();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/chat/completions", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = calls.incrementAndGet();
            if (call == 2) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String content = call == 1
                    ? "{\"summary\":\"上午八点时段入场较集中\",\"actions\":[\"人工核对该时段排班\",\"继续观察更多日期\"]}"
                    : "{\"summary\":\"\",\"actions\":[]}";
            String response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(content) + "}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fake.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + fake.getAddress().getPort()
                    + "/chat/completions");
            TrafficInsightService service = new TrafficInsightService(reports, endpoint,
                    "deepseek-flash", "fake-key", true, 3);
            TrafficInsight insight = service.generate("report-1");
            assertEquals("AI_ADVISORY", insight.source());
            assertEquals(2, insight.actions().size());
            assertTrue(sent.get().contains("totalEntries"));
            assertTrue(sent.get().contains("busiestBuckets"));
            assertFalse(sent.get().contains("plateNumber"));
            assertFalse(sent.get().contains("parkingSessionId"));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                    assertThrows(TrafficFailure.class, () -> service.generate("report-1")).status);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                    assertThrows(TrafficFailure.class, () -> service.generate("report-1")).status);
            TrafficInsightService disabled = new TrafficInsightService(reports, endpoint,
                    "deepseek-flash", "", false, 3);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                    assertThrows(TrafficFailure.class, () -> disabled.generate("report-1")).status);
            assertEquals(3, calls.get());
        } finally {
            fake.stop(0);
        }
    }
}
