package org.example.xqy1._026_silver_residence.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoSceneMapServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void layerRegistryMatchesPublishedServiceAndIndexedFilterFields() {
        GeoSceneMapService service = new GeoSceneMapService(
                new ObjectMapper().findAndRegisterModules(),
                "https://example.invalid/server/rest/services/demo/map/MapServer",
                "https://example.invalid/geoscene",
                false,
                1000,
                1000,
                1000
        );

        List<MapLayerDefinition> layers = service.getLayerDefinitions();

        assertEquals(
                List.of("shahekou_1", "xigang_1", "zhongshan_1", "ZhongShan", "XiGang", "ShaHeKou"),
                layers.stream().map(MapLayerDefinition::name).toList()
        );
        assertEquals(List.of("point", "point", "point", "polyline", "polyline", "polyline"),
                layers.stream().map(MapLayerDefinition::geometryType).toList());
        assertTrue(layers.get(0).fields().contains("交通密度"));
        assertFalse(layers.get(1).fields().contains("交通密度"));
        assertFalse(layers.get(2).fields().contains("交通密度"));
        for (int id = 0; id <= 2; id++) {
            assertTrue(filterNames(layers.get(id)).containsAll(
                    List.of("name", "adname", "房价", "覆盖度评分", "归一化总分")
            ));
        }
        for (int id = 3; id <= 5; id++) {
            assertTrue(filterNames(layers.get(id)).containsAll(
                    List.of("name", "GVI", "NOI", "WS", "Shape_Length")
            ));
        }
    }

    @Test
    void pointGeometryAlwaysIncludesContractSpatialReference() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/MapServer/0/query", exchange -> {
            byte[] body = """
                    {
                      "count": 1,
                      "exceededTransferLimit": false,
                      "features": [
                        {
                          "attributes": {"OBJECTID": 1, "name": "示例住宅", "房价": 19800},
                          "geometry": {"x": 121.62, "y": 38.91}
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        GeoSceneMapService service = new GeoSceneMapService(
                new ObjectMapper().findAndRegisterModules(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/MapServer",
                "https://example.invalid/geoscene",
                false,
                1000,
                1000,
                1000
        );
        MapFeatureQueryRequest request = new MapFeatureQueryRequest(
                0,
                List.of(new MapFilterCondition("房价", "<=", 20000)),
                null,
                List.of("OBJECTID", "name", "房价"),
                true,
                50,
                0,
                null,
                null,
                null,
                true
        );

        MapFeatureQueryResult result = service.query(request, false, true);

        assertEquals(1, result.features().size());
        assertEquals(121.62, result.features().get(0).geometry().path("x").asDouble());
        assertEquals(38.91, result.features().get(0).geometry().path("y").asDouble());
        assertEquals(4326, result.features().get(0).geometry().path("spatialReference").path("wkid").asInt());
    }

    @Test
    void mainQueryTimeoutHasDedicatedGatewayTimeoutError() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/MapServer/0/query", exchange -> {
            try {
                Thread.sleep(1500);
                byte[] body = "{\"features\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        GeoSceneMapService service = service(1000, 1000);

        MapContractException timeout = assertThrows(
                MapContractException.class,
                () -> service.query(pointRequest(false), false, true)
        );

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, timeout.getStatus());
        assertEquals("GEOSCENE_QUERY_TIMEOUT", timeout.getCode());
        assertEquals(true, timeout.isRetryable());
        assertEquals(1000, timeout.getDetails().get("timeoutMs"));
    }

    @Test
    void countTimeoutFailsExplicitlyInsteadOfReturningPageSizeAsTotal() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/MapServer/0/query", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            try {
                if (query != null && query.contains("returnCountOnly=true")) {
                    Thread.sleep(1500);
                }
                byte[] body = (query != null && query.contains("returnCountOnly=true")
                        ? "{\"count\":1}"
                        : "{\"features\":[{\"attributes\":{\"OBJECTID\":1},\"geometry\":{\"x\":121.62,\"y\":38.91}}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        GeoSceneMapService service = service(5000, 1000);

        MapContractException timeout = assertThrows(
                MapContractException.class,
                () -> service.query(pointRequest(true), false, true)
        );

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, timeout.getStatus());
        assertEquals("GEOSCENE_QUERY_TIMEOUT", timeout.getCode());
        assertEquals("count", timeout.getDetails().get("phase"));
        assertEquals(0, timeout.getDetails().get("layerId"));
    }

    private GeoSceneMapService service(int readTimeoutMs, int countTimeoutMs) {
        return new GeoSceneMapService(
                new ObjectMapper().findAndRegisterModules(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/MapServer",
                "https://example.invalid/geoscene",
                false,
                1000,
                readTimeoutMs,
                countTimeoutMs
        );
    }

    private MapFeatureQueryRequest pointRequest(boolean returnCount) {
        return new MapFeatureQueryRequest(
                0,
                List.of(new MapFilterCondition("房价", "<=", 20000)),
                null,
                List.of("OBJECTID", "房价"),
                true,
                50,
                0,
                null,
                null,
                null,
                returnCount
        );
    }

    private List<String> filterNames(MapLayerDefinition layer) {
        return layer.filterFields().stream().map(MapFieldDefinition::name).toList();
    }
}
