package cn.jsuhao.parking.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BillingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final ParkingPricing pricing;
    private final PricingProperties rates;

    BillingService(JdbcTemplate jdbc, ParkingPricing pricing, PricingProperties rates) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.rates = rates;
    }

    @Transactional
    BillView createBill(BillRequest request, String key) {
        requireKey(key);
        if (request == null) throw new IllegalArgumentException("Bill request is required");
        uuid(request.parkingSessionId(), "parkingSessionId");
        ParkingPricing.PriceBreakdown amount = pricing.calculate(request);
        String hash = hash(Arrays.asList(request.parkingSessionId(),
                request.entryTime().toInstant().toString(), request.exitTime().toInstant().toString(),
                request.benefitType(), request.prepaidCents(), request.chargingCents(),
                request.exceptionType()));
        String billId = UUID.randomUUID().toString();
        String items = serialize(amount.items());
        jdbc.update("""
                INSERT INTO bill (bill_id, parking_session_id, request_hash, create_key,
                    entry_epoch_ms, exit_epoch_ms, status, parking_base_cents, discount_cents,
                    prepaid_cents, prepaid_refund_cents, parking_due_cents, charging_cents,
                    exception_cents, amount_due_cents, rate_version, fee_items_json)
                VALUES (?,?,?,?,?,?,'UNPAID',?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE bill_id=bill_id
                """, billId, request.parkingSessionId(), hash, key,
                request.entryTime().toInstant().toEpochMilli(), request.exitTime().toInstant().toEpochMilli(),
                amount.parkingBaseCents(), amount.discountCents(), amount.prepaidCents(),
                amount.prepaidRefundCents(), amount.parkingDueCents(), amount.chargingCents(),
                amount.exceptionCents(), amount.amountDueCents(), rates.version(), items);
        List<BillView> bySession = jdbc.query("SELECT * FROM bill WHERE parking_session_id=?",
                this::mapBill, request.parkingSessionId());
        if (bySession.isEmpty()) throw new ApiFailure("IDEMPOTENCY_CONFLICT", "Idempotency key belongs to another bill", 409);
        BillView current = bySession.getFirst();
        String existingHash = jdbc.queryForObject("SELECT request_hash FROM bill WHERE bill_id=?",
                String.class, current.billId());
        String existingKey = jdbc.queryForObject("SELECT create_key FROM bill WHERE bill_id=?",
                String.class, current.billId());
        if (!Objects.equals(existingHash, hash)) {
            throw new ApiFailure("STATE_CONFLICT", "Parking session already has a different bill snapshot", 409);
        }
        if (!Objects.equals(existingKey, key)) {
            // parkingSessionId is the natural idempotency key for exit retries.
            return current;
        }
        return current;
    }

    BillView getBill(String billId) {
        uuid(billId, "billId");
        List<BillView> found = jdbc.query("SELECT * FROM bill WHERE bill_id=?", this::mapBill, billId);
        if (found.isEmpty()) throw new ApiFailure("NOT_FOUND", "Bill not found", 404);
        return found.getFirst();
    }

    @Transactional
    PaymentView pay(String billId, PaymentRequest request, String key, boolean allowMonthly) {
        requireKey(key);
        uuid(billId, "billId");
        if (request == null || request.simulatedResult() == null) {
            throw new IllegalArgumentException("simulatedResult is required");
        }
        String result = request.simulatedResult();
        if (!result.equals("SUCCESS") && !result.equals("FAILURE")) {
            throw new IllegalArgumentException("simulatedResult must be SUCCESS or FAILURE");
        }
        String source = request.paymentSource() == null ? "SIMULATED" : request.paymentSource();
        if (!source.equals("SIMULATED") && !source.equals("MONTHLY_BALANCE")) {
            throw new IllegalArgumentException("Unknown paymentSource");
        }
        if (source.equals("MONTHLY_BALANCE") && !allowMonthly) {
            throw new ApiFailure("INVALID_ARGUMENT", "Monthly balance payment is internal only", 400);
        }
        String requestHash = hash(List.of(billId, result, source));
        List<PaymentView> previousKey = paymentByKey(key);
        if (!previousKey.isEmpty()) {
            String original = jdbc.queryForObject("SELECT request_hash FROM payment WHERE idempotency_key=?",
                    String.class, key);
            if (!original.equals(requestHash)) throw new ApiFailure("IDEMPOTENCY_CONFLICT", "Key reused for a different payment", 409);
            return previousKey.getFirst();
        }
        List<BillView> bills = jdbc.query("SELECT * FROM bill WHERE bill_id=? FOR UPDATE", this::mapBill, billId);
        if (bills.isEmpty()) throw new ApiFailure("NOT_FOUND", "Bill not found", 404);
        BillView bill = bills.getFirst();
        if (bill.status().equals("PAID")) {
            return jdbc.query("SELECT p.*, b.status AS bill_status FROM payment p JOIN bill b ON b.bill_id=p.bill_id "
                    + "WHERE p.bill_id=? AND p.payment_status='SUCCESS' ORDER BY p.created_at LIMIT 1",
                    this::mapPayment, billId).getFirst();
        }
        String paymentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO payment (payment_id,bill_id,idempotency_key,request_hash,payment_status,payment_source,amount_cents) VALUES (?,?,?,?,?,?,?)",
                paymentId, billId, key, requestHash, result, source, bill.amountDueCents());
        if (result.equals("SUCCESS")) {
            jdbc.update("UPDATE bill SET status='PAID' WHERE bill_id=?", billId);
        }
        return new PaymentView(paymentId, billId, result.equals("SUCCESS") ? "PAID" : "UNPAID",
                result, source, bill.amountDueCents());
    }

    @Transactional
    InvoiceView requestInvoice(String billId, InvoiceRequest request, String key) {
        requireKey(key);
        uuid(billId, "billId");
        if (request == null || request.invoiceTitle() == null || request.invoiceTitle().isBlank()
                || request.invoiceTitle().length() > 200
                || (request.taxNumber() != null && request.taxNumber().length() > 64)) {
            throw new IllegalArgumentException("Invalid invoice title or tax number");
        }
        String hash = hash(List.of(billId, request.invoiceTitle(), Objects.toString(request.taxNumber(), "")));
        List<InvoiceView> existing = jdbc.query("SELECT invoice_request_id,bill_id,status FROM invoice_request WHERE idempotency_key=?",
                (rs, n) -> new InvoiceView(rs.getString(1), rs.getString(2), rs.getString(3)), key);
        if (!existing.isEmpty()) {
            String original = jdbc.queryForObject("SELECT request_hash FROM invoice_request WHERE idempotency_key=?", String.class, key);
            if (!original.equals(hash)) throw new ApiFailure("IDEMPOTENCY_CONFLICT", "Key reused for a different invoice", 409);
            return existing.getFirst();
        }
        BillView bill = getBill(billId);
        if (!bill.status().equals("PAID")) throw new ApiFailure("STATE_CONFLICT", "Pay the bill before requesting an invoice", 409);
        String invoiceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO invoice_request (invoice_request_id,bill_id,idempotency_key,request_hash,invoice_title,tax_number,status) VALUES (?,?,?,?,?,?,'REQUESTED')",
                invoiceId, billId, key, hash, request.invoiceTitle(), request.taxNumber());
        return new InvoiceView(invoiceId, billId, "REQUESTED");
    }

    private BillView mapBill(ResultSet rs, int row) throws SQLException {
        try {
            FeeItem[] items = json.readValue(rs.getString("fee_items_json"), FeeItem[].class);
            return new BillView(rs.getString("bill_id"), rs.getString("parking_session_id"),
                    rs.getString("status"), rs.getLong("parking_base_cents"), rs.getLong("discount_cents"),
                    rs.getLong("prepaid_cents"), rs.getLong("prepaid_refund_cents"),
                    rs.getLong("parking_due_cents"), rs.getLong("charging_cents"),
                    rs.getLong("exception_cents"), rs.getLong("amount_due_cents"),
                    rs.getString("rate_version"), Arrays.asList(items));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored bill fee items are invalid", ex);
        }
    }

    private List<PaymentView> paymentByKey(String key) {
        return jdbc.query("SELECT p.*, b.status AS bill_status FROM payment p JOIN bill b ON b.bill_id=p.bill_id WHERE p.idempotency_key=?",
                this::mapPayment, key);
    }

    private PaymentView mapPayment(ResultSet rs, int row) throws SQLException {
        return new PaymentView(rs.getString("payment_id"), rs.getString("bill_id"),
                rs.getString("bill_status"), rs.getString("payment_status"),
                rs.getString("payment_source"), rs.getLong("amount_cents"));
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Invalid request", ex); }
    }

    private String hash(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(serialize(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key is required (max 128 characters)");
        }
    }

    private static void uuid(String value, String name) {
        try { UUID.fromString(value); }
        catch (RuntimeException ex) { throw new IllegalArgumentException(name + " must be a UUID"); }
    }
}
