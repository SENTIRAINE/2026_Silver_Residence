package org.example.xqy1._026_silver_residence.map;

import java.util.List;

public record MapLayerDefinition(
        int id,
        String name,
        String geometryType,
        String displayField,
        List<String> fields,
        List<MapFieldDefinition> filterFields
) {
}
