package cn.jsuhao.parking.access;

import static cn.jsuhao.parking.access.ApiModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:access_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.cloud.nacos.discovery.enabled=false"
})
class AccessServiceTest {
    @Autowired AccessService service;
    @Autowired AccessRepository repository;
    @MockitoBean SpaceClient spaces;
    @MockitoBean BillingClient billing;
    @MockitoBean PassClient passes;

    @Test
    void entryPaymentFailureThenRetryClosesExactlyOneSession() {
        String spaceId = UUID.randomUUID().toString();
        String billId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        SpaceView available = new SpaceView(spaceId, "NORMAL", "B1", "A", "A-001", "AVAILABLE", null);
        when(spaces.available("NORMAL")).thenReturn(Envelope.ok(List.of(available)));
        when(spaces.occupy(eq(spaceId), anyString(), any())).thenAnswer(call -> {
            SessionRef ref = call.getArgument(2);
            return Envelope.ok(new SpaceView(spaceId, "NORMAL", "B1", "A", "A-001",
                    "OCCUPIED", ref.parkingSessionId()));
        });
        when(spaces.release(eq(spaceId), anyString(), any()))
                .thenReturn(Envelope.ok(available));
        when(spaces.settle(anyString(), anyString(), any())).thenAnswer(call ->
                Envelope.ok(new ChargingFees(call.<SessionRef>getArgument(2).parkingSessionId(), 0)));
        when(passes.eligibility(anyString(), anyString(), anyString(), eq("NORMAL"), anyString()))
                .thenReturn(Envelope.ok(new BenefitView("NONE", 0, null)));
        when(billing.createBill(anyString(), any()))
                .thenAnswer(call -> Envelope.ok(new BillView(billId, call.<BillRequest>getArgument(1)
                        .parkingSessionId(), "UNPAID", 1000, 0)));
        when(billing.bill(billId)).thenReturn(Envelope.ok(new BillView(billId, "",
                "UNPAID", 1000, 0)));
        when(billing.pay(eq(billId), anyString(), any())).thenAnswer(call -> {
            PaymentRequest request = call.getArgument(2);
            String status = request.simulatedResult().equals("SUCCESS") ? "PAID" : "UNPAID";
            return Envelope.ok(new PaymentView(paymentId, billId, status,
                    request.simulatedResult(), request.paymentSource(), 1000));
        });

        OffsetDateTime entryTime = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
        String key = UUID.randomUUID().toString();
        var first = service.enter(new EntryRequest("苏A12345", "NORMAL", null, entryTime), key);
        assertEquals(first, service.enter(new EntryRequest("苏A12345", "NORMAL", null, entryTime), key));
        assertEquals("A-001", service.locate("苏A12345").spaceNumber());
        assertEquals("ACTIVE_SESSION_EXISTS", assertThrows(ApiFailure.class,
                () -> service.enter(new EntryRequest("苏A12345", "NORMAL", null, entryTime),
                        UUID.randomUUID().toString())).code());

        var exitInput = new ExitRequest(entryTime.plusHours(2), "NONE", null);
        doThrow(remote(409, "STATE_CONFLICT")).when(spaces).settle(anyString(), anyString(), any());
        var activeCharging = assertThrows(ApiFailure.class, () -> service.requestExit(
                first.parkingSessionId(), exitInput, UUID.randomUUID().toString()));
        assertEquals("STATE_CONFLICT", activeCharging.code());
        assertEquals(409, activeCharging.status().value());
        verify(billing, never()).createBill(anyString(), any());
        doReturn(Envelope.ok(null)).when(spaces).settle(anyString(), anyString(), any());
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(ApiFailure.class, () -> service.requestExit(
                first.parkingSessionId(), exitInput, UUID.randomUUID().toString())).code());
        doReturn(Envelope.ok(new ChargingFees(first.parkingSessionId(), 0)))
                .when(spaces).settle(anyString(), anyString(), any());
        doThrow(remote(503, "DEPENDENCY_UNAVAILABLE")).when(billing).createBill(anyString(), any());
        String exitKey = UUID.randomUUID().toString();
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(ApiFailure.class, () -> service.requestExit(
                first.parkingSessionId(), exitInput, exitKey)).code());
        assertEquals("EXIT_PENDING_PAYMENT", service.locate("苏A12345").status());
        assertEquals(null, service.locate("苏A12345").billId());
        assertEquals(exitInput.exitTime().toInstant(), service.locate("苏A12345").exitTime().toInstant());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiFailure.class, () -> service.requestExit(
                first.parkingSessionId(), new ExitRequest(entryTime.plusHours(3), "NONE", null), exitKey)).code());
        assertEquals("STATE_CONFLICT", assertThrows(ApiFailure.class, () -> service.requestExit(
                first.parkingSessionId(), new ExitRequest(entryTime.plusHours(3), "NONE", null), UUID.randomUUID().toString())).code());
        doReturn(Envelope.ok(new BillView(billId, first.parkingSessionId(), "UNPAID", 1000, 0)))
                .when(billing).createBill(anyString(), any());
        var exit = service.requestExit(first.parkingSessionId(), exitInput, exitKey);
        assertEquals(billId, exit.billId());
        String paymentKey = UUID.randomUUID().toString();
        assertEquals("EXIT_PENDING_PAYMENT", service.completeExit(first.parkingSessionId(),
                new CompleteRequest("FAILURE"), paymentKey).status());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiFailure.class, () -> service.completeExit(
                first.parkingSessionId(), new CompleteRequest("SUCCESS"), paymentKey)).code());
        assertEquals("CLOSED", service.completeExit(first.parkingSessionId(),
                new CompleteRequest("SUCCESS"), UUID.randomUUID().toString()).status());
        verify(spaces, times(1)).occupy(eq(spaceId), anyString(), any());
        verify(spaces, times(1)).release(eq(spaceId), anyString(), any());
        assertEquals("NOT_FOUND", assertThrows(ApiFailure.class,
                () -> service.locate("苏A12345")).code());
        String pendingId = UUID.randomUUID().toString();
        repository.insert(pendingId, "苏A12346", "NORMAL", null, entryTime, UUID.randomUUID().toString(), "pending-demo");
        assertEquals(1, service.traffic(entryTime, entryTime.plusDays(1)).size());
        repository.entryFailed(pendingId);
        assertEquals(1, service.traffic(entryTime, entryTime.plusDays(1)).size());
    }

    private static FeignException remote(int status, String code) {
        return FeignException.errorStatus("settle", Response.builder().status(status).reason("test")
                .request(Request.create(Request.HttpMethod.POST, "http://space/settlement", Map.of(), null,
                        StandardCharsets.UTF_8, null)).headers(Map.of())
                .body("{\"code\":\"" + code + "\",\"message\":\"test rejection\"}", StandardCharsets.UTF_8).build());
    }
}
