package org.example.xqy1._026_silver_residence.housing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.example.xqy1._026_silver_residence.map.GeoSceneMapService;
import org.example.xqy1._026_silver_residence.map.MapLayerDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoSceneHousingSearchDataProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loadsSixLayersWithExplicitFieldsPaginationDeduplicationAndAtomicCacheReplacement() throws IOException {
        AtomicBoolean failRequests = new AtomicBoolean(false);
        List<RequestCapture> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/MapServer", exchange -> handle(exchange, requests, failRequests));
        server.start();

        String serviceUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/MapServer";
        GeoSceneMapService mapService = new GeoSceneMapService(
                objectMapper, serviceUrl, "https://example.invalid/geoscene",
                false, 1000, 1000, 1000
        );
        MutableClock clock = new MutableClock(Instant.parse("2026-07-29T00:00:00Z"));
        GeoSceneHousingSearchDataProvider provider = new GeoSceneHousingSearchDataProvider(
                mapService, 60_000, clock, 2, 20
        );

        provider.refreshSnapshot();
        HousingSearchSnapshot first = provider.loadSnapshot();

        assertEquals(4, first.housing().size());
        assertEquals(3, first.roads().size());
        assertEquals(List.of("0:1", "0:2", "1:1", "2:1"),
                first.housing().stream().map(HousingSearchFeature::id).toList());
        assertEquals(List.of("3:1", "4:1", "5:1"),
                first.roads().stream().map(HousingSearchFeature::id).toList());
        assertTrue(first.housing().get(0).attributes().containsKey("nullableField"));
        assertEquals(null, first.housing().get(0).attributes().get("nullableField"));
        assertThrows(UnsupportedOperationException.class,
                () -> first.housing().get(0).attributes().put("newField", "value"));
        assertEquals(List.of(0, 2), requests.stream()
                .filter(request -> request.layerId() == 0)
                .map(RequestCapture::offset)
                .toList());
        assertTrue(requests.stream().allMatch(request -> request.recordCount() == 2));
        assertTrue(requests.stream().allMatch(request -> !request.outFields().contains("*")));
        for (MapLayerDefinition layer : mapService.getLayerDefinitions()) {
            assertTrue(requests.stream()
                    .filter(request -> request.layerId() == layer.id())
                    .allMatch(request -> request.outFields().equals(layer.fields())));
        }

        int requestCountAfterBuild = requests.size();
        assertSame(first, provider.loadSnapshot());
        assertEquals(requestCountAfterBuild, requests.size());

        clock.set(Instant.parse("2026-07-29T00:01:01Z"));
        failRequests.set(true);
        assertThrows(MapContractException.class, provider::refreshSnapshot);
        assertEquals("DEGRADED", provider.health().get("status"));
        clock.set(Instant.parse("2026-07-29T00:00:30Z"));
        assertSame(first, provider.loadSnapshot());

        failRequests.set(false);
        clock.set(Instant.parse("2026-07-29T00:01:02Z"));
        provider.refreshSnapshot();
        HousingSearchSnapshot rebuilt = provider.loadSnapshot();
        assertEquals("READY", provider.health().get("status"));
        assertNotSame(first, rebuilt);
        assertEquals(first.housing().stream().map(HousingSearchFeature::id).toList(),
                rebuilt.housing().stream().map(HousingSearchFeature::id).toList());
        assertEquals(first.roads().stream().map(HousingSearchFeature::id).toList(),
                rebuilt.roads().stream().map(HousingSearchFeature::id).toList());
    }

    private void handle(
            HttpExchange exchange,
            List<RequestCapture> requests,
            AtomicBoolean failRequests
    ) throws IOException {
        if (failRequests.get()) {
            send(exchange, 503, "{\"error\":\"unavailable\"}");
            return;
        }
        String[] path = exchange.getRequestURI().getPath().split("/");
        int layerId = Integer.parseInt(path[path.length - 2]);
        Map<String, String> query = queryParameters(exchange.getRequestURI().getRawQuery());
        int offset = Integer.parseInt(query.get("resultOffset"));
        int recordCount = Integer.parseInt(query.get("resultRecordCount"));
        List<String> outFields = List.of(query.get("outFields").split(","));
        requests.add(new RequestCapture(layerId, offset, recordCount, outFields));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode features = response.putArray("features");
        if (layerId == 0 && offset == 0) {
            features.add(feature(layerId, 1));
            features.add(feature(layerId, 2));
            response.put("exceededTransferLimit", true);
        } else if (layerId == 0 && offset == 2) {
            features.add(feature(layerId, 2));
            response.put("exceededTransferLimit", false);
        } else if (offset == 0) {
            features.add(feature(layerId, 1));
            response.put("exceededTransferLimit", false);
        } else {
            response.put("exceededTransferLimit", false);
        }
        send(exchange, 200, objectMapper.writeValueAsString(response));
    }

    private ObjectNode feature(int layerId, int objectId) {
        ObjectNode feature = objectMapper.createObjectNode();
        ObjectNode attributes = feature.putObject("attributes");
        attributes.put(layerId >= 3 ? "OBJECTID_12" : "OBJECTID", objectId);
        attributes.put("name", "feature-" + layerId + "-" + objectId);
        attributes.putNull("nullableField");
        if (layerId <= 2) {
            attributes.put("房价", 10_000 + layerId * 1000);
            attributes.put("归一化总分", 70 + layerId);
            ObjectNode geometry = feature.putObject("geometry");
            geometry.put("x", 121.60 + layerId * 0.001);
            geometry.put("y", 38.90);
        } else {
            attributes.put("WS归一化", String.valueOf(60 + layerId));
            ArrayNode path = feature.putObject("geometry").putArray("paths").addArray();
            path.addArray().add(121.59).add(38.90);
            path.addArray().add(121.61).add(38.90);
        }
        return feature;
    }

    private Map<String, String> queryParameters(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
            );
        }
        return values;
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record RequestCapture(int layerId, int offset, int recordCount, List<String> outFields) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
