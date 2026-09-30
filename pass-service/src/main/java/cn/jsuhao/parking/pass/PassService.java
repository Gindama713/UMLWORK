package cn.jsuhao.parking.pass;

import feign.FeignException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PassService {
    private static final String RESERVATION = "RESERVATION";
    private final JdbcTemplate jdbc;
    private final SpaceClient spaces;
    private final IdempotencyStore idempotency;
    private final long prepaidCents;
    private final long initialBalanceCents;
    private final int paymentHoldMinutes;
    private final int monthlyDurationDays;

    PassService(JdbcTemplate jdbc, SpaceClient spaces, IdempotencyStore idempotency,
            @Value("${pass.reservation.prepaid-cents}") long prepaidCents,
            @Value("${pass.reservation.payment-hold-minutes}") int paymentHoldMinutes,
            @Value("${pass.monthly.initial-balance-cents}") long initialBalanceCents,
            @Value("${pass.monthly.duration-days}") int monthlyDurationDays) {
        if (prepaidCents <= 0 || paymentHoldMinutes <= 0 || initialBalanceCents < 0 || monthlyDurationDays <= 0) {
            throw new IllegalArgumentException("invalid pass configuration");
        }
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.idempotency = idempotency;
        this.prepaidCents = prepaidCents;
        this.paymentHoldMinutes = paymentHoldMinutes;
        this.initialBalanceCents = initialBalanceCents;
        this.monthlyDurationDays = monthlyDurationDays;
    }

    @Transactional
    ReservationView reserve(String key, CreateReservation request) {
        if (request == null || request.startTime() == null || request.endTime() == null) invalid("time is required");
        String plate = plate(request.plateNumber());
        String spaceId = id(request.spaceId(), "spaceId");
        Instant start = request.startTime().toInstant();
        Instant end = request.endTime().toInstant();
        if (!start.isAfter(Instant.now()) || !end.isAfter(start)) invalid("reservation must be a future positive interval");
        return idempotency.execute(key, "reserve", fingerprint(plate, spaceId, start, end), ReservationView.class, () -> {
            requireReservationSpace(spaceId);
            jdbc.update("INSERT INTO reservation_lock (space_id) VALUES (?) ON DUPLICATE KEY UPDATE space_id=space_id", spaceId);
            jdbc.queryForObject("SELECT space_id FROM reservation_lock WHERE space_id=? FOR UPDATE", String.class, spaceId);
            expireForSpace(spaceId);
            Integer overlaps = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE space_id=? "
                            + "AND status IN ('PENDING_PAYMENT','CONFIRMED','USED') "
                            + "AND start_time<? AND end_time>?", Integer.class,
                    spaceId, at(end), at(start));
            if (overlaps != null && overlaps > 0) conflict("RESERVATION_CONFLICT", "reservation interval overlaps");
            String reservationId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            jdbc.update("INSERT INTO reservation (reservation_id,space_id,plate_number,start_time,end_time,status,"
                            + "prepaid_cents,created_at,updated_at) VALUES (?,?,?,?,?,'PENDING_PAYMENT',?,?,?)",
                    reservationId, spaceId, plate, at(start), at(end), prepaidCents, at(now), at(now));
            return new ReservationView(reservationId, spaceId, "PENDING_PAYMENT", prepaidCents);
        });
    }

    @Transactional
    ReservationPaymentView pay(String key, String id, SimulatedPayment request) {
        String reservationId = id(id, "reservationId");
        if (request == null || request.simulatedResult() == null
                || !List.of("SUCCESS", "FAILURE").contains(request.simulatedResult())) {
            invalid("simulatedResult must be SUCCESS or FAILURE");
        }
        return idempotency.execute(key, "reservation-pay", fingerprint(reservationId, request),
                ReservationPaymentView.class, () -> {
                    Reservation r = reservation(reservationId, true);
                    if (r.status.equals("CONFIRMED") && request.simulatedResult().equals("SUCCESS")) {
                        return new ReservationPaymentView(r.id, r.status, successfulPrepaymentId(r.id));
                    }
                    if (!r.status.equals("PENDING_PAYMENT")) conflict("STATE_CONFLICT", "reservation cannot be paid");
                    if (!r.start.isAfter(Instant.now()) || !r.created.plusSeconds(60L * paymentHoldMinutes).isAfter(Instant.now())) {
                        jdbc.update("UPDATE reservation SET status='EXPIRED',updated_at=? WHERE reservation_id=?", at(Instant.now()), r.id);
                        return new ReservationPaymentView(r.id, "EXPIRED", null);
                    }
                    String entryId = UUID.randomUUID().toString();
                    jdbc.update("INSERT INTO reservation_prepayment (entry_id,reservation_id,kind,result,amount_cents,created_at) "
                                    + "VALUES (?,?,'PREPAY',?,?,?)", entryId, r.id, request.simulatedResult(),
                            r.prepaidCents, at(Instant.now()));
                    if (request.simulatedResult().equals("SUCCESS")) {
                        jdbc.update("UPDATE reservation SET status='CONFIRMED',updated_at=? WHERE reservation_id=?",
                                at(Instant.now()), r.id);
                    }
                    return new ReservationPaymentView(r.id,
                            request.simulatedResult().equals("SUCCESS") ? "CONFIRMED" : "PENDING_PAYMENT", entryId);
                });
    }

    @Transactional
    ReservationCancelView cancel(String key, String id) {
        String reservationId = id(id, "reservationId");
        return idempotency.execute(key, "reservation-cancel", fingerprint(reservationId),
                ReservationCancelView.class, () -> {
                    Reservation r = reservation(reservationId, true);
                    if (r.status.equals("CANCELLED")) {
                        return new ReservationCancelView(r.id, r.status, refundTotal(r.id));
                    }
                    if (!List.of("CONFIRMED", "PENDING_PAYMENT").contains(r.status)) {
                        conflict("STATE_CONFLICT", "reservation cannot be cancelled");
                    }
                    if (!r.start.isAfter(Instant.now())) conflict("STATE_CONFLICT", "reservation has started");
                    long refund = r.status.equals("CONFIRMED") ? r.prepaidCents : 0;
                    jdbc.update("UPDATE reservation SET status='CANCELLED',updated_at=? WHERE reservation_id=?",
                            at(Instant.now()), r.id);
                    if (refund > 0) addPrepayment(r.id, "CANCEL_REFUND", refund, null);
                    return new ReservationCancelView(r.id, "CANCELLED", refund);
                });
    }

    @Transactional
    ReservationView consume(String key, String id, ConsumeReservation request) {
        String reservationId = id(id, "reservationId");
        if (request == null || request.entryTime() == null) invalid("entryTime is required");
        String sessionId = id(request.parkingSessionId(), "parkingSessionId");
        String plate = plate(request.plateNumber());
        Instant entry = request.entryTime().toInstant();
        return idempotency.execute(key, "reservation-consume", fingerprint(reservationId, sessionId, plate, entry),
                ReservationView.class, () -> {
                    Reservation r = reservation(reservationId, true);
                    if (r.status.equals("USED") && sessionId.equals(r.consumedSessionId)) return view(r);
                    if (!r.status.equals("CONFIRMED") || !r.plate.equals(plate)
                            || entry.isBefore(r.start) || !entry.isBefore(r.end)) {
                        conflict("STATE_CONFLICT", "reservation is not eligible for this entry");
                    }
                    jdbc.update("UPDATE reservation SET status='USED',consumed_session_id=?,"
                                    + "last_released_session_id=NULL,updated_at=? WHERE reservation_id=?",
                            sessionId, at(Instant.now()), r.id);
                    return new ReservationView(r.id, r.spaceId, "USED", r.prepaidCents);
                });
    }

    @Transactional
    ReservationView release(String key, String id, SessionReference request) {
        String reservationId = id(id, "reservationId");
        String sessionId = id(request == null ? null : request.parkingSessionId(), "parkingSessionId");
        return idempotency.execute(key, "reservation-release", fingerprint(reservationId, sessionId),
                ReservationView.class, () -> {
                    Reservation r = reservation(reservationId, true);
                    if (r.status.equals("CONFIRMED") && r.consumedSessionId == null) return view(r);
                    if (!r.status.equals("USED") || !sessionId.equals(r.consumedSessionId)) {
                        conflict("STATE_CONFLICT", "reservation was not consumed by this session");
                    }
                    jdbc.update("UPDATE reservation SET status='CONFIRMED',consumed_session_id=NULL,"
                                    + "last_released_session_id=?,updated_at=? WHERE reservation_id=?",
                            sessionId, at(Instant.now()), r.id);
                    return new ReservationView(r.id, r.spaceId, "CONFIRMED", r.prepaidCents);
                });
    }

    @Transactional
    RefundView refundRemainder(String key, String id, RefundRemainder request) {
        String reservationId = id(id, "reservationId");
        String sessionId = id(request == null ? null : request.parkingSessionId(), "parkingSessionId");
        if (request.amountCents() == null || request.amountCents() < 0) invalid("amountCents must be nonnegative");
        long amount = request.amountCents();
        return idempotency.execute(key, "reservation-refund-remainder", fingerprint(reservationId, sessionId, amount),
                RefundView.class, () -> {
                    Reservation r = reservation(reservationId, true);
                    if (!r.status.equals("USED") || !sessionId.equals(r.consumedSessionId)) {
                        conflict("STATE_CONFLICT", "reservation was not consumed by this session");
                    }
                    var existing = jdbc.query("SELECT entry_id,amount_cents FROM reservation_prepayment WHERE "
                                    + "reservation_id=? AND kind='REMAINDER_REFUND' AND parking_session_id=?",
                            (rs, n) -> new RefundView(r.id, sessionId, rs.getLong("amount_cents"), rs.getString("entry_id")),
                            r.id, sessionId);
                    if (!existing.isEmpty()) {
                        if (existing.getFirst().refundCents() != amount) {
                            conflict("IDEMPOTENCY_CONFLICT", "refund amount differs from previous request");
                        }
                        return existing.getFirst();
                    }
                    if (amount > r.prepaidCents - refundTotal(r.id)) invalid("refund exceeds paid prepayment");
                    String refundId = addPrepayment(r.id, "REMAINDER_REFUND", amount, sessionId);
                    return new RefundView(r.id, sessionId, amount, refundId);
                });
    }

    EligibilityView eligibility(String plateNumber, OffsetDateTime entryTime, OffsetDateTime exitTime,
            String spaceType, String parkingSessionId) {
        String plate = plate(plateNumber);
        String sessionId = id(parkingSessionId, "parkingSessionId");
        if (entryTime == null || exitTime == null || !exitTime.toInstant().isAfter(entryTime.toInstant())) {
            invalid("entryTime and exitTime must form a positive interval");
        }
        if (spaceType == null || !List.of("NORMAL", "ACCESSIBLE", "CHARGING", RESERVATION).contains(spaceType)) {
            invalid("spaceType is invalid");
        }
        if (RESERVATION.equals(spaceType)) {
            var reserved = jdbc.query("SELECT prepaid_cents FROM reservation WHERE plate_number=? "
                            + "AND consumed_session_id=? AND status='USED' AND start_time<=? AND end_time>? "
                            + "ORDER BY start_time LIMIT 1",
                    (rs, n) -> rs.getLong(1), plate, sessionId,
                    at(entryTime.toInstant()), at(entryTime.toInstant()));
            if (!reserved.isEmpty()) return new EligibilityView(RESERVATION, reserved.getFirst(), null);
        }
        if ("NORMAL".equals(spaceType)) {
            var monthly = jdbc.query("SELECT monthly_pass_id FROM monthly_pass WHERE plate_number=? "
                            + "AND status='ACTIVE' AND start_time<=? AND end_time>=? ORDER BY start_time DESC LIMIT 1",
                    (rs, n) -> rs.getString(1), plate, at(entryTime.toInstant()), at(exitTime.toInstant()));
            if (!monthly.isEmpty()) return new EligibilityView("MONTHLY", 0, monthly.getFirst());
        }
        return new EligibilityView("NONE", 0, null);
    }

    @Transactional
    MonthlyPassView createMonthly(String key, CreateMonthlyPass request) {
        if (request == null || request.startTime() == null) invalid("startTime is required");
        String plate = plate(request.plateNumber());
        Instant start = request.startTime().toInstant();
        Instant end = start.plusSeconds(86400L * monthlyDurationDays);
        return idempotency.execute(key, "monthly-create", fingerprint(plate, start), MonthlyPassView.class, () -> {
            jdbc.update("INSERT INTO monthly_plate_lock (plate_number) VALUES (?) "
                    + "ON DUPLICATE KEY UPDATE plate_number=plate_number", plate);
            jdbc.queryForObject("SELECT plate_number FROM monthly_plate_lock WHERE plate_number=? FOR UPDATE", String.class, plate);
            Integer overlap = jdbc.queryForObject("SELECT COUNT(*) FROM monthly_pass WHERE plate_number=? AND status='ACTIVE' "
                            + "AND start_time<? AND end_time>?", Integer.class, plate, at(end), at(start));
            if (overlap != null && overlap > 0) conflict("STATE_CONFLICT", "monthly pass validity overlaps");
            String passId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            jdbc.update("INSERT INTO monthly_pass (monthly_pass_id,plate_number,start_time,end_time,balance_cents,"
                            + "status,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',?,?)",
                    passId, plate, at(start), at(end), initialBalanceCents, at(now), at(now));
            jdbc.update("INSERT INTO monthly_ledger (entry_id,monthly_pass_id,kind,amount_cents,balance_after_cents,created_at) "
                            + "VALUES (?,?,'INITIAL_CREDIT',?,?,?)", UUID.randomUUID().toString(), passId,
                    initialBalanceCents, initialBalanceCents, at(now));
            return new MonthlyPassView(passId, plate, end.atOffset(ZoneOffset.UTC), initialBalanceCents, "ACTIVE");
        });
    }

    @Transactional
    DeductionView deduct(String key, String id, Deduction request) {
        String passId = id(id, "monthlyPassId");
        String sessionId = id(request == null ? null : request.parkingSessionId(), "parkingSessionId");
        if (request.amountCents() == null || request.amountCents() < 0) invalid("amountCents must be nonnegative");
        long amount = request.amountCents();
        return idempotency.execute(key, "monthly-deduct", fingerprint(passId, sessionId, amount),
                DeductionView.class, () -> {
                    MonthlyPass pass = monthlyPass(passId, true);
                    var existing = jdbc.query("SELECT entry_id,monthly_pass_id,amount_cents,balance_after_cents "
                                    + "FROM monthly_ledger WHERE parking_session_id=? AND kind='DEBIT'",
                            (rs, n) -> new Ledger(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)), sessionId);
                    if (!existing.isEmpty()) {
                        Ledger prior = existing.getFirst();
                        if (!prior.passId.equals(passId) || prior.amount != amount) {
                            conflict("IDEMPOTENCY_CONFLICT", "session was deducted with different values");
                        }
                        return new DeductionView(prior.id, prior.balanceAfter);
                    }
                    if (!pass.status.equals("ACTIVE")) conflict("PASS_NOT_ELIGIBLE", "monthly pass is inactive");
                    if (pass.balance < amount) conflict("PASS_BALANCE_INSUFFICIENT", "monthly balance is insufficient");
                    long after = pass.balance - amount;
                    String entryId = UUID.randomUUID().toString();
                    jdbc.update("UPDATE monthly_pass SET balance_cents=?,updated_at=? WHERE monthly_pass_id=?",
                            after, at(Instant.now()), passId);
                    jdbc.update("INSERT INTO monthly_ledger (entry_id,monthly_pass_id,parking_session_id,kind,"
                                    + "amount_cents,balance_after_cents,created_at) VALUES (?,?,?,'DEBIT',?,?,?)",
                            entryId, passId, sessionId, amount, after, at(Instant.now()));
                    return new DeductionView(entryId, after);
                });
    }

    MonthlyPassView monthlyView(String rawId) {
        String passId = id(rawId, "monthlyPassId");
        var rows = jdbc.query("SELECT monthly_pass_id,plate_number,end_time,balance_cents,status "
                        + "FROM monthly_pass WHERE monthly_pass_id=?",
                (rs, n) -> new MonthlyPassView(rs.getString("monthly_pass_id"),
                        rs.getString("plate_number"),
                        rs.getTimestamp("end_time").toInstant().atOffset(ZoneOffset.UTC),
                        rs.getLong("balance_cents"), rs.getString("status")), passId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "monthly pass not found");
        return rows.getFirst();
    }

    List<MonthlyLedgerView> monthlyLedger(String rawId) {
        String passId = id(rawId, "monthlyPassId");
        monthlyPass(passId, false);
        return jdbc.query("SELECT entry_id,parking_session_id,kind,amount_cents,balance_after_cents,"
                        + "created_at FROM monthly_ledger WHERE monthly_pass_id=? ORDER BY created_at,entry_id",
                (rs, n) -> new MonthlyLedgerView(rs.getString("entry_id"),
                        rs.getString("parking_session_id"), rs.getString("kind"),
                        rs.getLong("amount_cents"), rs.getLong("balance_after_cents"),
                        rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC)), passId);
    }

    private void requireReservationSpace(String spaceId) {
        try {
            ApiResponse<List<SpaceView>> response = spaces.spaces(RESERVATION);
            if (response == null || response.data() == null) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "space response is unavailable");
            }
            SpaceView target = response.data().stream().filter(s -> spaceId.equals(s.spaceId())).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "parking space not found"));
            if (!RESERVATION.equals(target.type()) || "OUT_OF_SERVICE".equals(target.status())) {
                conflict("SPACE_UNAVAILABLE", "space cannot be reserved");
            }
        } catch (FeignException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "space service is unavailable");
        }
    }

    private void expireForSpace(String spaceId) {
        Instant now = Instant.now();
        jdbc.update("UPDATE reservation SET status='EXPIRED',updated_at=? WHERE space_id=? AND "
                        + "((status='PENDING_PAYMENT' AND created_at<?) OR "
                        + "(status='CONFIRMED' AND end_time<=?))",
                at(now), spaceId, at(now.minusSeconds(60L * paymentHoldMinutes)), at(now));
    }

    private Reservation reservation(String id, boolean locked) {
        var rows = jdbc.query("SELECT * FROM reservation WHERE reservation_id=?" + (locked ? " FOR UPDATE" : ""),
                RESERVATION_MAPPER, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "reservation not found");
        return rows.getFirst();
    }

    private MonthlyPass monthlyPass(String id, boolean locked) {
        var rows = jdbc.query("SELECT * FROM monthly_pass WHERE monthly_pass_id=?" + (locked ? " FOR UPDATE" : ""),
                (rs, n) -> new MonthlyPass(rs.getString("monthly_pass_id"), rs.getString("status"),
                        rs.getLong("balance_cents")), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "monthly pass not found");
        return rows.getFirst();
    }

    private String successfulPrepaymentId(String reservationId) {
        return jdbc.queryForObject("SELECT entry_id FROM reservation_prepayment WHERE reservation_id=? "
                + "AND kind='PREPAY' AND result='SUCCESS' LIMIT 1", String.class, reservationId);
    }

    private long refundTotal(String reservationId) {
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM reservation_prepayment "
                + "WHERE reservation_id=? AND kind IN ('CANCEL_REFUND','REMAINDER_REFUND')", Long.class, reservationId);
        return total == null ? 0 : total;
    }

    private String addPrepayment(String reservationId, String kind, long amount, String sessionId) {
        String entryId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO reservation_prepayment (entry_id,reservation_id,kind,result,amount_cents,"
                        + "parking_session_id,created_at) VALUES (?,?,?,'SUCCESS',?,?,?)",
                entryId, reservationId, kind, amount, sessionId, at(Instant.now()));
        return entryId;
    }

    private static ReservationView view(Reservation r) {
        return new ReservationView(r.id, r.spaceId, r.status, r.prepaidCents);
    }

    private static String plate(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[\\p{IsHan}A-Z0-9]{5,12}")) invalid("车牌应为 5 至 12 位汉字、字母或数字");
        return normalized;
    }

    private static String id(String value, String field) {
        if (value == null) invalid(field + " is required");
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException error) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", field + " must be UUID"); }
    }

    private static Timestamp at(Instant instant) {
        // MySQL DATETIME(6) stores microseconds; normalize bounds before both writes and comparisons.
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }
    private static void invalid(String message) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message); }
    private static void conflict(String code, String message) { throw new ApiException(HttpStatus.CONFLICT, code, message); }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(java.util.Arrays.deepToString(values).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static final RowMapper<Reservation> RESERVATION_MAPPER = (rs, n) -> reservationFrom(rs);

    private static Reservation reservationFrom(ResultSet rs) throws SQLException {
        return new Reservation(rs.getString("reservation_id"), rs.getString("space_id"), rs.getString("plate_number"),
                rs.getTimestamp("start_time").toInstant(), rs.getTimestamp("end_time").toInstant(),
                rs.getString("status"), rs.getLong("prepaid_cents"), rs.getString("consumed_session_id"),
                rs.getString("last_released_session_id"), rs.getTimestamp("created_at").toInstant());
    }

    private record Reservation(String id, String spaceId, String plate, Instant start, Instant end,
            String status, long prepaidCents, String consumedSessionId, String lastReleasedSessionId, Instant created) {}
    private record MonthlyPass(String id, String status, long balance) {}
    private record Ledger(String id, String passId, long amount, long balanceAfter) {}
}
