package org.example.xqy1._026_silver_residence.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@Service
public class GeoSceneMapService {
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_\\u4e00-\\u9fff][A-Za-z0-9_\\u4e00-\\u9fff]*");
    private static final Set<String> OPERATORS = Set.of("=", "!=", ">", ">=", "<", "<=", "in", "like", "isnull", "isnotnull");
    private static final String STREET_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{level}/{row}/{col}";
    private static final String IMAGERY_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{level}/{row}/{col}";

    private final ObjectMapper objectMapper;
    private final String serviceUrl;
    private final String portalUrl;
    private final boolean trustAllTls;
    private final SSLSocketFactory trustAllSslSocketFactory;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int countTimeoutMs;

    private final Map<Integer, MapLayerDefinition> layerDefinitions = createLayerDefinitions();

    public GeoSceneMapService(
            ObjectMapper objectMapper,
            @Value("${map.geoscene.service-url:https://xqy0919.dev.local/server/rest/services/demo/%E5%9C%B0%E5%9B%BE/MapServer}") String serviceUrl,
            @Value("${map.geoscene.portal-url:https://edutrial.geoscene.cn/geoscene}") String portalUrl,
            @Value("${map.geoscene.trust-all-tls:false}") boolean trustAllTls,
            @Value("${map.geoscene.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${map.geoscene.read-timeout-ms:90000}") int readTimeoutMs,
            @Value("${map.geoscene.count-timeout-ms:10000}") int countTimeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.serviceUrl = stripTrailingSlash(serviceUrl);
        this.portalUrl = portalUrl;
        this.trustAllTls = trustAllTls;
        this.connectTimeoutMs = Math.max(1000, connectTimeoutMs);
        this.readTimeoutMs = Math.max(1000, readTimeoutMs);
        this.countTimeoutMs = Math.max(1000, countTimeoutMs);
        this.trustAllSslSocketFactory = trustAllTls ? createTrustAllSslSocketFactory() : null;
    }

    public MapConfig getConfig() {
        return new MapConfig(
                portalUrl,
                Map.of("street", STREET_URL, "imagery", IMAGERY_URL),
                50000,
                19,
                serviceUrl,
                List.of(121.62, 38.91),
                11,
                List.copyOf(layerDefinitions.values())
        );
    }

    public List<MapLayerDefinition> getLayerDefinitions() {
        return List.copyOf(layerDefinitions.values());
    }

    public void validateAgentQuery(MapFeatureQueryRequest request, boolean linesOnly, boolean pointsOnly) {
        MapLayerDefinition layer = requireLayer(request, linesOnly, pointsOnly);
        if (request.filters().size() > 20) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "TOO_MANY_FILTERS", "Agent query accepts at most 20 filters");
        }
        Set<String> filterFields = layer.filterFields().stream()
                .map(MapFieldDefinition::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (MapFilterCondition filter : request.filters()) {
            if (filter == null || filter.field() == null || !filterFields.contains(filter.field())) {
                throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_FIELD", "Agent filter field is not in the tool catalog");
            }
        }
        int recordCount = request.resultRecordCount() == null ? 200 : request.resultRecordCount();
        if (recordCount < 1 || recordCount > 200) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_RECORD_COUNT", "Agent resultRecordCount must be between 1 and 200");
        }
        if (request.resultOffset() != null && request.resultOffset() < 0) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_RESULT_OFFSET", "resultOffset must not be negative");
        }
        buildWhere(request, layer);
        normalizeOutFields(request.outFields(), layer);
        addSpatialFilter(new LinkedHashMap<>(), request);
    }

    public GeoSceneProxyResponse proxyGet(String rawPath, String rawQuery) {
        if (rawPath == null || rawPath.isBlank()) {
            rawPath = "/";
        }
        if (!rawPath.matches("/[A-Za-z0-9_./%\\-]*") || rawPath.contains("..")) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_PROXY_PATH", "GeoScene proxy path is not allowed");
        }
        String endpoint = serviceUrl + (rawPath.startsWith("/") ? rawPath : "/" + rawPath);
        if (rawQuery != null && !rawQuery.isBlank()) {
            endpoint += "?" + rawQuery;
        }
        try {
            return sendGet(endpoint, readTimeoutMs, "*/*");
        } catch (Exception exception) {
            throw new MapContractException(HttpStatus.BAD_GATEWAY, "GEOSCENE_UNAVAILABLE", "Unable to reach GeoScene map service", true, Map.of("reason", exception.getClass().getSimpleName()));
        }
    }

    public MapFeatureQueryResult query(MapFeatureQueryRequest request, boolean linesOnly, boolean pointsOnly) {
        MapLayerDefinition layer = requireLayer(request, linesOnly, pointsOnly);
        int layerId = layer.id();

        String where = buildWhere(request, layer);
        List<String> outFields = normalizeOutFields(request.outFields(), layer);
        boolean returnGeometry = request.returnGeometry() == null || request.returnGeometry();
        int recordCount = clamp(request.resultRecordCount() == null ? 200 : request.resultRecordCount(), 1, 2000);
        int offset = Math.max(0, request.resultOffset() == null ? 0 : request.resultOffset());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("f", "json");
        params.put("where", where);
        params.put("outFields", String.join(",", outFields));
        params.put("returnGeometry", Boolean.toString(returnGeometry));
        params.put("outSR", "4326");
        params.put("resultRecordCount", Integer.toString(recordCount));
        params.put("resultOffset", Integer.toString(offset));
        addSpatialFilter(params, request);
        JsonNode response = getJson(
                serviceUrl + "/" + layerId + "/query",
                params,
                readTimeoutMs,
                Map.of(
                        "layerId", layerId,
                        "phase", "feature-page",
                        "offset", offset,
                        "recordCount", recordCount
                )
        );
        if (response.has("error")) {
            JsonNode error = response.path("error");
            throw new MapContractException(HttpStatus.BAD_GATEWAY, "GEOSCENE_QUERY_FAILED", error.path("message").asText("GeoScene query failed"), true, Map.of("layerId", layerId));
        }

        List<MapFeature> features = new ArrayList<>();
        for (JsonNode feature : response.path("features")) {
            Map<String, Object> attributes = objectMapper.convertValue(feature.path("attributes"), Map.class);
            JsonNode geometry = returnGeometry && feature.has("geometry")
                    ? normalizeGeometry(feature.get("geometry"))
                    : null;
            features.add(new MapFeature(attributes, geometry));
        }
        boolean returnCount = request.returnCount() == null || request.returnCount();
        long total = response.has("count")
                ? response.path("count").asLong()
                : returnCount ? countFeatures(layerId, where, request) : features.size();
        return new MapFeatureQueryResult(layerId, layer.name(), layer.geometryType(), total,
                response.path("exceededTransferLimit").asBoolean(false), features);
    }

    private MapLayerDefinition requireLayer(MapFeatureQueryRequest request, boolean linesOnly, boolean pointsOnly) {
        if (request == null) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Map query request is required");
        }
        int layerId = request.layerId() == null ? -1 : request.layerId();
        MapLayerDefinition layer = layerDefinitions.get(layerId);
        if (layer == null) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_LAYER", "layerId must be one of 0 through 5");
        }
        boolean isLine = "polyline".equals(layer.geometryType());
        if (linesOnly && !isLine || pointsOnly && isLine) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_LAYER_TYPE", "The requested endpoint does not accept this layer geometry type");
        }
        return layer;
    }

    private long countFeatures(int layerId, String where, MapFeatureQueryRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("f", "json");
        params.put("where", where);
        params.put("returnCountOnly", "true");
        params.put("outSR", "4326");
        addSpatialFilter(params, request);
        JsonNode countResponse = getJson(
                serviceUrl + "/" + layerId + "/query",
                params,
                countTimeoutMs,
                Map.of("layerId", layerId, "phase", "count")
        );
        if (!countResponse.has("count")) {
            throw new MapContractException(
                    HttpStatus.BAD_GATEWAY,
                    "GEOSCENE_QUERY_FAILED",
                    "GeoScene count response did not contain an exact count",
                    true,
                    Map.of("layerId", layerId, "phase", "count")
            );
        }
        return countResponse.path("count").asLong();
    }

    private JsonNode normalizeGeometry(JsonNode geometry) {
        if (geometry == null || !geometry.isObject()) {
            return geometry;
        }
        ObjectNode normalized = geometry.deepCopy();
        normalized.set("spatialReference", objectMapper.createObjectNode().put("wkid", 4326));
        return normalized;
    }

    private void addSpatialFilter(Map<String, String> params, MapFeatureQueryRequest request) {
        if (request.longitude() == null || request.latitude() == null) {
            return;
        }
        if (!Double.isFinite(request.longitude()) || !Double.isFinite(request.latitude())
                || request.longitude() < -180 || request.longitude() > 180
                || request.latitude() < -90 || request.latitude() > 90) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_GEOMETRY", "longitude and latitude must be valid WGS84 coordinates");
        }
        params.put("geometry", request.longitude() + "," + request.latitude());
        params.put("geometryType", "esriGeometryPoint");
        params.put("inSR", "4326");
        params.put("spatialRel", "esriSpatialRelIntersects");
        if (request.distanceMeters() != null) {
            double distance = request.distanceMeters();
            if (!Double.isFinite(distance) || distance < 0 || distance > 1000) {
                throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_DISTANCE", "distanceMeters must be between 0 and 1000");
            }
            params.put("distance", Double.toString(distance));
            params.put("units", "esriSRUnit_Meter");
        }
    }

    private String buildWhere(MapFeatureQueryRequest request, MapLayerDefinition layer) {
        if (request.where() != null && !request.where().isBlank() && !"1=1".equals(request.where().replace(" ", ""))) {
            throw new MapContractException(HttpStatus.BAD_REQUEST, "RAW_WHERE_NOT_ALLOWED", "Use filters instead of a raw where expression");
        }
        if (request.filters().isEmpty()) {
            return "1=1";
        }
        List<String> clauses = new ArrayList<>();
        for (MapFilterCondition filter : request.filters()) {
            if (filter == null || filter.field() == null || !layer.fields().contains(filter.field()) || !SAFE_FIELD.matcher(filter.field()).matches()) {
                throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_FIELD", "Filter field is not allowed for this layer");
            }
            String operator = filter.operator() == null ? "=" : filter.operator().toLowerCase(Locale.ROOT);
            if (!OPERATORS.contains(operator)) {
                throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_OPERATOR", "Filter operator is not supported");
            }
            if ((operator.equals("isnull") || operator.equals("isnotnull"))) {
                clauses.add(filter.field() + (operator.equals("isnull") ? " IS NULL" : " IS NOT NULL"));
                continue;
            }
            if (filter.value() == null) {
                throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_VALUE", "Filter value is required");
            }
            if (operator.equals("in")) {
                if (!(filter.value() instanceof List<?> values) || values.isEmpty() || values.size() > 100) {
                    throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_VALUE", "in requires one to one hundred values");
                }
                clauses.add(filter.field() + " IN (" + values.stream().map(this::sqlLiteral).reduce((a, b) -> a + "," + b).orElse("") + ")");
            } else {
                String sqlOperator = operator.equals("like") ? " LIKE " : " " + operator + " ";
                String literal = sqlLiteral(filter.value());
                if (operator.equals("like")) {
                    literal = sqlLiteral("%" + filter.value() + "%");
                }
                clauses.add(filter.field() + sqlOperator + literal);
            }
        }
        return String.join(" AND ", clauses);
    }

    private List<String> normalizeOutFields(List<String> requested, MapLayerDefinition layer) {
        if (requested == null || requested.isEmpty() || requested.contains("*")) {
            return List.of("*");
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (String field : requested) {
            if (field == null || !layer.fields().contains(field)) {
                throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_OUT_FIELD", "Requested outFields contains a field not allowed for this layer");
            }
            fields.add(field);
        }
        return fields.isEmpty() ? List.of("*") : List.copyOf(fields);
    }

    private String sqlLiteral(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private JsonNode getJson(String endpoint, Map<String, String> params) {
        return getJson(endpoint, params, readTimeoutMs, Map.of());
    }

    private JsonNode getJson(String endpoint, Map<String, String> params, int timeoutMs) {
        return getJson(endpoint, params, timeoutMs, Map.of());
    }

    private JsonNode getJson(
            String endpoint,
            Map<String, String> params,
            int timeoutMs,
            Map<String, Object> context
    ) {
        String query = params.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        long startedAt = System.nanoTime();
        try {
            GeoSceneProxyResponse response = sendGet(endpoint + "?" + query, timeoutMs, "application/json");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MapContractException(
                        HttpStatus.BAD_GATEWAY,
                        "GEOSCENE_HTTP_ERROR",
                        "GeoScene returned HTTP " + response.statusCode(),
                        true,
                        diagnosticDetails(endpoint, context, startedAt, Map.of("status", response.statusCode()))
                );
            }
            return objectMapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
        } catch (MapContractException exception) {
            throw exception;
        } catch (SocketTimeoutException exception) {
            throw new MapContractException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "GEOSCENE_QUERY_TIMEOUT",
                    "GeoScene query exceeded the configured timeout",
                    true,
                    diagnosticDetails(endpoint, context, startedAt, Map.of("timeoutMs", timeoutMs))
            );
        } catch (Exception exception) {
            throw new MapContractException(
                    HttpStatus.BAD_GATEWAY,
                    "GEOSCENE_UNAVAILABLE",
                    "Unable to reach GeoScene map service",
                    true,
                    diagnosticDetails(
                            endpoint,
                            context,
                            startedAt,
                            Map.of("reason", exception.getClass().getSimpleName())
                    )
            );
        }
    }

    private Map<String, Object> diagnosticDetails(
            String endpoint,
            Map<String, Object> context,
            long startedAt,
            Map<String, Object> additional
    ) {
        Map<String, Object> details = new LinkedHashMap<>(context);
        details.put("endpointPath", URI.create(endpoint).getPath());
        details.put("durationMs", (System.nanoTime() - startedAt) / 1_000_000L);
        details.putAll(additional);
        return Collections.unmodifiableMap(details);
    }

    private GeoSceneProxyResponse sendGet(String endpoint, int timeoutMs, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        if (connection instanceof HttpsURLConnection httpsConnection && trustAllTls) {
            httpsConnection.setSSLSocketFactory(trustAllSslSocketFactory);
            httpsConnection.setHostnameVerifier((hostname, session) -> true);
        }
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", accept);
        try {
            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            byte[] body;
            if (responseStream == null) {
                body = new byte[0];
            } else {
                try (InputStream input = responseStream) {
                    body = input.readAllBytes();
                }
            }
            return new GeoSceneProxyResponse(statusCode, connection.getContentType(), body);
        } finally {
            connection.disconnect();
        }
    }

    private SSLSocketFactory createTrustAllSslSocketFactory() {
        try {
            TrustManager[] trustManagers = {new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize GeoScene TLS", exception);
        }
    }

    private static Map<Integer, MapLayerDefinition> createLayerDefinitions() {
        Map<Integer, MapLayerDefinition> definitions = new LinkedHashMap<>();
        List<String> pointFields = List.of("OBJECTID", "Shape", "id", "name", "address", "wgs84_x", "wgs84_y", "pname", "cityname", "adname", "大类", "中类", "小类", "餐饮密度", "风景密度", "科教密度", "购物密度", "金融密度", "公共密度", "覆盖度", "覆盖度评分", "新步行", "总原始分", "归一化总分", "房价");
        List<String> shahekouPointFields = new ArrayList<>(pointFields);
        shahekouPointFields.add("交通密度");
        List<String> zhongshanLineFields = List.of("OBJECTID_12", "OBJECTID", "Shape", "fclass", "name", "typecode", "wgs84_x", "wgs84_y", "GVI", "NOI", "WS", "Shape_Length");
        List<String> districtLineFields = List.of("OBJECTID_12", "Shape", "fclass", "name", "typecode", "wgs84_x", "wgs84_y", "GVI", "NOI", "WS", "Shape_Length");
        definitions.put(0, new MapLayerDefinition(0, "shahekou_1", "point", "name", List.copyOf(shahekouPointFields), pointFilters()));
        definitions.put(1, new MapLayerDefinition(1, "xigang_1", "point", "name", pointFields, pointFilters()));
        definitions.put(2, new MapLayerDefinition(2, "zhongshan_1", "point", "name", pointFields, pointFilters()));
        definitions.put(3, new MapLayerDefinition(3, "ZhongShan", "polyline", "name", zhongshanLineFields, lineFilters()));
        definitions.put(4, new MapLayerDefinition(4, "XiGang", "polyline", "name", districtLineFields, lineFilters()));
        definitions.put(5, new MapLayerDefinition(5, "ShaHeKou", "polyline", "name", districtLineFields, lineFilters()));
        return Collections.unmodifiableMap(definitions);
    }

    private static List<MapFieldDefinition> pointFilters() {
        return List.of(
                field("name", "名称", "string", "=", "like"),
                field("adname", "行政区", "string", "=", "like"),
                field("房价", "房价", "number", "=", ">=", "<=", ">", "<"),
                field("覆盖度评分", "覆盖度评分", "number", "=", ">=", "<=", ">", "<"),
                field("归一化总分", "综合评分", "number", "=", ">=", "<=", ">", "<")
        );
    }

    private static List<MapFieldDefinition> lineFilters() {
        return List.of(
                field("GVI", "绿视率", "number", "=", ">=", "<=", ">", "<"),
                field("NOI", "道路噪声", "number", "=", ">=", "<=", ">", "<"),
                field("WS", "步行指数", "number", "=", ">=", "<=", ">", "<"),
                field("Shape_Length", "道路长度", "number", "=", ">=", "<=", ">", "<"),
                field("name", "道路名称", "string", "=", "like")
        );
    }

    private static MapFieldDefinition field(String name, String label, String type, String... operators) {
        return new MapFieldDefinition(name, label, type, List.of(operators));
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String stripTrailingSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
