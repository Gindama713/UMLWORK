package cn.jsuhao.parking.access;

import static cn.jsuhao.parking.access.ApiModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TravelAdviceServiceTest {
    private final SpaceClient spaces = mock(SpaceClient.class);
    private final BillingClient billing = mock(BillingClient.class);
    private final OffsetDateTime entry = OffsetDateTime.parse("2026-11-02T08:00:00+08:00");
    private final OffsetDateTime exit = OffsetDateTime.parse("2026-11-03T08:00:00+08:00");

    @Test
    void returnsRealServiceFactsWithAdvisoryTextOnly() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/chat/completions", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = calls.incrementAndGet() == 1
                    ? "{\\\"summary\\\":\\\"优先查看靠近航站楼的候选位。\\\",\\\"tips\\\":[\\\"核对航班值机要求。\\\",\\\"入场前刷新车位状态。\\\"]}"
                    : "{}";
            byte[] response = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\""
                    + content + "\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        fake.start();
        try {
            SpaceRecommendation space = new SpaceRecommendation("space-1", "NORMAL", "B1", "A",
                    "A-003", "AVAILABLE", 6, "演示步行", "demo-v1");
            ParkingEstimate estimate = new ParkingEstimate(6000, 6000, "rate-v1", List.of());
            when(spaces.recommendations("T1", "NORMAL")).thenReturn(Envelope.ok(List.of(space)));
            when(billing.estimate(any())).thenReturn(Envelope.ok(estimate));

            TravelAdviceService service = service(fake.getAddress().getPort(), true);
            TravelAdvice result = service.generate(request());
            assertEquals("AI_ADVISORY", result.source());
            assertEquals(List.of(space), result.recommendations());
            assertEquals(6000, result.estimate().amountDueCents());
            assertEquals(2, result.tips().size());
            assertTrue(sent.get().contains("A-003"));
            assertTrue(sent.get().contains("6000"));
            assertTrue(!sent.get().contains("plateNumber"));
            assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(ApiFailure.class,
                    () -> service.generate(request())).code());
            verify(spaces, org.mockito.Mockito.times(2)).recommendations("T1", "NORMAL");
            verify(billing, org.mockito.Mockito.times(2)).estimate(new TravelEstimateRequest(entry, exit));
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void rejectsInvalidInputAndUnavailableProviderWithoutBusinessCalls() {
        var service = service(1, false);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiFailure.class,
                () -> service.generate(new TravelAdviceRequest("T3", "NORMAL", entry, exit, null, null))).status());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiFailure.class,
                () -> service.generate(new TravelAdviceRequest("T1", "NORMAL", exit, entry, null, null))).status());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ApiFailure.class, () -> service.generate(request())).status());
        verifyNoInteractions(spaces, billing);
    }

    @Test
    void emptyCandidateAndFailedDependencyDoNotBecomeAdvice() {
        var service = service(1, true);
        when(spaces.recommendations("T1", "NORMAL")).thenReturn(Envelope.ok(List.of()));
        when(billing.estimate(any())).thenReturn(Envelope.ok(new ParkingEstimate(1000, 1000, "v1", List.of())));
        assertEquals("SPACE_UNAVAILABLE", assertThrows(ApiFailure.class,
                () -> service.generate(request())).code());
        when(spaces.recommendations("T1", "NORMAL")).thenReturn(null);
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(ApiFailure.class,
                () -> service.generate(request())).code());
        verifyNoInteractions(billing);
    }

    private TravelAdviceRequest request() {
        return new TravelAdviceRequest("T1", "NORMAL", entry, exit,
                OffsetDateTime.parse("2026-11-02T12:00:00+08:00"), "LUGGAGE");
    }

    private TravelAdviceService service(int port, boolean enabled) {
        return new TravelAdviceService(spaces, billing,
                URI.create("http://127.0.0.1:" + port + "/chat/completions"),
                "deepseek-flash", "test-key", enabled, 2);
    }
}
