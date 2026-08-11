package org.example.xqy1._026_silver_residence.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.xqy1._026_silver_residence.agent.AssistantCancelRequest;
import org.example.xqy1._026_silver_residence.agent.AssistantRunRequest;
import org.example.xqy1._026_silver_residence.agent.AssistantSessionIdentity;
import org.example.xqy1._026_silver_residence.agent.LangGraphGatewayService;
import org.example.xqy1._026_silver_residence.api.ContractResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/assistant")
public class AssistantGatewayController {
    private static final MediaType SSE_UTF8 = MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private final LangGraphGatewayService gatewayService;
    private final AssistantSessionIdentity sessionIdentity;

    @PostMapping(
            value = "/runs/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestBody AssistantRunRequest body,
            HttpServletRequest request
    ) {
        String traceId = traceId(request);
        request.setAttribute("assistant.traceId", traceId);
        if (body != null && body.messageId() != null) {
            request.setAttribute("assistant.messageId", body.messageId().toString());
        }
        AssistantSessionIdentity.Identity identity = sessionIdentity.require(request);
        LangGraphGatewayService.OpenedStream openedStream = gatewayService.openStream(body, identity, traceId);
        return sseResponse(openedStream, traceId);
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> replay(
            @PathVariable UUID runId,
            @RequestParam long afterSequence,
            HttpServletRequest request
    ) {
        String traceId = traceId(request);
        request.setAttribute("assistant.traceId", traceId);
        AssistantSessionIdentity.Identity identity = sessionIdentity.require(request);
        LangGraphGatewayService.OpenedStream openedStream = gatewayService.openReplayStream(
                runId,
                afterSequence,
                identity,
                traceId
        );
        return sseResponse(openedStream, traceId);
    }

    private ResponseEntity<StreamingResponseBody> sseResponse(
            LangGraphGatewayService.OpenedStream openedStream,
            String traceId
    ) {
        StreamingResponseBody responseBody = output -> gatewayService.relay(openedStream, output);
        return ResponseEntity.ok()
                .contentType(SSE_UTF8)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header("X-Accel-Buffering", "no")
                .header("X-Trace-Id", traceId)
                .body(responseBody);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ContractResponse<Map<String, Object>> cancel(
            @PathVariable UUID runId,
            @RequestBody(required = false) AssistantCancelRequest body,
            HttpServletRequest request
    ) {
        AssistantSessionIdentity.Identity identity = sessionIdentity.require(request);
        String traceId = traceId(request);
        String reason = body == null ? null : body.reason();
        return ContractResponse.success(gatewayService.cancel(runId, reason, identity, traceId), traceId);
    }

    private String traceId(HttpServletRequest request) {
        String value = request.getHeader("X-Trace-Id");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
