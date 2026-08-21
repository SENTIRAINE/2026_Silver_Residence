package org.example.xqy1._026_silver_residence.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.xqy1._026_silver_residence.api.ContractResponse;
import org.example.xqy1._026_silver_residence.map.GeoSceneMapService;
import org.example.xqy1._026_silver_residence.map.GeoSceneProxyResponse;
import org.example.xqy1._026_silver_residence.map.MapConfig;
import org.example.xqy1._026_silver_residence.map.MapFeatureQueryRequest;
import org.example.xqy1._026_silver_residence.map.MapFeatureQueryResult;
import org.example.xqy1._026_silver_residence.map.MapLayerDefinition;
import org.example.xqy1._026_silver_residence.map.LineRegionalStats;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/map")
public class MapFeatureController {
    private final GeoSceneMapService mapService;

    @GetMapping("/config")
    public ContractResponse<MapConfig> config(HttpServletRequest request) {
        return ContractResponse.success(mapService.getConfig(), traceId(request));
    }

    @GetMapping("/filter-schema")
    public ContractResponse<List<MapLayerDefinition>> filterSchema(HttpServletRequest request) {
        return ContractResponse.success(mapService.getLayerDefinitions(), traceId(request));
    }

    @GetMapping({"/geoscene", "/geoscene/{*path}"})
    public ResponseEntity<byte[]> proxyGeoScene(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/api/map/geoscene";
        String requestUri = request.getRequestURI();
        String rawPath = requestUri.startsWith(prefix) ? requestUri.substring(prefix.length()) : "/";
        if (rawPath.isEmpty()) {
            rawPath = "/";
        }
        GeoSceneProxyResponse upstream = mapService.proxyGet(rawPath, request.getQueryString());
        HttpHeaders headers = new HttpHeaders();
        if (upstream.contentType() != null && !upstream.contentType().isBlank()) {
            try {
                headers.setContentType(MediaType.parseMediaType(upstream.contentType()));
            } catch (IllegalArgumentException ignored) {
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            }
        }
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        return new ResponseEntity<>(upstream.body(), headers, upstream.statusCode());
    }

    @GetMapping({"/poi", "/poi/{*path}"})
    public ResponseEntity<byte[]> proxyPoi(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/api/map/poi";
        String requestUri = request.getRequestURI();
        String rawPath = requestUri.startsWith(prefix) ? requestUri.substring(prefix.length()) : "/";
        if (rawPath.isEmpty()) {
            rawPath = "/";
        }
        GeoSceneProxyResponse upstream = mapService.proxyPoiGet(rawPath, request.getQueryString());
        HttpHeaders headers = new HttpHeaders();
        if (upstream.contentType() != null && !upstream.contentType().isBlank()) {
            try {
                headers.setContentType(MediaType.parseMediaType(upstream.contentType()));
            } catch (IllegalArgumentException ignored) {
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            }
        }
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        return new ResponseEntity<>(upstream.body(), headers, upstream.statusCode());
    }

    @GetMapping({"/slope", "/slope/{*path}"})
    public ResponseEntity<byte[]> proxySlope(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/api/map/slope";
        String requestUri = request.getRequestURI();
        String rawPath = requestUri.startsWith(prefix) ? requestUri.substring(prefix.length()) : "/";
        if (rawPath.isEmpty()) {
            rawPath = "/";
        }
        GeoSceneProxyResponse upstream = mapService.proxySlopeGet(rawPath, request.getQueryString());
        HttpHeaders headers = new HttpHeaders();
        if (upstream.contentType() != null && !upstream.contentType().isBlank()) {
            try {
                headers.setContentType(MediaType.parseMediaType(upstream.contentType()));
            } catch (IllegalArgumentException ignored) {
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            }
        }
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        return new ResponseEntity<>(upstream.body(), headers, upstream.statusCode());
    }

    @PostMapping("/query-features")
    public ContractResponse<MapFeatureQueryResult> queryFeatures(
            @RequestBody MapFeatureQueryRequest request,
            HttpServletRequest servletRequest
    ) {
        return ContractResponse.success(mapService.query(request, false, false), traceId(servletRequest));
    }

    @PostMapping("/query-points")
    public ContractResponse<MapFeatureQueryResult> queryPoints(
            @RequestBody MapFeatureQueryRequest request,
            HttpServletRequest servletRequest
    ) {
        return ContractResponse.success(mapService.query(request, false, true), traceId(servletRequest));
    }

    @PostMapping("/query-lines")
    public ContractResponse<MapFeatureQueryResult> queryLines(
            @RequestBody MapFeatureQueryRequest request,
            HttpServletRequest servletRequest
    ) {
        return ContractResponse.success(mapService.query(request, true, false), traceId(servletRequest));
    }

    @GetMapping("/line-regional-stats/{layerId}")
    public ContractResponse<LineRegionalStats> lineRegionalStats(
            @org.springframework.web.bind.annotation.PathVariable int layerId,
            HttpServletRequest servletRequest
    ) {
        return ContractResponse.success(mapService.queryLineRegionalStats(layerId), traceId(servletRequest));
    }

    private String traceId(HttpServletRequest request) {
        String value = request.getHeader("X-Trace-Id");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
