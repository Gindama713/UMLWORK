package cn.jsuhao.parking.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class TrafficInsightService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final TrafficService reports;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final boolean enabled;
    private final Duration timeout;

    TrafficInsightService(TrafficService reports,
            @Value("${parking.analytics.ai.url}") URI endpoint,
            @Value("${parking.analytics.ai.model}") String model,
            @Value("${parking.analytics.ai.api-key:}") String apiKey,
            @Value("${parking.analytics.ai.enabled:false}") boolean enabled,
            @Value("${parking.analytics.ai.timeout-seconds:15}") int timeoutSeconds) {
        this.reports = reports;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.enabled = enabled;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    TrafficInsight generate(String reportId) {
        TrafficReport report = reports.saved(reportId);
        if (!enabled || apiKey.isBlank()) throw unavailable();
        try {
            String stats = json.writeValueAsString(aggregate(report));
            String body = json.writeValueAsString(Map.of(
                    "model", model,
                    "thinking", Map.of("type", "disabled"),
                    "max_tokens", 320,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", "你是机场停车运营报表解读助手。只依据用户提供的汇总计数，输出 JSON：{\"summary\":\"一句简短高峰解读\",\"actions\":[\"一条人工排班建议\",\"一条观察或验证建议\"]}。不得推断航班、收入、车位占用率或因果关系；不得声称建议已执行。样本不足时说明局限。不要输出 Markdown。"),
                            Map.of("role", "user", "content", "已保存报表的汇总数据（无车牌或停车记录）：" + stats))));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable();
            JsonNode completion = json.readTree(response.body());
            if (completion == null) throw unavailable();
            JsonNode choice = completion.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) throw unavailable();
            String content = choice.path("message").path("content").asText("");
            if (content.isBlank()) throw unavailable();
            JsonNode result = json.readTree(content);
            if (result == null || !result.isObject()) throw unavailable();
            String summary = result.path("summary").asText("").strip();
            JsonNode items = result.path("actions");
            if (!result.path("summary").isTextual() || summary.isBlank() || summary.length() > 180
                    || !items.isArray() || items.size() != 2
                    || !items.get(0).isTextual() || !items.get(1).isTextual())
                throw unavailable();
            List<String> actions = List.of(items.get(0).asText("").strip(), items.get(1).asText("").strip());
            if (actions.stream().anyMatch(item -> item.isBlank() || item.length() > 150))
                throw unavailable();
            return new TrafficInsight(reportId, "AI_ADVISORY", model, summary, actions);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException error) {
            throw unavailable();
        }
    }

    private static Map<String, Object> aggregate(TrafficReport report) {
        List<Map<String, Object>> busiest = report.buckets().stream()
                .filter(bucket -> bucket.count() > 0)
                .sorted(Comparator.comparingInt(TrafficBucket::count).reversed()
                        .thenComparing(TrafficBucket::start))
                .limit(8)
                .map(bucket -> Map.<String, Object>of(
                        "start", bucket.start().atZoneSameInstant(SHANGHAI).toOffsetDateTime().toString(),
                        "entries", bucket.count()))
                .toList();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("from", report.from().atZoneSameInstant(SHANGHAI).toOffsetDateTime().toString());
        stats.put("to", report.to().atZoneSameInstant(SHANGHAI).toOffsetDateTime().toString());
        stats.put("granularity", report.granularity());
        stats.put("totalEntries", report.totalEntries());
        stats.put("bucketCount", report.buckets().size());
        stats.put("activeBucketCount", report.buckets().stream().filter(bucket -> bucket.count() > 0).count());
        stats.put("busiestBuckets", busiest);
        return stats;
    }

    private static TrafficFailure unavailable() {
        return new TrafficFailure("DEPENDENCY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                "AI 运营解读暂不可用，原始报表仍可查看");
    }
}
