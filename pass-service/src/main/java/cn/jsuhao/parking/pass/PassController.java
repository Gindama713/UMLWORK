package cn.jsuhao.parking.pass;

import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
class PassController {
    private final PassService service;

    PassController(PassService service) { this.service = service; }

    @PostMapping("/api/v1/passes/reservations")
    ApiResponse<ReservationView> reserve(@RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CreateReservation request) {
        return ApiResponse.ok(service.reserve(key, request));
    }

    @PostMapping("/api/v1/passes/reservations/{id}/pay")
    ApiResponse<ReservationPaymentView> pay(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody SimulatedPayment request) {
        return ApiResponse.ok(service.pay(key, id, request));
    }

    @PostMapping("/api/v1/passes/reservations/{id}/cancel")
    ApiResponse<ReservationCancelView> cancel(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.ok(service.cancel(key, id));
    }

    @PostMapping("/internal/v1/passes/reservations/{id}/consume")
    ApiResponse<ReservationView> consume(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody ConsumeReservation request) {
        return ApiResponse.ok(service.consume(key, id, request));
    }

    @PostMapping("/internal/v1/passes/reservations/{id}/release-consumption")
    ApiResponse<ReservationView> release(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody SessionReference request) {
        return ApiResponse.ok(service.release(key, id, request));
    }

    @PostMapping("/internal/v1/passes/reservations/{id}/refund-remainder")
    ApiResponse<RefundView> refundRemainder(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody RefundRemainder request) {
        return ApiResponse.ok(service.refundRemainder(key, id, request));
    }

    @GetMapping("/internal/v1/passes/eligibility")
    ApiResponse<EligibilityView> eligibility(@RequestParam String plateNumber,
            @RequestParam OffsetDateTime entryTime, @RequestParam OffsetDateTime exitTime,
            @RequestParam String spaceType, @RequestParam String parkingSessionId) {
        return ApiResponse.ok(service.eligibility(plateNumber, entryTime, exitTime,
                spaceType, parkingSessionId));
    }

    @PostMapping("/api/v1/passes/monthly-passes")
    ApiResponse<MonthlyPassView> createMonthly(@RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CreateMonthlyPass request) {
        return ApiResponse.ok(service.createMonthly(key, request));
    }

    @GetMapping("/api/v1/passes/monthly-passes/{id}")
    ApiResponse<MonthlyPassView> monthly(@PathVariable String id) {
        return ApiResponse.ok(service.monthlyView(id));
    }

    @GetMapping("/api/v1/passes/monthly-passes/{id}/ledger")
    ApiResponse<java.util.List<MonthlyLedgerView>> ledger(@PathVariable String id) {
        return ApiResponse.ok(service.monthlyLedger(id));
    }

    @PostMapping("/internal/v1/passes/monthly-passes/{id}/deductions")
    ApiResponse<DeductionView> deduct(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Deduction request) {
        return ApiResponse.ok(service.deduct(key, id, request));
    }
}

record CreateReservation(String plateNumber, String spaceId, OffsetDateTime startTime, OffsetDateTime endTime) {}
record SimulatedPayment(String simulatedResult) {}
record ConsumeReservation(String plateNumber, OffsetDateTime entryTime, String parkingSessionId) {}
record SessionReference(String parkingSessionId) {}
record RefundRemainder(String parkingSessionId, Long amountCents) {}
record CreateMonthlyPass(String plateNumber, OffsetDateTime startTime) {}
record Deduction(String parkingSessionId, Long amountCents) {}

record ReservationView(String reservationId, String spaceId, String status, long prepaidCents) {}
record ReservationPaymentView(String reservationId, String status, String paymentId) {}
record ReservationCancelView(String reservationId, String status, long refundCents) {}
record RefundView(String reservationId, String parkingSessionId, long refundCents, String refundId) {}
record MonthlyPassView(String monthlyPassId, String plateNumber, OffsetDateTime endTime,
        long balanceCents, String status) {}
record EligibilityView(String benefitType, long prepaidCents, String monthlyPassId) {}
record DeductionView(String deductionId, long balanceCents) {}
record MonthlyLedgerView(String entryId, String parkingSessionId, String kind,
        long amountCents, long balanceAfterCents, OffsetDateTime createdAt) {}
