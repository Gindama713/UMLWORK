package cn.jsuhao.parking.analytics;

import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
class TrafficController {
    private final TrafficService service;

    TrafficController(TrafficService service) {
        this.service = service;
    }

    @GetMapping("/traffic")
    Envelope<TrafficReport> traffic(@RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to, @RequestParam(defaultValue = "HOUR") String granularity) {
        return Envelope.ok(service.generate(from, to, granularity));
    }

    @GetMapping("/traffic-preview")
    Envelope<TrafficReport> preview(@RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to, @RequestParam(defaultValue = "HOUR") String granularity) {
        return Envelope.ok(service.preview(from, to, granularity));
    }

    @GetMapping("/traffic-reports/{id}")
    Envelope<TrafficReport> saved(@PathVariable String id) {
        return Envelope.ok(service.saved(id));
    }
}
