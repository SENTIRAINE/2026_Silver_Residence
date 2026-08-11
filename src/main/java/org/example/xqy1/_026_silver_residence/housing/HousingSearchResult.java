package org.example.xqy1._026_silver_residence.housing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record HousingSearchResult(
        String policyVersion,
        String dataVersion,
        HousingSearchRequest.Mode mode,
        StatisticsScope statisticsScope,
        ResolvedCriteria resolvedCriteria,
        Summary summary,
        List<HousingCandidate> housingCandidates,
        List<RoadFeature> roadFeatures,
        List<BufferOverlay> bufferOverlays,
        List<String> warnings
) {
    public HousingSearchResult {
        housingCandidates = List.copyOf(housingCandidates);
        roadFeatures = List.copyOf(roadFeatures);
        bufferOverlays = List.copyOf(bufferOverlays);
        warnings = List.copyOf(warnings);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record StatisticsScope(String type, List<String> districts) {
        public StatisticsScope {
            districts = List.copyOf(districts);
        }
    }

    public record ResolvedCriteria(
            Double priceMin,
            Double priceMax,
            int bufferMeters,
            Double roadWsThreshold,
            Double roadWsThresholdPercentile,
            List<String> defaultsApplied,
            boolean relaxationApplied
    ) {
        public ResolvedCriteria {
            defaultsApplied = List.copyOf(defaultsApplied);
        }
    }

    public record Summary(
            long matchedHousingCount,
            int returnedHousingCount,
            long matchedRoadCount,
            int returnedRoadCount
    ) {
    }

    public record HousingCandidate(
            String housingId,
            int layerId,
            Map<String, Object> attributes,
            JsonNode geometry,
            Scores scores,
            SpatialEvidence spatialEvidence,
            List<String> reasons,
        List<String> warnings
    ) {
        public HousingCandidate {
            attributes = immutableMap(attributes);
            reasons = List.copyOf(reasons);
            warnings = List.copyOf(warnings);
        }
    }

    public record Scores(
            Double priceAffordabilityPercentile,
            Double conveniencePercentile,
            Double nearbyRoadWsRaw,
            Double nearbyRoadWsPercentile,
            Double recommendationScore,
            ScoreWeights weights
    ) {
    }

    public record ScoreWeights(
            double price,
            double convenience,
            double roadWalkability
    ) {
    }

    public record SpatialEvidence(
            int bufferMeters,
            int nearbyRoadCount,
            Double nearestRoadDistanceMeters,
            List<String> contributingRoadIds
    ) {
        public SpatialEvidence {
            contributingRoadIds = List.copyOf(contributingRoadIds);
        }
    }

    public record RoadFeature(
            String roadId,
            int layerId,
            Map<String, Object> attributes,
            JsonNode geometry
    ) {
        public RoadFeature {
            attributes = immutableMap(attributes);
        }
    }

    public record BufferOverlay(
            String overlayId,
            String kind,
            String geometryType,
            Map<String, Integer> spatialReference,
            List<String> sourceRoadIds,
            BufferAttributes attributes,
            JsonNode geometry
    ) {
        public BufferOverlay {
            spatialReference = Map.copyOf(spatialReference);
            sourceRoadIds = List.copyOf(sourceRoadIds);
        }
    }

    public record BufferAttributes(
            int bufferMeters,
            int sourceRoadCount,
            boolean sourceRoadIdsTruncated
    ) {
    }
}
