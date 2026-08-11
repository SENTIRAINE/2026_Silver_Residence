package org.example.xqy1._026_silver_residence.map;

import java.util.List;

public record MapFeatureQueryResult(
        int layerId,
        String layerName,
        String geometryType,
        long total,
        boolean exceededTransferLimit,
        List<MapFeature> features
) {
}
