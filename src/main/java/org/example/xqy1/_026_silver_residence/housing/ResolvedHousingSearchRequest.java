package org.example.xqy1._026_silver_residence.housing;

import java.util.List;

public record ResolvedHousingSearchRequest(
        HousingSearchRequest.Mode mode,
        List<String> districts,
        HousingSearchRequest.HardFilters hardFilters,
        HousingSearchRequest.Preferences preferences,
        HousingSearchRequest.RoadCriteria roadCriteria,
        int bufferMeters,
        HousingSearchRequest.Display display,
        int limit,
        List<String> defaultsApplied
) {
}
