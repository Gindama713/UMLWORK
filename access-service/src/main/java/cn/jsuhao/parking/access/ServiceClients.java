package cn.jsuhao.parking.access;

import static cn.jsuhao.parking.access.ApiModels.*;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

final class ServiceClients {
    private ServiceClients() {}
}

@FeignClient(name = "space-service", url = "${clients.space.url:}")
interface SpaceClient {
    @GetMapping("/internal/v1/spaces/available")
    Envelope<List<SpaceView>> available(@RequestParam("type") String type);

    @PostMapping("/internal/v1/spaces/{spaceId}/occupy")
    Envelope<SpaceView> occupy(@PathVariable("spaceId") String spaceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SessionRef body);

    @PostMapping("/internal/v1/spaces/{spaceId}/release")
    Envelope<SpaceView> release(@PathVariable("spaceId") String spaceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SessionRef body);

    @PostMapping("/internal/v1/spaces/{spaceId}/settlement")
    Envelope<ChargingFees> settle(@PathVariable("spaceId") String spaceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SessionRef body);
}

@FeignClient(name = "billing-service", url = "${clients.billing.url:}")
interface BillingClient {
    @PostMapping("/internal/v1/billing/bills")
    Envelope<BillView> createBill(@RequestHeader("Idempotency-Key") String key,
            @RequestBody BillRequest body);

    @GetMapping("/internal/v1/billing/bills/{billId}")
    Envelope<BillView> bill(@PathVariable("billId") String billId);

    @PostMapping("/internal/v1/billing/bills/{billId}/payments")
    Envelope<PaymentView> pay(@PathVariable("billId") String billId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody PaymentRequest body);
}

@FeignClient(name = "pass-service", url = "${clients.pass.url:}")
interface PassClient {
    @PostMapping("/internal/v1/passes/reservations/{id}/consume")
    Envelope<ReservationView> consume(@PathVariable("id") String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody ReservationUse body);

    @PostMapping("/internal/v1/passes/reservations/{id}/release-consumption")
    Envelope<ReservationView> releaseConsumption(@PathVariable("id") String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SessionRef body);

    @PostMapping("/internal/v1/passes/reservations/{id}/refund-remainder")
    Envelope<Object> refundRemainder(@PathVariable("id") String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Refund body);

    @GetMapping("/internal/v1/passes/eligibility")
    Envelope<BenefitView> eligibility(@RequestParam("plateNumber") String plate,
            @RequestParam("entryTime") String entry, @RequestParam("exitTime") String exit,
            @RequestParam("spaceType") String spaceType,
            @RequestParam("parkingSessionId") String parkingSessionId);

    @PostMapping("/internal/v1/passes/monthly-passes/{id}/deductions")
    Envelope<Object> deduct(@PathVariable("id") String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody DeductionRequest body);
}
