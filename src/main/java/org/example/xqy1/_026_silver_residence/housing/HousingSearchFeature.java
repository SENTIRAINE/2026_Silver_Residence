package org.example.xqy1._026_silver_residence.housing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record HousingSearchFeature(
        String id,
        int layerId,
        String district,
        Map<String, Object> attributes,
        JsonNode geometry
) {
    public HousingSearchFeature {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
