package cn.jsuhao.parking.pass;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/passes")
public class StatusController {
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("code", "OK", "message", "service ready",
                "data", Map.of("service", "pass-service", "phase", "SCAFFOLD"),
                "requestId", UUID.randomUUID().toString());
    }
}
