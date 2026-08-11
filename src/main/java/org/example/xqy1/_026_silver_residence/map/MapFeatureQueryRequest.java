package org.example.xqy1._026_silver_residence.map;

import java.util.List;

public record MapFeatureQueryRequest(
        Integer layerId,
        List<MapFilterCondition> filters,
        String where,
        List<String> outFields,
        Boolean returnGeometry,
        Integer resultRecordCount,
        Integer resultOffset,
        Double longitude,
        Double latitude,
        Double distanceMeters,
        Boolean returnCount
) {
    public MapFeatureQueryRequest {
        filters = filters == null ? List.of() : List.copyOf(filters);
        outFields = outFields == null ? List.of() : List.copyOf(outFields);
    }
}
