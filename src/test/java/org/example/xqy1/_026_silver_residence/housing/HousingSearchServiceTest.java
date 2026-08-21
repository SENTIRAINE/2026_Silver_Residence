package org.example.xqy1._026_silver_residence.housing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HousingSearchServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resultAttributeMapsPreserveNullValuesAndRemainImmutable() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("nullableField", null);
        HousingSearchResult.HousingCandidate housing = new HousingSearchResult.HousingCandidate(
                "2:1", 2, attributes, null, null, null, List.of(), List.of()
        );
        HousingSearchResult.RoadFeature road = new HousingSearchResult.RoadFeature(
                "3:1", 3, attributes, null
        );

        assertTrue(housing.attributes().containsKey("nullableField"));
        assertEquals(null, housing.attributes().get("nullableField"));
        assertTrue(road.attributes().containsKey("nullableField"));
        assertEquals(null, road.attributes().get("nullableField"));
        assertThrows(UnsupportedOperationException.class,
                () -> housing.attributes().put("newField", "value"));
        assertThrows(UnsupportedOperationException.class,
                () -> road.attributes().put("newField", "value"));
    }

    @Test
    void resolvesP75AndP90WithoutRelaxingTheRoadConstraint() {
        HousingSearchSnapshot snapshot = snapshot(
                List.of(housing("2:1", "中山区", 121.60, 38.90, 12_000, 80, 999)),
                List.of(
                        road("3:1", "中山区", 10, 38.90),
                        road("3:2", "中山区", 20, 38.90),
                        road("3:3", "中山区", 30, 38.90),
                        road("3:4", "中山区", 40, 38.90)
                )
        );
        HousingSearchService service = service(snapshot);

        HousingSearchResult high = service.search(bufferRequest(
                List.of("中山区"), HousingSearchRequest.PreferenceLevel.HIGH, 100
        ));
        HousingSearchResult veryHigh = service.search(bufferRequest(
                List.of("中山区"), HousingSearchRequest.PreferenceLevel.VERY_HIGH, 100
        ));

        assertEquals(75.0, high.resolvedCriteria().roadWsThresholdPercentile());
        assertEquals(32.5, high.resolvedCriteria().roadWsThreshold());
        assertEquals(90.0, veryHigh.resolvedCriteria().roadWsThresholdPercentile());
        assertEquals(37.0, veryHigh.resolvedCriteria().roadWsThreshold());
        assertFalse(high.resolvedCriteria().relaxationApplied());
        assertFalse(veryHigh.resolvedCriteria().relaxationApplied());
    }

    @Test
    void usesUnifiedSupportedRegionStatisticsWhenDistrictsAreEmpty() {
        HousingSearchSnapshot snapshot = snapshot(
                List.of(
                        housing("0:1", "沙河口区", 121.60, 38.90, 10_000, 10, 10),
                        housing("1:1", "西岗区", 121.60, 38.90, 11_000, 20, 20),
                        housing("2:1", "中山区", 121.60, 38.90, 12_000, 30, 30)
                ),
                List.of(
                        road("5:1", "沙河口区", 10, 38.90),
                        road("4:1", "西岗区", 20, 38.90),
                        road("3:1", "中山区", 30, 38.90)
                )
        );

        HousingSearchResult result = service(snapshot).search(bufferRequest(
                List.of(), HousingSearchRequest.PreferenceLevel.HIGH, 100
        ));

        assertEquals("SUPPORTED_REGION", result.statisticsScope().type());
        assertEquals(HousingSearchPolicyService.SUPPORTED_DISTRICTS, result.statisticsScope().districts());
        assertEquals(25.0, result.resolvedCriteria().roadWsThreshold());
    }

    @Test
    void scoresLowPriceAsAnIndependentSoftPreferenceAndIgnoresNewWalking() {
        HousingSearchSnapshot snapshot = snapshot(
                List.of(
                        housing("2:1", "中山区", 121.600, 38.900, 100, 50, 0),
                        housing("2:2", "中山区", 121.601, 38.900, 200, 50, 100)
                ),
                List.of(road("3:1", "中山区", 60, 38.900))
        );
        HousingSearchRequest request = new HousingSearchRequest(
                HousingSearchRequest.Mode.RANK,
                List.of("中山区"),
                new HousingSearchRequest.HardFilters(null, null),
                new HousingSearchRequest.Preferences(
                        new HousingSearchRequest.PricePreference(
                                true, HousingSearchRequest.PricePreferenceLevel.PREFER_LOW, 0.5
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.5
                        ),
                        new HousingSearchRequest.Preference(
                                false, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.0
                        )
                ),
                new HousingSearchRequest.RoadCriteria(null, null, null),
                new HousingSearchRequest.Spatial(HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, 200),
                new HousingSearchRequest.Display(false, false),
                20
        );

        HousingSearchResult result = service(snapshot).search(request);

        assertEquals(List.of("2:1", "2:2"), result.housingCandidates().stream()
                .map(HousingSearchResult.HousingCandidate::housingId).toList());
        assertEquals(100.0, result.housingCandidates().get(0).scores().priceAffordabilityPercentile());
        assertEquals(50.0, result.housingCandidates().get(1).scores().priceAffordabilityPercentile());
        assertEquals(100.0, result.housingCandidates().get(0).scores().recommendationScore());
        assertEquals(75.0, result.housingCandidates().get(1).scores().recommendationScore());
        assertEquals(new HousingSearchResult.ScoreWeights(0.5, 0.5, 0.0),
                result.housingCandidates().get(0).scores().weights());
    }

    @Test
    void defaultsToEqualConvenienceAndRoadWeightsAndRejectsOversizedBuffers() {
        HousingSearchPolicyService policy = policy();
        HousingSearchRequest base = new HousingSearchRequest(
                HousingSearchRequest.Mode.RANK,
                List.of("中山区"),
                new HousingSearchRequest.HardFilters(null, null),
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
                new HousingSearchRequest.Spatial(HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, null),
                new HousingSearchRequest.Display(true, true),
                20
        );

        ResolvedHousingSearchRequest resolved = policy.resolve(base);
        assertEquals(0.5, resolved.preferences().convenience().weight());
        assertEquals(0.5, resolved.preferences().roadWalkability().weight());
        assertEquals(100, resolved.bufferMeters());
        assertEquals(2, resolved.defaultsApplied().size());
        assertTrue(resolved.defaultsApplied().containsAll(List.of("BUFFER_METERS", "PREFERENCE_WEIGHTS")));

        HousingSearchRequest invalid = new HousingSearchRequest(
                base.mode(), base.districts(), base.hardFilters(), base.preferences(), base.roadCriteria(),
                new HousingSearchRequest.Spatial(HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, 10_000),
                base.display(), base.limit()
        );
        MapContractException exception = assertThrows(MapContractException.class, () -> policy.resolve(invalid));
        assertEquals("INVALID_BUFFER_DISTANCE", exception.getCode());
    }

    @Test
    void rejectsAmbiguousBufferFilteringInsteadOfSilentlyApplyingP75() {
        HousingSearchRequest valid = bufferRequest(
                List.of("中山区"), HousingSearchRequest.PreferenceLevel.HIGH, 100
        );
        HousingSearchRequest ambiguous = new HousingSearchRequest(
                valid.mode(),
                valid.districts(),
                valid.hardFilters(),
                new HousingSearchRequest.Preferences(
                        valid.preferences().price(),
                        valid.preferences().convenience(),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 1.0
                        )
                ),
                valid.roadCriteria(),
                valid.spatial(),
                valid.display(),
                valid.limit()
        );

        MapContractException exception = assertThrows(
                MapContractException.class,
                () -> policy().resolve(ambiguous)
        );
        assertEquals("INVALID_HOUSING_SEARCH_ARGUMENT", exception.getCode());
    }

    @Test
    void keepsAllContributingRoadIdsForOverlappingRoads() {
        HousingSearchSnapshot snapshot = snapshot(
                List.of(housing("2:1", "中山区", 121.60, 38.90, 10_000, 80, 10)),
                List.of(
                        road("3:1", "中山区", 60, 38.9000),
                        road("3:2", "中山区", 80, 38.9002)
                )
        );
        HousingSearchRequest request = new HousingSearchRequest(
                HousingSearchRequest.Mode.RANK,
                List.of("中山区"),
                new HousingSearchRequest.HardFilters(null, null),
                new HousingSearchRequest.Preferences(
                        new HousingSearchRequest.PricePreference(
                                false, HousingSearchRequest.PricePreferenceLevel.PREFER_LOW, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                false, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 1.0
                        )
                ),
                new HousingSearchRequest.RoadCriteria(null, null, null),
                new HousingSearchRequest.Spatial(HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, 100),
                new HousingSearchRequest.Display(true, true),
                20
        );

        HousingSearchResult result = service(snapshot).search(request);

        assertEquals(1, result.housingCandidates().size());
        assertEquals(2, result.housingCandidates().get(0).spatialEvidence().nearbyRoadCount());
        assertEquals(List.of("3:1", "3:2"),
                result.housingCandidates().get(0).spatialEvidence().contributingRoadIds());
        assertEquals(1, result.bufferOverlays().size());
        assertEquals("polygon", result.bufferOverlays().get(0).geometryType());
        assertEquals(Map.of("wkid", 4326), result.bufferOverlays().get(0).spatialReference());
    }

    @Test
    void explicitlyRenormalizesToConvenienceWhenRoadWsIsUnavailableForEveryCandidate() {
        HousingSearchSnapshot snapshot = snapshot(
                List.of(
                        housing("2:1", "中山区", 121.600, 38.900, 20_000, 90, 0),
                        housing("2:2", "中山区", 121.601, 38.900, 10_000, 10, 100)
                ),
                List.of(roadWithoutWs("3:1", "中山区", 38.900))
        );
        HousingSearchRequest request = new HousingSearchRequest(
                HousingSearchRequest.Mode.RANK,
                List.of("中山区"),
                new HousingSearchRequest.HardFilters(null, null),
                new HousingSearchRequest.Preferences(
                        new HousingSearchRequest.PricePreference(
                                false, HousingSearchRequest.PricePreferenceLevel.PREFER_LOW, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.5
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.5
                        )
                ),
                new HousingSearchRequest.RoadCriteria(null, null, null),
                new HousingSearchRequest.Spatial(
                        HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, 100
                ),
                new HousingSearchRequest.Display(false, false),
                20
        );

        HousingSearchResult result = service(snapshot).search(request);

        assertEquals(List.of("2:1", "2:2"), result.housingCandidates().stream()
                .map(HousingSearchResult.HousingCandidate::housingId).toList());
        assertEquals(100.0, result.housingCandidates().get(0).scores().recommendationScore());
        assertEquals(new HousingSearchResult.ScoreWeights(0.0, 1.0, 0.0),
                result.housingCandidates().get(0).scores().weights());
        assertTrue(result.warnings().contains("MISSING_NEARBY_ROAD_METRIC"));
        assertTrue(result.warnings().contains("PREFERENCE_WEIGHTS_RENORMALIZED"));
        assertFalse(result.resolvedCriteria().relaxationApplied());
    }

    @Test
    void usesExplicitRoadThresholdAndDistanceWithoutApplyingPercentileOrDistanceDefaults() {
        HousingSearchSnapshot snapshot = snapshot(
                List.of(housing("2:1", "中山区", 121.60, 38.90, 12_000, 80, 10)),
                List.of(
                        road("3:1", "中山区", 74, 38.9000),
                        road("3:2", "中山区", 75, 38.9002)
                )
        );
        HousingSearchRequest request = new HousingSearchRequest(
                HousingSearchRequest.Mode.BUFFER_FILTER,
                List.of("中山区"),
                new HousingSearchRequest.HardFilters(null, null),
                new HousingSearchRequest.Preferences(
                        new HousingSearchRequest.PricePreference(
                                false, HousingSearchRequest.PricePreferenceLevel.PREFER_LOW, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                false, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 1.0
                        )
                ),
                new HousingSearchRequest.RoadCriteria(75.0, null, null),
                new HousingSearchRequest.Spatial(
                        HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, 300
                ),
                new HousingSearchRequest.Display(true, true),
                20
        );

        HousingSearchResult result = service(snapshot).search(request);

        assertEquals(75.0, result.resolvedCriteria().roadWsThreshold());
        assertEquals(null, result.resolvedCriteria().roadWsThresholdPercentile());
        assertEquals(300, result.resolvedCriteria().bufferMeters());
        assertTrue(result.resolvedCriteria().defaultsApplied().isEmpty());
        assertEquals(List.of("3:2"), result.roadFeatures().stream()
                .map(HousingSearchResult.RoadFeature::roadId).toList());
        assertFalse(result.warnings().contains("DEFAULT_BUFFER_APPLIED"));
    }

    @Test
    void roadCriteriaUseRawVegetationAndNoiseInsteadOfDisplayGrades() {
        HousingSearchSnapshot snapshot = snapshot(
                List.of(housing("2:1", "中山区", 121.60, 38.90, 12_000, 80, 10)),
                List.of(
                        roadWithMetrics("3:1", "中山区", 80, 38.9000, 5, 5, 0.80, 40),
                        roadWithMetrics("3:2", "中山区", 80, 38.9001, 0, 0, 0.20, 80)
                )
        );
        HousingSearchRequest request = new HousingSearchRequest(
                HousingSearchRequest.Mode.BUFFER_FILTER,
                List.of("中山区"),
                new HousingSearchRequest.HardFilters(null, null),
                new HousingSearchRequest.Preferences(
                        new HousingSearchRequest.PricePreference(
                                false, HousingSearchRequest.PricePreferenceLevel.PREFER_LOW, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                false, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 1.0
                        )
                ),
                new HousingSearchRequest.RoadCriteria(70.0, 0.5, 60.0),
                new HousingSearchRequest.Spatial(
                        HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, 100
                ),
                new HousingSearchRequest.Display(true, false),
                20
        );

        HousingSearchResult result = service(snapshot).search(request);

        assertEquals(List.of("3:1"), result.roadFeatures().stream()
                .map(HousingSearchResult.RoadFeature::roadId).toList());
    }

    @Test
    void restrictsStatisticsAndCandidatesToRequestedDistrictAndPreservesCustomWeights() {
        HousingSearchSnapshot snapshot = snapshot(
                List.of(
                        housing("2:1", "中山区", 121.600, 38.900, 14_000, 90, 10),
                        housing("1:1", "西岗区", 121.600, 38.900, 10_000, 100, 10)
                ),
                List.of(
                        road("3:1", "中山区", 80, 38.900),
                        road("4:1", "西岗区", 100, 38.900)
                )
        );
        HousingSearchRequest request = new HousingSearchRequest(
                HousingSearchRequest.Mode.RANK,
                List.of("中山区"),
                new HousingSearchRequest.HardFilters(null, 15_000.0),
                new HousingSearchRequest.Preferences(
                        new HousingSearchRequest.PricePreference(
                                false, HousingSearchRequest.PricePreferenceLevel.PREFER_LOW, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.8
                        ),
                        new HousingSearchRequest.Preference(
                                true, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.2
                        )
                ),
                new HousingSearchRequest.RoadCriteria(null, null, null),
                new HousingSearchRequest.Spatial(
                        HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, 100
                ),
                new HousingSearchRequest.Display(true, false),
                20
        );

        HousingSearchResult result = service(snapshot).search(request);

        assertEquals("DISTRICT", result.statisticsScope().type());
        assertEquals(List.of("中山区"), result.statisticsScope().districts());
        assertEquals(List.of("2:1"), result.housingCandidates().stream()
                .map(HousingSearchResult.HousingCandidate::housingId).toList());
        assertEquals(new HousingSearchResult.ScoreWeights(0.0, 0.8, 0.2),
                result.housingCandidates().get(0).scores().weights());
    }

    @Test
    void returnsRoadsAndBuffersWhenQualifiedRoadsHaveNoHousingInsideTheirBuffer() {
        HousingSearchSnapshot snapshot = snapshot(
                List.of(housing("2:1", "中山区", 121.70, 38.90, 12_000, 80, 10)),
                List.of(
                        road("3:1", "中山区", 10, 38.9000),
                        road("3:2", "中山区", 20, 38.9000),
                        road("3:3", "中山区", 30, 38.9000),
                        road("3:4", "中山区", 40, 38.9000)
                )
        );

        HousingSearchResult result = service(snapshot).search(bufferRequest(
                List.of("中山区"), HousingSearchRequest.PreferenceLevel.HIGH, 100
        ));

        assertTrue(result.housingCandidates().isEmpty());
        assertFalse(result.roadFeatures().isEmpty());
        assertFalse(result.bufferOverlays().isEmpty());
        assertTrue(result.warnings().contains("NO_HOUSING_IN_BUFFER"));
        assertFalse(result.resolvedCriteria().relaxationApplied());
    }

    private HousingSearchService service(HousingSearchSnapshot snapshot) {
        return new HousingSearchService(
                policy(),
                new MetricStatisticsService(),
                () -> snapshot,
                new RoadSpatialSearchService(objectMapper)
        );
    }

    private HousingSearchPolicyService policy() {
        return new HousingSearchPolicyService(
                HousingSearchPolicyService.DEFAULT_POLICY_VERSION,
                100, 20, 2000, 0.75, 0.90, 0.5, 0.5,
                20, 50, 200, 50, 20
        );
    }

    private HousingSearchRequest bufferRequest(
            List<String> districts,
            HousingSearchRequest.PreferenceLevel roadLevel,
            int bufferMeters
    ) {
        return new HousingSearchRequest(
                HousingSearchRequest.Mode.BUFFER_FILTER,
                districts,
                new HousingSearchRequest.HardFilters(null, null),
                new HousingSearchRequest.Preferences(
                        new HousingSearchRequest.PricePreference(
                                false, HousingSearchRequest.PricePreferenceLevel.PREFER_LOW, 0.0
                        ),
                        new HousingSearchRequest.Preference(
                                false, HousingSearchRequest.PreferenceLevel.PREFER_HIGH, 0.0
                        ),
                        new HousingSearchRequest.Preference(true, roadLevel, 1.0)
                ),
                new HousingSearchRequest.RoadCriteria(null, null, null),
                new HousingSearchRequest.Spatial(
                        HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER, bufferMeters
                ),
                new HousingSearchRequest.Display(true, true),
                20
        );
    }

    private HousingSearchSnapshot snapshot(
            List<HousingSearchFeature> housing,
            List<HousingSearchFeature> roads
    ) {
        return new HousingSearchSnapshot("test-snapshot", Instant.parse("2026-07-29T00:00:00Z"), housing, roads);
    }

    private HousingSearchFeature housing(
            String id,
            String district,
            double x,
            double y,
            double price,
            double convenience,
            double newWalking
    ) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("x", x);
        geometry.put("y", y);
        geometry.set("spatialReference", objectMapper.createObjectNode().put("wkid", 4326));
        return new HousingSearchFeature(
                id,
                Integer.parseInt(id.substring(0, 1)),
                district,
                Map.of("房价", price, "归一化总分", convenience, "新步行", newWalking),
                geometry
        );
    }

    private HousingSearchFeature road(String id, String district, double ws, double latitude) {
        return roadWithMetrics(id, district, ws, latitude, 3, 1.25, 0.5, 40);
    }

    private HousingSearchFeature roadWithMetrics(
            String id,
            String district,
            double ws,
            double latitude,
            double gviGrade,
            double noiGrade,
            double vegetation,
            double noise
    ) {
        ObjectNode geometry = objectMapper.createObjectNode();
        var path = geometry.putArray("paths").addArray();
        path.addArray().add(121.59).add(latitude);
        path.addArray().add(121.61).add(latitude);
        geometry.set("spatialReference", objectMapper.createObjectNode().put("wkid", 4326));
        return new HousingSearchFeature(
                id,
                Integer.parseInt(id.substring(0, 1)),
                district,
                Map.of(
                        "WS归一化", String.valueOf(ws),
                        "GVI", gviGrade,
                        "NOI", noiGrade,
                        "绿视率原始值", vegetation,
                        "道路噪声原始值", noise
                ),
                geometry
        );
    }

    private HousingSearchFeature roadWithoutWs(String id, String district, double latitude) {
        ObjectNode geometry = objectMapper.createObjectNode();
        var path = geometry.putArray("paths").addArray();
        path.addArray().add(121.59).add(latitude);
        path.addArray().add(121.61).add(latitude);
        geometry.set("spatialReference", objectMapper.createObjectNode().put("wkid", 4326));
        return new HousingSearchFeature(
                id,
                Integer.parseInt(id.substring(0, 1)),
                district,
                Map.of("GVI", 0.5, "NOI", 0.2),
                geometry
        );
    }
}
