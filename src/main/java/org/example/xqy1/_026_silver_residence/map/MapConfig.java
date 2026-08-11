package org.example.xqy1._026_silver_residence.map;

import java.util.List;
import java.util.Map;

public record MapConfig(
        String portalUrl,
        Map<String, String> basemapUrlTemplates,
        int basemapSwitchScale,
        int maxZoom,
        String mapServiceUrl,
        List<Double> defaultCenter,
        int defaultZoom,
        List<MapLayerDefinition> sublayers
) {
}
