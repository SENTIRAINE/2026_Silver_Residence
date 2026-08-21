package org.example.xqy1._026_silver_residence.housing;

import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

@Service
public class HousingSearchService {
    private static final int DEFAULT_ROAD_DISPLAY_COUNT = 30;

    private final HousingSearchPolicyService policy;
    private final MetricStatisticsService statistics;
    private final HousingSearchDataProvider dataProvider;
    private final RoadSpatialSearchService spatialSearch;

    public HousingSearchService(
            HousingSearchPolicyService policy,
            MetricStatisticsService statistics,
            HousingSearchDataProvider dataProvider,
            RoadSpatialSearchService spatialSearch
    ) {
        this.policy = policy;
        this.statistics = statistics;
        this.dataProvider = dataProvider;
        this.spatialSearch = spatialSearch;
    }

    public ResolvedHousingSearchRequest validate(HousingSearchRequest request) {
        return policy.resolve(request);
    }

    public HousingSearchResult search(HousingSearchRequest request) {
        ResolvedHousingSearchRequest resolved = policy.resolve(request);
        HousingSearchSnapshot snapshot = dataProvider.loadSnapshot();
        Set<String> requestedDistricts = Set.copyOf(resolved.districts());
        List<HousingSearchFeature> scopeHousing = snapshot.housing().stream()
                .filter(feature -> requestedDistricts.contains(feature.district()))
                .toList();
        List<HousingSearchFeature> scopeRoads = snapshot.roads().stream()
                .filter(feature -> requestedDistricts.contains(feature.district()))
                .toList();
        if (scopeHousing.isEmpty() || scopeRoads.isEmpty()) {
            throw new MapContractException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "METRIC_STATISTICS_UNAVAILABLE",
                    "当前统计作用域缺少住宅或道路数据",
                    true,
                    Map.of("districts", resolved.districts())
            );
        }

        List<String> warnings = new ArrayList<>();
        applyDefaultWarnings(resolved, warnings);
        MetricStatisticsService.MetricDistribution convenienceDistribution = optionalDistribution(
                scopeHousing.stream().map(feature -> number(feature.attributes().get(
                        HousingSearchPolicyService.CONVENIENCE_SOURCE_FIELD))).toList(),
                "convenience"
        );
        MetricStatisticsService.MetricDistribution priceDistribution = optionalDistribution(
                scopeHousing.stream().map(feature -> number(feature.attributes().get("房价"))).toList(),
                "priceAffordability"
        );
        MetricStatisticsService.MetricDistribution roadWsDistribution = optionalDistribution(
                scopeRoads.stream().map(feature -> number(feature.attributes().get(
                        HousingSearchPolicyService.ROAD_WALKABILITY_SOURCE_FIELD))).toList(),
                "roadWalkability"
        );

        Double roadThreshold = resolved.roadCriteria().wsMin();
        Double roadThresholdPercentile = null;
        List<HousingSearchFeature> calculationRoads = scopeRoads.stream()
                .filter(roadCriteria(resolved.roadCriteria()))
                .toList();
        if (resolved.mode() == HousingSearchRequest.Mode.BUFFER_FILTER
                && resolved.roadCriteria().wsMin() == null) {
            requireStatistics(roadWsDistribution, "roadWalkability");
            double thresholdPercentile = preferencePercentile(
                    resolved.preferences().roadWalkability().level()
            );
            roadThresholdPercentile = thresholdPercentile * 100.0;
            roadThreshold = roadWsDistribution.percentileValue(thresholdPercentile);
            double threshold = roadThreshold;
            calculationRoads = calculationRoads.stream()
                    .filter(road -> finiteNumber(road.attributes().get(HousingSearchPolicyService.ROAD_WALKABILITY_SOURCE_FIELD)) != null
                            && finiteNumber(road.attributes().get(HousingSearchPolicyService.ROAD_WALKABILITY_SOURCE_FIELD)) >= threshold)
                    .toList();
        }
        calculationRoads = calculationRoads.stream()
                .sorted(roadOrder())
                .toList();
        if (resolved.mode() == HousingSearchRequest.Mode.BUFFER_FILTER
                && calculationRoads.size() > policy.maxRoadCalculationCount()) {
            throw new MapContractException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SPATIAL_RESULT_TOO_LARGE",
                    "符合条件的道路超过可靠空间计算上限，请缩小行政区或提高阈值",
                    false,
                    Map.of("count", calculationRoads.size(), "max", policy.maxRoadCalculationCount())
            );
        }

        RoadSpatialSearchService.SpatialMatches matches = spatialSearch.match(
                scopeHousing,
                calculationRoads,
                resolved.bufferMeters()
        );
        Map<String, NearbyRoadMetric> nearbyMetrics = aggregateNearbyMetrics(scopeHousing, matches);
        MetricStatisticsService.MetricDistribution nearbyRoadDistribution = optionalDistribution(
                nearbyMetrics.values().stream().map(NearbyRoadMetric::weightedWs).toList(),
                "nearbyRoadWalkability"
        );

        List<CandidateComputation> candidates = new ArrayList<>();
        boolean missingPrice = false;
        boolean missingConvenience = false;
        boolean missingRoadMetric = false;
        for (HousingSearchFeature housing : scopeHousing) {
            if (!matchesHardFilters(housing, resolved.hardFilters())) {
                continue;
            }
            NearbyRoadMetric nearby = nearbyMetrics.get(housing.id());
            if (resolved.mode() == HousingSearchRequest.Mode.BUFFER_FILTER && nearby == null) {
                continue;
            }
            Double convenience = finiteNumber(housing.attributes().get(
                    HousingSearchPolicyService.CONVENIENCE_SOURCE_FIELD
            ));
            Double price = finiteNumber(housing.attributes().get("房价"));
            Double priceAffordabilityPercentile = price != null && priceDistribution != null
                    ? priceDistribution.reversePercentileRank(price)
                    : null;
            Double conveniencePercentile = convenience != null && convenienceDistribution != null
                    ? convenienceDistribution.percentileRank(convenience)
                    : null;
            Double nearbyPercentile = nearby != null && nearbyRoadDistribution != null
                    ? nearbyRoadDistribution.percentileRank(nearby.weightedWs())
                    : null;

            if (!passesQualitativePreference(
                    resolved.preferences().convenience(), conveniencePercentile, "convenience"
            )) {
                continue;
            }
            if (resolved.mode() == HousingSearchRequest.Mode.RANK
                    && !passesQualitativePreference(
                            resolved.preferences().roadWalkability(), nearbyPercentile, "roadWalkability"
                    )) {
                continue;
            }
            if (Boolean.TRUE.equals(resolved.preferences().price().enabled())
                    && priceAffordabilityPercentile == null) {
                missingPrice = true;
            }
            if (Boolean.TRUE.equals(resolved.preferences().convenience().enabled())
                    && conveniencePercentile == null) {
                missingConvenience = true;
            }
            if (Boolean.TRUE.equals(resolved.preferences().roadWalkability().enabled())
                    && nearbyPercentile == null) {
                missingRoadMetric = true;
            }
            candidates.add(new CandidateComputation(
                    housing, priceAffordabilityPercentile, conveniencePercentile,
                    nearby, nearbyPercentile, null, null
            ));
        }
        candidates = scoreCandidates(candidates, resolved.preferences(), warnings);
        candidates.sort(candidateOrder());
        long matchedHousingCount = candidates.size();
        if (candidates.size() > resolved.limit()) {
            warnings.add("HOUSING_RESULT_TRUNCATED");
        }
        if (missingPrice) {
            warnings.add("MISSING_PRICE_METRIC");
        }
        if (missingConvenience) {
            warnings.add("MISSING_CONVENIENCE_METRIC");
        }
        if (missingRoadMetric) {
            warnings.add("MISSING_NEARBY_ROAD_METRIC");
        }
        if (resolved.mode() == HousingSearchRequest.Mode.BUFFER_FILTER
                && candidates.isEmpty() && !calculationRoads.isEmpty()) {
            warnings.add("NO_HOUSING_IN_BUFFER");
        }
        List<CandidateComputation> returned = candidates.stream().limit(resolved.limit()).toList();
        List<HousingSearchResult.HousingCandidate> housingResults = returned.stream()
                .map(value -> housingResult(value, resolved))
                .toList();

        List<HousingSearchFeature> resultRoads = resultRoads(
                resolved.mode(), calculationRoads, returned
        );
        int roadDisplayLimit = Math.min(DEFAULT_ROAD_DISPLAY_COUNT, policy.maxRoadDisplayCount());
        if (resultRoads.size() > roadDisplayLimit) {
            warnings.add("ROAD_RESULT_TRUNCATED");
        }
        List<HousingSearchFeature> displayedRoads = Boolean.TRUE.equals(resolved.display().includeRoads())
                ? resultRoads.stream().limit(roadDisplayLimit).toList()
                : List.of();
        List<HousingSearchResult.RoadFeature> roadResults = displayedRoads.stream()
                .map(this::roadResult)
                .toList();

        List<HousingSearchFeature> overlayRoads = resolved.mode() == HousingSearchRequest.Mode.BUFFER_FILTER
                ? calculationRoads
                : resultRoads;
        List<HousingSearchResult.BufferOverlay> overlays = Boolean.TRUE.equals(resolved.display().includeBuffers())
                ? spatialSearch.buildProjectedBufferOverlays(
                        projectedRoads(matches, overlayRoads),
                        resolved.bufferMeters(),
                        policy.maxBufferOverlayCount()
                )
                : List.of();
        if (!overlays.isEmpty()) {
            warnings.add("DISPLAY_GEOMETRY_SIMPLIFIED");
        }
        warnings = distinct(warnings);

        return new HousingSearchResult(
                policy.policyVersion(),
                snapshot.dataVersion(),
                resolved.mode(),
                statisticsScope(resolved.districts()),
                new HousingSearchResult.ResolvedCriteria(
                        resolved.hardFilters().priceMin(),
                        resolved.hardFilters().priceMax(),
                        resolved.bufferMeters(),
                        roadThreshold,
                        roadThresholdPercentile,
                        resolved.defaultsApplied(),
                        false
                ),
                new HousingSearchResult.Summary(
                        matchedHousingCount,
                        housingResults.size(),
                        resultRoads.size(),
                        roadResults.size()
                ),
                housingResults,
                roadResults,
                overlays,
                warnings
        );
    }

    private void applyDefaultWarnings(ResolvedHousingSearchRequest request, List<String> warnings) {
        if (request.defaultsApplied().contains("BUFFER_METERS")) {
            warnings.add("DEFAULT_BUFFER_APPLIED");
        }
        if (request.defaultsApplied().contains("PREFERENCE_WEIGHTS")) {
            warnings.add("DEFAULT_WEIGHTS_APPLIED");
        }
    }

    private MetricStatisticsService.MetricDistribution optionalDistribution(
            List<Double> values,
            String metric
    ) {
        try {
            return statistics.distribution(values, metric);
        } catch (MapContractException exception) {
            if ("METRIC_STATISTICS_UNAVAILABLE".equals(exception.getCode())) {
                return null;
            }
            throw exception;
        }
    }

    private void requireStatistics(
            MetricStatisticsService.MetricDistribution distribution,
            String metric
    ) {
        if (distribution == null) {
            throw new MapContractException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "METRIC_STATISTICS_UNAVAILABLE",
                    "指标统计不可用: " + metric,
                    true,
                    null
            );
        }
    }

    private double preferencePercentile(HousingSearchRequest.PreferenceLevel level) {
        return level == HousingSearchRequest.PreferenceLevel.VERY_HIGH
                ? policy.veryHighPercentile()
                : policy.highPercentile();
    }

    private Predicate<HousingSearchFeature> roadCriteria(HousingSearchRequest.RoadCriteria criteria) {
        return road -> {
            Double ws = finiteNumber(road.attributes().get(HousingSearchPolicyService.ROAD_WALKABILITY_SOURCE_FIELD));
            Double gvi = finiteNumber(road.attributes().get(HousingSearchPolicyService.VEGETATION_SOURCE_FIELD));
            Double noi = finiteNumber(road.attributes().get(HousingSearchPolicyService.NOISE_SOURCE_FIELD));
            return (criteria.wsMin() == null || ws != null && ws >= criteria.wsMin())
                    && (criteria.gviMin() == null || gvi != null && gvi >= criteria.gviMin())
                    && (criteria.noiMax() == null || noi != null && noi <= criteria.noiMax());
        };
    }

    private Map<String, NearbyRoadMetric> aggregateNearbyMetrics(
            List<HousingSearchFeature> housing,
            RoadSpatialSearchService.SpatialMatches matches
    ) {
        Map<String, NearbyRoadMetric> result = new HashMap<>();
        for (HousingSearchFeature feature : housing) {
            List<RoadSpatialSearchService.RoadMatch> valid = matches.byHousingId()
                    .getOrDefault(feature.id(), List.of())
                    .stream()
                    .filter(match -> finiteNumber(match.road().attributes().get(HousingSearchPolicyService.ROAD_WALKABILITY_SOURCE_FIELD)) != null)
                    .toList();
            if (valid.isEmpty()) {
                continue;
            }
            double weightedSum = 0;
            double totalWeight = 0;
            for (RoadSpatialSearchService.RoadMatch match : valid) {
                double weight = 1.0 / (match.distanceMeters() + 10.0);
                weightedSum += finiteNumber(match.road().attributes().get(HousingSearchPolicyService.ROAD_WALKABILITY_SOURCE_FIELD)) * weight;
                totalWeight += weight;
            }
            result.put(feature.id(), new NearbyRoadMetric(
                    weightedSum / totalWeight,
                    valid.get(0).distanceMeters(),
                    valid
            ));
        }
        return Map.copyOf(result);
    }

    private boolean matchesHardFilters(
            HousingSearchFeature housing,
            HousingSearchRequest.HardFilters filters
    ) {
        Double price = finiteNumber(housing.attributes().get("房价"));
        return (filters.priceMin() == null || price != null && price >= filters.priceMin())
                && (filters.priceMax() == null || price != null && price <= filters.priceMax());
    }

    private boolean passesQualitativePreference(
            HousingSearchRequest.Preference preference,
            Double percentile,
            String metric
    ) {
        if (!Boolean.TRUE.equals(preference.enabled())
                || preference.level() == HousingSearchRequest.PreferenceLevel.PREFER_HIGH) {
            return true;
        }
        if (percentile == null) {
            throw new MapContractException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "METRIC_STATISTICS_UNAVAILABLE",
                    "定性硬约束缺少可用指标: " + metric,
                    true,
                    null
            );
        }
        double threshold = preference.level() == HousingSearchRequest.PreferenceLevel.VERY_HIGH
                ? policy.veryHighPercentile() * 100.0
                : policy.highPercentile() * 100.0;
        return percentile >= threshold;
    }

    private List<CandidateComputation> scoreCandidates(
            List<CandidateComputation> candidates,
            HousingSearchRequest.Preferences preferences,
            List<String> warnings
    ) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        boolean priceAvailable = metricAvailable(
                Boolean.TRUE.equals(preferences.price().enabled()),
                preferences.price().weight(),
                candidates.stream().map(CandidateComputation::priceAffordabilityPercentile).toList()
        );
        boolean convenienceAvailable = metricAvailable(
                Boolean.TRUE.equals(preferences.convenience().enabled()),
                preferences.convenience().weight(),
                candidates.stream().map(CandidateComputation::conveniencePercentile).toList()
        );
        boolean roadAvailable = metricAvailable(
                Boolean.TRUE.equals(preferences.roadWalkability().enabled()),
                preferences.roadWalkability().weight(),
                candidates.stream().map(CandidateComputation::nearbyRoadPercentile).toList()
        );
        double activeWeight = (priceAvailable ? preferences.price().weight() : 0.0)
                + (convenienceAvailable ? preferences.convenience().weight() : 0.0)
                + (roadAvailable ? preferences.roadWalkability().weight() : 0.0);
        HousingSearchResult.ScoreWeights effectiveWeights = activeWeight > 0
                ? new HousingSearchResult.ScoreWeights(
                        priceAvailable ? preferences.price().weight() / activeWeight : 0.0,
                        convenienceAvailable ? preferences.convenience().weight() / activeWeight : 0.0,
                        roadAvailable ? preferences.roadWalkability().weight() / activeWeight : 0.0
                )
                : new HousingSearchResult.ScoreWeights(0.0, 0.0, 0.0);
        boolean omittedUnavailablePreference = unavailableEnabledPreference(
                Boolean.TRUE.equals(preferences.price().enabled()), preferences.price().weight(), priceAvailable
        ) || unavailableEnabledPreference(
                Boolean.TRUE.equals(preferences.convenience().enabled()),
                preferences.convenience().weight(),
                convenienceAvailable
        ) || unavailableEnabledPreference(
                Boolean.TRUE.equals(preferences.roadWalkability().enabled()),
                preferences.roadWalkability().weight(),
                roadAvailable
        );
        if (omittedUnavailablePreference && activeWeight > 0) {
            warnings.add("PREFERENCE_WEIGHTS_RENORMALIZED");
        }
        return new ArrayList<>(candidates.stream()
                .map(candidate -> new CandidateComputation(
                        candidate.housing(),
                        candidate.priceAffordabilityPercentile(),
                        candidate.conveniencePercentile(),
                        candidate.nearbyRoad(),
                        candidate.nearbyRoadPercentile(),
                        recommendationScore(
                                effectiveWeights,
                                candidate.priceAffordabilityPercentile(),
                                candidate.conveniencePercentile(),
                                candidate.nearbyRoadPercentile()
                        ),
                        effectiveWeights
                ))
                .toList());
    }

    private boolean metricAvailable(boolean enabled, double weight, List<Double> values) {
        return enabled && weight > 0 && values.stream().anyMatch(Objects::nonNull);
    }

    private boolean unavailableEnabledPreference(boolean enabled, double weight, boolean available) {
        return enabled && weight > 0 && !available;
    }

    private Double recommendationScore(
            HousingSearchResult.ScoreWeights weights,
            Double priceAffordabilityPercentile,
            Double conveniencePercentile,
            Double nearbyRoadPercentile
    ) {
        double weighted = 0;
        if (weights.price() > 0) {
            if (priceAffordabilityPercentile == null) {
                return null;
            }
            weighted += priceAffordabilityPercentile * weights.price();
        }
        if (weights.convenience() > 0) {
            if (conveniencePercentile == null) {
                return null;
            }
            weighted += conveniencePercentile * weights.convenience();
        }
        if (weights.roadWalkability() > 0) {
            if (nearbyRoadPercentile == null) {
                return null;
            }
            weighted += nearbyRoadPercentile * weights.roadWalkability();
        }
        if (weights.price() == 0 && weights.convenience() == 0 && weights.roadWalkability() == 0) {
            return null;
        }
        return MetricStatisticsService.roundOneDecimal(weighted);
    }

    private Comparator<CandidateComputation> candidateOrder() {
        return Comparator
                .comparing(CandidateComputation::recommendationScore,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(value -> finiteNumber(value.housing().attributes().get("房价")),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(value -> value.housing().id());
    }

    private Comparator<HousingSearchFeature> roadOrder() {
        return Comparator
                .comparing((HousingSearchFeature road) -> finiteNumber(road.attributes().get(HousingSearchPolicyService.ROAD_WALKABILITY_SOURCE_FIELD)),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(HousingSearchFeature::id);
    }

    private HousingSearchResult.HousingCandidate housingResult(
            CandidateComputation value,
            ResolvedHousingSearchRequest request
    ) {
        NearbyRoadMetric nearby = value.nearbyRoad();
        List<String> contributing = nearby == null
                ? List.of()
                : nearby.matches().stream().map(match -> match.road().id()).distinct().limit(20).toList();
        List<String> warnings = new ArrayList<>();
        if (value.conveniencePercentile() == null
                && Boolean.TRUE.equals(request.preferences().convenience().enabled())) {
            warnings.add("MISSING_CONVENIENCE_METRIC");
        }
        if (value.priceAffordabilityPercentile() == null
                && Boolean.TRUE.equals(request.preferences().price().enabled())) {
            warnings.add("MISSING_PRICE_METRIC");
        }
        if (nearby == null && Boolean.TRUE.equals(request.preferences().roadWalkability().enabled())) {
            warnings.add("MISSING_NEARBY_ROAD_METRIC");
        }
        return new HousingSearchResult.HousingCandidate(
                value.housing().id(),
                value.housing().layerId(),
                value.housing().attributes(),
                value.housing().geometry(),
                new HousingSearchResult.Scores(
                        value.priceAffordabilityPercentile(),
                        value.conveniencePercentile(),
                        nearby == null ? null : MetricStatisticsService.roundOneDecimal(nearby.weightedWs()),
                        value.nearbyRoadPercentile(),
                        value.recommendationScore(),
                        value.scoreWeights()
                ),
                new HousingSearchResult.SpatialEvidence(
                        request.bufferMeters(),
                        nearby == null ? 0 : nearby.matches().size(),
                        nearby == null ? null : MetricStatisticsService.roundOneDecimal(nearby.nearestDistanceMeters()),
                        contributing
                ),
                reasons(value, request),
                warnings
        );
    }

    private List<String> reasons(CandidateComputation value, ResolvedHousingSearchRequest request) {
        List<String> result = new ArrayList<>();
        if (request.hardFilters().priceMin() != null) {
            result.add("房价不低于 " + formatNumber(request.hardFilters().priceMin()) + " 元/㎡");
        }
        if (request.hardFilters().priceMax() != null) {
            result.add("房价不超过 " + formatNumber(request.hardFilters().priceMax()) + " 元/㎡");
        }
        if (value.conveniencePercentile() != null) {
            result.add("便利度位于当前统计范围前 "
                    + formatNumber(100.0 - value.conveniencePercentile()) + "%");
        }
        if (value.nearbyRoadPercentile() != null) {
            result.add("周边道路步行水平位于当前统计范围前 "
                    + formatNumber(100.0 - value.nearbyRoadPercentile()) + "%");
        }
        return List.copyOf(result);
    }

    private List<HousingSearchFeature> resultRoads(
            HousingSearchRequest.Mode mode,
            List<HousingSearchFeature> calculationRoads,
            List<CandidateComputation> returned
    ) {
        if (mode == HousingSearchRequest.Mode.BUFFER_FILTER && returned.isEmpty()) {
            return calculationRoads;
        }
        Set<String> contributingIds = new LinkedHashSet<>();
        returned.forEach(candidate -> {
            if (candidate.nearbyRoad() != null) {
                candidate.nearbyRoad().matches().forEach(match -> contributingIds.add(match.road().id()));
            }
        });
        Map<String, HousingSearchFeature> byId = new LinkedHashMap<>();
        calculationRoads.forEach(road -> byId.put(road.id(), road));
        return contributingIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .sorted(roadOrder())
                .toList();
    }

    private HousingSearchResult.RoadFeature roadResult(HousingSearchFeature road) {
        return new HousingSearchResult.RoadFeature(
                road.id(), road.layerId(), road.attributes(), road.geometry()
        );
    }

    private HousingSearchResult.StatisticsScope statisticsScope(List<String> districts) {
        String type = districts.size() == HousingSearchPolicyService.SUPPORTED_DISTRICTS.size()
                ? "SUPPORTED_REGION"
                : "DISTRICT";
        return new HousingSearchResult.StatisticsScope(type, districts);
    }

    private List<RoadSpatialSearchService.ProjectedRoad> projectedRoads(
            RoadSpatialSearchService.SpatialMatches matches,
            List<HousingSearchFeature> selectedRoads
    ) {
        Set<String> selectedIds = selectedRoads.stream()
                .map(HousingSearchFeature::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return matches.projectedRoads().stream()
                .filter(road -> selectedIds.contains(road.feature().id()))
                .toList();
    }

    private List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private Double number(Object value) {
        return finiteNumber(value);
    }

    private static Double finiteNumber(Object value) {
        final double result;
        if (value instanceof Number number) {
            result = number.doubleValue();
        } else if (value instanceof String text) {
            try {
                result = Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(result) ? result : null;
    }

    private String formatNumber(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
    }

    private record NearbyRoadMetric(
            double weightedWs,
            double nearestDistanceMeters,
            List<RoadSpatialSearchService.RoadMatch> matches
    ) {
        private NearbyRoadMetric {
            matches = List.copyOf(matches);
        }
    }

    private record CandidateComputation(
            HousingSearchFeature housing,
            Double priceAffordabilityPercentile,
            Double conveniencePercentile,
            NearbyRoadMetric nearbyRoad,
            Double nearbyRoadPercentile,
            Double recommendationScore,
            HousingSearchResult.ScoreWeights scoreWeights
    ) {
    }
}
