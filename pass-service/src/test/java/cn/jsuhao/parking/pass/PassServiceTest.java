package cn.jsuhao.parking.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pass_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.cloud.nacos.discovery.enabled=false"
})
class PassServiceTest {
    @Autowired PassService service;
    @MockitoBean SpaceClient spaces;

    @Test
    void overlappingReservationIsRejectedAndFailedEntryCanReleaseConsumption() {
        String spaceId = UUID.randomUUID().toString();
        when(spaces.spaces(eq("RESERVATION"))).thenReturn(ApiResponse.ok(List.of(
                new SpaceView(spaceId, "RESERVATION", "P2", "B", "B-018", "AVAILABLE"))));
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        OffsetDateTime end = start.plusHours(2);
        var first = service.reserve(key(), new CreateReservation("苏A12345", spaceId, start, end));
        var adjacent = service.reserve(key(), new CreateReservation("苏A12345", spaceId, end, end.plusHours(1)));
        assertEquals("PENDING_PAYMENT", adjacent.status());
        assertEquals("RESERVATION_CONFLICT", assertThrows(ApiException.class,
                () -> service.reserve(key(), new CreateReservation("苏B67890", spaceId,
                        start.plusMinutes(30), end.plusMinutes(30)))).code());
        assertEquals("CONFIRMED", service.pay(key(), first.reservationId(), new SimulatedPayment("SUCCESS")).status());
        String sessionId = UUID.randomUUID().toString();
        var consumed = service.consume(key(), first.reservationId(),
                new ConsumeReservation("苏A12345", start.plusMinutes(10), sessionId));
        assertEquals(spaceId, consumed.spaceId());
        assertEquals("USED", consumed.status());
        assertEquals("CONFIRMED", service.release(key(), first.reservationId(), new SessionReference(sessionId)).status());
        assertEquals("CONFIRMED", service.release(key(), first.reservationId(), new SessionReference(sessionId)).status());
    }

    @Test
    void monthlyBalanceDeductsExactlyOncePerParkingSession() {
        assertEquals("INVALID_ARGUMENT", assertThrows(ApiException.class, () -> service.createMonthly(
                key(), new CreateMonthlyPass("bad!", OffsetDateTime.now(ZoneOffset.UTC)))).code());
        var monthly = service.createMonthly(key(), new CreateMonthlyPass(
                "苏C12345", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)));
        String sessionId = UUID.randomUUID().toString();
        var first = service.deduct(key(), monthly.monthlyPassId(), new Deduction(sessionId, 700L));
        var retried = service.deduct(key(), monthly.monthlyPassId(), new Deduction(sessionId, 700L));
        assertEquals(first, retried);
        assertEquals(19300L, first.balanceCents());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class,
                () -> service.deduct(key(), monthly.monthlyPassId(), new Deduction(sessionId, 800L))).code());
        assertEquals("PASS_BALANCE_INSUFFICIENT", assertThrows(ApiException.class,
                () -> service.deduct(key(), monthly.monthlyPassId(),
                        new Deduction(UUID.randomUUID().toString(), 20000L))).code());
    }

    @Test
    void simultaneousRequestsCannotReserveTheSameInterval() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        when(spaces.spaces(eq("RESERVATION"))).thenReturn(ApiResponse.ok(List.of(
                new SpaceView(spaceId, "RESERVATION", "P1", "A", "A-009", "AVAILABLE"))));
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var request = (java.util.concurrent.Callable<String>) () -> {
                ready.countDown();
                go.await();
                try {
                    service.reserve(key(), new CreateReservation("苏D12345", spaceId, start, start.plusHours(1)));
                    return "OK";
                } catch (ApiException error) {
                    return error.code();
                }
            };
            var first = pool.submit(request);
            var second = pool.submit(request);
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            assertEquals(List.of("OK", "RESERVATION_CONFLICT"),
                    java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                            .sorted().toList());
        }
    }

    @Test
    void paymentFailureCanRetryAndCancellationRefundsOnce() {
        String spaceId = UUID.randomUUID().toString();
        when(spaces.spaces(eq("RESERVATION"))).thenReturn(ApiResponse.ok(List.of(
                new SpaceView(spaceId, "RESERVATION", "P2", "B", "B-019", "AVAILABLE"))));
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3);
        var reservation = service.reserve(key(), new CreateReservation("苏E12345", spaceId, start, start.plusHours(1)));
        assertEquals("PENDING_PAYMENT",
                service.pay(key(), reservation.reservationId(), new SimulatedPayment("FAILURE")).status());
        assertEquals("CONFIRMED",
                service.pay(key(), reservation.reservationId(), new SimulatedPayment("SUCCESS")).status());
        var cancelled = service.cancel(key(), reservation.reservationId());
        assertEquals(500L, cancelled.refundCents());
        assertEquals(cancelled, service.cancel(key(), reservation.reservationId()));
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
