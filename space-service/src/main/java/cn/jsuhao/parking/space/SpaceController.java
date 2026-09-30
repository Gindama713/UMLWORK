package cn.jsuhao.parking.space;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SpaceController {
    private final SpaceOperations operations;

    public SpaceController(SpaceOperations operations) { this.operations = operations; }

    public record OccupancyRequest(String parkingSessionId) {}
    public record CreateSpace(String type, String floor, String zone, String number) {}
    public record ServiceStatus(String status) {}
    public record SpaceSummary(String spaceId, String type, String floor, String zone,
            String number, String status) {
        static SpaceSummary from(SpaceOperations.SpaceView space) {
            return new SpaceSummary(space.spaceId(), space.type(), space.floor(), space.zone(),
                    space.number(), space.status());
        }
    }

    @GetMapping("/api/v1/spaces")
    public ApiResponse<List<SpaceSummary>> spaces(@RequestParam(required = false) String type) {
        return ApiResponse.ok(operations.spaces(type, false).stream().map(SpaceSummary::from).toList());
    }

    @PostMapping("/api/v1/spaces")
    public ApiResponse<SpaceOperations.SpaceView> create(
            @RequestHeader("Idempotency-Key") String key, @RequestBody CreateSpace request) {
        return ApiResponse.ok(operations.create(request.type(), request.floor(), request.zone(),
                request.number(), key));
    }

    @PatchMapping("/api/v1/spaces/{spaceId}/service-status")
    public ApiResponse<SpaceOperations.SpaceView> serviceStatus(@PathVariable String spaceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody ServiceStatus request) {
        return ApiResponse.ok(operations.serviceStatus(spaceId, request.status(), key));
    }

    @GetMapping("/internal/v1/spaces/available")
    public ApiResponse<List<SpaceSummary>> available(@RequestParam String type) {
        return ApiResponse.ok(operations.spaces(type, true).stream().map(SpaceSummary::from).toList());
    }

    @PostMapping("/internal/v1/spaces/{spaceId}/occupy")
    public ApiResponse<SpaceOperations.SpaceView> occupy(@PathVariable String spaceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody OccupancyRequest request) {
        return ApiResponse.ok(operations.occupy(spaceId, request.parkingSessionId(), key));
    }

    @PostMapping("/internal/v1/spaces/{spaceId}/release")
    public ApiResponse<SpaceOperations.SpaceView> release(@PathVariable String spaceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody OccupancyRequest request) {
        return ApiResponse.ok(operations.release(spaceId, request.parkingSessionId(), key));
    }

    @PostMapping("/internal/v1/spaces/{spaceId}/settlement")
    public ApiResponse<SpaceOperations.ChargingFeeView> settle(@PathVariable String spaceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody OccupancyRequest request) {
        return ApiResponse.ok(operations.settle(spaceId, request.parkingSessionId(), key));
    }

    @GetMapping("/api/v1/spaces/chargers")
    public ApiResponse<List<SpaceOperations.ChargerView>> chargers(@RequestParam(required = false) String spaceId) {
        return ApiResponse.ok(operations.chargers(spaceId));
    }
}
