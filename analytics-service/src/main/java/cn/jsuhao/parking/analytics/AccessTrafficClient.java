package cn.jsuhao.parking.analytics;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "access-service", url = "${clients.access.url:}")
interface AccessTrafficClient {
    @GetMapping("/internal/v1/access/sessions")
    Envelope<List<TrafficSession>> sessions(@RequestParam("from") String from,
            @RequestParam("to") String to);
}
