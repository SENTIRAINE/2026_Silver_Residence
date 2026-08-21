package org.example.xqy1._026_silver_residence.housing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadSpatialSearchServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void includesTheMetricBoundaryWithoutExpandingPastIt() {
        CoordinateTransformFactory factory = new CoordinateTransformFactory();
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem wgs84 = crsFactory.createFromParameters(
                "WGS84", "+proj=longlat +datum=WGS84 +no_defs"
        );
        CoordinateReferenceSystem utm51 = crsFactory.createFromParameters(
                "UTM51N", "+proj=utm +zone=51 +datum=WGS84 +units=m +no_defs"
        );
        CoordinateTransform toMeters = factory.createTransform(wgs84, utm51);
        CoordinateTransform toWgs84 = factory.createTransform(utm51, wgs84);
        ProjCoordinate origin = new ProjCoordinate();
        toMeters.transform(new ProjCoordinate(121.60, 38.90), origin);

        HousingSearchFeature road = road(
                inverse(toWgs84, origin.x - 500, origin.y),
                inverse(toWgs84, origin.x + 500, origin.y)
        );
        List<HousingSearchFeature> points = List.of(
                point("2:99.9", inverse(toWgs84, origin.x, origin.y + 99.9)),
                point("2:100", inverse(toWgs84, origin.x, origin.y + 100.0)),
                point("2:100.1", inverse(toWgs84, origin.x, origin.y + 100.1))
        );

        RoadSpatialSearchService.SpatialMatches matches = new RoadSpatialSearchService(objectMapper)
                .match(points, List.of(road), 100);

        assertTrue(matches.byHousingId().containsKey("2:99.9"));
        assertTrue(matches.byHousingId().containsKey("2:100"));
        assertFalse(matches.byHousingId().containsKey("2:100.1"));
    }

    private double[] inverse(CoordinateTransform transform, double x, double y) {
        ProjCoordinate result = new ProjCoordinate();
        transform.transform(new ProjCoordinate(x, y), result);
        return new double[]{result.x, result.y};
    }

    private HousingSearchFeature point(String id, double[] coordinate) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("x", coordinate[0]);
        geometry.put("y", coordinate[1]);
        return new HousingSearchFeature(id, 2, "中山区", Map.of(), geometry);
    }

    private HousingSearchFeature road(double[] start, double[] end) {
        ObjectNode geometry = objectMapper.createObjectNode();
        var path = geometry.putArray("paths").addArray();
        path.addArray().add(start[0]).add(start[1]);
        path.addArray().add(end[0]).add(end[1]);
        return new HousingSearchFeature("3:1", 3, "中山区", Map.of("WS归一化", "80"), geometry);
    }
}
