package org.example.xqy1._026_silver_residence.housing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.xqy1._026_silver_residence.api.MapContractException;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RoadSpatialSearchService {
    private static final double DISTANCE_EPSILON_METERS = 0.01;

    private final ObjectMapper objectMapper;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final CoordinateTransform toMeters;
    private final CoordinateTransform toWgs84;

    public RoadSpatialSearchService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem wgs84 = crsFactory.createFromParameters(
                "WGS84", "+proj=longlat +datum=WGS84 +no_defs"
        );
        CoordinateReferenceSystem utm51 = crsFactory.createFromParameters(
                "UTM51N", "+proj=utm +zone=51 +datum=WGS84 +units=m +no_defs"
        );
        CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();
        this.toMeters = transformFactory.createTransform(wgs84, utm51);
        this.toWgs84 = transformFactory.createTransform(utm51, wgs84);
    }

    public SpatialMatches match(
            List<HousingSearchFeature> housing,
            List<HousingSearchFeature> roads,
            int bufferMeters
    ) {
        List<ProjectedRoad> projectedRoads = roads.stream()
                .map(this::projectRoad)
                .filter(value -> value.geometry() != null && !value.geometry().isEmpty())
                .toList();
        STRtree index = new STRtree();
        projectedRoads.forEach(road -> index.insert(road.geometry().getEnvelopeInternal(), road));
        index.build();

        Map<String, List<RoadMatch>> byHousing = new LinkedHashMap<>();
        for (HousingSearchFeature feature : housing) {
            Point point = projectPoint(feature.geometry());
            if (point == null) {
                continue;
            }
            Envelope search = new Envelope(point.getCoordinate());
            search.expandBy(bufferMeters);
            @SuppressWarnings("unchecked")
            List<ProjectedRoad> candidates = index.query(search);
            List<RoadMatch> matches = new ArrayList<>();
            for (ProjectedRoad road : candidates) {
                double distance = road.geometry().distance(point);
                if (distance <= bufferMeters + DISTANCE_EPSILON_METERS) {
                    matches.add(new RoadMatch(road.feature(), distance, road.geometry()));
                }
            }
            matches.sort(Comparator.comparingDouble(RoadMatch::distanceMeters)
                    .thenComparing(value -> value.road().id()));
            if (!matches.isEmpty()) {
                byHousing.put(feature.id(), List.copyOf(matches));
            }
        }
        return new SpatialMatches(Map.copyOf(byHousing), projectedRoads);
    }

    public List<HousingSearchResult.BufferOverlay> buildBufferOverlays(
            List<HousingSearchFeature> roads,
            int bufferMeters,
            int maxOverlays
    ) {
        List<ProjectedRoad> projected = roads.stream()
                .map(this::projectRoad)
                .filter(value -> value.geometry() != null && !value.geometry().isEmpty())
                .toList();
        return buildProjectedBufferOverlays(projected, bufferMeters, maxOverlays);
    }

    public List<HousingSearchResult.BufferOverlay> buildProjectedBufferOverlays(
            List<ProjectedRoad> projected,
            int bufferMeters,
            int maxOverlays
    ) {
        if (projected.isEmpty()) {
            return List.of();
        }
        List<RoadBuffer> roadBuffers = projected.stream()
                .map(value -> new RoadBuffer(value.feature(), value.geometry().buffer(bufferMeters, 8)))
                .toList();
        Geometry dissolved = UnaryUnionOp.union(roadBuffers.stream().map(RoadBuffer::geometry).toList());
        Geometry displayGeometry = TopologyPreservingSimplifier.simplify(
                dissolved,
                Math.min(2.0, Math.max(0.25, bufferMeters / 40.0))
        );
        List<Polygon> polygons = polygons(displayGeometry);
        if (polygons.size() > maxOverlays) {
            throw new MapContractException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SPATIAL_RESULT_TOO_LARGE",
                    "缓冲区 polygon 数量超过安全上限",
                    false,
                    Map.of("count", polygons.size(), "max", maxOverlays)
            );
        }
        STRtree bufferIndex = new STRtree();
        roadBuffers.forEach(buffer -> bufferIndex.insert(buffer.geometry().getEnvelopeInternal(), buffer));
        bufferIndex.build();
        List<HousingSearchResult.BufferOverlay> result = new ArrayList<>();
        for (int i = 0; i < polygons.size(); i++) {
            Polygon polygon = polygons.get(i);
            @SuppressWarnings("unchecked")
            List<RoadBuffer> candidates = bufferIndex.query(polygon.getEnvelopeInternal());
            List<String> sourceRoadIds = candidates.stream()
                    .filter(buffer -> buffer.geometry().intersects(polygon))
                    .map(buffer -> buffer.feature().id())
                    .distinct()
                    .sorted()
                    .toList();
            result.add(new HousingSearchResult.BufferOverlay(
                    "buf-" + String.format("%02d", i + 1),
                    "ROAD_BUFFER",
                    "polygon",
                    Map.of("wkid", 4326),
                    sourceRoadIds.stream().limit(20).toList(),
                    new HousingSearchResult.BufferAttributes(
                            bufferMeters,
                            sourceRoadIds.size(),
                            sourceRoadIds.size() > 20
                    ),
                    polygonJson(polygon)
            ));
        }
        return List.copyOf(result);
    }

    private ProjectedRoad projectRoad(HousingSearchFeature feature) {
        JsonNode paths = feature.geometry() == null ? null : feature.geometry().path("paths");
        if (paths == null || !paths.isArray() || paths.isEmpty()) {
            return new ProjectedRoad(feature, null);
        }
        List<org.locationtech.jts.geom.LineString> lines = new ArrayList<>();
        for (JsonNode path : paths) {
            if (!path.isArray() || path.size() < 2) {
                continue;
            }
            List<Coordinate> coordinates = new ArrayList<>();
            for (JsonNode pair : path) {
                if (pair.isArray() && pair.size() >= 2) {
                    coordinates.add(project(pair.get(0).asDouble(), pair.get(1).asDouble()));
                }
            }
            if (coordinates.size() >= 2) {
                lines.add(geometryFactory.createLineString(coordinates.toArray(Coordinate[]::new)));
            }
        }
        if (lines.isEmpty()) {
            return new ProjectedRoad(feature, null);
        }
        Geometry geometry = lines.size() == 1
                ? lines.get(0)
                : geometryFactory.createMultiLineString(lines.toArray(org.locationtech.jts.geom.LineString[]::new));
        return new ProjectedRoad(feature, geometry);
    }

    private Point projectPoint(JsonNode geometry) {
        if (geometry == null || !geometry.isObject() || !geometry.has("x") || !geometry.has("y")) {
            return null;
        }
        return geometryFactory.createPoint(project(geometry.path("x").asDouble(), geometry.path("y").asDouble()));
    }

    private Coordinate project(double longitude, double latitude) {
        ProjCoordinate output = new ProjCoordinate();
        toMeters.transform(new ProjCoordinate(longitude, latitude), output);
        return new Coordinate(output.x, output.y);
    }

    private Coordinate unproject(Coordinate coordinate) {
        ProjCoordinate output = new ProjCoordinate();
        toWgs84.transform(new ProjCoordinate(coordinate.x, coordinate.y), output);
        return new Coordinate(output.x, output.y);
    }

    private List<Polygon> polygons(Geometry geometry) {
        List<Polygon> result = new ArrayList<>();
        if (geometry instanceof Polygon polygon) {
            result.add(polygon);
        } else if (geometry instanceof MultiPolygon multiPolygon) {
            for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
                result.add((Polygon) multiPolygon.getGeometryN(i));
            }
        } else {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                result.addAll(polygons(geometry.getGeometryN(i)));
            }
        }
        result.sort(Comparator.comparingDouble(Polygon::getArea).reversed());
        return result;
    }

    private JsonNode polygonJson(Polygon polygon) {
        ObjectNode value = objectMapper.createObjectNode();
        ArrayNode rings = value.putArray("rings");
        appendRing(rings, polygon.getExteriorRing(), true);
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            appendRing(rings, polygon.getInteriorRingN(i), false);
        }
        value.set("spatialReference", objectMapper.createObjectNode().put("wkid", 4326));
        return value;
    }

    private void appendRing(ArrayNode rings, org.locationtech.jts.geom.LineString line, boolean exterior) {
        Coordinate[] coordinates = line.getCoordinates();
        boolean isCounterClockwise = Orientation.isCCW(coordinates);
        boolean reverse = exterior ? isCounterClockwise : !isCounterClockwise;
        ArrayNode ring = rings.addArray();
        if (reverse) {
            for (int i = coordinates.length - 1; i >= 0; i--) {
                addCoordinate(ring, coordinates[i]);
            }
        } else {
            for (Coordinate coordinate : coordinates) {
                addCoordinate(ring, coordinate);
            }
        }
    }

    private void addCoordinate(ArrayNode ring, Coordinate coordinate) {
        Coordinate wgs84 = unproject(coordinate);
        ArrayNode pair = ring.addArray();
        pair.add(wgs84.x);
        pair.add(wgs84.y);
    }

    public record RoadMatch(
            HousingSearchFeature road,
            double distanceMeters,
            Geometry projectedGeometry
    ) {
    }

    public record SpatialMatches(
            Map<String, List<RoadMatch>> byHousingId,
            List<ProjectedRoad> projectedRoads
    ) {
        public SpatialMatches {
            byHousingId = Map.copyOf(byHousingId);
            projectedRoads = List.copyOf(projectedRoads);
        }
    }

    public record ProjectedRoad(HousingSearchFeature feature, Geometry geometry) {
    }

    private record RoadBuffer(HousingSearchFeature feature, Geometry geometry) {
    }
}
