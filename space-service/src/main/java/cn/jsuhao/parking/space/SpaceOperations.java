package cn.jsuhao.parking.space;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.cloud.context.config.annotation.RefreshScope;

@Service
@RefreshScope
public class SpaceOperations {
    private static final Set<String> SPACE_TYPES = Set.of("NORMAL", "ACCESSIBLE", "CHARGING", "RESERVATION");
    private final JdbcTemplate jdbc;
    private final Idempotency idempotency;
    private final String rateVersion;
    private final long rateCentsPerKwh;

    public SpaceOperations(JdbcTemplate jdbc, Idempotency idempotency,
            @Value("${parking.charging.rate-version}") String rateVersion,
            @Value("${parking.charging.rate-cents-per-kwh}") long rateCentsPerKwh) {
        if (rateCentsPerKwh < 0 || rateVersion.isBlank()) {
            throw new IllegalArgumentException("Invalid charging rate configuration");
        }
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.rateVersion = rateVersion;
        this.rateCentsPerKwh = rateCentsPerKwh;
    }

    public record SpaceView(String spaceId, String type, String floor, String zone,
            String number, String status, String parkingSessionId) {}
    public record ChargerView(String chargerId, String spaceId, String status) {}
    public record ChargingView(String chargingSessionId, String chargerId, String parkingSessionId,
            String status, String startTime, String endTime, BigDecimal energyKwh,
            Long chargingCents, String rateVersion) {}
    public record ChargingFeeView(String parkingSessionId, long chargingCents) {}

    public SpaceView create(String type, String floor, String zone, String number, String key) {
        if (type == null || !SPACE_TYPES.contains(type)) throw SpaceException.invalid("Unknown space type");
        floor = label(floor, "floor");
        zone = label(zone, "zone");
        number = label(number, "number");
        String finalFloor = floor;
        String finalZone = zone;
        String finalNumber = number;
        return idempotency.run(key, "SPACE_CREATE",
                type + ":" + floor + ":" + zone + ":" + number, SpaceView.class, () -> {
                    String id = UUID.randomUUID().toString();
                    try {
                        jdbc.update("INSERT INTO parking_space (id,type,floor,zone_code,space_number,status) "
                                + "VALUES (?,?,?,?,?,'AVAILABLE')",
                                id, type, finalFloor, finalZone, finalNumber);
                    } catch (DuplicateKeyException duplicate) {
                        throw SpaceException.conflict("Space number already exists");
                    }
                    if ("CHARGING".equals(type))
                        jdbc.update("INSERT INTO charger (id,space_id) VALUES (?,?)",
                                UUID.randomUUID().toString(), id);
                    return spaceById(id);
                });
    }

    public SpaceView serviceStatus(String spaceId, String status, String key) {
        validId(spaceId);
        if (!"AVAILABLE".equals(status) && !"OUT_OF_SERVICE".equals(status))
            throw SpaceException.invalid("status must be AVAILABLE or OUT_OF_SERVICE");
        return idempotency.run(key, "SPACE_SERVICE_STATUS", spaceId + ":" + status,
                SpaceView.class, () -> {
                    spaceByIdForUpdate(spaceId);
                    SpaceView current = spaceById(spaceId);
                    if ("OCCUPIED".equals(current.status()))
                        throw SpaceException.conflict("Occupied space cannot change service status");
                    if (!status.equals(current.status()))
                        jdbc.update("UPDATE parking_space SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                                status, spaceId);
                    return spaceById(spaceId);
                });
    }

    public List<SpaceView> spaces(String type, boolean availableOnly) {
        if (type != null && !SPACE_TYPES.contains(type)) throw SpaceException.invalid("Unknown space type");
        String sql = "SELECT id,type,floor,zone_code,space_number,status,parking_session_id FROM parking_space WHERE 1=1"
                + (type == null ? "" : " AND type=?")
                + (availableOnly ? " AND status='AVAILABLE'" : "") + " ORDER BY floor,zone_code,space_number";
        return type == null ? jdbc.query(sql, SpaceOperations::mapSpace)
                : jdbc.query(sql, SpaceOperations::mapSpace, type);
    }

    public List<ChargerView> chargers(String spaceId) {
        if (spaceId != null) validId(spaceId);
        String sql = "SELECT id,space_id,active_session_id FROM charger"
                + (spaceId == null ? "" : " WHERE space_id=?") + " ORDER BY space_id";
        return spaceId == null ? jdbc.query(sql, SpaceOperations::mapCharger)
                : jdbc.query(sql, SpaceOperations::mapCharger, spaceId);
    }

    public SpaceView occupy(String spaceId, String parkingSessionId, String key) {
        validId(spaceId);
        validId(parkingSessionId);
        return idempotency.run(key, "OCCUPY", spaceId + ":" + parkingSessionId, SpaceView.class, () -> {
            int updated = jdbc.update("UPDATE parking_space SET status='OCCUPIED',parking_session_id=?,last_session_id=?,"
                    + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='AVAILABLE'",
                    parkingSessionId, parkingSessionId, spaceId);
            SpaceView space = spaceById(spaceId);
            if (updated == 0 && !parkingSessionId.equals(space.parkingSessionId())) {
                throw SpaceException.unavailable("Parking space is not available");
            }
            return space;
        });
    }

    public SpaceView release(String spaceId, String parkingSessionId, String key) {
        validId(spaceId);
        validId(parkingSessionId);
        return idempotency.run(key, "RELEASE", spaceId + ":" + parkingSessionId, SpaceView.class, () -> {
            spaceByIdForUpdate(spaceId);
            List<String> active = jdbc.query("SELECT active_session_id FROM charger WHERE space_id=? "
                    + "AND active_session_id IS NOT NULL FOR UPDATE", (rs, row) -> rs.getString(1), spaceId);
            if (!active.isEmpty()) throw SpaceException.conflict("Finish charging before releasing the space");
            int updated = jdbc.update("UPDATE parking_space SET status='AVAILABLE',parking_session_id=NULL,"
                    + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='OCCUPIED' AND parking_session_id=?",
                    spaceId, parkingSessionId);
            SpaceView space = spaceById(spaceId);
            if (updated == 0) {
                String lastSession = jdbc.queryForObject("SELECT last_session_id FROM parking_space WHERE id=?",
                        String.class, spaceId);
                if (!"AVAILABLE".equals(space.status()) || !parkingSessionId.equals(lastSession)) {
                    throw SpaceException.conflict("Space is not occupied by this parking session");
                }
            }
            return space;
        });
    }

    public ChargingFeeView settle(String spaceId, String parkingSessionId, String key) {
        validId(spaceId);
        validId(parkingSessionId);
        return idempotency.run(key, "CHARGE_SETTLEMENT", spaceId + ":" + parkingSessionId,
                ChargingFeeView.class, () -> {
                    spaceByIdForUpdate(spaceId);
                    SpaceView space = spaceById(spaceId);
                    if (!"OCCUPIED".equals(space.status()) || !parkingSessionId.equals(space.parkingSessionId()))
                        throw SpaceException.conflict("Space is not occupied by this parking session");
                    List<Long> saved = jdbc.query("SELECT charging_cents FROM charging_settlement "
                            + "WHERE parking_session_id=?", (rs, row) -> rs.getLong(1), parkingSessionId);
                    if (!saved.isEmpty()) return new ChargingFeeView(parkingSessionId, saved.getFirst());
                    if (!jdbc.queryForList("SELECT active_session_id FROM charger WHERE space_id=? "
                            + "AND active_session_id IS NOT NULL FOR UPDATE", String.class, spaceId).isEmpty())
                        throw SpaceException.conflict("请先结束充电，再生成出场账单");
                    ChargingFeeView fees = chargingFees(parkingSessionId);
                    jdbc.update("INSERT INTO charging_settlement (parking_session_id,space_id,charging_cents) "
                            + "VALUES (?,?,?)", parkingSessionId, spaceId, fees.chargingCents());
                    return fees;
                });
    }

    public ChargingView startCharging(String chargerId, String parkingSessionId, String startTime, String key) {
        validId(chargerId);
        validId(parkingSessionId);
        Instant start = parseTime(startTime);
        return idempotency.run(key, "CHARGE_START", chargerId + ":" + parkingSessionId + ":" + start,
                ChargingView.class, () -> {
                    List<String> eligible = jdbc.query("SELECT s.id FROM parking_space s JOIN charger c ON c.space_id=s.id "
                            + "WHERE c.id=? AND s.status='OCCUPIED' AND s.parking_session_id=? FOR UPDATE",
                            (rs, row) -> rs.getString(1), chargerId, parkingSessionId);
                    if (eligible.isEmpty()) {
                        if (jdbc.queryForList("SELECT id FROM charger WHERE id=?", String.class, chargerId).isEmpty()) {
                            throw SpaceException.notFound("Charger not found");
                        }
                        throw SpaceException.conflict("Charger space is not occupied by this parking session");
                    }
                    if (!jdbc.queryForList("SELECT parking_session_id FROM charging_settlement "
                            + "WHERE parking_session_id=?", String.class, parkingSessionId).isEmpty())
                        throw SpaceException.conflict("本次停车已进入结算，不能新增充电");
                    String chargingId = UUID.randomUUID().toString();
                    int updated = jdbc.update("UPDATE charger SET active_session_id=?,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND active_session_id IS NULL", chargingId, chargerId);
                    if (updated == 0) throw SpaceException.unavailable("Charger is already in use");
                    jdbc.update("INSERT INTO charging_session (id,charger_id,parking_session_id,status,start_at,"
                            + "rate_version,rate_cents_per_kwh) VALUES (?,?,?,'ACTIVE',?,?,?)",
                            chargingId, chargerId, parkingSessionId, Timestamp.from(start), rateVersion, rateCentsPerKwh);
                    return chargingById(chargingId);
                });
    }

    public ChargingView finishCharging(String chargingId, String endTime, BigDecimal energyKwh, String key) {
        validId(chargingId);
        Instant end = parseTime(endTime);
        if (energyKwh == null || energyKwh.signum() <= 0 || energyKwh.scale() > 3
                || energyKwh.precision() > 12) throw SpaceException.invalid("energyKwh must be positive with at most 3 decimals");
        return idempotency.run(key, "CHARGE_FINISH", chargingId + ":" + end + ":" + energyKwh,
                ChargingView.class, () -> {
                    ChargingView current = chargingById(chargingId, true);
                    if (!"ACTIVE".equals(current.status())) {
                        if (end.toString().equals(current.endTime()) && energyKwh.compareTo(current.energyKwh()) == 0) return current;
                        throw SpaceException.conflict("Charging session already finished with different data");
                    }
                    if (!end.isAfter(Instant.parse(current.startTime()))) throw SpaceException.invalid("endTime must follow startTime");
                    long rate = jdbc.queryForObject("SELECT rate_cents_per_kwh FROM charging_session WHERE id=?",
                            Long.class, chargingId);
                    long amount = energyKwh.multiply(BigDecimal.valueOf(rate))
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
                    jdbc.update("UPDATE charging_session SET status='FINISHED',end_at=?,energy_kwh=?,charging_cents=?,"
                            + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='ACTIVE'",
                            Timestamp.from(end), energyKwh, amount, chargingId);
                    jdbc.update("UPDATE charger SET active_session_id=NULL,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND active_session_id=?", current.chargerId(), chargingId);
                    return chargingById(chargingId);
                });
    }

    public ChargingFeeView chargingFees(String parkingSessionId) {
        validId(parkingSessionId);
        Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(charging_cents),0) FROM charging_session "
                + "WHERE parking_session_id=? AND status='FINISHED'", Long.class, parkingSessionId);
        return new ChargingFeeView(parkingSessionId, sum == null ? 0 : sum);
    }

    private SpaceView spaceById(String id) {
        List<SpaceView> spaces = jdbc.query("SELECT id,type,floor,zone_code,space_number,status,parking_session_id "
                + "FROM parking_space WHERE id=?", SpaceOperations::mapSpace, id);
        if (spaces.isEmpty()) throw SpaceException.notFound("Parking space not found");
        return spaces.getFirst();
    }

    private void spaceByIdForUpdate(String id) {
        if (jdbc.queryForList("SELECT id FROM parking_space WHERE id=? FOR UPDATE", String.class, id).isEmpty()) {
            throw SpaceException.notFound("Parking space not found");
        }
    }

    private ChargingView chargingById(String id) {
        return chargingById(id, false);
    }

    private ChargingView chargingById(String id, boolean lock) {
        List<ChargingView> sessions = jdbc.query("SELECT id,charger_id,parking_session_id,status,start_at,end_at,"
                + "energy_kwh,charging_cents,rate_version FROM charging_session WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), (rs, row) ->
                new ChargingView(rs.getString("id"), rs.getString("charger_id"), rs.getString("parking_session_id"),
                        rs.getString("status"), rs.getTimestamp("start_at").toInstant().toString(),
                        rs.getTimestamp("end_at") == null ? null : rs.getTimestamp("end_at").toInstant().toString(),
                        rs.getBigDecimal("energy_kwh"), rs.getObject("charging_cents") == null
                                ? null : rs.getLong("charging_cents"), rs.getString("rate_version")), id);
        if (sessions.isEmpty()) throw SpaceException.notFound("Charging session not found");
        return sessions.getFirst();
    }

    private static SpaceView mapSpace(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SpaceView(rs.getString("id"), rs.getString("type"), rs.getString("floor"),
                rs.getString("zone_code"), rs.getString("space_number"), rs.getString("status"),
                rs.getString("parking_session_id"));
    }

    private static ChargerView mapCharger(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ChargerView(rs.getString("id"), rs.getString("space_id"),
                rs.getString("active_session_id") == null ? "AVAILABLE" : "IN_USE");
    }

    private static void validId(String id) {
        try { UUID.fromString(id); }
        catch (RuntimeException exception) { throw SpaceException.invalid("ID must be a UUID"); }
    }

    private static String label(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 20)
            throw SpaceException.invalid(name + " must contain 1 to 20 characters");
        return value.trim();
    }

    private static Instant parseTime(String value) {
        try { return OffsetDateTime.parse(value).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
        catch (RuntimeException exception) { throw SpaceException.invalid("Time must be ISO 8601 with offset"); }
    }
}
