package cn.jsuhao.parking.space;

import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChargingController {
    private final SpaceOperations operations;

    public ChargingController(SpaceOperations operations) { this.operations = operations; }

    public record StartRequest(String chargerId, String parkingSessionId, String startTime) {}
    public record FinishRequest(String endTime, BigDecimal energyKwh) {}

    @PostMapping("/api/v1/spaces/charging-sessions")
    public ApiResponse<SpaceOperations.ChargingView> start(@RequestHeader("Idempotency-Key") String key,
            @RequestBody StartRequest request) {
        return ApiResponse.ok(operations.startCharging(
                request.chargerId(), request.parkingSessionId(), request.startTime(), key));
    }

    @PostMapping("/api/v1/spaces/charging-sessions/{id}/finish")
    public ApiResponse<SpaceOperations.ChargingView> finish(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody FinishRequest request) {
        return ApiResponse.ok(operations.finishCharging(id, request.endTime(), request.energyKwh(), key));
    }

    @GetMapping("/internal/v1/spaces/charging-fees")
    public ApiResponse<SpaceOperations.ChargingFeeView> fees(@RequestParam String parkingSessionId) {
        return ApiResponse.ok(operations.chargingFees(parkingSessionId));
    }
}
