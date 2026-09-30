package cn.jsuhao.parking.space;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Idempotency {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public Idempotency(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    public <T> T run(String key, String operation, String signature, Class<T> resultType, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw SpaceException.invalid("Idempotency-Key must contain 1 to 128 characters");
        }
        return transactions.execute(status -> {
            jdbc.update("INSERT INTO idempotency_record (idempotency_key,operation_name,request_signature) "
                    + "VALUES (?,?,?) ON DUPLICATE KEY UPDATE idempotency_key=idempotency_key", key, operation, signature);
            var saved = jdbc.queryForMap("SELECT operation_name,request_signature,response_json "
                    + "FROM idempotency_record WHERE idempotency_key=? FOR UPDATE", key);
            if (!operation.equals(saved.get("operation_name")) || !signature.equals(saved.get("request_signature"))) {
                throw new SpaceException(org.springframework.http.HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT", "Idempotency-Key already belongs to a different request");
            }
            Object response = saved.get("response_json");
            if (response != null) {
                try {
                    return json.readValue(response.toString(), resultType);
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("Corrupt idempotency response", exception);
                }
            }
            T result = action.get();
            try {
                jdbc.update("UPDATE idempotency_record SET response_json=? WHERE idempotency_key=?",
                        json.writeValueAsString(result), key);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Cannot serialize idempotency response", exception);
            }
            return result;
        });
    }
}
