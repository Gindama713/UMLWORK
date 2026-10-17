package cn.jsuhao.parking.access;

import static cn.jsuhao.parking.access.ApiModels.ApiFailure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
final class PlateRecognitionService {
    private final ObjectMapper json;
    private final HttpClient client;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final boolean enabled;
    private final int maxBytes;
    private final Duration timeout;

    PlateRecognitionService(@Value("${plate-recognition.url}") URI endpoint,
            @Value("${plate-recognition.model}") String model,
            @Value("${plate-recognition.api-key:}") String apiKey,
            @Value("${plate-recognition.enabled:false}") boolean enabled,
            @Value("${plate-recognition.max-bytes:3145728}") int maxBytes,
            @Value("${plate-recognition.timeout-seconds:15}") int timeoutSeconds) {
        this.json = new ObjectMapper();
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.enabled = enabled;
        this.maxBytes = maxBytes;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    record PlateCandidate(String plateNumber, String source, String model) {}

    PlateCandidate recognize(MultipartFile image) {
        if (!enabled || apiKey.isBlank()) throw unavailable();
        if (image == null || image.isEmpty() || image.getSize() > maxBytes)
            throw invalid();
        String type = image.getContentType();
        if (!"image/jpeg".equals(type) && !"image/png".equals(type)) throw invalid();
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException error) {
            throw invalid();
        }
        if (bytes.length > maxBytes || !matchesHeader(type, bytes)) throw invalid();
        try {
            String dataUrl = "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
            String body = json.writeValueAsString(Map.of(
                    "model", model,
                    "thinking", Map.of("type", "disabled"),
                    "max_tokens", 32,
                    "messages", new Object[] {
                            Map.of("role", "system", "content", "Read one Chinese vehicle licence plate in the image. Reply with only the plate characters in uppercase, or NONE if missing, ambiguous or multiple plates. No explanation."),
                            Map.of("role", "user", "content", new Object[] {
                                    Map.of("type", "text", "text", "Identify the single clearly visible vehicle plate. Return only its characters or NONE."),
                                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                            })
                    }));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable();
            JsonNode answer = json.readTree(response.body());
            JsonNode choice = answer.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) throw unavailable();
            String candidate = choice.path("message").path("content").asText("").trim().toUpperCase(Locale.ROOT);
            if ("NONE".equals(candidate)) return new PlateCandidate(null, "AI_CANDIDATE", model);
            if (!candidate.matches("[\\p{IsHan}A-Z0-9]{5,12}") || !candidate.matches(".*[0-9].*"))
                throw unavailable();
            return new PlateCandidate(candidate, "AI_CANDIDATE", model);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException error) {
            throw unavailable();
        }
    }

    private static boolean matchesHeader(String type, byte[] bytes) {
        if ("image/png".equals(type))
            return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d
                    && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
        return bytes.length >= 4 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                && bytes[bytes.length - 2] == (byte) 0xff && bytes[bytes.length - 1] == (byte) 0xd9;
    }

    private static ApiFailure invalid() {
        return new ApiFailure("INVALID_ARGUMENT", HttpStatus.BAD_REQUEST, "请上传不超过限制的 JPEG 或 PNG 图片");
    }

    private static ApiFailure unavailable() {
        return new ApiFailure("DEPENDENCY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                "图片识别暂不可用，请手动输入车牌");
    }
}
