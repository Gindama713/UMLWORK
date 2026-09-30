package cn.jsuhao.parking.pass;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "space-service", url = "${space-service.url:}")
interface SpaceClient {
    @GetMapping("/api/v1/spaces")
    ApiResponse<List<SpaceView>> spaces(@RequestParam("type") String type);
}

record SpaceView(String spaceId, String type, String floor, String zone, String number, String status) {}
