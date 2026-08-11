package org.example.xqy1._026_silver_residence.map;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record MapFeature(
        Map<String, Object> attributes,
        JsonNode geometry
) {
}
