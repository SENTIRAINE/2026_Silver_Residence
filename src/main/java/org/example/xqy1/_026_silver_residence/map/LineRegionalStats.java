package org.example.xqy1._026_silver_residence.map;

import java.util.List;

public record LineRegionalStats(
        int layerId,
        String layerName,
        String district,
        long sampleCount,
        List<LineRegionalMetric> metrics
) {
}
