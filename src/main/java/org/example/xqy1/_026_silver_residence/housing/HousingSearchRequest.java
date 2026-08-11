package org.example.xqy1._026_silver_residence.housing;

import java.util.List;

public record HousingSearchRequest(
        Mode mode,
        List<String> districts,
        HardFilters hardFilters,
        Preferences preferences,
        RoadCriteria roadCriteria,
        Spatial spatial,
        Display display,
        Integer limit
) {
    public enum Mode { RANK, BUFFER_FILTER }

    public enum PreferenceLevel { PREFER_HIGH, HIGH, VERY_HIGH }

    public enum PricePreferenceLevel { PREFER_LOW }

    public enum SpatialRelation { WITHIN_ROAD_BUFFER }

    public record HardFilters(Double priceMin, Double priceMax) {
    }

    public record Preferences(
            PricePreference price,
            Preference convenience,
            Preference roadWalkability
    ) {
    }

    public record PricePreference(
            Boolean enabled,
            PricePreferenceLevel level,
            Double weight
    ) {
    }

    public record Preference(
            Boolean enabled,
            PreferenceLevel level,
            Double weight
    ) {
    }

    public record RoadCriteria(
            Double wsMin,
            Double gviMin,
            Double noiMax
    ) {
    }

    public record Spatial(
            SpatialRelation relation,
            Integer bufferMeters
    ) {
    }

    public record Display(
            Boolean includeRoads,
            Boolean includeBuffers
    ) {
    }
}
