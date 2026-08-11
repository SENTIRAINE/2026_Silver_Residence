package org.example.xqy1._026_silver_residence.housing;

import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class HousingSearchPolicyService {
    public static final String CONVENIENCE_SOURCE_FIELD = "归一化总分";
    public static final String ROAD_WALKABILITY_SOURCE_FIELD = "WS";
    public static final List<String> SUPPORTED_DISTRICTS = List.of("中山区", "西岗区", "沙河口区");

    private final String policyVersion;
    private final int defaultRoadBufferMeters;
    private final int minRoadBufferMeters;
    private final int maxRoadBufferMeters;
    private final double highPercentile;
    private final double veryHighPercentile;
    private final double defaultConvenienceWeight;
    private final double defaultRoadWalkabilityWeight;
    private final int defaultHousingLimit;
    private final int maxHousingLimit;
    private final int maxRoadCalculationCount;
    private final int maxRoadDisplayCount;
    private final int maxBufferOverlayCount;

    public HousingSearchPolicyService(
            @Value("${housing.search.policy-version:housing-search-policy-2026-07-29.1}") String policyVersion,
            @Value("${housing.search.default-road-buffer-meters:100}") int defaultRoadBufferMeters,
            @Value("${housing.search.min-road-buffer-meters:20}") int minRoadBufferMeters,
            @Value("${housing.search.max-road-buffer-meters:2000}") int maxRoadBufferMeters,
            @Value("${housing.search.high-percentile:0.75}") double highPercentile,
            @Value("${housing.search.very-high-percentile:0.90}") double veryHighPercentile,
            @Value("${housing.search.default-convenience-weight:0.50}") double defaultConvenienceWeight,
            @Value("${housing.search.default-road-walkability-weight:0.50}") double defaultRoadWalkabilityWeight,
            @Value("${housing.search.default-housing-limit:20}") int defaultHousingLimit,
            @Value("${housing.search.max-housing-limit:50}") int maxHousingLimit,
            @Value("${housing.search.max-road-calculation-count:2000}") int maxRoadCalculationCount,
            @Value("${housing.search.max-road-display-count:50}") int maxRoadDisplayCount,
            @Value("${housing.search.max-buffer-overlay-count:20}") int maxBufferOverlayCount
    ) {
        this.policyVersion = policyVersion;
        this.defaultRoadBufferMeters = defaultRoadBufferMeters;
        this.minRoadBufferMeters = minRoadBufferMeters;
        this.maxRoadBufferMeters = maxRoadBufferMeters;
        this.highPercentile = highPercentile;
        this.veryHighPercentile = veryHighPercentile;
        this.defaultConvenienceWeight = defaultConvenienceWeight;
        this.defaultRoadWalkabilityWeight = defaultRoadWalkabilityWeight;
        this.defaultHousingLimit = defaultHousingLimit;
        this.maxHousingLimit = maxHousingLimit;
        this.maxRoadCalculationCount = maxRoadCalculationCount;
        this.maxRoadDisplayCount = maxRoadDisplayCount;
        this.maxBufferOverlayCount = maxBufferOverlayCount;
    }

    public ResolvedHousingSearchRequest resolve(HousingSearchRequest request) {
        if (request == null || request.mode() == null) {
            invalid("mode is required");
        }
        List<String> districts = resolveDistricts(request.districts());
        HousingSearchRequest.HardFilters hardFilters = request.hardFilters() == null
                ? new HousingSearchRequest.HardFilters(null, null)
                : request.hardFilters();
        validatePrices(hardFilters);

        List<String> defaults = new ArrayList<>();
        HousingSearchRequest.Preferences preferences = resolvePreferences(request.preferences(), defaults);
        HousingSearchRequest.RoadCriteria roadCriteria = request.roadCriteria() == null
                ? new HousingSearchRequest.RoadCriteria(null, null, null)
                : request.roadCriteria();
        validateRoadCriteria(roadCriteria);
        validateBufferFilterSemantics(request.mode(), preferences, roadCriteria);

        HousingSearchRequest.Spatial spatial = request.spatial();
        if (spatial != null && spatial.relation() != null
                && spatial.relation() != HousingSearchRequest.SpatialRelation.WITHIN_ROAD_BUFFER) {
            invalid("spatial.relation must be WITHIN_ROAD_BUFFER");
        }
        int bufferMeters;
        if (spatial == null || spatial.bufferMeters() == null) {
            bufferMeters = defaultRoadBufferMeters;
            defaults.add("BUFFER_METERS");
        } else {
            bufferMeters = spatial.bufferMeters();
        }
        if (bufferMeters < minRoadBufferMeters || bufferMeters > maxRoadBufferMeters) {
            throw new MapContractException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BUFFER_DISTANCE",
                    "bufferMeters must be between " + minRoadBufferMeters + " and " + maxRoadBufferMeters
            );
        }

        HousingSearchRequest.Display display = request.display() == null
                ? new HousingSearchRequest.Display(true, true)
                : new HousingSearchRequest.Display(
                        request.display().includeRoads() == null || request.display().includeRoads(),
                        request.display().includeBuffers() == null || request.display().includeBuffers()
                );
        int limit = request.limit() == null ? defaultHousingLimit : request.limit();
        if (limit < 1 || limit > maxHousingLimit) {
            invalid("limit must be between 1 and " + maxHousingLimit);
        }
        return new ResolvedHousingSearchRequest(
                request.mode(), districts, hardFilters, preferences, roadCriteria,
                bufferMeters, display, limit, List.copyOf(new LinkedHashSet<>(defaults))
        );
    }

    private List<String> resolveDistricts(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return SUPPORTED_DISTRICTS;
        }
        Set<String> distinct = new LinkedHashSet<>(requested);
        if (distinct.size() != requested.size() || !SUPPORTED_DISTRICTS.containsAll(distinct)) {
            invalid("districts contains an unsupported or duplicate value");
        }
        return List.copyOf(distinct);
    }

    private HousingSearchRequest.Preferences resolvePreferences(
            HousingSearchRequest.Preferences value,
            List<String> defaults
    ) {
        if (value == null || value.price() == null
                || value.convenience() == null || value.roadWalkability() == null) {
            invalid("price, convenience and roadWalkability preference objects are required");
        }
        HousingSearchRequest.PricePreference price = resolvePricePreference(value.price());
        HousingSearchRequest.Preference convenience = resolvePreference(
                value.convenience(), defaultConvenienceWeight, defaults
        );
        HousingSearchRequest.Preference road = resolvePreference(
                value.roadWalkability(), defaultRoadWalkabilityWeight, defaults
        );
        double total = enabledWeight(price) + enabledWeight(convenience) + enabledWeight(road);
        if (Math.abs(total - 1.0) > 0.000001) {
            invalid("enabled preference weights must sum to 1");
        }
        return new HousingSearchRequest.Preferences(price, convenience, road);
    }

    private HousingSearchRequest.PricePreference resolvePricePreference(
            HousingSearchRequest.PricePreference value
    ) {
        boolean enabled = Boolean.TRUE.equals(value.enabled());
        HousingSearchRequest.PricePreferenceLevel level = value.level() == null
                ? HousingSearchRequest.PricePreferenceLevel.PREFER_LOW
                : value.level();
        double weight = value.weight() == null ? 0.0 : value.weight();
        if (!Double.isFinite(weight) || weight < 0 || weight > 1 || !enabled && weight != 0) {
            invalid("price preference weight must be between 0 and 1 and must be zero when disabled");
        }
        return new HousingSearchRequest.PricePreference(enabled, level, weight);
    }

    private HousingSearchRequest.Preference resolvePreference(
            HousingSearchRequest.Preference value,
            double defaultWeight,
            List<String> defaults
    ) {
        boolean enabled = Boolean.TRUE.equals(value.enabled());
        HousingSearchRequest.PreferenceLevel level = value.level() == null
                ? HousingSearchRequest.PreferenceLevel.PREFER_HIGH
                : value.level();
        double weight;
        if (value.weight() == null) {
            weight = enabled ? defaultWeight : 0.0;
            if (enabled) {
                defaults.add("PREFERENCE_WEIGHTS");
            }
        } else {
            weight = value.weight();
        }
        if (!Double.isFinite(weight) || weight < 0 || weight > 1 || !enabled && weight != 0) {
            invalid("preference weight must be between 0 and 1 and disabled preferences must use zero");
        }
        return new HousingSearchRequest.Preference(enabled, level, weight);
    }

    private double enabledWeight(HousingSearchRequest.Preference preference) {
        return Boolean.TRUE.equals(preference.enabled()) ? preference.weight() : 0.0;
    }

    private double enabledWeight(HousingSearchRequest.PricePreference preference) {
        return Boolean.TRUE.equals(preference.enabled()) ? preference.weight() : 0.0;
    }

    private void validatePrices(HousingSearchRequest.HardFilters value) {
        if (invalidNonNegative(value.priceMin()) || invalidNonNegative(value.priceMax())) {
            invalid("price bounds must be finite and non-negative");
        }
        if (value.priceMin() != null && value.priceMax() != null && value.priceMin() > value.priceMax()) {
            invalid("priceMax must not be less than priceMin");
        }
    }

    private void validateRoadCriteria(HousingSearchRequest.RoadCriteria value) {
        if (invalidNonNegative(value.wsMin()) || invalidNonNegative(value.gviMin())
                || invalidNonNegative(value.noiMax())) {
            invalid("road criteria must be finite and non-negative");
        }
    }

    private void validateBufferFilterSemantics(
            HousingSearchRequest.Mode mode,
            HousingSearchRequest.Preferences preferences,
            HousingSearchRequest.RoadCriteria roadCriteria
    ) {
        if (mode != HousingSearchRequest.Mode.BUFFER_FILTER || roadCriteria.wsMin() != null) {
            return;
        }
        HousingSearchRequest.Preference road = preferences.roadWalkability();
        if (!Boolean.TRUE.equals(road.enabled())
                || road.level() == HousingSearchRequest.PreferenceLevel.PREFER_HIGH) {
            invalid("BUFFER_FILTER without wsMin requires roadWalkability HIGH or VERY_HIGH");
        }
    }

    private boolean invalidNonNegative(Double value) {
        return value != null && (!Double.isFinite(value) || value < 0);
    }

    private void invalid(String message) {
        throw new MapContractException(HttpStatus.BAD_REQUEST, "INVALID_HOUSING_SEARCH_ARGUMENT", message);
    }

    public String policyVersion() { return policyVersion; }
    public double highPercentile() { return highPercentile; }
    public double veryHighPercentile() { return veryHighPercentile; }
    public int maxRoadCalculationCount() { return maxRoadCalculationCount; }
    public int maxRoadDisplayCount() { return maxRoadDisplayCount; }
    public int maxBufferOverlayCount() { return maxBufferOverlayCount; }
}
