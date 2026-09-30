package cn.jsuhao.parking.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.cloud.nacos.discovery.enabled=false"
})
class BillingServiceTest {
    @Autowired BillingService service;

    @Test
    void billSnapshotPaymentRetryAndInvoiceStayConsistent() {
        String sessionId = UUID.randomUUID().toString();
        BillRequest request = new BillRequest(sessionId,
                OffsetDateTime.parse("2026-10-01T08:00:00+08:00"),
                OffsetDateTime.parse("2026-10-01T10:00:00+08:00"),
                "NONE", 0L, 0L, "NONE");
        var bill = service.createBill(request, UUID.randomUUID().toString());
        assertEquals(1000L, bill.amountDueCents());
        assertEquals(bill, service.getBill(bill.billId()));
        assertEquals(bill, service.createBill(request, UUID.randomUUID().toString()));
        var failed = service.pay(bill.billId(), new PaymentRequest("FAILURE", "SIMULATED"),
                UUID.randomUUID().toString(), false);
        assertEquals("UNPAID", failed.billStatus());
        String paymentKey = UUID.randomUUID().toString();
        var paid = service.pay(bill.billId(), new PaymentRequest("SUCCESS", "SIMULATED"),
                paymentKey, false);
        assertEquals("PAID", paid.billStatus());
        assertEquals(paid, service.pay(bill.billId(),
                new PaymentRequest("SUCCESS", "SIMULATED"), paymentKey, false));
        assertEquals("PAID", service.getBill(bill.billId()).status());
        var invoice = service.requestInvoice(bill.billId(),
                new InvoiceRequest("姜苏豪", null), UUID.randomUUID().toString());
        assertEquals("REQUESTED", invoice.status());
        assertEquals("STATE_CONFLICT", assertThrows(ApiFailure.class,
                () -> service.createBill(new BillRequest(sessionId, request.entryTime(),
                        request.exitTime().plusHours(1), "NONE", 0L, 0L, "NONE"),
                        UUID.randomUUID().toString())).code());
    }
}
