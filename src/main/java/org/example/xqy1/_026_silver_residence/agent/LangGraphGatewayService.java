package org.example.xqy1._026_silver_residence.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class LangGraphGatewayService {
    private static final int MAX_QUERY_LENGTH = 4000;
    private static final int MAX_BUSINESS_OBJECTS = 50;
    private static final int MAX_UPSTREAM_ERROR_BODY_BYTES = 64 * 1024;
    private static final Pattern UPSTREAM_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern UPSTREAM_TRACE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String serviceToken;
    private final boolean enabled;

    public LangGraphGatewayService(
            ObjectMapper objectMapper,
            @Value("${agent.langgraph.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${agent.langgraph.service-token:}") String serviceToken,
            @Value("${agent.langgraph.enabled:false}") boolean enabled,
            @Value("${agent.langgraph.connect-timeout-ms:5000}") int connectTimeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.enabled = enabled;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(1000, connectTimeoutMs)))
                .build();
    }

    public OpenedStream openStream(
            AssistantRunRequest request,
            AssistantSessionIdentity.Identity identity,
            String traceId
    ) {
        requireConfigured();
        validate(request);
        byte[] body = serialize(runPayload(request, identity));
        HttpRequest upstream = requestBuilder("/api/v1/runs/stream", identity, traceId)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return openSse(upstream, "LangGraph 拒绝创建运行");
    }

    public OpenedStream openReplayStream(
            UUID runId,
            long afterSequence,
            AssistantSessionIdentity.Identity identity,
            String traceId
    ) {
        if (afterSequence < 0) {
            throw new MapContractException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REPLAY_CURSOR",
                    "afterSequence 不能小于 0"
            );
        }
        requireConfigured();
        HttpRequest upstream = requestBuilder(
                "/api/v1/runs/" + runId + "/events?afterSequence=" + afterSequence,
                identity,
                traceId
        )
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        return openSse(upstream, "LangGraph 拒绝续流请求");
    }

    public void relay(
            OpenedStream openedStream,
            OutputStream clientOutput
    ) throws IOException {
        try (InputStream input = openedStream.inputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.write('\n');
                if (line.isEmpty()) {
                    writer.flush();
                }
            }
            writer.flush();
        }
    }

    public Map<String, Object> cancel(
            UUID runId,
            String reason,
            AssistantSessionIdentity.Identity identity,
            String traceId
    ) {
        requireConfigured();
        Map<String, Object> body = Map.of("reason", normalizeReason(reason));
        HttpRequest request = requestBuilder("/api/v1/runs/" + runId + "/cancel", identity, traceId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(serialize(body)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamFailure(
                        response.statusCode(),
                        "LangGraph 拒绝取消运行",
                        errorBody(response.body())
                );
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.has("data") ? root.path("data") : root;
            return objectMapper.convertValue(data, new TypeReference<>() { });
        } catch (MapContractException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private HttpRequest.Builder requestBuilder(
            String path,
            AssistantSessionIdentity.Identity identity,
            String traceId
    ) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", authorizationHeader())
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("X-Trace-Id", traceId)
                .header("X-Tenant-Id", identity.tenantId())
                .header("X-User-Id", identity.userId());
    }

    private Map<String, Object> runPayload(
            AssistantRunRequest request,
            AssistantSessionIdentity.Identity identity
    ) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", identity.userId());
        user.put("tenantId", identity.tenantId());
        user.put("roles", identity.roles());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("locale", request.context().locale());
        context.put("businessObjectIds", request.context().businessObjectIds());
        if (request.context().map() != null) {
            context.put("map", request.context().map());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", request.conversationId());
        payload.put("messageId", request.messageId());
        payload.put("query", request.query().trim());
        payload.put("user", user);
        payload.put("context", context);
        return payload;
    }

    private void validate(AssistantRunRequest request) {
        if (request == null || request.conversationId() == null || request.messageId() == null) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_REQUEST", "conversationId 和 messageId 必填");
        }
        if (request.query() == null || request.query().isBlank() || request.query().length() > MAX_QUERY_LENGTH) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_QUERY", "query 长度必须为 1 至 4000 个字符");
        }
        if (request.context().locale().length() > 32) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_CONTEXT", "locale 长度不能超过 32");
        }
        if (request.context().businessObjectIds().size() > MAX_BUSINESS_OBJECTS
                || request.context().businessObjectIds().stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 128)) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_CONTEXT", "businessObjectIds 不合法");
        }
        validateMapContext(request.context().map());
    }

    private void validateMapContext(AssistantRunRequest.MapContext map) {
        if (map == null) {
            return;
        }
        if (map.visibleLayerIds().size() > 6 || map.visibleLayerIds().stream().anyMatch(id -> id == null || id < 0 || id > 5)) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_MAP_CONTEXT", "visibleLayerIds 只能包含 0 至 5");
        }
        if (map.zoom() != null && (!Double.isFinite(map.zoom()) || map.zoom() < 0 || map.zoom() > 19)) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_MAP_CONTEXT", "zoom 必须在 0 至 19 之间");
        }
        AssistantRunRequest.Extent extent = map.extent();
        if (extent == null) {
            return;
        }
        if (Stream.of(extent.xmin(), extent.ymin(), extent.xmax(), extent.ymax())
                .anyMatch(value -> value == null || !Double.isFinite(value))
                || extent.xmin() >= extent.xmax()
                || extent.ymin() >= extent.ymax()
                || extent.wkid() != 4326) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_MAP_CONTEXT", "extent 必须是合法的 WGS84 范围");
        }
    }

    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException exception) {
            throw new MapContractException(HttpStatus.INTERNAL_SERVER_ERROR, "AGENT_SERIALIZATION_FAILED", "无法构建 Agent 请求");
        }
    }

    private void requireConfigured() {
        if (!enabled || baseUrl.isBlank() || serviceToken.isBlank()) {
            throw new MapContractException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_NOT_CONFIGURED",
                    "LangGraph 服务尚未配置",
                    true,
                    null
            );
        }
    }

    private String authorizationHeader() {
        return serviceToken.regionMatches(true, 0, "Bearer ", 0, 7)
                ? serviceToken
                : "Bearer " + serviceToken;
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "USER_CANCELLED";
        }
        return reason.length() > 128 ? reason.substring(0, 128) : reason;
    }

    private MapContractException unavailable(Exception exception) {
        return new MapContractException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "LANGGRAPH_UNAVAILABLE",
                "LangGraph 服务暂时不可用",
                true,
                Map.of("reason", exception.getClass().getSimpleName())
        );
    }

    private MapContractException upstreamFailure(int status, String message, UpstreamErrorBody body) {
        HttpStatus resolved = HttpStatus.resolve(status);
        HttpStatus httpStatus = status >= 400 && status < 500 && resolved != null
                ? resolved
                : HttpStatus.BAD_GATEWAY;
        String code = "LANGGRAPH_REQUEST_FAILED";
        boolean retryable = status >= 500;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status);

        if (body != null && !body.truncated() && body.bytes().length > 0) {
            try {
                JsonNode root = objectMapper.readTree(body.bytes());
                if (root != null && root.isObject()) {
                    JsonNode error = root.path("error");
                    JsonNode upstreamCode = error.path("code");
                    if (upstreamCode.isTextual() && UPSTREAM_ERROR_CODE.matcher(upstreamCode.textValue()).matches()) {
                        code = upstreamCode.textValue();
                    }
                    JsonNode upstreamRetryable = error.path("retryable");
                    if (upstreamRetryable.isBoolean()) {
                        retryable = upstreamRetryable.booleanValue();
                    }
                    JsonNode upstreamTraceId = root.path("traceId");
                    if (upstreamTraceId.isTextual()
                            && UPSTREAM_TRACE_ID.matcher(upstreamTraceId.textValue()).matches()) {
                        details.put("upstreamTraceId", upstreamTraceId.textValue());
                    }
                }
            } catch (IOException ignored) {
                // Keep the stable gateway error when the upstream body is not trusted JSON.
            }
        }
        return new MapContractException(httpStatus, code, message, retryable, Map.copyOf(details));
    }

    private OpenedStream openSse(HttpRequest request, String failureMessage) {
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                UpstreamErrorBody errorBody = readErrorBody(response.body());
                closeQuietly(response.body());
                throw upstreamFailure(response.statusCode(), failureMessage, errorBody);
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.toLowerCase().contains("text/event-stream")) {
                closeQuietly(response.body());
                throw new MapContractException(
                        HttpStatus.BAD_GATEWAY,
                        "INVALID_AGENT_RESPONSE",
                        "LangGraph 未返回 SSE 事件流",
                        true,
                        Map.of("contentType", contentType)
                );
            }
            return new OpenedStream(response.body());
        } catch (MapContractException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private void closeQuietly(InputStream inputStream) {
        try {
            inputStream.close();
        } catch (IOException ignored) {
            // Nothing else can be done for an upstream error body.
        }
    }

    private UpstreamErrorBody readErrorBody(InputStream inputStream) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(MAX_UPSTREAM_ERROR_BODY_BYTES);
            byte[] buffer = new byte[4096];
            int remaining = MAX_UPSTREAM_ERROR_BODY_BYTES + 1;
            while (remaining > 0) {
                int read = inputStream.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length > MAX_UPSTREAM_ERROR_BODY_BYTES) {
                return new UpstreamErrorBody(new byte[0], true);
            }
            return new UpstreamErrorBody(bytes, false);
        } catch (IOException ignored) {
            return new UpstreamErrorBody(new byte[0], false);
        }
    }

    private UpstreamErrorBody errorBody(String value) {
        if (value == null || value.isEmpty()) {
            return new UpstreamErrorBody(new byte[0], false);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_UPSTREAM_ERROR_BODY_BYTES) {
            return new UpstreamErrorBody(new byte[0], true);
        }
        return new UpstreamErrorBody(bytes, false);
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    public record OpenedStream(InputStream inputStream) {
    }

    private record UpstreamErrorBody(byte[] bytes, boolean truncated) {
    }
}
