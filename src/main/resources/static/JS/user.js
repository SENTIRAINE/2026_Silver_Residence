const GEOSCENE_PORTAL_URL = "https://edutrial.geoscene.cn/geoscene";
const STREET_BASEMAP_URL_TEMPLATE = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{level}/{row}/{col}";
const IMAGERY_BASEMAP_URL_TEMPLATE = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{level}/{row}/{col}";
const BASEMAP_SWITCH_SCALE = 50000;
const MAX_MAP_ZOOM = 19;
const GEOSCENE_MAP_SERVICE_URL = "/api/map/geoscene";
const EXPECTED_MAP_SUBLAYERS = new Map([
    [0, "shahekou_1"],
    [1, "xigang_1"],
    [2, "zhongshan_1"],
    [3, "ZhongShan"],
    [4, "XiGang"],
    [5, "ShaHeKou"]
]);

function createClientUuid() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
        return window.crypto.randomUUID();
    }
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (value) => {
        const random = Math.floor(Math.random() * 16);
        const digit = value === "x" ? random : (random & 0x3) | 0x8;
        return digit.toString(16);
    });
}

function parseSseBlock(block) {
    let eventName = "message";
    let eventId = "";
    const dataLines = [];
    block.split("\n").forEach((line) => {
        if (!line || line.startsWith(":")) {
            return;
        }
        const separator = line.indexOf(":");
        const field = separator < 0 ? line : line.slice(0, separator);
        let value = separator < 0 ? "" : line.slice(separator + 1);
        if (value.startsWith(" ")) {
            value = value.slice(1);
        }
        if (field === "event") {
            eventName = value;
        } else if (field === "id") {
            eventId = value;
        } else if (field === "data") {
            dataLines.push(value);
        }
    });
    if (dataLines.length === 0) {
        return null;
    }
    return { eventName, eventId, data: dataLines.join("\n") };
}

async function consumeSseStream(stream, onEvent) {
    if (!stream) {
        throw new Error("Agent 接口没有返回事件流");
    }
    const reader = stream.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    while (true) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
        buffer = buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
        let boundary = buffer.indexOf("\n\n");
        while (boundary >= 0) {
            const block = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            const event = parseSseBlock(block);
            if (event) {
                await onEvent(event);
            }
            boundary = buffer.indexOf("\n\n");
        }
        if (done) {
            break;
        }
    }
    if (buffer.trim()) {
        const event = parseSseBlock(buffer.trim());
        if (event) {
            await onEvent(event);
        }
    }
}

function runWhenDomReady(callback) {
    if (document.readyState === "loading") {
        window.addEventListener("DOMContentLoaded", callback);
        return;
    }

    callback();
}

function removeMapOverlays() {
    document
        .querySelectorAll("#mapContainer .map-shade, #mapContainer .grid-pattern")
        .forEach((element) => element.remove());
}

function updateMapStatus(message, state = "loading") {
    const statusElement = document.getElementById("mapStatus");

    if (!statusElement) {
        return;
    }

    const textElement = statusElement.querySelector("[data-map-status-text]");

    if (textElement) {
        textElement.textContent = message;
    }

    statusElement.classList.toggle("is-ready", state === "ready");
    statusElement.classList.toggle("is-error", state === "error");
}

function validateMapSublayers(mapServiceLayer) {
    const actualSublayers = new Map(
        mapServiceLayer.allSublayers.map((sublayer) => [sublayer.id, sublayer.title])
    );
    const missingSublayers = [];

    EXPECTED_MAP_SUBLAYERS.forEach((title, id) => {
        if (actualSublayers.get(id) !== title) {
            missingSublayers.push(`${id}:${title}`);
        }
    });

    if (missingSublayers.length > 0) {
        throw new Error(`地图服务缺少预期子图层：${missingSublayers.join(", ")}`);
    }
}

function escapePopupValue(value) {
    return String(value == null ? "" : value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function getGraphicObjectId(attributes) {
    const objectIdKey = ["OBJECTID_12", "OBJECTID", "objectid", "FID", "fid"]
        .find((key) => attributes && attributes[key] != null);
    return objectIdKey ? { field: objectIdKey, value: attributes[objectIdKey] } : null;
}

function resolveMapHit(item) {
    const candidates = [item && item.layer, item && item.graphic && item.graphic.layer, item && item.graphic && item.graphic.sourceLayer];
    const layer = candidates.find((candidate) => {
        if (!candidate) {
            return false;
        }
        const id = Number(candidate.id);
        return EXPECTED_MAP_SUBLAYERS.has(id) || [...EXPECTED_MAP_SUBLAYERS.values()].includes(candidate.title);
    });
    if (!layer) {
        return null;
    }
    const numericId = Number(layer.id);
    const layerId = EXPECTED_MAP_SUBLAYERS.has(numericId)
        ? numericId
        : [...EXPECTED_MAP_SUBLAYERS.entries()].find(([, title]) => title === layer.title)?.[0];
    return layerId == null ? null : { layerId, layerName: EXPECTED_MAP_SUBLAYERS.get(layerId) };
}

function formatPopupValue(field, value) {
    if (value == null || value === "") {
        return "—";
    }
    const number = Number(value);
    if (!Number.isFinite(number)) {
        return String(value);
    }
    if (field === "房价") {
        return `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 }).format(number)} 元/㎡`;
    }
    if (field === "Shape_Length") {
        return `${number.toFixed(1)} 米`;
    }
    if (["GVI", "NOI", "WS", "覆盖度评分", "归一化总分"].includes(field)) {
        return number.toFixed(1).replace(/\.0$/, "");
    }
    return String(value);
}

function popupMetric(field, label, attributes) {
    return `<div class="map-popup-metric"><span>${escapePopupValue(label)}</span><strong>${escapePopupValue(formatPopupValue(field, attributes[field]))}</strong></div>`;
}

function buildLinePopupContent(attributes) {
    const length = attributes.Shape_Length == null
        ? ""
        : `<div class="map-popup-detail"><span>道路长度</span><strong>${escapePopupValue(formatPopupValue("Shape_Length", attributes.Shape_Length))}</strong></div>`;
    return `
        <div class="map-popup-metrics">
            ${popupMetric("GVI", "绿视率", attributes)}
            ${popupMetric("NOI", "道路噪声", attributes)}
            ${popupMetric("WS", "步行指数", attributes)}
        </div>
        ${length}
    `;
}

function buildPointPopupContent(attributes) {
    const address = escapePopupValue(attributes.address || "地址信息暂缺");
    const district = attributes.adname
        ? `<span class="point-feature-popup__district">${escapePopupValue(attributes.adname)}</span>`
        : "";
    const categories = [attributes.大类, attributes.中类, attributes.小类]
        .filter((value) => value != null && String(value).trim() !== "")
        .map(escapePopupValue)
        .join("<span aria-hidden=\"true\">·</span>");
    const category = categories
        ? `<div class="point-feature-popup__category"><span>项目分类</span><p>${categories}</p></div>`
        : "";
    return `
        <div class="point-feature-popup__location">
            <p>${address}</p>
            ${district}
        </div>
        <div class="point-feature-popup__metrics">
            ${popupMetric("房价", "参考房价", attributes)}
            ${popupMetric("覆盖度评分", "覆盖评分", attributes)}
            ${popupMetric("归一化总分", "社区便利度", attributes)}
        </div>
        ${category}
    `;
}

function closeLineFeaturePopup() {
    const popup = document.getElementById("lineFeaturePopup");
    if (popup) {
        popup.hidden = true;
    }
}

function closePointFeaturePopup() {
    const popup = document.getElementById("pointFeaturePopup");
    if (popup) {
        popup.hidden = true;
    }
}

function positionFeaturePopup(popup, screenPoint, view, fallbackHeight) {
    const width = popup.offsetWidth || 320;
    const height = popup.offsetHeight || fallbackHeight;
    const left = Math.max(12, Math.min(view.width - width - 12, screenPoint.x - width / 2));
    let top = screenPoint.y - height - 18;
    popup.classList.remove("is-below");
    if (top < 96) {
        top = screenPoint.y + 18;
        popup.classList.add("is-below");
    }
    popup.style.left = `${left}px`;
    popup.style.top = `${Math.max(12, Math.min(top, view.height - height - 12))}px`;
}

function openPointFeaturePopup(mapContainer, attributes, screenPoint, view) {
    let popup = document.getElementById("pointFeaturePopup");
    if (!popup) {
        popup = document.createElement("div");
        popup.id = "pointFeaturePopup";
        popup.className = "point-feature-popup";
        popup.setAttribute("role", "dialog");
        popup.setAttribute("aria-label", "项目详情");
        popup.innerHTML = `
            <button class="point-feature-popup__close" type="button" aria-label="关闭详情" title="关闭">×</button>
            <div data-point-popup-content></div>
            <span class="point-feature-popup__pointer" aria-hidden="true"></span>
        `;
        popup.querySelector(".point-feature-popup__close").addEventListener("click", closePointFeaturePopup);
        mapContainer.appendChild(popup);
    }
    const name = escapePopupValue(attributes.name || "项目详情");
    popup.querySelector("[data-point-popup-content]").innerHTML = `<h3>${name}</h3>${buildPointPopupContent(attributes)}`;
    popup.hidden = false;
    positionFeaturePopup(popup, screenPoint, view, 220);
}

function openLineFeaturePopup(mapContainer, attributes, screenPoint, view) {
    let popup = document.getElementById("lineFeaturePopup");
    if (!popup) {
        popup = document.createElement("div");
        popup.id = "lineFeaturePopup";
        popup.className = "line-feature-popup";
        popup.setAttribute("role", "dialog");
        popup.setAttribute("aria-label", "道路详情");
        popup.innerHTML = `
            <button class="line-feature-popup__close" type="button" aria-label="关闭详情" title="关闭">×</button>
            <div data-line-popup-content></div>
            <span class="line-feature-popup__pointer" aria-hidden="true"></span>
        `;
        popup.querySelector(".line-feature-popup__close").addEventListener("click", closeLineFeaturePopup);
        mapContainer.appendChild(popup);
    }
    const name = escapePopupValue(attributes.name || "道路详情");
    popup.querySelector("[data-line-popup-content]").innerHTML = `<h3>${name}</h3>${buildLinePopupContent(attributes)}`;
    popup.hidden = false;
    positionFeaturePopup(popup, screenPoint, view, 154);
}

function popupOutFields(layerId, objectIdField) {
    if (layerId >= 3) {
        return [objectIdField || "OBJECTID_12", "name", "GVI", "NOI", "WS", "Shape_Length"];
    }
    return [objectIdField || "OBJECTID", "name", "address", "adname", "大类", "中类", "小类", "房价", "覆盖度评分", "归一化总分"];
}

async function fetchMapFeature(requestBody) {
    const response = await fetch("/api/map/query-features", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(requestBody)
    });
    if (!response.ok) {
        throw new Error(`地图要素查询失败：HTTP ${response.status}`);
    }
    const payload = await response.json();
    if (!payload || !payload.success || !payload.data) {
        throw new Error(payload?.error?.message || "地图要素查询没有返回数据");
    }
    return payload.data;
}

function mapPointCoordinates(mapPoint) {
    const longitude = Number(mapPoint?.longitude);
    const latitude = Number(mapPoint?.latitude);
    return Number.isFinite(longitude) && Number.isFinite(latitude)
        ? { longitude, latitude }
        : null;
}

async function queryPopupAttributes(layerId, attributes, mapPoint) {
    const objectId = getGraphicObjectId(attributes);
    const coordinates = mapPointCoordinates(mapPoint);
    const requestBody = {
        layerId,
        filters: objectId ? [{ field: objectId.field, operator: "=", value: objectId.value }] : [],
        outFields: popupOutFields(layerId, objectId?.field),
        returnGeometry: false,
        resultRecordCount: 1,
        returnCount: false
    };
    if (!objectId && coordinates) {
        Object.assign(requestBody, coordinates, { distanceMeters: layerId >= 3 ? 45 : 100 });
    }
    const data = await fetchMapFeature(requestBody);
    const feature = data.features && data.features[0];
    return feature && feature.attributes ? feature.attributes : attributes;
}

function spatialClickTolerance(view, layerId) {
    const resolution = Number(view?.resolution);
    const isLine = layerId >= 3;
    if (!Number.isFinite(resolution)) {
        return isLine ? 30 : 40;
    }
    const pixelTolerance = isLine ? 7 : 9;
    const minimum = isLine ? 8 : 12;
    const maximum = isLine ? 45 : 60;
    return Math.max(minimum, Math.min(maximum, resolution * pixelTolerance));
}

function pointToSegmentDistance(point, start, end) {
    const dx = end.x - start.x;
    const dy = end.y - start.y;
    if (dx === 0 && dy === 0) {
        return Math.hypot(point.x - start.x, point.y - start.y);
    }
    const ratio = Math.max(0, Math.min(1,
        ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy)
    ));
    return Math.hypot(point.x - (start.x + ratio * dx), point.y - (start.y + ratio * dy));
}

function identifyResultDistance(view, screenPoint, result) {
    const geometry = result.geometry;
    if (!geometry) {
        return Number.POSITIVE_INFINITY;
    }
    if (result.geometryType === "esriGeometryPoint") {
        const point = view.toScreen({ x: geometry.x, y: geometry.y, spatialReference: { wkid: 4326 } });
        return point ? Math.hypot(screenPoint.x - point.x, screenPoint.y - point.y) : Number.POSITIVE_INFINITY;
    }
    if (result.geometryType === "esriGeometryPolyline") {
        let minimum = Number.POSITIVE_INFINITY;
        (geometry.paths || []).forEach((path) => {
            for (let index = 1; index < path.length; index += 1) {
                const start = view.toScreen({ x: path[index - 1][0], y: path[index - 1][1], spatialReference: { wkid: 4326 } });
                const end = view.toScreen({ x: path[index][0], y: path[index][1], spatialReference: { wkid: 4326 } });
                if (start && end) {
                    minimum = Math.min(minimum, pointToSegmentDistance(screenPoint, start, end));
                }
            }
        });
        return minimum;
    }
    return Number.POSITIVE_INFINITY;
}

async function identifySpatialFeature(mapPoint, view, screenPoint) {
    const coordinates = mapPointCoordinates(mapPoint);
    if (!coordinates) {
        return null;
    }
    const topLeft = mapPointCoordinates(view.toMap({ x: 0, y: 0 }));
    const bottomRight = mapPointCoordinates(view.toMap({ x: view.width, y: view.height }));
    if (!topLeft || !bottomRight) {
        return null;
    }
    const params = new URLSearchParams({
        f: "json",
        geometry: `${coordinates.longitude},${coordinates.latitude}`,
        geometryType: "esriGeometryPoint",
        sr: "4326",
        tolerance: "12",
        mapExtent: `${topLeft.longitude},${bottomRight.latitude},${bottomRight.longitude},${topLeft.latitude}`,
        imageDisplay: `${Math.round(view.width)},${Math.round(view.height)},96`,
        layers: "all",
        returnGeometry: "true"
    });
    const response = await fetch(`/api/map/geoscene/identify?${params}`);
    if (!response.ok) {
        throw new Error(`GeoScene identify 失败：HTTP ${response.status}`);
    }
    const payload = await response.json();
    const candidates = (payload.results || [])
        .filter((result) => EXPECTED_MAP_SUBLAYERS.has(Number(result.layerId)))
        .map((result) => ({
            layerId: Number(result.layerId),
            attributes: result.attributes || {},
            distance: identifyResultDistance(view, screenPoint, result)
        }))
        .filter((candidate) => Number.isFinite(candidate.distance));
    candidates.sort((left, right) => {
        const difference = left.distance - right.distance;
        if (Math.abs(difference) <= 2 && (left.layerId <= 2) !== (right.layerId <= 2)) {
            return left.layerId <= 2 ? -1 : 1;
        }
        return difference;
    });
    return candidates[0] || null;
}

async function querySpatialFeature(mapPoint, view) {
    const coordinates = mapPointCoordinates(mapPoint);
    if (!coordinates) {
        return null;
    }
    const queryLayers = (layerIds) => new Promise((resolve) => {
        let remaining = layerIds.length;
        let settled = false;
        layerIds.forEach(async (layerId) => {
            let match = null;
            try {
                const data = await fetchMapFeature({
                    layerId,
                    filters: [],
                    outFields: popupOutFields(layerId),
                    returnGeometry: false,
                    resultRecordCount: 1,
                    returnCount: false,
                    ...coordinates,
                    distanceMeters: spatialClickTolerance(view, layerId)
                });
                const feature = data.features && data.features[0];
                if (feature && feature.attributes) {
                    match = { layerId, attributes: feature.attributes };
                }
            } catch (error) {
                console.warn(`空间命中查询失败（图层 ${layerId}）`, error);
            }
            if (match && !settled) {
                settled = true;
                resolve(match);
                return;
            }
            remaining -= 1;
            if (remaining === 0 && !settled) {
                resolve(null);
            }
        });
    });
    const pointPromise = queryLayers([0, 1, 2]);
    const linePromise = queryLayers([3, 4, 5]);
    const pointMatch = await Promise.race([
        pointPromise,
        new Promise((resolve) => window.setTimeout(() => resolve(null), 2500))
    ]);
    if (pointMatch) {
        return pointMatch;
    }
    const lineMatch = await linePromise;
    return lineMatch || await pointPromise;
}

function installMapClickHandler(view, mapContainer, agentLayers) {
    view.on("click", async (event) => {
        const response = await view.hitTest(event);
        const agentHit = response.results.find((item) => (
            (item?.graphic?.layer === agentLayers.housing || item?.graphic?.layer === agentLayers.road)
            && EXPECTED_MAP_SUBLAYERS.has(Number(item.graphic.attributes?.__agentLayerId))
        ));
        const bufferHit = response.results.find((item) => item?.graphic?.layer === agentLayers.buffer);
        const serviceHit = response.results.find((item) => resolveMapHit(item));
        if (!agentHit && bufferHit) {
            closeLineFeaturePopup();
            closePointFeaturePopup();
            view.openPopup({
                features: [bufferHit.graphic],
                location: event.mapPoint
            });
            return;
        }
        const hit = agentHit || serviceHit;
        const hitInfo = serviceHit && resolveMapHit(serviceHit);
        let layerId = agentHit ? Number(agentHit.graphic.attributes.__agentLayerId) : hitInfo?.layerId;
        let layerName = hitInfo?.layerName;
        let fallbackAttributes = hit?.graphic?.attributes || {};
        let resolvedBySpatialQuery = Boolean(agentHit);
        if (agentHit) {
            fallbackAttributes = Object.fromEntries(
                Object.entries(fallbackAttributes).filter(([key]) => !key.startsWith("__agent"))
            );
            layerName = EXPECTED_MAP_SUBLAYERS.get(layerId);
        }
        if (!agentHit && (!hit || !hitInfo || !hit.graphic)) {
            let spatialMatch = null;
            try {
                spatialMatch = await identifySpatialFeature(event.mapPoint, view, { x: event.x, y: event.y });
            } catch (error) {
                console.warn("GeoScene identify 不可用，回退到分图层空间查询", error);
            }
            if (!spatialMatch) {
                spatialMatch = await querySpatialFeature(event.mapPoint, view);
            }
            if (!spatialMatch) {
                view.closePopup();
                closeLineFeaturePopup();
                closePointFeaturePopup();
                return;
            }
            layerId = spatialMatch.layerId;
            layerName = EXPECTED_MAP_SUBLAYERS.get(layerId);
            fallbackAttributes = spatialMatch.attributes;
            resolvedBySpatialQuery = true;
        }
        if (layerId == null) {
            view.closePopup();
            closeLineFeaturePopup();
            closePointFeaturePopup();
            return;
        }

        layerName = layerName || "地图要素";
        try {
            const attributes = resolvedBySpatialQuery
                ? fallbackAttributes
                : await queryPopupAttributes(layerId, fallbackAttributes, event.mapPoint);
            if (layerId >= 3) {
                view.closePopup();
                closePointFeaturePopup();
                openLineFeaturePopup(mapContainer, attributes, { x: event.x, y: event.y }, view);
            } else {
                view.closePopup();
                closeLineFeaturePopup();
                openPointFeaturePopup(mapContainer, attributes, { x: event.x, y: event.y }, view);
            }
        } catch (error) {
            console.warn("地图详情接口不可用，使用命中要素属性展示", error);
            if (layerId >= 3) {
                view.closePopup();
                closePointFeaturePopup();
                openLineFeaturePopup(mapContainer, fallbackAttributes, { x: event.x, y: event.y }, view);
            } else {
                view.closePopup();
                closeLineFeaturePopup();
                openPointFeaturePopup(mapContainer, fallbackAttributes, { x: event.x, y: event.y }, view);
            }
        }
    });
}

function initUserMap() {
    if (typeof window.require !== "function") {
        console.error("GeoScene SDK 未加载，window.require 不存在");
        return;
    }

    window.require([
        "geoscene/config",
        "geoscene/Map",
        "geoscene/views/MapView",
        "geoscene/layers/WebTileLayer",
        "geoscene/layers/MapImageLayer",
        "geoscene/layers/GraphicsLayer",
        "geoscene/Graphic",
        "geoscene/geometry/support/jsonUtils"
    ], (geosceneConfig, GeoSceneMap, MapView, WebTileLayer, MapImageLayer, GraphicsLayer, Graphic, geometryJsonUtils) => {
        const mapContainer = document.getElementById("mapContainer");

        if (!mapContainer) {
            console.error("地图容器 #mapContainer 不存在");
            return;
        }

        geosceneConfig.portalUrl = GEOSCENE_PORTAL_URL;

        const imageryBasemapLayer = new WebTileLayer({
            urlTemplate: IMAGERY_BASEMAP_URL_TEMPLATE,
            title: "World Imagery",
            minScale: BASEMAP_SWITCH_SCALE,
            opacity: 0.72,
            copyright: "Sources: Esri, Maxar, Earthstar Geographics, and the GIS User Community"
        });

        const streetBasemapLayer = new WebTileLayer({
            urlTemplate: STREET_BASEMAP_URL_TEMPLATE,
            title: "World Street Map",
            maxScale: BASEMAP_SWITCH_SCALE,
            copyright: "Sources: Esri, HERE, Garmin, USGS, OpenStreetMap contributors, and the GIS User Community"
        });

        const residenceMapLayer = new MapImageLayer({
            url: GEOSCENE_MAP_SERVICE_URL,
            title: "大连市适老环境要素"
        });

        const agentBufferLayer = new GraphicsLayer({
            title: "道路缓冲区",
            listMode: "hide"
        });

        const agentRoadLayer = new GraphicsLayer({
            title: "贡献道路",
            listMode: "hide"
        });

        const agentHousingLayer = new GraphicsLayer({
            title: "候选住宅",
            listMode: "hide"
        });

        const map = new GeoSceneMap({
            layers: [
                imageryBasemapLayer,
                streetBasemapLayer,
                residenceMapLayer,
                agentBufferLayer,
                agentRoadLayer,
                agentHousingLayer
            ]
        });

        const view = new MapView({
            container: mapContainer,
            map,
            zoom: 11,
            center: [121.62, 38.91],
            constraints: {
                maxZoom: MAX_MAP_ZOOM,
                snapToZoom: false
            }
        });

        view.popup.autoOpenEnabled = false;
        view.ui.move("zoom", "bottom-right");

        window.userMapDebug = {
            map,
            mapContainer,
            basemapLayer: streetBasemapLayer,
            basemapLayers: {
                imagery: imageryBasemapLayer,
                street: streetBasemapLayer
            },
            residenceMapLayer,
            agentResultLayer: agentHousingLayer,
            agentResultLayers: {
                buffer: agentBufferLayer,
                road: agentRoadLayer,
                housing: agentHousingLayer
            },
            view
        };

        const agentSymbol = (geometryType) => geometryType === "polyline"
            ? {
                type: "simple-line",
                color: [56, 111, 80, 0.95],
                width: 4
            }
            : {
                type: "simple-marker",
                style: "circle",
                color: [57, 113, 81, 0.95],
                size: 10,
                outline: { color: [255, 253, 249, 1], width: 2 }
            };

        const bufferSymbol = {
            type: "simple-fill",
            color: [54, 126, 166, 0.2],
            outline: {
                color: [44, 104, 139, 0.82],
                width: 1.25
            }
        };

        const resultLayerFor = (resultSet) => (
            resultSet?.role === "CONTRIBUTING_ROADS" || resultSet?.geometryType === "polyline"
                ? agentRoadLayer
                : agentHousingLayer
        );

        const orderedResultLayers = (layerOrder) => {
            const roleLayers = {
                ROAD_BUFFER: [agentBufferLayer],
                CONTRIBUTING_ROADS: [agentRoadLayer],
                HOUSING_CANDIDATES: [agentHousingLayer],
                PRIMARY_RESULTS: [agentRoadLayer, agentHousingLayer]
            };
            const ordered = [];
            (Array.isArray(layerOrder) ? layerOrder : []).forEach((role) => {
                (roleLayers[role] || []).forEach((layer) => {
                    if (!ordered.includes(layer)) {
                        ordered.push(layer);
                    }
                });
            });
            [agentBufferLayer, agentRoadLayer, agentHousingLayer].forEach((layer) => {
                if (!ordered.includes(layer)) {
                    ordered.push(layer);
                }
            });
            ordered.forEach((layer, offset) => map.reorder(layer, 3 + offset));
        };

        const requireWkid4326 = (spatialReference, context) => {
            if (Number(spatialReference?.wkid) !== 4326) {
                throw new Error(`${context} 缺少合法的 WKID 4326 空间参考`);
            }
        };

        const validateAgentMapSpatialReferences = (payload) => {
            (payload?.resultSets || []).forEach((resultSet) => {
                requireWkid4326(resultSet?.spatialReference, `图层 ${resultSet?.layerId ?? "unknown"}`);
                (resultSet?.features || []).forEach((feature) => {
                    requireWkid4326(feature?.geometry?.spatialReference, `要素 ${feature?.id || "unknown"}`);
                });
            });
            (payload?.overlays || []).forEach((overlay) => {
                requireWkid4326(overlay?.spatialReference, `覆盖物 ${overlay?.overlayId || "unknown"}`);
                requireWkid4326(
                    overlay?.geometry?.spatialReference,
                    `覆盖物几何 ${overlay?.overlayId || "unknown"}`
                );
            });
        };

        const applyAgentMapResult = async (payload) => {
            validateAgentMapSpatialReferences(payload);
            const mode = payload?.mode === "append" ? "append" : "replace";
            if (mode === "replace") {
                agentBufferLayer.removeAll();
                agentRoadLayer.removeAll();
                agentHousingLayer.removeAll();
                closeLineFeaturePopup();
                closePointFeaturePopup();
                view.closePopup();
            }
            orderedResultLayers(payload?.display?.layerOrder);
            const existingIds = new Set(
                [...agentRoadLayer.graphics.toArray(), ...agentHousingLayer.graphics.toArray()]
                    .map((graphic) => graphic.attributes?.__agentFeatureId)
            );
            const graphicsByLayer = new Map([
                [agentRoadLayer, []],
                [agentHousingLayer, []]
            ]);
            (payload?.resultSets || []).forEach((resultSet) => {
                const layerId = Number(resultSet.layerId);
                if (!EXPECTED_MAP_SUBLAYERS.has(layerId)) {
                    return;
                }
                const targetLayer = resultLayerFor(resultSet);
                (resultSet.features || []).forEach((feature) => {
                    if (!feature?.geometry || !feature.id || existingIds.has(feature.id)) {
                        return;
                    }
                    try {
                        const geometry = geometryJsonUtils.fromJSON(feature.geometry);
                        if (!geometry) {
                            return;
                        }
                        graphicsByLayer.get(targetLayer).push(new Graphic({
                            geometry,
                            symbol: agentSymbol(resultSet.geometryType),
                            attributes: {
                                ...(feature.attributes || {}),
                                __agentLayerId: layerId,
                                __agentFeatureId: feature.id
                            }
                        }));
                        existingIds.add(feature.id);
                    } catch (error) {
                        console.warn("忽略无法解析的 Agent 地图要素", feature.id, error);
                    }
                });
            });
            graphicsByLayer.forEach((graphics, layer) => {
                if (graphics.length > 0) {
                    layer.addMany(graphics);
                }
            });

            const existingOverlayIds = new Set(
                agentBufferLayer.graphics.toArray().map((graphic) => graphic.attributes?.__agentOverlayId)
            );
            const overlayGraphics = [];
            (payload?.overlays || []).forEach((overlay) => {
                if (overlay?.kind !== "ROAD_BUFFER" || overlay?.geometryType !== "polygon"
                    || !overlay.overlayId || existingOverlayIds.has(overlay.overlayId)) {
                    return;
                }
                try {
                    const geometry = geometryJsonUtils.fromJSON(overlay.geometry);
                    if (!geometry) {
                        return;
                    }
                    const bufferMeters = Number(overlay.attributes?.bufferMeters);
                    const sourceRoadCount = Number(overlay.attributes?.sourceRoadCount);
                    overlayGraphics.push(new Graphic({
                        geometry,
                        symbol: bufferSymbol,
                        attributes: {
                            bufferMeters: Number.isFinite(bufferMeters) ? bufferMeters : null,
                            sourceRoadCount: Number.isFinite(sourceRoadCount) ? sourceRoadCount : 0,
                            __agentOverlayId: overlay.overlayId
                        },
                        popupTemplate: {
                            title: "道路缓冲区",
                            content: [{
                                type: "fields",
                                fieldInfos: [
                                    { fieldName: "bufferMeters", label: "缓冲距离", format: { digitSeparator: true, places: 0 } },
                                    { fieldName: "sourceRoadCount", label: "来源道路数", format: { digitSeparator: true, places: 0 } }
                                ]
                            }]
                        }
                    }));
                    existingOverlayIds.add(overlay.overlayId);
                } catch (error) {
                    console.warn("忽略无法解析的 Agent 缓冲区", overlay.overlayId, error);
                }
            });
            if (overlayGraphics.length > 0) {
                agentBufferLayer.addMany(overlayGraphics);
            }

            const allGraphics = [
                ...agentBufferLayer.graphics.toArray(),
                ...agentRoadLayer.graphics.toArray(),
                ...agentHousingLayer.graphics.toArray()
            ];
            if (payload?.display?.fitBounds && allGraphics.length > 0) {
                try {
                    await view.goTo(allGraphics, {
                        animate: true,
                        duration: 450,
                        padding: Number(payload.display.paddingPx) || 0
                    });
                    const maxZoom = Number(payload.display.maxZoom);
                    if (Number.isFinite(maxZoom) && view.zoom > maxZoom) {
                        await view.goTo({ zoom: maxZoom }, { animate: false });
                    }
                } catch (error) {
                    if (error?.name !== "AbortError") {
                        console.warn("Agent 地图结果定位失败", error);
                    }
                }
            }
            const total = (payload?.resultSets || []).reduce(
                (sum, resultSet) => sum + (Number(resultSet.total) || 0),
                0
            );
            window.dispatchEvent(new CustomEvent("agent-map-result-applied", {
                detail: { total, rendered: allGraphics.length, mode }
            }));
        };

        const agentMapResultHandler = (event) => {
            applyAgentMapResult(event.detail).catch((error) => {
                console.error("Agent 地图结果应用失败", error);
            });
        };
        window.addEventListener("agent-map-result", agentMapResultHandler);

        (async () => {
            try {
                await view.when();
                mapContainer.classList.add("is-loaded");
                removeMapOverlays();

                await Promise.all([
                    imageryBasemapLayer.when(),
                    streetBasemapLayer.when(),
                    residenceMapLayer.when()
                ]);
                validateMapSublayers(residenceMapLayer);

                residenceMapLayer.allSublayers.forEach((sublayer) => {
                    sublayer.visible = true;
                    sublayer.popupEnabled = true;
                    sublayer.popupTemplate = {
                        title: `${sublayer.title} 要素详情`,
                        outFields: ["*"]
                    };
                });

                installMapClickHandler(view, mapContainer, {
                    buffer: agentBufferLayer,
                    road: agentRoadLayer,
                    housing: agentHousingLayer
                });

                await Promise.all([
                    view.whenLayerView(imageryBasemapLayer),
                    view.whenLayerView(streetBasemapLayer),
                    view.whenLayerView(residenceMapLayer)
                ]);

                if (residenceMapLayer.fullExtent) {
                    try {
                        await view.goTo(residenceMapLayer.fullExtent.expand(1.08), {
                            animate: false
                        });
                    } catch (error) {
                        console.warn("地图服务范围定位失败，保留大连默认视图:", error);
                    }
                }

                updateMapStatus("完整地图与要素加载完成", "ready");
                console.info("GeoScene 底图与业务要素初始化完成", {
                    imageryBasemapLayer,
                    streetBasemapLayer,
                    residenceMapLayer,
                    view
                });
            } catch (error) {
                updateMapStatus("完整地图加载失败，请检查地图服务", "error");
                console.error("GeoScene 底图与业务要素初始化失败:", error);
            }
        })();

        window.addEventListener("pagehide", () => {
            window.removeEventListener("agent-map-result", agentMapResultHandler);
            view.destroy();
        }, { once: true });
    });
}

function initUserApp() {
    if (!window.Vue) {
        console.error("Vue 未加载，用户页面交互无法初始化");
        return;
    }

    const { createApp } = window.Vue;

    createApp({
        data() {
            return {
                searchKeyword: "",
                assistantCollapsed: true,
                chatInput: "",
                chatMessages: [],
                currentUser: null,
                conversationId: "",
                activeMessageId: null,
                activeRunId: null,
                activeAbortController: null,
                assistantRunning: false,
                hasAgentMapResults: false,
                lastSequenceByRun: {},
                mapResultAppliedHandler: null
            };
        },
        mounted() {
            const savedUser = localStorage.getItem("user");
            if (savedUser) {
                try {
                    this.currentUser = JSON.parse(savedUser);
                } catch (error) {
                    console.error("解析登录用户失败:", error);
                }
            }
            try {
                this.conversationId = sessionStorage.getItem("assistantConversationId") || createClientUuid();
                sessionStorage.setItem("assistantConversationId", this.conversationId);
            } catch (error) {
                this.conversationId = createClientUuid();
            }
            this.mapResultAppliedHandler = (event) => {
                this.hasAgentMapResults = Number(event.detail?.rendered) > 0;
            };
            window.addEventListener("agent-map-result-applied", this.mapResultAppliedHandler);
        },
        beforeUnmount() {
            if (this.mapResultAppliedHandler) {
                window.removeEventListener("agent-map-result-applied", this.mapResultAppliedHandler);
            }
            this.activeAbortController?.abort();
        },
        methods: {
            formatTime() {
                return new Date().toLocaleTimeString("zh-CN", {
                    hour: "2-digit",
                    minute: "2-digit"
                });
            },

            toggleAssistant() {
                this.assistantCollapsed = !this.assistantCollapsed;
            },

            useSuggestion(value) {
                this.chatInput = value;
                this.assistantCollapsed = false;
            },

            goToProfile() {
                if (!this.currentUser) {
                    window.location.href = "/index.html";
                    return;
                }

                this.pushAssistantReply("个人中心入口已保留，后续可接入用户资料、收藏和退出登录页面。");
            },

            async handleSearch() {
                const keyword = this.searchKeyword.trim();

                if (!keyword) {
                    this.pushAssistantReply("请输入筛选需求，例如“200 万以内、靠近医院、电梯房”。");
                    return;
                }

                await this.submitRequirement(keyword, "search");
            },

            async sendMessage() {
                if (this.assistantRunning) {
                    this.cancelActiveRun();
                    return;
                }
                const message = this.chatInput.trim();

                if (!message) {
                    return;
                }

                this.chatInput = "";
                await this.submitRequirement(message, "chat");
            },

            async submitRequirement(message, source) {
                if (this.assistantRunning) {
                    this.cancelActiveRun(false);
                }
                this.assistantCollapsed = false;
                const messageId = createClientUuid();
                this.chatMessages.push({
                    id: `user-${messageId}`,
                    role: "user",
                    content: message,
                    time: this.formatTime()
                });
                this.chatMessages.push({
                    id: `assistant-${messageId}`,
                    messageId,
                    role: "assistant",
                    content: "",
                    status: "running",
                    statusText: "正在理解需求",
                    citations: [],
                    warnings: [],
                    time: this.formatTime()
                });

                const controller = new AbortController();
                this.activeAbortController = controller;
                this.activeMessageId = messageId;
                this.activeRunId = null;
                this.assistantRunning = true;
                this.scrollChatToEnd();

                window.dispatchEvent(new CustomEvent("agent-map-filter-request", {
                    detail: {
                        source,
                        text: message
                    }
                }));

                let terminalReceived = false;
                try {
                    const response = await fetch("/api/assistant/runs/stream", {
                        method: "POST",
                        headers: {
                            "Content-Type": "application/json",
                            "Accept": "text/event-stream"
                        },
                        body: JSON.stringify({
                            conversationId: this.conversationId,
                            messageId,
                            query: message,
                            context: this.agentRequestContext()
                        }),
                        signal: controller.signal
                    });
                    const contentType = response.headers.get("content-type") || "";
                    if (!contentType.toLowerCase().includes("text/event-stream")) {
                        let detail = "智能助手接口响应异常";
                        try {
                            const payload = await response.json();
                            detail = payload?.error?.message || detail;
                        } catch (error) {
                            // Keep the stable public error when the upstream body is not JSON.
                        }
                        throw new Error(detail);
                    }
                    await consumeSseStream(response.body, async (event) => {
                        terminalReceived = this.handleAgentEvent(event, messageId) || terminalReceived;
                    });
                    if (!terminalReceived && !controller.signal.aborted) {
                        throw new Error("Agent 事件流在完成前中断");
                    }
                } catch (error) {
                    const assistantMessage = this.assistantMessage(messageId);
                    if (error?.name === "AbortError") {
                        if (assistantMessage && assistantMessage.status === "running") {
                            assistantMessage.status = "cancelled";
                            assistantMessage.statusText = "已停止";
                        }
                    } else {
                        console.error("Agent 对话失败:", error);
                        if (assistantMessage) {
                            assistantMessage.status = "error";
                            assistantMessage.statusText = "连接失败";
                            if (!assistantMessage.content) {
                                assistantMessage.content = error.message || "智能助手暂时无法连接，请稍后重试。";
                            }
                        }
                    }
                } finally {
                    if (this.activeMessageId === messageId) {
                        this.assistantRunning = false;
                        this.activeAbortController = null;
                        this.activeMessageId = null;
                        this.activeRunId = null;
                    }
                    this.scrollChatToEnd();
                }
            },

            agentRequestContext() {
                const view = window.userMapDebug?.view;
                const visibleLayerIds = window.userMapDebug?.residenceMapLayer?.allSublayers
                    ?.filter((layer) => layer.visible)
                    .map((layer) => layer.id) || [...EXPECTED_MAP_SUBLAYERS.keys()];
                return {
                    locale: "zh-CN",
                    map: {
                        visibleLayerIds,
                        zoom: Number.isFinite(Number(view?.zoom)) ? Number(view.zoom.toFixed(2)) : null,
                        extent: null
                    },
                    businessObjectIds: []
                };
            },

            handleAgentEvent(event, messageId) {
                let envelope;
                try {
                    envelope = JSON.parse(event.data);
                } catch (error) {
                    throw new Error(`Agent 事件不是合法 JSON：${event.eventName}`);
                }
                if (envelope.schemaVersion !== "1.1") {
                    throw new Error(`不支持的 Agent 事件版本：${envelope.schemaVersion || "unknown"}`);
                }
                if (envelope.messageId && envelope.messageId !== messageId) {
                    return false;
                }
                const runKey = envelope.runId || `message:${messageId}`;
                const sequence = Number(envelope.sequence);
                if (!Number.isInteger(sequence) || sequence < 1) {
                    throw new Error(`Agent 事件缺少合法 sequence：${event.eventName}`);
                }
                const lastSequence = Number(this.lastSequenceByRun[runKey]) || 0;
                if (sequence <= lastSequence) {
                    return false;
                }
                this.lastSequenceByRun[runKey] = sequence;
                const payload = envelope.payload || {};
                const assistantMessage = this.assistantMessage(messageId);
                if (!assistantMessage) {
                    return false;
                }
                if (this.activeMessageId !== messageId
                        && (assistantMessage.status === "running" || assistantMessage.status === "cancelled")) {
                    return false;
                }

                switch (event.eventName) {
                    case "run.started":
                        this.activeRunId = envelope.runId || null;
                        assistantMessage.statusText = "正在分析需求";
                        break;
                    case "route.selected":
                        assistantMessage.intent = payload.intent;
                        assistantMessage.statusText = this.intentStatus(payload.intent);
                        break;
                    case "retrieval.completed":
                        assistantMessage.statusText = `已检索 ${Number(payload.documents) || 0} 条知识来源`;
                        break;
                    case "tool.started":
                        assistantMessage.statusText = "正在查询地图数据";
                        break;
                    case "tool.completed":
                        assistantMessage.statusText = "地图数据查询完成";
                        break;
                    case "map.result":
                        window.dispatchEvent(new CustomEvent("agent-map-result", { detail: payload }));
                        assistantMessage.mapSummary = payload.querySummary || "";
                        assistantMessage.statusText = "正在整理结果";
                        break;
                    case "citation.added":
                        this.mergeCitation(assistantMessage, payload);
                        break;
                    case "answer.delta":
                        assistantMessage.content += payload.content || "";
                        assistantMessage.statusText = "正在回答";
                        this.scrollChatToEnd();
                        break;
                    case "run.completed":
                        if (typeof payload.answer === "string" && payload.answer.trim()) {
                            assistantMessage.content = payload.answer;
                        }
                        assistantMessage.citations = Array.isArray(payload.citations)
                            ? payload.citations
                            : assistantMessage.citations;
                        assistantMessage.warnings = Array.isArray(payload.warnings) ? payload.warnings : [];
                        assistantMessage.status = "complete";
                        assistantMessage.statusText = "已完成";
                        return true;
                    case "run.cancelled":
                        assistantMessage.status = "cancelled";
                        assistantMessage.statusText = "已停止";
                        if (!assistantMessage.content) {
                            assistantMessage.content = payload.reason || "已停止本次请求。";
                        }
                        return true;
                    case "preflight.failed":
                    case "run.failed":
                        assistantMessage.status = "error";
                        assistantMessage.statusText = "未完成";
                        if (!assistantMessage.content) {
                            assistantMessage.content = payload.error?.message || "智能助手暂时无法处理该请求。";
                        }
                        return true;
                    default:
                        break;
                }
                return false;
            },

            intentStatus(intent) {
                return {
                    MAP_QUERY: "正在规划地图筛选",
                    RAG_QA: "正在检索知识库",
                    HYBRID: "正在查询地图与知识库",
                    CLARIFY: "正在确认筛选条件"
                }[intent] || "正在处理";
            },

            mergeCitation(message, citation) {
                if (!citation?.citationId) {
                    return;
                }
                const index = message.citations.findIndex((item) => item.citationId === citation.citationId);
                if (index >= 0) {
                    message.citations.splice(index, 1, citation);
                } else {
                    message.citations.push(citation);
                }
            },

            assistantMessage(messageId) {
                return this.chatMessages.find((message) => (
                    message.role === "assistant" && message.messageId === messageId
                ));
            },

            cancelActiveRun(markCancelled = true) {
                const messageId = this.activeMessageId;
                const runId = this.activeRunId;
                this.activeAbortController?.abort();
                if (markCancelled && messageId) {
                    const message = this.assistantMessage(messageId);
                    if (message && message.status === "running") {
                        message.status = "cancelled";
                        message.statusText = "已停止";
                    }
                }
                this.assistantRunning = false;
                this.activeAbortController = null;
                this.activeMessageId = null;
                this.activeRunId = null;
                if (runId) {
                    fetch(`/api/assistant/runs/${encodeURIComponent(runId)}/cancel`, {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ reason: "USER_CANCELLED" }),
                        keepalive: true
                    }).catch((error) => console.warn("Agent 运行取消通知失败", error));
                }
            },

            clearAgentResults() {
                window.dispatchEvent(new CustomEvent("agent-map-result", {
                    detail: { mode: "replace", resultSets: [], display: { fitBounds: false } }
                }));
                this.hasAgentMapResults = false;
            },

            citationAccessUrl(citation) {
                const value = citation?.source?.url || citation?.accessUrl || "";
                return typeof value === "string" && value.startsWith("/api/assistant/citations/")
                    ? value
                    : "";
            },

            citationTitle(citation) {
                return citation?.title || citation?.source?.label || "知识库文档";
            },

            citationPages(citation) {
                const start = Number(citation?.pageStart);
                const end = Number(citation?.pageEnd);
                if (!Number.isFinite(start)) {
                    return Array.isArray(citation?.sectionPath)
                        ? citation.sectionPath.join(" · ")
                        : citation?.sectionPath || "";
                }
                return Number.isFinite(end) && end !== start ? `第 ${start}-${end} 页` : `第 ${start} 页`;
            },

            scrollChatToEnd() {
                this.$nextTick(() => {
                    const body = document.querySelector(".assistant-body");
                    if (body) {
                        body.scrollTop = body.scrollHeight;
                    }
                });
            },

            pushAssistantReply(content) {
                this.chatMessages.push({
                    id: `assistant-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
                    role: "assistant",
                    content,
                    status: "complete",
                    statusText: "已完成",
                    citations: [],
                    warnings: [],
                    time: this.formatTime()
                });
                this.assistantCollapsed = false;
                this.scrollChatToEnd();
            }
        }
    }).mount("#user-app");
}

runWhenDomReady(() => {
    initUserApp();
    initUserMap();
});
