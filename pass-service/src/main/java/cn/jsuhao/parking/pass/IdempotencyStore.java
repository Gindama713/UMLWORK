package cn.jsuhao.parking.pass;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class IdempotencyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    IdempotencyStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    <T> T execute(String key, String operation, String fingerprint, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Idempotency-Key is required (max 128 characters)");
        }
        jdbc.update("INSERT INTO pass_idempotency (request_key,operation,fingerprint,created_at) "
                        + "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE request_key=request_key",
                key, operation, fingerprint, Timestamp.from(Instant.now()));
        var row = jdbc.queryForMap("SELECT operation,fingerprint,response_json FROM pass_idempotency "
                + "WHERE request_key=? FOR UPDATE", key);
        if (!operation.equals(row.get("operation")) || !fingerprint.equals(row.get("fingerprint"))) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "key was used for another request");
        }
        if (row.get("response_json") != null) {
            try {
                return json.readValue((String) row.get("response_json"), type);
            } catch (JacksonException error) {
                throw new IllegalStateException("stored response is invalid", error);
            }
        }
        T result = action.get();
        try {
            jdbc.update("UPDATE pass_idempotency SET response_json=? WHERE request_key=?",
                    json.writeValueAsString(result), key);
        } catch (JacksonException error) {
            throw new IllegalStateException("response is invalid", error);
        }
        return result;
    }
}
