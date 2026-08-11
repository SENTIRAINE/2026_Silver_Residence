package org.example.xqy1._026_silver_residence.housing;

import java.time.Instant;
import java.util.List;

public record HousingSearchSnapshot(
        String dataVersion,
        Instant builtAt,
        List<HousingSearchFeature> housing,
        List<HousingSearchFeature> roads
) {
    public HousingSearchSnapshot {
        housing = List.copyOf(housing);
        roads = List.copyOf(roads);
    }
}
