package org.example.xqy1._026_silver_residence.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContractFixtureTest {
    private static final Path EXAMPLES = Path.of("docs", "examples");
    private static final Set<String> EVENTS = Set.of(
            "run.started", "route.selected", "retrieval.completed", "tool.started",
            "tool.completed", "map.result", "citation.added", "answer.delta",
            "run.completed", "run.failed", "run.cancelled"
    );
    private static final Set<String> TERMINAL_EVENTS = Set.of(
            "run.completed", "run.failed", "run.cancelled"
    );

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void allSseFixturesHaveValidEnvelopeSequenceAndTerminalEvent() throws IOException {
        for (String file : List.of(
                "agent-sse-hybrid.txt",
                "agent-sse-empty-map.txt",
                "agent-sse-rag.txt",
                "agent-sse-clarify.txt",
                "agent-sse-tool-failure.txt",
                "agent-sse-cancelled.txt",
                "agent-sse-replay-after-2.txt",
                "agent-sse-housing-buffer.txt"
        )) {
            validateSseFixture(file);
        }
    }

    @Test
    void ragFixtureContainsCitationSourceMetadata() throws IOException {
        JsonNode root = objectMapper.readTree(EXAMPLES.resolve("rag-search-fixture.json").toFile());
        JsonNode item = root.path("data").path(0);

        for (String field : List.of(
                "content", "score", "documentId", "documentVersion", "title",
                "contentType", "chunkId", "sectionPath", "pageStart", "pageEnd",
                "resourceRef", "warnings"
        )) {
            assertTrue(item.has(field), "RAG fixture is missing " + field);
        }
    }

    private void validateSseFixture(String file) throws IOException {
        String text = Files.readString(EXAMPLES.resolve(file), StandardCharsets.UTF_8);
        List<SseEvent> events = parseEvents(text);
        assertFalse(events.isEmpty(), file + " has no SSE events");

        boolean replay = file.contains("replay");
        if (!replay) {
            assertEquals("run.started", events.get(0).name(), file + " must start with run.started");
            assertEquals(1, events.get(0).data().path("sequence").asInt());
        }

        String runId = events.get(0).data().path("runId").asText();
        String messageId = events.get(0).data().path("messageId").asText();
        int expectedSequence = events.get(0).data().path("sequence").asInt();
        int terminalCount = 0;

        for (SseEvent event : events) {
            JsonNode data = event.data();
            assertTrue(EVENTS.contains(event.name()), file + " has unknown event " + event.name());
            assertEquals("1.1", data.path("schemaVersion").asText());
            assertEquals(runId, data.path("runId").asText());
            assertEquals(messageId, data.path("messageId").asText());
            assertEquals(expectedSequence, data.path("sequence").asInt());
            assertEquals(runId + ":" + expectedSequence, event.id());
            assertTrue(data.hasNonNull("traceId"));
            assertTrue(data.hasNonNull("timestamp"));
            assertTrue(data.path("payload").isObject());
            validatePayload(file, event);
            if (TERMINAL_EVENTS.contains(event.name())) {
                terminalCount++;
            }
            expectedSequence++;
        }

        assertEquals(1, terminalCount, file + " must contain exactly one terminal event");
        assertTrue(TERMINAL_EVENTS.contains(events.get(events.size() - 1).name()), file + " must end terminally");
    }

    private void validatePayload(String file, SseEvent event) {
        JsonNode payload = event.data().path("payload");
        switch (event.name()) {
            case "run.started" -> assertEquals("RUNNING", payload.path("status").asText());
            case "route.selected" -> assertTrue(Set.of("MAP_QUERY", "RAG_QA", "HYBRID", "CLARIFY")
                    .contains(payload.path("intent").asText()));
            case "retrieval.completed" -> assertTrue(payload.path("documents").canConvertToInt());
            case "tool.started" -> requireFields(file, payload, "toolCallId", "toolName");
            case "tool.completed" -> requireFields(file, payload, "toolCallId", "toolName", "status", "durationMs");
            case "answer.delta" -> assertFalse(payload.path("content").asText().isBlank());
            case "run.completed" -> requireFields(file, payload, "status", "answer", "citations", "warnings");
            case "run.failed" -> requireFields(file, payload, "status", "error");
            case "run.cancelled" -> requireFields(file, payload, "status", "reason");
            case "citation.added" -> validateCitation(file, payload);
            case "map.result" -> validateMapResult(file, payload);
            default -> throw new AssertionError("Unexpected event " + event.name());
        }
    }

    private void validateMapResult(String file, JsonNode payload) {
        requireFields(file, payload, "queryId", "toolCallIds", "mode", "querySummary",
                "appliedFilters", "resultSets", "overlays", "display", "warnings");
        int featureCount = 0;
        for (JsonNode resultSet : payload.path("resultSets")) {
            requireFields(file, resultSet, "toolCallId", "role", "layerId", "layerName",
                    "geometryType", "spatialReference", "total", "returned",
                    "exceededTransferLimit", "objectIdField", "features");
            int returned = resultSet.path("returned").asInt();
            assertEquals(returned, resultSet.path("features").size(), file + " returned mismatch");
            assertTrue(resultSet.path("total").asInt() >= returned, file + " total is below returned");
            featureCount += returned;
        }
        requireFields(file, payload.path("display"), "fitBounds", "paddingPx", "maxZoom", "layerOrder");
        for (JsonNode overlay : payload.path("overlays")) {
            requireFields(file, overlay, "overlayId", "kind", "geometryType", "spatialReference",
                    "sourceRoadIds", "attributes", "geometry");
            for (JsonNode ring : overlay.path("geometry").path("rings")) {
                assertTrue(ring.size() >= 4, file + " overlay ring is too short");
                assertEquals(ring.get(0), ring.get(ring.size() - 1), file + " overlay ring is not closed");
            }
        }
        assertTrue(featureCount <= 200, file + " exceeds the map.result feature limit");
    }

    private void validateCitation(String file, JsonNode citation) {
        requireFields(file, citation, "citationId", "ordinal", "documentId", "documentVersion",
                "title", "contentType", "sectionPath", "pageStart", "pageEnd", "chunkId",
                "excerpt", "excerptAllowed", "score", "source", "warnings");
        assertTrue(citation.path("source").hasNonNull("resourceRef"));
    }

    private void requireFields(String file, JsonNode value, String... fields) {
        for (String field : fields) {
            assertNotNull(value.get(field), file + " is missing " + field);
        }
    }

    private List<SseEvent> parseEvents(String text) throws IOException {
        List<SseEvent> events = new ArrayList<>();
        for (String block : text.split("\\R\\s*\\R")) {
            String id = null;
            String name = null;
            String data = null;
            for (String line : block.split("\\R")) {
                if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring(5).trim();
                }
            }
            if (data != null) {
                assertNotNull(id, "SSE event is missing id");
                assertNotNull(name, "SSE event is missing name");
                events.add(new SseEvent(id, name, objectMapper.readTree(data)));
            }
        }
        return events;
    }

    private record SseEvent(String id, String name, JsonNode data) {
    }
}
