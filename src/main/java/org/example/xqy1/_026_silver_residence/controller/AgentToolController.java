package org.example.xqy1._026_silver_residence.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.servlet.http.HttpServletRequest;
import org.example.xqy1._026_silver_residence.api.ContractResponse;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.example.xqy1._026_silver_residence.housing.HousingSearchPolicyService;
import org.example.xqy1._026_silver_residence.housing.HousingSearchRequest;
import org.example.xqy1._026_silver_residence.housing.HousingSearchService;
import org.example.xqy1._026_silver_residence.housing.GeoSceneHousingSearchDataProvider;
import org.example.xqy1._026_silver_residence.map.GeoSceneMapService;
import org.example.xqy1._026_silver_residence.map.MapFeatureQueryRequest;
import org.example.xqy1._026_silver_residence.map.MapFeatureQueryResult;
import org.example.xqy1._026_silver_residence.map.MapFieldDefinition;
import org.example.xqy1._026_silver_residence.map.MapLayerDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/internal/agent-tools")
public class AgentToolController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentToolController.class);
    private static final String CATALOG_VERSION = "2026-08-21.1";
    private static final Set<String> ALLOWED_ARGUMENTS = Set.of(
            "layerId", "filters", "outFields", "returnGeometry",
            "resultRecordCount", "resultOffset", "returnCount"
    );

    private final GeoSceneMapService mapService;
    private final HousingSearchService housingSearchService;
    private final GeoSceneHousingSearchDataProvider housingDataProvider;
    private final ObjectMapper objectMapper;
    private final ObjectMapper housingArgumentsMapper;
    private final String serviceToken;
    private final int toolTimeoutMs;
    private final String housingPolicyVersion;
    private final Map<String, StoredExecution> executions = new ConcurrentHashMap<>();

    @Autowired
    public AgentToolController(
            GeoSceneMapService mapService,
            HousingSearchService housingSearchService,
            GeoSceneHousingSearchDataProvider housingDataProvider,
            ObjectMapper objectMapper,
            @Value("${agent.tools.service-token:}") String serviceToken,
            @Value("${agent.tools.timeout-ms:120000}") int toolTimeoutMs,
            @Value("${housing.search.policy-version:housing-search-policy-2026-08-21.1}") String housingPolicyVersion
    ) {
        this.mapService = mapService;
        this.housingSearchService = housingSearchService;
        this.housingDataProvider = housingDataProvider;
        this.objectMapper = objectMapper;
        this.housingArgumentsMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.toolTimeoutMs = Math.max(1000, toolTimeoutMs);
        this.housingPolicyVersion = housingPolicyVersion;
    }

    public AgentToolController(
            GeoSceneMapService mapService,
            HousingSearchService housingSearchService,
            GeoSceneHousingSearchDataProvider housingDataProvider,
            ObjectMapper objectMapper,
            String serviceToken,
            int toolTimeoutMs
    ) {
        this(
                mapService, housingSearchService, housingDataProvider, objectMapper,
                serviceToken, toolTimeoutMs, HousingSearchPolicyService.DEFAULT_POLICY_VERSION
        );
    }

    public AgentToolController(
            GeoSceneMapService mapService,
            HousingSearchService housingSearchService,
            ObjectMapper objectMapper,
            String serviceToken,
            int toolTimeoutMs
    ) {
        this(mapService, housingSearchService, null, objectMapper, serviceToken, toolTimeoutMs);
    }

    public AgentToolController(
            GeoSceneMapService mapService,
            ObjectMapper objectMapper,
            String serviceToken,
            int toolTimeoutMs
    ) {
        this(mapService, null, null, objectMapper, serviceToken, toolTimeoutMs);
    }

    @GetMapping("/catalog")
    public ContractResponse<Map<String, Object>> catalog(HttpServletRequest request) {
        requireServiceRequest(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", CATALOG_VERSION);
        data.put("tools", List.of(
                tool(
                        "queryMapFeatures",
                        "按一个地图图层和结构化筛选条件查询点或线要素。只接受目录字段和运算符，不执行任意 SQL。",
                        List.of(0, 1, 2, 3, 4, 5)
                ),
                tool(
                        "queryMapPoints",
                        "查询 0 至 2 号住宅点图层。新步行是点要素步行评分，不等同于道路 WS。",
                        List.of(0, 1, 2)
                ),
                tool(
                        "queryMapLines",
                        "查询 3 至 5 号道路图层。GVI/NOI 是等级字段，vegetation/noise 是原始分，WS归一化是 0-100 的道路步行指数。",
                        List.of(3, 4, 5)
                ),
                housingSearchTool()
        ));
        return ContractResponse.success(data, traceId(request));
    }

    @PostMapping("/tools/{toolName}/invoke")
    public ContractResponse<Map<String, Object>> invoke(
            @PathVariable String toolName,
            @RequestBody ToolInvokeRequest body,
            HttpServletRequest request
    ) {
        requireRunRequest(request);
        ToolRoute route = route(toolName);
        validateInvokeBody(body);
        String fingerprint = fingerprint(toolName, body);
        BeginExecution begin = beginExecution(toolName, body.toolCallId(), fingerprint);
        StoredExecution stored = begin.execution();
        if (!begin.created()) {
            return ContractResponse.success(stored.data(), traceId(request));
        }
        stored = updateExecution(stored, "RUNNING", null, null, false);

        try {
            Object result;
            if (route.housingSearch()) {
                if (housingSearchService == null) {
                    throw new MapContractException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "HOUSING_SEARCH_NOT_CONFIGURED",
                            "Housing search service is not configured",
                            true,
                            null
                    );
                }
                HousingSearchRequest query = convertHousingArguments(body.arguments());
                var resolved = housingSearchService.validate(query);
                if (Boolean.TRUE.equals(body.dryRun())) {
                    result = Map.of(
                            "valid", true,
                            "toolName", toolName,
                            "mode", resolved.mode(),
                            "policyVersion", housingPolicyVersion,
                            "catalogVersion", CATALOG_VERSION
                    );
                } else {
                    result = housingSearchService.search(query);
                }
            } else {
                MapFeatureQueryRequest query = convertArguments(body.arguments());
                mapService.validateAgentQuery(query, route.linesOnly(), route.pointsOnly());
                if (Boolean.TRUE.equals(body.dryRun())) {
                    result = Map.of(
                            "valid", true,
                            "toolName", toolName,
                            "layerId", query.layerId(),
                            "catalogVersion", CATALOG_VERSION
                    );
                } else {
                    result = execute(route, query);
                }
            }
            StoredExecution completed = updateExecution(stored, "SUCCEEDED", result, null, true);
            return ContractResponse.success(completed.data(), traceId(request));
        } catch (MapContractException exception) {
            String status = exception.getStatus().is4xxClientError() ? "REJECTED" : "FAILED";
            updateExecution(stored, status, null, error(exception), true);
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Agent Tool execution failed toolName={} toolCallId={} traceId={}",
                    toolName,
                    body.toolCallId(),
                    traceId(request),
                    exception
            );
            Map<String, Object> error = Map.of(
                    "code", "TOOL_EXECUTION_FAILED",
                    "message", "Agent Tool 执行失败",
                    "retryable", true
            );
            updateExecution(stored, "FAILED", null, error, true);
            throw new MapContractException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "TOOL_EXECUTION_FAILED",
                    "Agent Tool 执行失败",
                    true,
                    null
            );
        }
    }

    @GetMapping("/executions/{toolCallId}")
    public ContractResponse<Map<String, Object>> execution(
            @PathVariable String toolCallId,
            HttpServletRequest request
    ) {
        requireRunRequest(request);
        StoredExecution value = executions.get(toolCallId);
        if (value == null) {
            throw new MapContractException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Tool execution was not found");
        }
        return ContractResponse.success(value.data(), traceId(request));
    }

    @GetMapping("/health")
    public ContractResponse<Map<String, Object>> health(HttpServletRequest request) {
        requireServiceRequest(request);
        Map<String, Object> snapshot = housingDataProvider == null
                ? Map.of("status", "UNKNOWN")
                : housingDataProvider.health();
        boolean ready = "READY".equals(snapshot.get("status"));
        return ContractResponse.success(
                Map.of(
                        "status", ready ? "READY" : "DEGRADED",
                        "catalogVersion", CATALOG_VERSION,
                        "executionStore", "memory",
                        "geosceneConfigured", true,
                        "housingSnapshot", snapshot
                ),
                traceId(request)
        );
    }

    private ToolRoute route(String toolName) {
        return switch (toolName) {
            case "queryMapFeatures" -> new ToolRoute(false, false, false);
            case "queryMapPoints" -> new ToolRoute(false, true, false);
            case "queryMapLines" -> new ToolRoute(true, false, false);
            case "searchHousingCandidates" -> new ToolRoute(false, false, true);
            default -> throw new MapContractException(HttpStatus.NOT_FOUND, "TOOL_NOT_FOUND", "Tool is not in the catalog");
        };
    }

    private MapFeatureQueryResult execute(ToolRoute route, MapFeatureQueryRequest query) {
        return mapService.query(query, route.linesOnly(), route.pointsOnly());
    }

    private MapFeatureQueryRequest convertArguments(Map<String, Object> arguments) {
        Map<String, Object> values = arguments == null ? Map.of() : arguments;
        Set<String> unknown = new java.util.LinkedHashSet<>(values.keySet());
        unknown.removeAll(ALLOWED_ARGUMENTS);
        if (!unknown.isEmpty()) {
            throw new MapContractException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_TOOL_ARGUMENT",
                    "Tool arguments contain fields outside the catalog",
                    false,
                    Map.of("fields", List.copyOf(unknown))
            );
        }
        try {
            return objectMapper.convertValue(values, MapFeatureQueryRequest.class);
        } catch (IllegalArgumentException exception) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_TOOL_ARGUMENT", "Tool arguments do not match the catalog schema");
        }
    }

    private HousingSearchRequest convertHousingArguments(Map<String, Object> arguments) {
        Map<String, Object> values = arguments == null ? Map.of() : arguments;
        try {
            return housingArgumentsMapper.convertValue(values, HousingSearchRequest.class);
        } catch (IllegalArgumentException exception) {
            throw new MapContractException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_HOUSING_SEARCH_ARGUMENT",
                    "Housing search arguments do not match the catalog schema"
            );
        }
    }

    private void validateInvokeBody(ToolInvokeRequest body) {
        if (body == null || body.toolCallId() == null || body.toolCallId().isBlank()) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_TOOL_CALL", "toolCallId is required");
        }
        try {
            UUID.fromString(body.toolCallId());
        } catch (IllegalArgumentException exception) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_TOOL_CALL", "toolCallId must be a UUID");
        }
    }

    private BeginExecution beginExecution(String toolName, String toolCallId, String fingerprint) {
        synchronized (executions) {
            StoredExecution existing = executions.get(toolCallId);
            if (existing != null) {
                if (!MessageDigest.isEqual(
                        existing.fingerprint().getBytes(StandardCharsets.UTF_8),
                        fingerprint.getBytes(StandardCharsets.UTF_8)
                )) {
                    throw new MapContractException(
                            HttpStatus.CONFLICT,
                            "TOOL_CALL_CONFLICT",
                            "toolCallId was already used with different arguments"
                    );
                }
                return new BeginExecution(existing, false);
            }
            String startedAt = Instant.now().toString();
            StoredExecution created = new StoredExecution(
                    fingerprint,
                    startedAt,
                    executionData(toolName, toolCallId, "RECEIVED", startedAt, null, null, false)
            );
            executions.put(toolCallId, created);
            return new BeginExecution(created, true);
        }
    }

    private StoredExecution updateExecution(
            StoredExecution current,
            String status,
            Object result,
            Map<String, Object> error,
            boolean terminal
    ) {
        String toolName = String.valueOf(current.data().get("toolName"));
        String toolCallId = String.valueOf(current.data().get("toolCallId"));
        StoredExecution updated = new StoredExecution(
                current.fingerprint(),
                current.startedAt(),
                executionData(toolName, toolCallId, status, current.startedAt(), result, error, terminal)
        );
        executions.put(toolCallId, updated);
        return updated;
    }

    private Map<String, Object> executionData(
            String toolName,
            String toolCallId,
            String status,
            String startedAt,
            Object result,
            Map<String, Object> error,
            boolean terminal
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", toolCallId);
        data.put("toolName", toolName);
        data.put("status", status);
        data.put("catalogVersion", CATALOG_VERSION);
        data.put("startedAt", startedAt);
        data.put("updatedAt", Instant.now().toString());
        if (terminal) {
            data.put("completedAt", Instant.now().toString());
            data.put("durationMs", Math.max(0L, Duration.between(
                    Instant.parse(startedAt), Instant.now()
            ).toMillis()));
        }
        if (result != null) {
            data.put("result", result);
        }
        if (error != null) {
            data.put("error", error);
        }
        return Collections.unmodifiableMap(data);
    }

    private Map<String, Object> error(MapContractException exception) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", exception.getCode());
        error.put("message", exception.getMessage());
        error.put("retryable", exception.isRetryable());
        if (exception.getDetails() != null) {
            error.put("details", exception.getDetails());
        }
        return Collections.unmodifiableMap(error);
    }

    private String fingerprint(String toolName, ToolInvokeRequest body) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("toolName", toolName);
        value.put("arguments", body.arguments() == null ? Map.of() : body.arguments());
        value.put("dryRun", Boolean.TRUE.equals(body.dryRun()));
        try {
            byte[] canonical = objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(value);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (Exception exception) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_TOOL_ARGUMENT", "Tool arguments cannot be serialized");
        }
    }

    private Map<String, Object> tool(String name, String description, List<Integer> layerIds) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("sideEffect", false);
        tool.put("requiresConfirmation", false);
        tool.put("timeoutMs", toolTimeoutMs);
        tool.put("inputSchema", Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "oneOf", layerIds.stream().map(this::layerQuerySchema).toList()
        ));
        tool.put("outputSchema", outputSchema());
        return Collections.unmodifiableMap(tool);
    }

    private Map<String, Object> housingSearchTool() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "searchHousingCandidates");
        tool.put("description", "按住宅硬约束、便利度软偏好和道路 WS归一化空间证据执行确定性推荐或道路缓冲区筛选。便利度固定使用归一化总分，WS归一化仅来自道路图层。");
        tool.put("sideEffect", false);
        tool.put("requiresConfirmation", false);
        tool.put("timeoutMs", toolTimeoutMs);
        tool.put("inputSchema", housingSearchInputSchema());
        tool.put("outputSchema", housingSearchOutputSchema());
        return Collections.unmodifiableMap(tool);
    }

    private Map<String, Object> housingSearchInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mode", Map.of("type", "string", "enum", List.of("RANK", "BUFFER_FILTER")));
        properties.put("districts", Map.of(
                "type", "array",
                "maxItems", 3,
                "uniqueItems", true,
                "items", Map.of("type", "string", "enum", HousingSearchPolicyService.SUPPORTED_DISTRICTS)
        ));
        properties.put("hardFilters", objectSchema(
                Map.of(
                        "priceMin", Map.of("type", "number", "minimum", 0),
                        "priceMax", Map.of("type", "number", "minimum", 0)
                ),
                List.of()
        ));
        Map<String, Object> preference = objectSchema(
                Map.of(
                        "enabled", Map.of("type", "boolean"),
                        "level", Map.of("type", "string", "enum", List.of("PREFER_HIGH", "HIGH", "VERY_HIGH")),
                        "weight", Map.of("type", "number", "minimum", 0, "maximum", 1)
                ),
                List.of("enabled", "level")
        );
        Map<String, Object> pricePreference = objectSchema(
                Map.of(
                        "enabled", Map.of("type", "boolean"),
                        "level", Map.of("const", "PREFER_LOW"),
                        "weight", Map.of("type", "number", "minimum", 0, "maximum", 1)
                ),
                List.of("enabled", "level", "weight")
        );
        properties.put("preferences", objectSchema(
                Map.of(
                        "price", pricePreference,
                        "convenience", preference,
                        "roadWalkability", preference
                ),
                List.of("price", "convenience", "roadWalkability")
        ));
        properties.put("roadCriteria", objectSchema(
                Map.of(
                        "wsMin", Map.of(
                                "type", "number", "minimum", 0, "maximum", 100,
                                "description", "道路 WS归一化 最小值（0-100）"
                        ),
                        "gviMin", Map.of(
                                "type", "number", "minimum", 0, "maximum", 1,
                                "description", "道路绿视率原始分 vegetation 最小值（0-1），不使用 GVI 等级值"
                        ),
                        "noiMax", Map.of(
                                "type", "number", "minimum", 0, "maximum", 100,
                                "description", "道路噪声原始分 noise 最大值（0-100），不使用 NOI 等级值"
                        )
                ),
                List.of()
        ));
        properties.put("spatial", objectSchema(
                Map.of(
                        "relation", Map.of("const", "WITHIN_ROAD_BUFFER"),
                        "bufferMeters", Map.of("type", "integer", "minimum", 20, "maximum", 2000)
                ),
                List.of("relation")
        ));
        properties.put("display", objectSchema(
                Map.of(
                        "includeRoads", Map.of("type", "boolean"),
                        "includeBuffers", Map.of("type", "boolean")
                ),
                List.of("includeRoads", "includeBuffers")
        ));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 50));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(
                "mode", "districts", "hardFilters", "preferences", "spatial", "display", "limit"
        ));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    private Map<String, Object> housingSearchOutputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("policyVersion", Map.of("type", "string", "const", housingPolicyVersion));
        properties.put("dataVersion", Map.of("type", "string", "minLength", 1));
        properties.put("mode", Map.of("type", "string", "enum", List.of("RANK", "BUFFER_FILTER")));
        properties.put("statisticsScope", objectSchema(
                Map.of(
                        "type", Map.of("type", "string", "enum", List.of("SUPPORTED_REGION", "DISTRICT")),
                        "districts", Map.of(
                                "type", "array", "minItems", 1, "maxItems", 3,
                                "uniqueItems", true,
                                "items", Map.of("type", "string", "enum", HousingSearchPolicyService.SUPPORTED_DISTRICTS)
                        )
                ),
                List.of("type", "districts")
        ));
        properties.put("resolvedCriteria", objectSchema(
                nullableProperties(Map.of(
                        "priceMin", Map.of("type", "number"),
                        "priceMax", Map.of("type", "number"),
                        "bufferMeters", Map.of("type", "integer", "minimum", 20, "maximum", 2000),
                        "roadWsThreshold", Map.of("type", "number"),
                        "roadWsThresholdPercentile", Map.of("type", "number", "minimum", 0, "maximum", 100),
                        "defaultsApplied", Map.of(
                                "type", "array", "uniqueItems", true,
                                "items", Map.of("type", "string", "enum", List.of("BUFFER_METERS", "PREFERENCE_WEIGHTS"))
                        ),
                        "relaxationApplied", Map.of("const", false)
                ), Set.of("priceMin", "priceMax", "roadWsThreshold", "roadWsThresholdPercentile")),
                List.of(
                        "priceMin", "priceMax", "bufferMeters", "roadWsThreshold",
                        "roadWsThresholdPercentile", "defaultsApplied", "relaxationApplied"
                )
        ));
        properties.put("summary", objectSchema(
                Map.of(
                        "matchedHousingCount", Map.of("type", "integer", "minimum", 0),
                        "returnedHousingCount", Map.of("type", "integer", "minimum", 0, "maximum", 50),
                        "matchedRoadCount", Map.of("type", "integer", "minimum", 0),
                        "returnedRoadCount", Map.of("type", "integer", "minimum", 0, "maximum", 50)
                ),
                List.of("matchedHousingCount", "returnedHousingCount", "matchedRoadCount", "returnedRoadCount")
        ));
        properties.put("housingCandidates", Map.of(
                "type", "array", "maxItems", 50, "items", housingCandidateOutputSchema()
        ));
        properties.put("roadFeatures", Map.of(
                "type", "array", "maxItems", 50, "items", roadFeatureOutputSchema()
        ));
        properties.put("bufferOverlays", Map.of(
                "type", "array", "maxItems", 20, "items", bufferOverlayOutputSchema()
        ));
        properties.put("warnings", Map.of("type", "array", "items", Map.of("type", "string")));
        return objectSchemaWithDialect(properties, List.of(
                "policyVersion", "dataVersion", "mode", "statisticsScope", "resolvedCriteria", "summary",
                "housingCandidates", "roadFeatures", "bufferOverlays", "warnings"
        ));
    }

    private Map<String, Object> housingCandidateOutputSchema() {
        Map<String, Object> weights = objectSchema(Map.of(
                "price", Map.of("type", "number", "minimum", 0, "maximum", 1),
                "convenience", Map.of("type", "number", "minimum", 0, "maximum", 1),
                "roadWalkability", Map.of("type", "number", "minimum", 0, "maximum", 1)
        ), List.of("price", "convenience", "roadWalkability"));
        Map<String, Object> scoreProperties = nullableProperties(Map.of(
                "priceAffordabilityPercentile", Map.of("type", "number", "minimum", 0, "maximum", 100),
                "conveniencePercentile", Map.of("type", "number", "minimum", 0, "maximum", 100),
                "nearbyRoadWsRaw", Map.of("type", "number"),
                "nearbyRoadWsPercentile", Map.of("type", "number", "minimum", 0, "maximum", 100),
                "recommendationScore", Map.of("type", "number", "minimum", 0, "maximum", 100)
        ), Set.of(
                "priceAffordabilityPercentile", "conveniencePercentile", "nearbyRoadWsRaw",
                "nearbyRoadWsPercentile", "recommendationScore"
        ));
        scoreProperties.put("weights", weights);
        Map<String, Object> scores = objectSchema(
                scoreProperties,
                List.of(
                        "priceAffordabilityPercentile", "conveniencePercentile", "nearbyRoadWsRaw",
                        "nearbyRoadWsPercentile", "recommendationScore", "weights"
                )
        );
        Map<String, Object> evidence = objectSchema(nullableProperties(Map.of(
                "bufferMeters", Map.of("type", "integer", "minimum", 20, "maximum", 2000),
                "nearbyRoadCount", Map.of("type", "integer", "minimum", 0),
                "nearestRoadDistanceMeters", Map.of("type", "number", "minimum", 0),
                "contributingRoadIds", Map.of(
                        "type", "array", "maxItems", 50, "uniqueItems", true,
                        "items", Map.of("type", "string", "pattern", "^[3-5]:.+")
                )
        ), Set.of("nearestRoadDistanceMeters")), List.of(
                "bufferMeters", "nearbyRoadCount", "nearestRoadDistanceMeters", "contributingRoadIds"
        ));
        return objectSchema(Map.of(
                "housingId", Map.of("type", "string", "pattern", "^[0-2]:.+"),
                "layerId", Map.of("type", "integer", "minimum", 0, "maximum", 2),
                "attributes", Map.of("type", "object"),
                "geometry", pointGeometrySchema(),
                "scores", scores,
                "spatialEvidence", evidence,
                "reasons", Map.of("type", "array", "items", Map.of("type", "string")),
                "warnings", Map.of("type", "array", "items", Map.of("type", "string"))
        ), List.of("housingId", "layerId", "attributes", "geometry", "scores", "spatialEvidence", "reasons", "warnings"));
    }

    private Map<String, Object> roadFeatureOutputSchema() {
        return objectSchema(Map.of(
                "roadId", Map.of("type", "string", "pattern", "^[3-5]:.+"),
                "layerId", Map.of("type", "integer", "minimum", 3, "maximum", 5),
                "attributes", Map.of("type", "object"),
                "geometry", polylineGeometrySchema()
        ), List.of("roadId", "layerId", "attributes", "geometry"));
    }

    private Map<String, Object> bufferOverlayOutputSchema() {
        Map<String, Object> attributes = objectSchema(Map.of(
                "bufferMeters", Map.of("type", "integer", "minimum", 20, "maximum", 2000),
                "sourceRoadCount", Map.of("type", "integer", "minimum", 1),
                "sourceRoadIdsTruncated", Map.of("type", "boolean")
        ), List.of("bufferMeters", "sourceRoadCount", "sourceRoadIdsTruncated"));
        return objectSchema(Map.of(
                "overlayId", Map.of("type", "string", "pattern", "^buf-[A-Za-z0-9._:-]+$", "maxLength", 128),
                "kind", Map.of("const", "ROAD_BUFFER"),
                "geometryType", Map.of("const", "polygon"),
                "spatialReference", spatialReferenceSchema(),
                "sourceRoadIds", Map.of(
                        "type", "array", "maxItems", 20, "uniqueItems", true,
                        "items", Map.of("type", "string", "pattern", "^[3-5]:.+")
                ),
                "attributes", attributes,
                "geometry", polygonGeometrySchema()
        ), List.of(
                "overlayId", "kind", "geometryType", "spatialReference",
                "sourceRoadIds", "attributes", "geometry"
        ));
    }

    private Map<String, Object> pointGeometrySchema() {
        return objectSchema(Map.of(
                "x", Map.of("type", "number"),
                "y", Map.of("type", "number"),
                "spatialReference", spatialReferenceSchema()
        ), List.of("x", "y", "spatialReference"));
    }

    private Map<String, Object> polylineGeometrySchema() {
        Map<String, Object> coordinate = coordinateSchema();
        return objectSchema(Map.of(
                "paths", Map.of(
                        "type", "array",
                        "minItems", 1,
                        "items", Map.of(
                                "type", "array",
                                "minItems", 2,
                                "items", coordinate
                        )
                ),
                "spatialReference", spatialReferenceSchema()
        ), List.of("paths", "spatialReference"));
    }

    private Map<String, Object> polygonGeometrySchema() {
        Map<String, Object> coordinate = coordinateSchema();
        return objectSchema(Map.of(
                "rings", Map.of(
                        "type", "array",
                        "minItems", 1,
                        "items", Map.of(
                                "type", "array",
                                "minItems", 4,
                                "items", coordinate
                        )
                ),
                "spatialReference", spatialReferenceSchema()
        ), List.of("rings", "spatialReference"));
    }

    private Map<String, Object> coordinateSchema() {
        return Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "number"), Map.of("type", "number")),
                "minItems", 2,
                "maxItems", 2
        );
    }

    private Map<String, Object> spatialReferenceSchema() {
        return objectSchema(Map.of("wkid", Map.of("const", 4326)), List.of("wkid"));
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    private Map<String, Object> objectSchemaWithDialect(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>(objectSchema(properties, required));
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        return Collections.unmodifiableMap(schema);
    }

    private Map<String, Object> nullableObjectSchema(Map<String, Object> properties) {
        Map<String, Object> nullable = nullableProperties(properties, properties.keySet());
        return objectSchema(nullable, List.copyOf(properties.keySet()));
    }

    private Map<String, Object> nullableProperties(Map<String, Object> properties, Set<String> nullableFields) {
        Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((name, schema) -> result.put(
                name,
                nullableFields.contains(name) ? Map.of("oneOf", List.of(schema, Map.of("type", "null"))) : schema
        ));
        return result;
    }

    private Map<String, Object> layerQuerySchema(int layerId) {
        MapLayerDefinition layer = mapService.getLayerDefinitions().stream()
                .filter(value -> value.id() == layerId)
                .findFirst()
                .orElseThrow();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("layerId", Map.of("const", layerId));
        properties.put("filters", Map.of(
                "type", "array",
                "maxItems", 20,
                "items", Map.of("oneOf", layer.filterFields().stream().map(this::filterSchema).toList())
        ));
        properties.put("outFields", Map.of(
                "type", "array",
                "maxItems", 50,
                "uniqueItems", true,
                "items", Map.of("type", "string", "enum", layer.fields())
        ));
        properties.put("returnGeometry", Map.of("type", "boolean"));
        properties.put("resultRecordCount", Map.of("type", "integer", "minimum", 1, "maximum", 200));
        properties.put("resultOffset", Map.of("type", "integer", "minimum", 0));
        properties.put("returnCount", Map.of("type", "boolean"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("title", layer.name() + " query");
        schema.put("description", layerDescription(layer));
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("layerId", "filters", "returnGeometry", "resultRecordCount"));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    private String layerDescription(MapLayerDefinition layer) {
        return switch (layer.id()) {
            case 0 -> "沙河口区住宅点图层 shahekou_1";
            case 1 -> "西岗区住宅点图层 xigang_1";
            case 2 -> "中山区住宅点图层 zhongshan_1";
            case 3 -> "中山区道路线图层 ZhongShan";
            case 4 -> "西岗区道路线图层 XiGang";
            case 5 -> "沙河口区道路线图层 ShaHeKou";
            default -> throw new IllegalArgumentException("Unknown layer: " + layer.id());
        };
    }

    private Map<String, Object> filterSchema(MapFieldDefinition field) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("field", Map.of("const", field.name(), "description", field.label()));
        properties.put("operator", Map.of("type", "string", "enum", field.operators()));
        properties.put("value", Map.of());

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("field", "operator"));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    private Map<String, Object> outputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("layerId", Map.of("type", "integer", "minimum", 0, "maximum", 5));
        properties.put("layerName", Map.of(
                "type", "string",
                "enum", List.of("shahekou_1", "xigang_1", "zhongshan_1", "ZhongShan", "XiGang", "ShaHeKou")
        ));
        properties.put("geometryType", Map.of("type", "string", "enum", List.of("point", "polyline")));
        properties.put("total", Map.of("type", "integer", "minimum", 0));
        properties.put("exceededTransferLimit", Map.of("type", "boolean"));
        properties.put("features", Map.of(
                "type", "array",
                "maxItems", 200,
                "items", featureOutputSchema()
        ));
        return Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object",
                "properties", properties,
                "required", List.of("layerId", "layerName", "geometryType", "total", "exceededTransferLimit", "features"),
                "additionalProperties", false
        );
    }

    private Map<String, Object> featureOutputSchema() {
        Map<String, Object> spatialReference = Map.of(
                "type", "object",
                "properties", Map.of("wkid", Map.of("const", 4326)),
                "required", List.of("wkid"),
                "additionalProperties", false
        );
        Map<String, Object> point = Map.of(
                "type", "object",
                "properties", Map.of(
                        "x", Map.of("type", "number"),
                        "y", Map.of("type", "number"),
                        "spatialReference", spatialReference
                ),
                "required", List.of("x", "y", "spatialReference"),
                "additionalProperties", true
        );
        Map<String, Object> coordinate = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "number"), Map.of("type", "number")),
                "minItems", 2,
                "maxItems", 2
        );
        Map<String, Object> polyline = Map.of(
                "type", "object",
                "properties", Map.of(
                        "paths", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "items", Map.of(
                                        "type", "array",
                                        "minItems", 2,
                                        "items", coordinate
                                )
                        ),
                        "spatialReference", spatialReference
                ),
                "required", List.of("paths", "spatialReference"),
                "additionalProperties", true
        );
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "attributes", Map.of("type", "object"),
                        "geometry", Map.of("oneOf", List.of(point, polyline, Map.of("type", "null")))
                ),
                "required", List.of("attributes", "geometry"),
                "additionalProperties", false
        );
    }

    private void requireServiceRequest(HttpServletRequest request) {
        if (serviceToken.isBlank()) {
            throw new MapContractException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_TOOL_AUTH_NOT_CONFIGURED",
                    "Agent Tool service authentication is not configured",
                    true,
                    null
            );
        }
        String authorization = request.getHeader("Authorization");
        String expected = serviceToken.regionMatches(true, 0, "Bearer ", 0, 7)
                ? serviceToken
                : "Bearer " + serviceToken;
        if (authorization == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new MapContractException(HttpStatus.UNAUTHORIZED, "INVALID_SERVICE_IDENTITY", "Invalid Agent Tool service identity");
        }
        requireHeader(request, "X-Trace-Id");
    }

    private void requireRunRequest(HttpServletRequest request) {
        requireServiceRequest(request);
        requireHeader(request, "X-Tenant-Id");
        requireHeader(request, "X-User-Id");
        requireHeader(request, "X-Run-Id");
    }

    private void requireHeader(HttpServletRequest request, String header) {
        if (request.getHeader(header) == null || request.getHeader(header).isBlank()) {
            throw new MapContractException(HttpStatus.UNAUTHORIZED, "MISSING_INTERNAL_HEADER", "Missing required header: " + header);
        }
    }

    private String traceId(HttpServletRequest request) {
        String value = request.getHeader("X-Trace-Id");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    public record ToolInvokeRequest(String toolCallId, Map<String, Object> arguments, Boolean dryRun) {
    }

    private record ToolRoute(boolean linesOnly, boolean pointsOnly, boolean housingSearch) {
    }

    private record StoredExecution(String fingerprint, String startedAt, Map<String, Object> data) {
    }

    private record BeginExecution(StoredExecution execution, boolean created) {
    }
}
