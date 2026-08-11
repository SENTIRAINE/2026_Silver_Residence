package org.example.xqy1._026_silver_residence.housing;

import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.example.xqy1._026_silver_residence.map.GeoSceneMapService;
import org.example.xqy1._026_silver_residence.map.MapFeature;
import org.example.xqy1._026_silver_residence.map.MapFeatureQueryRequest;
import org.example.xqy1._026_silver_residence.map.MapFeatureQueryResult;
import org.example.xqy1._026_silver_residence.map.MapLayerDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class GeoSceneHousingSearchDataProvider implements HousingSearchDataProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeoSceneHousingSearchDataProvider.class);
    private static final int DEFAULT_PAGE_SIZE = 2000;
    private static final int DEFAULT_MAX_PAGES_PER_LAYER = 1000;
    private static final Map<Integer, String> DISTRICTS = Map.of(
            0, "沙河口区",
            1, "西岗区",
            2, "中山区",
            3, "中山区",
            4, "西岗区",
            5, "沙河口区"
    );
    private static final Map<Integer, String> OBJECT_ID_FIELDS = Map.of(
            0, "OBJECTID",
            1, "OBJECTID",
            2, "OBJECTID",
            3, "OBJECTID_12",
            4, "OBJECTID_12",
            5, "OBJECTID_12"
    );

    private final GeoSceneMapService mapService;
    private final long snapshotTtlMs;
    private final long snapshotMaxStaleMs;
    private final Clock clock;
    private final int pageSize;
    private final int maxPagesPerLayer;
    private final Map<Integer, List<String>> outFieldsByLayer;
    private final Executor snapshotExecutor;
    private final boolean prewarmEnabled;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    private volatile HousingSearchSnapshot cached;
    private volatile Instant lastRefreshFailureAt;
    private volatile String lastRefreshFailureCode;

    @Autowired
    public GeoSceneHousingSearchDataProvider(
            GeoSceneMapService mapService,
            @Value("${housing.search.snapshot-ttl-ms:3600000}") long snapshotTtlMs,
            @Value("${housing.search.snapshot-max-stale-ms:86400000}") long snapshotMaxStaleMs,
            @Value("${housing.search.snapshot-prewarm-enabled:true}") boolean prewarmEnabled,
            @Qualifier("housingSnapshotExecutor") Executor snapshotExecutor
    ) {
        this(
                mapService,
                Math.max(60_000L, snapshotTtlMs),
                Math.max(snapshotTtlMs, snapshotMaxStaleMs),
                Clock.systemUTC(),
                DEFAULT_PAGE_SIZE,
                DEFAULT_MAX_PAGES_PER_LAYER,
                prewarmEnabled,
                snapshotExecutor
        );
    }

    GeoSceneHousingSearchDataProvider(
            GeoSceneMapService mapService,
            long snapshotTtlMs,
            Clock clock,
            int pageSize,
            int maxPagesPerLayer
    ) {
        this(mapService, snapshotTtlMs, Long.MAX_VALUE, clock, pageSize, maxPagesPerLayer, false, Runnable::run);
    }

    GeoSceneHousingSearchDataProvider(
            GeoSceneMapService mapService,
            long snapshotTtlMs,
            long snapshotMaxStaleMs,
            Clock clock,
            int pageSize,
            int maxPagesPerLayer,
            boolean prewarmEnabled,
            Executor snapshotExecutor
    ) {
        this.mapService = mapService;
        this.snapshotTtlMs = Math.max(1L, snapshotTtlMs);
        this.snapshotMaxStaleMs = Math.max(this.snapshotTtlMs, snapshotMaxStaleMs);
        this.clock = clock;
        this.pageSize = Math.max(1, pageSize);
        this.maxPagesPerLayer = Math.max(1, maxPagesPerLayer);
        this.prewarmEnabled = prewarmEnabled;
        this.snapshotExecutor = snapshotExecutor;
        Map<Integer, List<String>> fields = new LinkedHashMap<>();
        for (MapLayerDefinition layer : mapService.getLayerDefinitions()) {
            fields.put(layer.id(), List.copyOf(layer.fields()));
        }
        this.outFieldsByLayer = Map.copyOf(fields);
    }

    @Override
    public HousingSearchSnapshot loadSnapshot() {
        HousingSearchSnapshot current = cached;
        Instant now = clock.instant();
        if (current == null) {
            triggerRefresh();
            throw snapshotUnavailable("warming", now);
        }
        if (!current.builtAt().plusMillis(snapshotTtlMs).isAfter(now)) {
            triggerRefresh();
        }
        if (!current.builtAt().plusMillis(snapshotMaxStaleMs).isAfter(now)) {
            throw snapshotUnavailable("stale", now);
        }
        return current;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prewarmAfterStartup() {
        if (prewarmEnabled) {
            triggerRefresh();
        }
    }

    @Scheduled(fixedDelayString = "${housing.search.snapshot-refresh-interval-ms:300000}")
    public void scheduleRefresh() {
        if (!prewarmEnabled) {
            return;
        }
        HousingSearchSnapshot current = cached;
        if (current == null || !current.builtAt().plusMillis(snapshotTtlMs).isAfter(clock.instant())) {
            triggerRefresh();
        }
    }

    public void refreshSnapshot() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        Instant startedAt = clock.instant();
        try {
            HousingSearchSnapshot rebuilt = rebuild(startedAt);
            cached = rebuilt;
            lastRefreshFailureAt = null;
            lastRefreshFailureCode = null;
            LOGGER.info(
                    "GeoScene housing snapshot refreshed version={} housingCount={} roadCount={}",
                    rebuilt.dataVersion(), rebuilt.housing().size(), rebuilt.roads().size()
            );
        } catch (RuntimeException exception) {
            lastRefreshFailureAt = clock.instant();
            lastRefreshFailureCode = exception instanceof MapContractException contractException
                    ? contractException.getCode()
                    : exception.getClass().getSimpleName();
            LOGGER.error(
                    "GeoScene housing snapshot refresh failed code={}",
                    lastRefreshFailureCode,
                    exception
            );
            throw exception;
        } finally {
            refreshInProgress.set(false);
        }
    }

    public Map<String, Object> health() {
        HousingSearchSnapshot current = cached;
        Instant now = clock.instant();
        Map<String, Object> status = new LinkedHashMap<>();
        String snapshotStatus;
        if (current == null) {
            snapshotStatus = "WARMING";
        } else if (!current.builtAt().plusMillis(snapshotMaxStaleMs).isAfter(now)) {
            snapshotStatus = "STALE";
        } else if (lastRefreshFailureAt != null && lastRefreshFailureAt.isAfter(current.builtAt())) {
            snapshotStatus = "DEGRADED";
        } else {
            snapshotStatus = "READY";
        }
        status.put("status", snapshotStatus);
        status.put("refreshInProgress", refreshInProgress.get());
        status.put("snapshotVersion", current == null ? null : current.dataVersion());
        status.put("snapshotBuiltAt", current == null ? null : current.builtAt().toString());
        status.put("lastRefreshFailureAt", lastRefreshFailureAt == null ? null : lastRefreshFailureAt.toString());
        status.put("lastRefreshFailureCode", lastRefreshFailureCode);
        return java.util.Collections.unmodifiableMap(status);
    }

    private void triggerRefresh() {
        if (refreshInProgress.get()) {
            return;
        }
        snapshotExecutor.execute(() -> {
            try {
                refreshSnapshot();
            } catch (RuntimeException ignored) {
                // The failure is retained in health() and logged by refreshSnapshot().
            }
        });
    }

    private MapContractException snapshotUnavailable(String state, Instant now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("snapshotState", state);
        details.put("observedAt", now.toString());
        details.put("refreshInProgress", refreshInProgress.get());
        details.put("lastRefreshFailureCode", lastRefreshFailureCode);
        return new MapContractException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "METRIC_STATISTICS_UNAVAILABLE",
                "GeoScene housing statistics snapshot is not ready",
                true,
                details
        );
    }

    private HousingSearchSnapshot rebuild(Instant builtAt) {
        List<HousingSearchFeature> housing = new ArrayList<>();
        List<HousingSearchFeature> roads = new ArrayList<>();
        List<CompletableFuture<List<HousingSearchFeature>>> layers = new ArrayList<>();
        for (int layerId = 0; layerId <= 5; layerId++) {
            int requestedLayerId = layerId;
            layers.add(CompletableFuture.supplyAsync(() -> loadLayer(requestedLayerId), snapshotExecutor));
        }
        for (int layerId = 0; layerId <= 5; layerId++) {
            List<HousingSearchFeature> features;
            try {
                features = layers.get(layerId).join();
            } catch (CompletionException exception) {
                layers.forEach(future -> future.cancel(true));
                if (exception.getCause() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw exception;
            }
            if (layerId <= 2) {
                housing.addAll(features);
            } else {
                roads.addAll(features);
            }
        }
        if (housing.isEmpty() || roads.isEmpty()) {
            throw new MapContractException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "METRIC_STATISTICS_UNAVAILABLE",
                    "住宅或道路数据快照为空",
                    true,
                    null
            );
        }
        String version = "geoscene-snapshot-" + builtAt.truncatedTo(ChronoUnit.SECONDS);
        return new HousingSearchSnapshot(version, builtAt, housing, roads);
    }

    private List<HousingSearchFeature> loadLayer(int layerId) {
        List<HousingSearchFeature> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int offset = 0;
        List<String> outFields = outFieldsByLayer.get(layerId);
        if (outFields == null || outFields.isEmpty()) {
            throw new MapContractException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATA_VERSION_MISMATCH",
                    "GeoScene 图层目录缺少快照字段定义",
                    true,
                    Map.of("layerId", layerId)
            );
        }
        for (int page = 0; page < maxPagesPerLayer; page++) {
            MapFeatureQueryRequest request = new MapFeatureQueryRequest(
                    layerId,
                    List.of(),
                    null,
                    outFields,
                    true,
                    pageSize,
                    offset,
                    null,
                    null,
                    null,
                    false
            );
            MapFeatureQueryResult response = mapService.query(request, layerId >= 3, layerId <= 2);
            if (response.features().isEmpty()) {
                return List.copyOf(result);
            }
            for (MapFeature feature : response.features()) {
                String id = featureId(layerId, feature.attributes());
                if (seen.add(id)) {
                    result.add(new HousingSearchFeature(
                            id,
                            layerId,
                            DISTRICTS.get(layerId),
                            new LinkedHashMap<>(feature.attributes()),
                            feature.geometry()
                    ));
                }
            }
            offset += response.features().size();
            if (response.features().size() < pageSize && !response.exceededTransferLimit()) {
                return List.copyOf(result);
            }
        }
        throw new MapContractException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SPATIAL_RESULT_TOO_LARGE",
                "GeoScene 图层分页超过安全上限",
                false,
                Map.of("layerId", layerId, "maxPages", maxPagesPerLayer)
        );
    }

    private String featureId(int layerId, Map<String, Object> attributes) {
        String field = OBJECT_ID_FIELDS.get(layerId);
        Object objectId = attributes.get(field);
        if (objectId == null && layerId >= 3) {
            objectId = attributes.get("OBJECTID");
        }
        if (objectId == null) {
            throw new MapContractException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATA_VERSION_MISMATCH",
                    "GeoScene 要素缺少稳定对象 ID",
                    true,
                    Map.of("layerId", layerId, "objectIdField", field)
            );
        }
        return layerId + ":" + objectId;
    }
}
