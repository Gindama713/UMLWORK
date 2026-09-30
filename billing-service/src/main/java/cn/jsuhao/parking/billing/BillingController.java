package cn.jsuhao.parking.billing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BillingController {
    private final BillingService billing;

    BillingController(BillingService billing) { this.billing = billing; }

    @PostMapping("/internal/v1/billing/bills")
    ApiResponse<BillView> createBill(@RequestBody BillRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(billing.createBill(request, key), http);
    }

    @GetMapping("/internal/v1/billing/bills/{billId}")
    ApiResponse<BillView> getBill(@PathVariable String billId, HttpServletRequest http) {
        return ApiResponse.ok(billing.getBill(billId), http);
    }

    @GetMapping("/api/v1/billing/bills/{billId}")
    ApiResponse<BillView> publicBill(@PathVariable String billId, HttpServletRequest http) {
        return ApiResponse.ok(billing.getBill(billId), http);
    }

    @PostMapping("/internal/v1/billing/bills/{billId}/payments")
    ApiResponse<PaymentView> internalPay(@PathVariable String billId, @RequestBody PaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(billing.pay(billId, request, key, true), http);
    }

    @PostMapping("/api/v1/billing/bills/{billId}/payments")
    ApiResponse<PaymentView> publicPay(@PathVariable String billId, @RequestBody PaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(billing.pay(billId, request, key, false), http);
    }

    @PostMapping("/api/v1/billing/bills/{billId}/invoice-requests")
    ApiResponse<InvoiceView> invoice(@PathVariable String billId, @RequestBody InvoiceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(billing.requestInvoice(billId, request, key), http);
    }
}

record ApiResponse<T>(String code, String message, T data, String requestId) {
    static <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return new ApiResponse<>("OK", "success", data, requestId(request));
    }

    static String requestId(HttpServletRequest request) {
        String provided = request.getHeader("X-Request-Id");
        if (provided != null && !provided.isBlank()) return provided;
        Object existing = request.getAttribute("billingRequestId");
        if (existing != null) return existing.toString();
        String generated = UUID.randomUUID().toString();
        request.setAttribute("billingRequestId", generated);
        return generated;
    }
}
