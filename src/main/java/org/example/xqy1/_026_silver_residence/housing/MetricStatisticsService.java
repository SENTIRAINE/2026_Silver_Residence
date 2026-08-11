package org.example.xqy1._026_silver_residence.housing;

import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Service
public class MetricStatisticsService {
    public MetricDistribution distribution(Collection<Double> rawValues, String metricName) {
        List<Double> values = rawValues.stream()
                .filter(value -> value != null && Double.isFinite(value))
                .sorted()
                .toList();
        if (values.isEmpty()) {
            throw new MapContractException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "METRIC_STATISTICS_UNAVAILABLE",
                    "指标统计不可用: " + metricName,
                    true,
                    null
            );
        }
        return new MetricDistribution(values);
    }

    public static final class MetricDistribution {
        private final List<Double> values;

        private MetricDistribution(List<Double> values) {
            this.values = Collections.unmodifiableList(new ArrayList<>(values));
        }

        public int sampleCount() {
            return values.size();
        }

        public double percentileValue(double percentile) {
            if (!Double.isFinite(percentile) || percentile < 0 || percentile > 1) {
                throw new IllegalArgumentException("percentile must be between zero and one");
            }
            if (values.size() == 1) {
                return values.get(0);
            }
            double index = (values.size() - 1) * percentile;
            int lower = (int) Math.floor(index);
            int upper = (int) Math.ceil(index);
            if (lower == upper) {
                return values.get(lower);
            }
            double fraction = index - lower;
            return values.get(lower) + (values.get(upper) - values.get(lower)) * fraction;
        }

        public double percentileRank(double value) {
            int upperBound = upperBound(values, value);
            return roundOneDecimal(upperBound * 100.0 / values.size());
        }

        public double reversePercentileRank(double value) {
            int lowerBound = lowerBound(values, value);
            return roundOneDecimal((values.size() - lowerBound) * 100.0 / values.size());
        }

        private int upperBound(List<Double> sorted, double value) {
            int low = 0;
            int high = sorted.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (sorted.get(middle) <= value) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }

        private int lowerBound(List<Double> sorted, double value) {
            int low = 0;
            int high = sorted.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (sorted.get(middle) < value) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }
    }

    static double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
