package org.example.xqy1._026_silver_residence.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LangGraphGatewayServiceTest {
    private static final UUID CONVERSATION_ID = UUID.fromString("d00f61d6-1713-4458-af0b-86027a58b032");
    private static final UUID MESSAGE_ID = UUID.fromString("7ec1cff4-6705-401c-b36a-692b5f9173a7");
    private static final UUID RUN_ID = UUID.fromString("13546154-1f31-4091-b7ce-a890b39d16bf");
    private static final String TRACE_ID = "08ae51fa-b5bc-4faf-bf18-62b0fba857e2";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> capturedTenant = new AtomicReference<>();
    private final AtomicReference<String> capturedUser = new AtomicReference<>();
    private final AtomicReference<String> capturedTrace = new AtomicReference<>();
    private final AtomicReference<String> capturedQuery = new AtomicReference<>();
    private final AtomicReference<String> capturedProtocol = new AtomicReference<>();
    private final AtomicReference<StubResponse> runResponse = new AtomicReference<>();
    private final AtomicReference<StubResponse> cancelResponse = new AtomicReference<>();
    private final AtomicInteger cancelRequests = new AtomicInteger();
    private HttpServer server;
    private LangGraphGatewayService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/runs/stream", this::handleRunStream);
        server.createContext("/api/v1/runs/" + RUN_ID + "/events", this::handleReplayStream);
        server.createContext("/api/v1/runs/" + RUN_ID + "/cancel", this::handleCancel);
        cancelRequests.set(0);
        runResponse.set(null);
        cancelResponse.set(null);
        server.start();
        service = new LangGraphGatewayService(
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-service-token",
                true,
                1000
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void forwardsTrustedIdentityAndRelaysNamedSseEvents() throws Exception {
        AssistantSessionIdentity.Identity identity = new AssistantSessionIdentity.Identity(
                "user-1001",
                "tenant-default",
                List.of("USER")
        );
        LangGraphGatewayService.OpenedStream openedStream = service.openStream(validRequest(), identity, TRACE_ID);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        service.relay(openedStream, output);

        JsonNode body = capturedBody.get();
        assertEquals(CONVERSATION_ID.toString(), body.path("conversationId").asText());
        assertEquals(MESSAGE_ID.toString(), body.path("messageId").asText());
        assertEquals("user-1001", body.path("user").path("userId").asText());
        assertEquals("tenant-default", body.path("user").path("tenantId").asText());
        assertEquals("USER", body.path("user").path("roles").get(0).asText());
        assertEquals("Bearer test-service-token", capturedAuthorization.get());
        assertEquals("tenant-default", capturedTenant.get());
        assertEquals("user-1001", capturedUser.get());
        assertEquals(TRACE_ID, capturedTrace.get());
        assertEquals("HTTP/1.1", capturedProtocol.get());

        String events = output.toString(StandardCharsets.UTF_8);
        assertTrue(events.contains("event: run.started"));
        assertTrue(events.contains("event: answer.delta"));
        assertTrue(events.contains("event: run.completed"));
        assertTrue(events.contains("\"content\":\"已找到道路\""));
    }

    @Test
    void rejectsInvalidExtentAsContractErrorInsteadOfNullPointer() {
        AssistantRunRequest invalid = new AssistantRunRequest(
                CONVERSATION_ID,
                MESSAGE_ID,
                "测试",
                new AssistantRunRequest.Context(
                        "zh-CN",
                        new AssistantRunRequest.MapContext(
                                List.of(3),
                                12.0,
                                new AssistantRunRequest.Extent(null, 38.8, 121.8, 39.0, 4326)
                        ),
                        List.of()
                )
        );

        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> service.openStream(invalid, identity(), TRACE_ID)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("INVALID_MAP_CONTEXT", exception.getCode());
    }

    @Test
    void failsClosedWhenLangGraphIsNotConfigured() {
        LangGraphGatewayService disabled = new LangGraphGatewayService(
                objectMapper,
                "http://127.0.0.1:8000",
                "",
                false,
                1000
        );

        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> disabled.openStream(validRequest(), identity(), TRACE_ID)
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("AGENT_NOT_CONFIGURED", exception.getCode());
        assertTrue(exception.isRetryable());
    }

    @Test
    void cancelUsesHeaderIdentityAndDoesNotDuplicateUserInBody() {
        var result = service.cancel(RUN_ID, "用户停止", identity(), TRACE_ID);

        assertEquals("CANCELLED", result.get("status"));
        assertEquals("用户停止", capturedBody.get().path("reason").asText());
        assertTrue(capturedBody.get().path("requestedBy").isMissingNode());
        assertEquals("user-1001", capturedUser.get());
        assertEquals(1, cancelRequests.get());
    }

    @Test
    void replaysPersistedEventsAfterRequestedSequence() throws Exception {
        LangGraphGatewayService.OpenedStream openedStream = service.openReplayStream(
                RUN_ID,
                2,
                identity(),
                TRACE_ID
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        service.relay(openedStream, output);

        assertEquals("afterSequence=2", capturedQuery.get());
        assertEquals("user-1001", capturedUser.get());
        String events = output.toString(StandardCharsets.UTF_8);
        assertTrue(events.contains("id: " + RUN_ID + ":3"));
        assertTrue(events.contains("event: run.completed"));
        assertTrue(!events.contains("event: run.started"));
    }

    @Test
    void rejectsNegativeReplayCursor() {
        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> service.openReplayStream(RUN_ID, -1, identity(), TRACE_ID)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("INVALID_REPLAY_CURSOR", exception.getCode());
    }

    @Test
    void preservesSanitizedStructuredErrorForSseFailure() {
        runResponse.set(new StubResponse(503, """
                {
                  "success": false,
                  "error": {
                    "code": "AGENT_DATABASE_BUSY",
                    "message": "database is locked: secret-path",
                    "retryable": false,
                    "details": {"database": "agent.sqlite3"}
                  },
                  "traceId": "upstream-08ae51fa-b5bc-4faf-bf18-62b0fba857e2"
                }
                """));

        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> service.openStream(validRequest(), identity(), TRACE_ID)
        );

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("AGENT_DATABASE_BUSY", exception.getCode());
        assertFalse(exception.isRetryable());
        assertEquals("LangGraph 拒绝创建运行", exception.getMessage());
        assertEquals(503, exception.getDetails().get("status"));
        assertEquals(
                "upstream-08ae51fa-b5bc-4faf-bf18-62b0fba857e2",
                exception.getDetails().get("upstreamTraceId")
        );
        assertEquals(2, exception.getDetails().size());
    }

    @Test
    void cancelUsesTheSameSanitizedUpstreamErrorParser() {
        cancelResponse.set(new StubResponse(409, """
                {
                  "success": false,
                  "error": {
                    "code": "RUN_ALREADY_FINISHED",
                    "message": "internal cancellation detail",
                    "retryable": true
                  },
                  "traceId": "cancel-trace-13546154"
                }
                """));

        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> service.cancel(RUN_ID, "用户停止", identity(), TRACE_ID)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("RUN_ALREADY_FINISHED", exception.getCode());
        assertTrue(exception.isRetryable());
        assertEquals("LangGraph 拒绝取消运行", exception.getMessage());
        assertEquals(409, exception.getDetails().get("status"));
        assertEquals("cancel-trace-13546154", exception.getDetails().get("upstreamTraceId"));
        assertEquals(2, exception.getDetails().size());
    }

    @Test
    void malformedUpstreamJsonFallsBackToStableGatewayError() {
        runResponse.set(new StubResponse(500, "{not-json"));

        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> service.openStream(validRequest(), identity(), TRACE_ID)
        );

        assertEquals("LANGGRAPH_REQUEST_FAILED", exception.getCode());
        assertTrue(exception.isRetryable());
        assertEquals(java.util.Map.of("status", 500), exception.getDetails());
    }

    @Test
    void oversizedUpstreamBodyIsNotParsedOrExposed() {
        String body = "{\"padding\":\"" + "x".repeat(70_000)
                + "\",\"error\":{\"code\":\"SHOULD_NOT_ESCAPE\",\"retryable\":false}}";
        runResponse.set(new StubResponse(500, body));

        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> service.openStream(validRequest(), identity(), TRACE_ID)
        );

        assertEquals("LANGGRAPH_REQUEST_FAILED", exception.getCode());
        assertTrue(exception.isRetryable());
        assertEquals(java.util.Map.of("status", 500), exception.getDetails());
    }

    @Test
    void invalidCodeTraceIdAndNonBooleanRetryableAreIgnored() {
        runResponse.set(new StubResponse(400, """
                {
                  "error": {
                    "code": "database.error",
                    "retryable": "true"
                  },
                  "traceId": "bad trace/id"
                }
                """));

        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> service.openStream(validRequest(), identity(), TRACE_ID)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("LANGGRAPH_REQUEST_FAILED", exception.getCode());
        assertFalse(exception.isRetryable());
        assertEquals(java.util.Map.of("status", 400), exception.getDetails());
    }

    @Test
    void liveLangGraphAcceptsTheSpringHttp11RequestBody() throws Exception {
        assumeTrue("1".equals(System.getenv("RUN_LANGGRAPH_LIVE")));
        String token = System.getenv("LANGGRAPH_SERVICE_TOKEN");
        assumeTrue(token != null && !token.isBlank());
        String baseUrl = System.getenv().getOrDefault("LANGGRAPH_BASE_URL", "http://127.0.0.1:8000");
        LangGraphGatewayService liveService = new LangGraphGatewayService(
                objectMapper,
                baseUrl,
                token,
                true,
                5000
        );
        AssistantRunRequest request = new AssistantRunRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "hi",
                new AssistantRunRequest.Context(
                        "zh-CN",
                        new AssistantRunRequest.MapContext(List.of(0, 1, 2, 3, 4, 5), 13.0, null),
                        List.of()
                )
        );

        LangGraphGatewayService.OpenedStream openedStream = liveService.openStream(
                request,
                new AssistantSessionIdentity.Identity("spring-live-user", "tenant-default", List.of("USER")),
                "spring-http11-live-" + UUID.randomUUID()
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        liveService.relay(openedStream, output);

        String events = output.toString(StandardCharsets.UTF_8);
        assertTrue(events.contains("event: run.started"));
        assertTrue(events.contains("event: run.completed"));
    }

    private AssistantRunRequest validRequest() {
        return new AssistantRunRequest(
                CONVERSATION_ID,
                MESSAGE_ID,
                "筛选中山区绿视率高的道路",
                new AssistantRunRequest.Context(
                        "zh-CN",
                        new AssistantRunRequest.MapContext(List.of(0, 3), 13.0, null),
                        List.of()
                )
        );
    }

    private AssistantSessionIdentity.Identity identity() {
        return new AssistantSessionIdentity.Identity("user-1001", "tenant-default", List.of("USER"));
    }

    private void handleRunStream(HttpExchange exchange) throws IOException {
        capturedProtocol.set(exchange.getProtocol());
        capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        capturedTenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
        capturedUser.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
        capturedTrace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
        capturedBody.set(objectMapper.readTree(exchange.getRequestBody()));

        StubResponse stubResponse = runResponse.get();
        if (stubResponse != null) {
            respond(exchange, stubResponse.status(), stubResponse.body());
            return;
        }

        String stream = """
                id: %s:1
                event: run.started
                data: {"schemaVersion":"1.0","runId":"%s","messageId":"%s","sequence":1,"traceId":"%s","timestamp":"2026-07-26T08:00:00Z","payload":{"status":"RUNNING"}}

                id: %s:2
                event: answer.delta
                data: {"schemaVersion":"1.0","runId":"%s","messageId":"%s","sequence":2,"traceId":"%s","timestamp":"2026-07-26T08:00:01Z","payload":{"content":"已找到道路"}}

                id: %s:3
                event: run.completed
                data: {"schemaVersion":"1.0","runId":"%s","messageId":"%s","sequence":3,"traceId":"%s","timestamp":"2026-07-26T08:00:02Z","payload":{"status":"SUCCEEDED","answer":"已找到道路","citations":[],"warnings":[]}}

                """.formatted(
                RUN_ID, RUN_ID, MESSAGE_ID, TRACE_ID,
                RUN_ID, RUN_ID, MESSAGE_ID, TRACE_ID,
                RUN_ID, RUN_ID, MESSAGE_ID, TRACE_ID
        );
        byte[] response = stream.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleCancel(HttpExchange exchange) throws IOException {
        cancelRequests.incrementAndGet();
        capturedUser.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
        capturedBody.set(objectMapper.readTree(exchange.getRequestBody()));
        StubResponse stubResponse = cancelResponse.get();
        if (stubResponse != null) {
            respond(exchange, stubResponse.status(), stubResponse.body());
            return;
        }
        byte[] response = ("{\"success\":true,\"data\":{\"runId\":\"" + RUN_ID
                + "\",\"status\":\"CANCELLED\"},\"traceId\":\"" + TRACE_ID + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleReplayStream(HttpExchange exchange) throws IOException {
        capturedQuery.set(exchange.getRequestURI().getRawQuery());
        capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        capturedTenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
        capturedUser.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
        capturedTrace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
        String stream = """
                id: %s:3
                event: answer.delta
                data: {"schemaVersion":"1.0","runId":"%s","messageId":"%s","sequence":3,"traceId":"%s","timestamp":"2026-07-26T08:00:01Z","payload":{"content":"重放事件"}}

                id: %s:4
                event: run.completed
                data: {"schemaVersion":"1.0","runId":"%s","messageId":"%s","sequence":4,"traceId":"%s","timestamp":"2026-07-26T08:00:02Z","payload":{"status":"SUCCEEDED","answer":"重放事件","citations":[],"warnings":[]}}

                """.formatted(
                RUN_ID, RUN_ID, MESSAGE_ID, TRACE_ID,
                RUN_ID, RUN_ID, MESSAGE_ID, TRACE_ID
        );
        byte[] response = stream.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record StubResponse(int status, String body) {
    }
}
