package org.example.xqy1._026_silver_residence.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class MapContractExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MapContractExceptionHandler.class);
    private static final MediaType SSE_UTF8 = MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private final ObjectMapper objectMapper;

    public MapContractExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(MapContractException.class)
    public ResponseEntity<?> handleMapContractException(
            MapContractException exception,
            HttpServletRequest request
    ) {
        ContractError error = new ContractError(
                exception.getCode(),
                exception.getMessage(),
                exception.isRetryable(),
                exception.getDetails()
        );
        return errorResponse(exception.getStatus(), error, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        ContractError error = new ContractError(
                "INVALID_REQUEST",
                "请求体不是合法 JSON 或字段类型不正确",
                false,
                null
        );
        return errorResponse(HttpStatus.BAD_REQUEST, error, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResourceFound(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        ContractError error = new ContractError(
                "NOT_FOUND",
                "Requested resource was not found",
                false,
                Map.of("path", request.getRequestURI())
        );
        return errorResponse(HttpStatus.NOT_FOUND, error, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        LOGGER.error(
                "Unhandled request failure traceId={} method={} path={} exceptionType={}",
                traceId(request),
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getName(),
                exception
        );
        ContractError error = new ContractError(
                "INTERNAL_ERROR",
                "服务暂时无法处理请求",
                true,
                null
        );
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, error, request);
    }

    private ResponseEntity<?> errorResponse(HttpStatus status, ContractError error, HttpServletRequest request) {
        String traceId = traceId(request);
        if (isAssistantSseRequest(request)) {
            return ResponseEntity.status(status)
                    .contentType(SSE_UTF8)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Accel-Buffering", "no")
                    .header("X-Trace-Id", traceId)
                    .body(failedEvent(error, traceId, request));
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ContractResponse.failure(error, traceId));
    }

    private String failedEvent(ContractError error, String traceId, HttpServletRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "FAILED");
        payload.put("error", error);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", "1.1");
        envelope.put("runId", null);
        envelope.put("messageId", request.getAttribute("assistant.messageId"));
        envelope.put("sequence", 1);
        envelope.put("traceId", traceId);
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("payload", payload);

        try {
            return "id: preflight:" + traceId + ":1\n"
                    + "event: preflight.failed\n"
                    + "data: " + objectMapper.writeValueAsString(envelope) + "\n\n";
        } catch (JsonProcessingException exception) {
            return "event: preflight.failed\n"
                    + "data: {\"schemaVersion\":\"1.1\",\"payload\":{\"status\":\"FAILED\",\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"服务暂时无法处理请求\",\"retryable\":true}}}\n\n";
        }
    }

    private boolean isAssistantSseRequest(HttpServletRequest request) {
        String streamPath = request.getContextPath() + "/api/assistant/runs/stream";
        String replayPrefix = request.getContextPath() + "/api/assistant/runs/";
        String requestUri = request.getRequestURI();
        String accept = request.getHeader("Accept");
        boolean assistantStream = streamPath.equals(requestUri)
                || (requestUri.startsWith(replayPrefix) && requestUri.endsWith("/events"));
        return assistantStream
                && accept != null
                && accept.toLowerCase().contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private String traceId(HttpServletRequest request) {
        Object attribute = request.getAttribute("assistant.traceId");
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        String value = request.getHeader("X-Trace-Id");
        String resolved = value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
        request.setAttribute("assistant.traceId", resolved);
        return resolved;
    }
}
