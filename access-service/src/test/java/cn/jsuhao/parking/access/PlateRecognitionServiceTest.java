package cn.jsuhao.parking.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class PlateRecognitionServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @Test
    void returnsCandidateOnlyAndRejectsInvalidImages() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/chat/completions", exchange -> {
            int call = calls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(true, body.contains("data:image/png;base64,"));
            byte[] response = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\""
                    + (call == 1 ? "粤B12345" : "NONE") + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            if (call == 3) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        fake.start();
        try {
            var recognition = service(fake.getAddress().getPort(), true);
            var image = new MockMultipartFile("image", "car.png", "image/png", PNG);
            assertEquals("粤B12345", recognition.recognize(image).plateNumber());
            assertEquals(1, calls.get());
            assertNull(recognition.recognize(image).plateNumber());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                    assertThrows(ApiModels.ApiFailure.class, () -> recognition.recognize(image)).status());
            var invalid = new MockMultipartFile("image", "car.png", "image/png", new byte[] {1, 2, 3});
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ApiModels.ApiFailure.class, () -> recognition.recognize(invalid)).status());
            var tooLarge = new MockMultipartFile("image", "car.png", "image/png", new byte[1025]);
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ApiModels.ApiFailure.class, () -> recognition.recognize(tooLarge)).status());
            assertEquals(3, calls.get());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void disabledRecognitionNeverCallsProvider() {
        var disabled = service(1, false);
        var image = new MockMultipartFile("image", "car.png", "image/png", PNG);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ApiModels.ApiFailure.class, () -> disabled.recognize(image)).status());
    }

    private static PlateRecognitionService service(int port, boolean enabled) {
        return new PlateRecognitionService(URI.create("http://127.0.0.1:" + port + "/chat/completions"),
                "deepseek-flash", "test-key", enabled, 1024, 2);
    }
}
