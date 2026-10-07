package cn.jsuhao.parking.access;

import static cn.jsuhao.parking.access.ApiModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class AccessRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<SessionRow> mapper = this::map;

    AccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<SessionRow> byId(String id) {
        return one("SELECT * FROM parking_session WHERE id=?", id);
    }

    Optional<SessionRow> byRequestKey(String key) {
        return one("SELECT * FROM parking_session WHERE request_key=?", key);
    }

    void registerRequest(String key, String operation, String payload) {
        String signature;
        try {
            signature = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        jdbc.update("INSERT INTO access_request_key(request_key,operation_name,request_signature) "
                + "VALUES (?,?,?) ON DUPLICATE KEY UPDATE request_key=request_key", key, operation, signature);
        var saved = jdbc.queryForMap("SELECT operation_name,request_signature FROM access_request_key WHERE request_key=?", key);
        if (!operation.equals(saved.get("operation_name")) || !signature.equals(saved.get("request_signature")))
            throw new ApiFailure("IDEMPOTENCY_CONFLICT", org.springframework.http.HttpStatus.CONFLICT,
                    "请求键已用于其他请求内容");
    }

    Optional<ExitIntent> exitIntent(String id) {
        return jdbc.query("SELECT * FROM exit_intent WHERE parking_session_id=?", (rs, row) ->
                new ExitIntent(fromDb(rs, "exit_time"), rs.getString("exception_type"),
                        rs.getString("operator_name"), rs.getString("benefit_type"),
                        rs.getLong("prepaid_cents"), rs.getString("monthly_pass_id")), id).stream().findFirst();
    }

    @Transactional
    ExitIntent reserveExit(String id, ExitIntent intent) {
        jdbc.update("""
                INSERT INTO exit_intent(parking_session_id,exit_time,exception_type,operator_name,
                    benefit_type,prepaid_cents,monthly_pass_id) VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE parking_session_id=parking_session_id
                """, id, utc(intent.exit()), intent.exceptionType(), intent.operator(), intent.benefitType(),
                intent.prepaidCents(), intent.monthlyPassId());
        jdbc.update("UPDATE parking_session SET status='EXIT_PENDING_PAYMENT' "
                + "WHERE id=? AND status='PARKED' AND bill_id IS NULL", id);
        return exitIntent(id).orElseThrow();
    }

    record ExitIntent(OffsetDateTime exit, String exceptionType, String operator,
                      String benefitType, long prepaidCents, String monthlyPassId) {}

    Optional<SessionRow> activePlate(String plate) {
        return one("SELECT * FROM parking_session WHERE active_plate=?", plate);
    }

    private Optional<SessionRow> one(String sql, Object value) {
        return jdbc.query(sql, mapper, value).stream().findFirst();
    }

    void insert(String id, String plate, String type, String reservationId,
                OffsetDateTime entry, String key, String payload) {
        jdbc.update("""
                INSERT INTO parking_session
                (id,plate_number,active_plate,space_type,entry_time,status,reservation_id,request_key,request_payload)
                VALUES (?,?,?,?,?,'ENTERING',?,?,?)
                """, id, plate, plate, type, utc(entry), reservationId, key, payload);
    }

    void chooseSpace(String id, SpaceView space) {
        jdbc.update("""
                UPDATE parking_session SET space_id=?,floor_name=?,zone_name=?,space_number=?
                WHERE id=? AND status='ENTERING'
                """, space.spaceId(), space.floor(), space.zone(), space.number(), id);
    }

    void parked(String id) {
        jdbc.update("UPDATE parking_session SET status='PARKED' WHERE id=? AND status='ENTERING'", id);
    }

    void entryFailed(String id) {
        jdbc.update("""
                UPDATE parking_session SET status='ENTRY_FAILED', active_plate=NULL
                WHERE id=? AND status='ENTERING'
                """, id);
    }

    void exitRequested(String id, OffsetDateTime exit, String billId, String exception,
                       String operator, String monthlyPassId) {
        jdbc.update("""
                UPDATE parking_session
                SET exit_time=?,bill_id=?,exception_type=?,operator_name=?,monthly_pass_id=?,
                    status='EXIT_PENDING_PAYMENT'
                WHERE id=? AND status='EXIT_PENDING_PAYMENT' AND bill_id IS NULL
                """, utc(exit), billId, exception, operator, monthlyPassId, id);
    }

    void paid(String id) {
        jdbc.update("""
                UPDATE parking_session SET status='PAID_PENDING_RELEASE'
                WHERE id=? AND status='EXIT_PENDING_PAYMENT'
                """, id);
    }

    void closed(String id) {
        jdbc.update("""
                UPDATE parking_session SET status='CLOSED',active_plate=NULL
                WHERE id=? AND status='PAID_PENDING_RELEASE'
                """, id);
    }

    List<TrafficSession> traffic(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query("""
                SELECT id,entry_time,exit_time,status FROM parking_session
                WHERE entry_time >= ? AND entry_time < ?
                    AND status IN ('PARKED','EXIT_PENDING_PAYMENT','PAID_PENDING_RELEASE','CLOSED')
                ORDER BY entry_time,id
                """, (rs, row) -> new TrafficSession(rs.getString("id"),
                fromDb(rs, "entry_time"), fromDb(rs, "exit_time"), rs.getString("status")),
                utc(from), utc(to));
    }

    private SessionRow map(ResultSet rs, int row) throws SQLException {
        return new SessionRow(
                rs.getString("id"), rs.getString("plate_number"), rs.getString("space_id"),
                rs.getString("space_type"), rs.getString("floor_name"), rs.getString("zone_name"),
                rs.getString("space_number"), fromDb(rs, "entry_time"), fromDb(rs, "exit_time"),
                rs.getString("status"), rs.getString("bill_id"), rs.getString("exception_type"),
                rs.getString("operator_name"), rs.getString("reservation_id"),
                rs.getString("monthly_pass_id"), rs.getString("request_key"),
                rs.getString("request_payload"));
    }

    private static LocalDateTime utc(OffsetDateTime time) {
        return time.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static OffsetDateTime fromDb(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    record SessionRow(String id, String plate, String spaceId, String spaceType,
                      String floor, String zone, String number, OffsetDateTime entry,
                      OffsetDateTime exit, String status, String billId, String exceptionType,
                      String operator, String reservationId, String monthlyPassId,
                      String requestKey, String requestPayload) {
        SessionView view() {
            return new SessionView(id, plate, spaceId, floor, zone, number, entry, status,
                    exit, billId, exceptionType, operator);
        }
    }
}
