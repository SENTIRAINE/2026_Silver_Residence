package org.example.xqy1._026_silver_residence.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.xqy1._026_silver_residence.api.ContractResponse;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.example.xqy1._026_silver_residence.housing.HousingSearchFeature;
import org.example.xqy1._026_silver_residence.housing.GeoSceneHousingSearchDataProvider;
import org.example.xqy1._026_silver_residence.housing.HousingSearchPolicyService;
import org.example.xqy1._026_silver_residence.housing.HousingSearchRequest;
import org.example.xqy1._026_silver_residence.housing.HousingSearchResult;
import org.example.xqy1._026_silver_residence.housing.HousingSearchService;
import org.example.xqy1._026_silver_residence.housing.HousingSearchSnapshot;
import org.example.xqy1._026_silver_residence.housing.MetricStatisticsService;
import org.example.xqy1._026_silver_residence.housing.RoadSpatialSearchService;
import org.example.xqy1._026_silver_residence.map.GeoSceneMapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolControllerTest {
    private static final String TOKEN = "tool-service-token";
    private static final String TRACE_ID = "08ae51fa-b5bc-4faf-bf18-62b0fba857e2";

    private AgentToolController controller;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        GeoSceneMapService mapService = new GeoSceneMapService(
                objectMapper,
                "http://127.0.0.1:1/MapServer",
                "https://example.invalid/geoscene",
                false,
                1000,
                1000,
                1000
        );
        controller = new AgentToolController(mapService, objectMapper, TOKEN, 120000);
    }

    @Test
    void catalogRequiresServiceIdentityAndPublishesCompleteLayerSchemas() {
        MapContractException unauthorized = assertThrows(
                MapContractException.class,
                () -> controller.catalog(new MockHttpServletRequest())
        );
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatus());

        ContractResponse<Map<String, Object>> response = controller.catalog(serviceRequest());
        List<?> tools = (List<?>) response.data().get("tools");
        assertEquals(4, tools.size());
        assertTrue(tools.stream().allMatch(tool -> ((Map<?, ?>) tool).get("timeoutMs").equals(120000)));

        Map<?, ?> lineTool = (Map<?, ?>) tools.get(2);
        Map<?, ?> inputSchema = (Map<?, ?>) lineTool.get("inputSchema");
        List<?> layerSchemas = (List<?>) inputSchema.get("oneOf");
        assertEquals(3, layerSchemas.size());
        Map<?, ?> firstLayerSchema = (Map<?, ?>) layerSchemas.get(0);
        Map<?, ?> properties = (Map<?, ?>) firstLayerSchema.get("properties");
        assertTrue(properties.containsKey("layerId"));
        assertTrue(properties.containsKey("filters"));
        assertTrue(properties.containsKey("outFields"));
        assertEquals(false, firstLayerSchema.get("additionalProperties"));

        Map<?, ?> outputSchema = (Map<?, ?>) lineTool.get("outputSchema");
        Map<?, ?> outputProperties = (Map<?, ?>) outputSchema.get("properties");
        Map<?, ?> features = (Map<?, ?>) outputProperties.get("features");
        Map<?, ?> featureItems = (Map<?, ?>) features.get("items");
        Map<?, ?> featureProperties = (Map<?, ?>) featureItems.get("properties");
        Map<?, ?> geometry = (Map<?, ?>) featureProperties.get("geometry");
        List<?> geometryOptions = (List<?>) geometry.get("oneOf");
        assertEquals(3, geometryOptions.size());
        assertTrue(((List<?>) ((Map<?, ?>) geometryOptions.get(0)).get("required")).contains("spatialReference"));
        assertTrue(((List<?>) ((Map<?, ?>) geometryOptions.get(1)).get("required")).contains("spatialReference"));
    }

    @Test
    void invokeEndpointUsesUnambiguousToolsNamespace() throws NoSuchMethodException {
        PostMapping mapping = AgentToolController.class
                .getMethod(
                        "invoke",
                        String.class,
                        AgentToolController.ToolInvokeRequest.class,
                        jakarta.servlet.http.HttpServletRequest.class
                )
                .getAnnotation(PostMapping.class);

        assertEquals("/tools/{toolName}/invoke", mapping.value()[0]);
    }

    @Test
    void healthReflectsSnapshotReadinessInsteadOfConfigurationOnly() {
        GeoSceneHousingSearchDataProvider provider = mock(GeoSceneHousingSearchDataProvider.class);
        when(provider.health()).thenReturn(Map.of("status", "READY"));
        controller = new AgentToolController(
                new GeoSceneMapService(
                        objectMapper,
                        "http://127.0.0.1:1/MapServer",
                        "https://example.invalid/geoscene",
                        false, 1000, 1000, 1000
                ),
                null,
                provider,
                objectMapper,
                TOKEN,
                120000
        );

        ContractResponse<Map<String, Object>> ready = controller.health(serviceRequest());
        assertEquals("READY", ready.data().get("status"));

        when(provider.health()).thenReturn(Map.of("status", "DEGRADED"));
        ContractResponse<Map<String, Object>> degraded = controller.health(serviceRequest());
        assertEquals("DEGRADED", degraded.data().get("status"));
    }

    @Test
    void catalogMatchesVersionedLangGraphFixture() throws IOException {
        ContractResponse<Map<String, Object>> response = controller.catalog(serviceRequest());
        var expected = objectMapper.readTree(Path.of(
                "docs",
                "examples",
                "agent-tool-catalog-2026-07-29.1.json"
        ).toFile());

        assertEquals(expected, objectMapper.valueToTree(response.data()));
    }

    @Test
    void dryRunValidatesWithoutCallingGeoSceneAndIsIdempotent() {
        String toolCallId = UUID.randomUUID().toString();
        Map<String, Object> arguments = validLineArguments(0.4);
        AgentToolController.ToolInvokeRequest body = new AgentToolController.ToolInvokeRequest(
                toolCallId,
                arguments,
                true
        );

        ContractResponse<Map<String, Object>> first = controller.invoke("queryMapLines", body, runRequest());
        ContractResponse<Map<String, Object>> second = controller.invoke("queryMapLines", body, runRequest());

        assertEquals("SUCCEEDED", first.data().get("status"));
        assertEquals(first.data(), second.data());
        Map<?, ?> result = (Map<?, ?>) first.data().get("result");
        assertEquals(true, result.get("valid"));
        assertFalse(first.data().containsKey("businessVersion"));
    }

    @Test
    void catalogDeclaredPointPriceFilterIsAcceptedAtRuntime() {
        String toolCallId = UUID.randomUUID().toString();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("layerId", 0);
        arguments.put("filters", List.of(Map.of(
                "field", "房价",
                "operator", "<=",
                "value", 20000
        )));
        arguments.put("outFields", List.of("OBJECTID", "name", "房价"));
        arguments.put("returnGeometry", true);
        arguments.put("resultRecordCount", 50);

        ContractResponse<Map<String, Object>> response = controller.invoke(
                "queryMapPoints",
                new AgentToolController.ToolInvokeRequest(toolCallId, arguments, true),
                runRequest()
        );

        assertEquals("SUCCEEDED", response.data().get("status"));
        Map<?, ?> result = (Map<?, ?>) response.data().get("result");
        assertEquals(true, result.get("valid"));
        assertEquals(0, result.get("layerId"));
    }

    @Test
    void sameToolCallIdWithDifferentArgumentsReturnsConflict() {
        String toolCallId = UUID.randomUUID().toString();
        controller.invoke(
                "queryMapLines",
                new AgentToolController.ToolInvokeRequest(toolCallId, validLineArguments(0.4), true),
                runRequest()
        );

        MapContractException conflict = assertThrows(
                MapContractException.class,
                () -> controller.invoke(
                        "queryMapLines",
                        new AgentToolController.ToolInvokeRequest(toolCallId, validLineArguments(0.6), true),
                        runRequest()
                )
        );

        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("TOOL_CALL_CONFLICT", conflict.getCode());
    }

    @Test
    void rejectsFieldsOutsideAgentFilterCatalogAndPersistsRejectedStatus() {
        String toolCallId = UUID.randomUUID().toString();
        Map<String, Object> arguments = new LinkedHashMap<>(validLineArguments(0.4));
        arguments.put("filters", List.of(Map.of(
                "field", "OBJECTID_12",
                "operator", "=",
                "value", 1
        )));

        MapContractException rejected = assertThrows(
                MapContractException.class,
                () -> controller.invoke(
                        "queryMapLines",
                        new AgentToolController.ToolInvokeRequest(toolCallId, arguments, true),
                        runRequest()
                )
        );
        ContractResponse<Map<String, Object>> execution = controller.execution(toolCallId, runRequest());

        assertEquals("INVALID_FILTER_FIELD", rejected.getCode());
        assertEquals("REJECTED", execution.data().get("status"));
        Map<?, ?> error = (Map<?, ?>) execution.data().get("error");
        assertEquals("INVALID_FILTER_FIELD", error.get("code"));
    }

    @Test
    void invokesHousingToolThroughControllerAndReturnsResolvedDefaults() {
        ObjectNode housingGeometry = objectMapper.createObjectNode();
        housingGeometry.put("x", 121.60);
        housingGeometry.put("y", 38.90);
        housingGeometry.set("spatialReference", objectMapper.createObjectNode().put("wkid", 4326));
        ObjectNode roadGeometry = objectMapper.createObjectNode();
        var path = roadGeometry.putArray("paths").addArray();
        path.addArray().add(121.59).add(38.90);
        path.addArray().add(121.61).add(38.90);
        roadGeometry.set("spatialReference", objectMapper.createObjectNode().put("wkid", 4326));
        HousingSearchSnapshot snapshot = new HousingSearchSnapshot(
                "controller-test-snapshot",
                Instant.parse("2026-07-29T00:00:00Z"),
                List.of(new HousingSearchFeature(
                        "2:1", 2, "中山区",
                        Map.of("房价", 12_000, "归一化总分", 80),
                        housingGeometry
                )),
                List.of(new HousingSearchFeature(
                        "3:1", 3, "中山区",
                        Map.of("WS", 70, "GVI", 0.5, "NOI", 0.2),
                        roadGeometry
                ))
        );
        HousingSearchPolicyService policy = new HousingSearchPolicyService(
                "housing-search-policy-2026-07-29.1",
                100, 20, 2000, 0.75, 0.90, 0.5, 0.5,
                20, 50, 200, 50, 20
        );
        HousingSearchService housingService = new HousingSearchService(
                policy,
                new MetricStatisticsService(),
                () -> snapshot,
                new RoadSpatialSearchService(objectMapper)
        );
        controller = new AgentToolController(
                new GeoSceneMapService(
                        objectMapper,
                        "http://127.0.0.1:1/MapServer",
                        "https://example.invalid/geoscene",
                        false, 1000, 1000, 1000
                ),
                housingService,
                objectMapper,
                TOKEN,
                120000
        );
        HousingSearchRequest housingRequest = new HousingSearchRequest(
                HousingSearchRequest.Mode.RANK,
                List.of("中山区"),
                new HousingSearchRequest.HardFilters(null, 15_000.0),
                new HousingSearchRequest.Preferences(
                        new HousingSearchRequest.PricePreference(
                                false, HousingSearchRequest.PricePreferenceLevel.PREFER_LOW, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, null
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, null
                        )
                ),
                new HousingSearchRequest.RoadCriteria(null, null, null),
                new HousingSearchRequest.Spatial(
                        HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, null
                ),
                new HousingSearchRequest.Display(true, true),
                20
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = objectMapper.convertValue(housingRequest, Map.class);

        ContractResponse<Map<String, Object>> response = controller.invoke(
                "searchHousingCandidates",
                new AgentToolController.ToolInvokeRequest(UUID.randomUUID().toString(), arguments, false),
                runRequest()
        );

        assertEquals("SUCCEEDED", response.data().get("status"));
        HousingSearchResult result = (HousingSearchResult) response.data().get("result");
        assertEquals(1, result.housingCandidates().size());
        assertEquals(100, result.resolvedCriteria().bufferMeters());
        assertTrue(result.resolvedCriteria().defaultsApplied()
                .containsAll(List.of("BUFFER_METERS", "PREFERENCE_WEIGHTS")));
        assertFalse(result.resolvedCriteria().relaxationApplied());
    }

    @Test
    void rejectsUnknownHousingFieldsAtAnyDepth() {
        controller = new AgentToolController(
                new GeoSceneMapService(
                        objectMapper,
                        "http://127.0.0.1:1/MapServer",
                        "https://example.invalid/geoscene",
                        false, 1000, 1000, 1000
                ),
                mock(HousingSearchService.class),
                objectMapper,
                TOKEN,
                120000
        );
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("mode", "BUFFER_FILTER");
        arguments.put("districts", List.of());
        arguments.put("hardFilters", Map.of());
        arguments.put("preferences", Map.of(
                "price", Map.of("enabled", false, "level", "PREFER_LOW", "weight", 0),
                "convenience", Map.of("enabled", false, "level", "PREFER_HIGH", "weight", 0),
                "roadWalkability", Map.of("enabled", true, "level", "HIGH", "weight", 1)
        ));
        arguments.put("roadCriteria", Map.of("newWalkingMin", 80));
        arguments.put("spatial", Map.of("relation", "WITHIN_ROAD_BUFFER"));
        arguments.put("display", Map.of("includeRoads", true, "includeBuffers", true));
        arguments.put("limit", 20);

        MapContractException rejected = assertThrows(
                MapContractException.class,
                () -> controller.invoke(
                        "searchHousingCandidates",
                        new AgentToolController.ToolInvokeRequest(
                                UUID.randomUUID().toString(), arguments, true
                        ),
                        runRequest()
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals("INVALID_HOUSING_SEARCH_ARGUMENT", rejected.getCode());
    }

    private Map<String, Object> validLineArguments(double gvi) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("layerId", 3);
        arguments.put("filters", List.of(Map.of(
                "field", "GVI",
                "operator", ">=",
                "value", gvi
        )));
        arguments.put("outFields", List.of("OBJECTID_12", "name", "GVI", "NOI", "WS"));
        arguments.put("returnGeometry", true);
        arguments.put("resultRecordCount", 50);
        arguments.put("resultOffset", 0);
        arguments.put("returnCount", true);
        return arguments;
    }

    private MockHttpServletRequest serviceRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("X-Trace-Id", TRACE_ID);
        return request;
    }

    private MockHttpServletRequest runRequest() {
        MockHttpServletRequest request = serviceRequest();
        request.addHeader("X-Tenant-Id", "tenant-default");
        request.addHeader("X-User-Id", "user-1001");
        request.addHeader("X-Run-Id", UUID.randomUUID().toString());
        return request;
    }
}
