const GEOSCENE_PORTAL_URL = "https://edutrial.geoscene.cn/geoscene";
const STREET_BASEMAP_URL_TEMPLATE = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{level}/{row}/{col}";
const IMAGERY_BASEMAP_URL_TEMPLATE = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{level}/{row}/{col}";
const BASEMAP_SWITCH_SCALE = 50000;
const MAX_MAP_ZOOM = 19;
const GEOSCENE_MAP_SERVICE_URL = "/api/map/geoscene";
const POI_MAP_SERVICE_URL = "/api/map/poi";
const POI_LAYER_ID = 0;
const POI_TARGET_DISTRICTS = Object.freeze(["中山区", "西岗区", "沙河口区"]);
const POI_GROUP_DISPLAY_LIMIT = 220;
const POI_TOTAL_DISPLAY_LIMIT = 3000;
const POI_OBJECT_ID_BATCH_SIZE = 300;
const POI_ANIMATION_LIMIT = 800;
const POI_BASE_MARKER_SIZE = 11;
const POI_WEIGHT_MARKER_RANGE = 4;
const EXPECTED_MAP_SUBLAYERS = new Map([
    [0, "shahekou_1"],
    [1, "xigang_1"],
    [2, "zhongshan_1"],
    [3, "ZhongShan"],
    [4, "XiGang"],
    [5, "ShaHeKou"]
]);

const POINT_RADAR_METRICS = Object.freeze([
    { field: "餐饮密度", label: "餐饮", statisticName: "foodMax" },
    { field: "风景密度", label: "风景", statisticName: "sceneryMax" },
    { field: "科教密度", label: "科教", statisticName: "educationMax" },
    { field: "购物密度", label: "购物", statisticName: "shoppingMax" },
    { field: "金融密度", label: "金融", statisticName: "financeMax" },
    { field: "归一化总分", label: "便利度", statisticName: "convenienceMax" }
]);
const POINT_LAYER_IDS = Object.freeze([0, 1, 2]);
const HOUSING_CLUSTER_MAX_SCALE = 6000;
const HOUSING_LABEL_MIN_SCALE = HOUSING_CLUSTER_MAX_SCALE;
const HOUSING_POINT_LAYER_ID_PREFIX = "residence-point-";
let pointRadarMaximumsPromise = null;
let pointPopupRenderToken = 0;

const POI_CATEGORIES = Object.freeze([
    { code: "RESTAURANT", label: "餐饮", color: "#e06f56" },
    { code: "SCENIC", label: "景点", color: "#5f9f83" },
    { code: "PUBLIC_SERVICE", label: "公共服务", color: "#5b8fc5" },
    { code: "SHOPPING", label: "购物", color: "#c99645" },
    { code: "EDUCATION", label: "教育", color: "#8067b4" },
    { code: "FINANCIAL", label: "金融", color: "#388e82" },
    { code: "MEDICAL", label: "医疗", color: "#cf5c75" },
    { code: "SPORTS", label: "体育", color: "#4f9dc0" },
    { code: "GOVERNMENT", label: "政府机构", color: "#7d8792" }
]);
const POI_OUT_FIELDS = Object.freeze([
    "OBJECTID",
    "poi_id",
    "poi_name",
    "category_code",
    "category_name",
    "subcategory",
    "district_name",
    "phone",
    "source_layer",
    "address",
    "wgs84_x",
    "wgs84_y",
    "weight"
]);
const POI_CATEGORY_BY_CODE = new Map(POI_CATEGORIES.map((category) => [category.code, category]));
let poiPopupRenderToken = 0;
const SLOPE_MAP_SERVICE_URL = "/api/map/slope";
const SLOPE_DEM_LAYER_ID = 3;
const SLOPE_ROAD_LAYER_CONFIGS = Object.freeze([
    Object.freeze({ serviceLayerId: 0, mapLayerId: 4, title: "XiGang" }),
    Object.freeze({ serviceLayerId: 1, mapLayerId: 5, title: "ShaHeKou" }),
    Object.freeze({ serviceLayerId: 2, mapLayerId: 3, title: "ZhongShan" })
]);
const SLOPE_WALKABILITY_LEVELS = Object.freeze([
    Object.freeze({ key: "level-1", label: "0–20", color: "#d9b36c" }),
    Object.freeze({ key: "level-2", label: "20–40", color: "#d7864f" }),
    Object.freeze({ key: "level-3", label: "40–60", color: "#bd5d49" }),
    Object.freeze({ key: "level-4", label: "60–80", color: "#934348" }),
    Object.freeze({ key: "level-5", label: "80–100", color: "#653342" })
]);
const SLOPE_WALKABILITY_EXPRESSION = `
var rawValue = $feature["WS归一化"];
if (IsEmpty(rawValue)) {
    return "missing";
}
var value = Number(rawValue);
return When(
    value < 0, "missing",
    value < 20, "level-1",
    value < 40, "level-2",
    value < 60, "level-3",
    value < 80, "level-4",
    value <= 100, "level-5",
    "missing"
);
`;

function createSlopeRoadSymbol(color, style = "solid", width = 3.5) {
    return {
        type: "simple-line",
        style,
        color,
        width
    };
}

function createSlopeRoadRenderer() {
    return {
        type: "unique-value",
        valueExpression: SLOPE_WALKABILITY_EXPRESSION,
        valueExpressionTitle: "步行指数",
        uniqueValueInfos: SLOPE_WALKABILITY_LEVELS.map((level) => ({
            value: level.key,
            label: level.label,
            symbol: createSlopeRoadSymbol(level.color)
        })),
        defaultLabel: "暂无",
        defaultSymbol: createSlopeRoadSymbol("#a99b91", "short-dot", 2.5)
    };
}
const STATIC_HOUSING_TYPE_TEMPLATES = Object.freeze({
    "中山区": Object.freeze([
        Object.freeze({ name: "悦享两居", rooms: "2室1厅1卫", area: 72, orientation: "南向", floor: "中层 / 18层", elevator: "有电梯", feature: "客餐一体 · 南向阳台", image: "images/housing-type-2br.png" }),
        Object.freeze({ name: "舒阔三房", rooms: "3室2厅2卫", area: 89, orientation: "南北", floor: "中高层 / 24层", elevator: "有电梯", feature: "动静分区 · 双面采光", image: "images/housing-type-3br.png" }),
        Object.freeze({ name: "颐居三房·书房", rooms: "3室2厅2卫+书房", area: 112, orientation: "南北", floor: "低层 / 16层", elevator: "有电梯", feature: "双卫设计 · 独立书房", image: "images/housing-type-3br-study.png" })
    ]),
    "西岗区": Object.freeze([
        Object.freeze({ name: "明朗两居", rooms: "2室1厅1卫", area: 75, orientation: "南向", floor: "中层 / 20层", elevator: "有电梯", feature: "方正格局 · 卧室朝南", image: "images/housing-type-2br.png" }),
        Object.freeze({ name: "通透三房", rooms: "3室2厅2卫", area: 92, orientation: "南北", floor: "高层 / 26层", elevator: "有电梯", feature: "南北通透 · 独立餐厅", image: "images/housing-type-3br.png" }),
        Object.freeze({ name: "宽境三房·书房", rooms: "3室2厅2卫+书房", area: 118, orientation: "东南", floor: "中层 / 22层", elevator: "有电梯", feature: "三面采光 · 独立书房", image: "images/housing-type-3br-study.png" })
    ]),
    "沙河口区": Object.freeze([
        Object.freeze({ name: "轻享两居", rooms: "2室1厅1卫", area: 78, orientation: "南向", floor: "中层 / 18层", elevator: "有电梯", feature: "短动线 · 阳台连客厅", image: "images/housing-type-2br.png" }),
        Object.freeze({ name: "雅致三房", rooms: "3室2厅2卫", area: 96, orientation: "南北", floor: "中高层 / 25层", elevator: "有电梯", feature: "明厨明卫 · 双厅布局", image: "images/housing-type-3br.png" }),
        Object.freeze({ name: "和悦三房·书房", rooms: "3室2厅2卫+书房", area: 122, orientation: "南北", floor: "低层 / 15层", elevator: "有电梯", feature: "大面宽客厅 · 独立书房", image: "images/housing-type-3br-study.png" })
    ])
});
const DEFAULT_STATIC_HOUSING_TYPES = STATIC_HOUSING_TYPE_TEMPLATES["中山区"];
const RENTAL_HOUSING_PHOTOS = Object.freeze(
    Array.from({ length: 15 }, (_, index) => `photo/${index + 1}.png`)
);
const rentalHousingPhotoCache = new Map();
// 2026-07 residential listing averages from China Real Estate Price (CRE Price).
const HOUSING_MARKET_REFERENCE = Object.freeze({
    asOf: "2026-07",
    saleSourceUrl: "https://m.creprice.cn/city/dl.html",
    rentSourceUrl: "https://m.creprice.cn/city/dl.html?type=lease"
});
const DISTRICT_RENT_UNIT_AVERAGES = Object.freeze({
    "中山区": 39.17,
    "西岗区": 36.35,
    "沙河口区": 35.14
});
const DISTRICT_SALE_UNIT_AVERAGES = Object.freeze({
    "中山区": 18034,
    "西岗区": 13587,
    "沙河口区": 14200
});
const DEFAULT_RENT_UNIT_AVERAGE = 29.71;
const HOUSING_UNIT_PRICE_REFERENCE_RANGE = Object.freeze({ minimumRatio: 0.5, maximumRatio: 1.8 });
const rentalHousingRentCache = new Map();
const LINE_RADAR_METRICS = Object.freeze([
    { field: "绿视率原始值", label: "绿视率", maximum: 1 },
    { field: "道路噪声原始值", label: "道路噪声", maximum: 100 },
    { field: "WS归一化", label: "步行指数", maximum: 100 }
]);
const LINE_POPUP_REQUIRED_FIELDS = Object.freeze(["GVI", "NOI", "WS归一化", "绿视率原始值", "道路噪声原始值"]);
let linePopupRenderToken = 0;

const DEFAULT_HOUSING_FILTERS = Object.freeze({
    districts: ["中山区", "西岗区", "沙河口区"],
    priceMin: null,
    priceMax: null,
    convenience: "PREFER_HIGH",
    roadWalkability: "PREFER_HIGH",
    bufferMeters: 100,
    limit: 20
});
const HOUSING_LAYER_ID_BY_DISTRICT = Object.freeze({
    "沙河口区": 0,
    "西岗区": 1,
    "中山区": 2
});

function createDefaultHousingFilters() {
    return {
        ...DEFAULT_HOUSING_FILTERS,
        districts: [...DEFAULT_HOUSING_FILTERS.districts]
    };
}

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
        if (String(candidate.id || "").startsWith(HOUSING_POINT_LAYER_ID_PREFIX)) {
            return true;
        }
        const id = Number(candidate.id);
        return EXPECTED_MAP_SUBLAYERS.has(id) || [...EXPECTED_MAP_SUBLAYERS.values()].includes(candidate.title);
    });
    if (!layer) {
        return null;
    }
    const layerIdFromPrefix = String(layer.id || "").startsWith(HOUSING_POINT_LAYER_ID_PREFIX)
        ? Number(String(layer.id).slice(HOUSING_POINT_LAYER_ID_PREFIX.length))
        : null;
    const numericId = Number(layer.id);
    const layerId = POINT_LAYER_IDS.includes(layerIdFromPrefix)
        ? layerIdFromPrefix
        : EXPECTED_MAP_SUBLAYERS.has(numericId)
            ? numericId
            : [...EXPECTED_MAP_SUBLAYERS.entries()].find(([, title]) => title === layer.title)?.[0];
    return layerId == null ? null : { layerId, layerName: EXPECTED_MAP_SUBLAYERS.get(layerId) };
}

function createResidencePointLayer(FeatureLayer, layerId) {
    return new FeatureLayer({
        id: `${HOUSING_POINT_LAYER_ID_PREFIX}${layerId}`,
        url: `${GEOSCENE_MAP_SERVICE_URL}/${layerId}`,
        title: EXPECTED_MAP_SUBLAYERS.get(layerId),
        outFields: ["*"],
        popupEnabled: false,
        labelsVisible: true,
        listMode: "hide",
        effect: "drop-shadow(0px, 2px, 3px, rgba(43, 91, 70, 0.28))",
        renderer: {
            type: "simple",
            symbol: {
                type: "simple-marker",
                style: "circle",
                size: 9,
                color: "#4f8f72",
                outline: { color: "#fffdf9", width: 1.2 }
            }
        },
        featureReduction: {
            type: "cluster",
            clusterRadius: "96px",
            maxScale: HOUSING_CLUSTER_MAX_SCALE,
            labelingInfo: [{
                deconflictionStrategy: "none",
                labelPlacement: "center-center",
                labelExpressionInfo: { expression: "Text($feature.cluster_count, '#,###')" },
                symbol: {
                    type: "text",
                    color: "#ffffff",
                    haloColor: [101, 55, 38, 0.42],
                    haloSize: 1,
                    font: { family: "Microsoft YaHei", size: 11, weight: "bold" }
                }
            }],
            renderer: {
                type: "simple",
                symbol: {
                    type: "simple-marker",
                    style: "circle",
                    color: "#4f8f72",
                    outline: { color: "#fff8f1", width: 2 }
                },
                visualVariables: [{
                    type: "size",
                    field: "cluster_count",
                    stops: [
                        { value: 2, size: 24 },
                        { value: 50, size: 38 },
                        { value: 500, size: 54 }
                    ]
                }]
            }
        },
        labelingInfo: [{
            minScale: HOUSING_LABEL_MIN_SCALE,
            deconflictionStrategy: "none",
            labelPlacement: "above-center",
            labelExpressionInfo: { expression: "$feature.name" },
            symbol: {
                type: "text",
                color: "#234f3e",
                haloColor: "#ffffff",
                haloSize: 1.5,
                yoffset: 8,
                font: { family: "Microsoft YaHei", size: 10, weight: "bold" }
            }
        }]
    });
}

function formatPopupValue(field, value) {
    if (value == null || (typeof value === "string" && value.trim() === "")) {
        return "暂无";
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
    if (["GVI", "NOI", "WS归一化", "绿视率原始值", "道路噪声原始值", "覆盖度评分", "归一化总分"].includes(field)) {
        return number.toFixed(1).replace(/\.0$/, "");
    }
    return String(value);
}

function popupMetric(field, label, attributes) {
    return `<div class="map-popup-metric"><span>${escapePopupValue(label)}</span><strong>${escapePopupValue(formatPopupValue(field, attributes[field]))}</strong></div>`;
}

const LINE_GVI_LEVELS = Object.freeze({ 0: "高", 1: "较高", 3: "中等", 5: "低" });
const LINE_NOI_LEVELS = Object.freeze({ 0: "低", 1.25: "较低", 2.5: "中", 3.75: "较高", 5: "高" });

function formatLineGrade(field, value) {
    if (value == null || value === "") return "暂无";
    const number = Number(value);
    if (!Number.isFinite(number)) return String(value);
    const levels = field === "GVI" ? LINE_GVI_LEVELS : LINE_NOI_LEVELS;
    const match = Object.entries(levels).find(([key]) => Math.abs(Number(key) - number) < 0.001);
    return match ? match[1] : formatPopupValue(field, value);
}

function formatLineMetricValue(field, attributes) {
    if (field === "GVI" || field === "NOI") {
        return formatLineGrade(field, attributes[field]);
    }
    return formatPopupValue(field, attributes[field]);
}

function linePopupMetric(field, label, attributes) {
    return `<div class="map-popup-metric"><span>${escapePopupValue(label)}</span><strong>${escapePopupValue(formatLineMetricValue(field, attributes))}</strong></div>`;
}

function staticHousingTypes(attributes) {
    const district = String(attributes?.adname || "").trim();
    return STATIC_HOUSING_TYPE_TEMPLATES[district] || DEFAULT_STATIC_HOUSING_TYPES;
}

function isReasonableHousingUnitPrice(unitPrice, attributes) {
    if (!Number.isFinite(unitPrice) || unitPrice <= 0) return false;
    const district = String(attributes?.adname || "").trim();
    const referencePrice = DISTRICT_SALE_UNIT_AVERAGES[district];
    if (!Number.isFinite(referencePrice)) {
        return unitPrice >= 3000 && unitPrice <= 100000;
    }
    return unitPrice >= referencePrice * HOUSING_UNIT_PRICE_REFERENCE_RANGE.minimumRatio
        && unitPrice <= referencePrice * HOUSING_UNIT_PRICE_REFERENCE_RANGE.maximumRatio;
}

function formatHousingTypeTotalPrice(area, attributes) {
    const unitPrice = Number(attributes?.房价);
    if (!Number.isFinite(area) || area <= 0 || !isReasonableHousingUnitPrice(unitPrice, attributes)) return "暂无";
    const total = Math.round((unitPrice * area) / 10000);
    return `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 }).format(total)} 万`;
}

function rentalHousingPhotosForCommunity(communityName) {
    const cacheKey = String(communityName || "当前小区").trim() || "当前小区";
    if (rentalHousingPhotoCache.has(cacheKey)) {
        return rentalHousingPhotoCache.get(cacheKey);
    }
    const photoPool = [...RENTAL_HOUSING_PHOTOS];
    for (let index = photoPool.length - 1; index > 0; index -= 1) {
        const randomIndex = Math.floor(Math.random() * (index + 1));
        [photoPool[index], photoPool[randomIndex]] = [photoPool[randomIndex], photoPool[index]];
    }
    const selection = Object.freeze(photoPool.slice(0, 3));
    rentalHousingPhotoCache.set(cacheKey, selection);
    return selection;
}

function rentalMonthlyRent(type, attributes) {
    const district = String(attributes?.adname || "").trim();
    const communityName = String(attributes?.name || "当前小区").trim();
    const cacheKey = `${district}|${communityName}|${type.rooms}|${type.area}`;
    if (rentalHousingRentCache.has(cacheKey)) {
        return rentalHousingRentCache.get(cacheKey);
    }
    const unitAverage = DISTRICT_RENT_UNIT_AVERAGES[district] || DEFAULT_RENT_UNIT_AVERAGE;
    const randomFactor = 0.88 + Math.random() * 0.24;
    const monthlyRent = Math.max(500, Math.round((unitAverage * type.area * randomFactor) / 100) * 100);
    const formatted = `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 }).format(monthlyRent)} 元/月`;
    rentalHousingRentCache.set(cacheKey, formatted);
    return formatted;
}

function buildStaticHousingTypeShowcase(attributes) {
    const types = staticHousingTypes(attributes);
    const communityName = escapePopupValue(attributes?.name || "当前小区");
    const items = types.map((type) => `
        <article class="housing-type-item">
            <div class="housing-type-item__heading">
                <div>
                    <span>${escapePopupValue(type.name)}</span>
                    <h5>${escapePopupValue(type.rooms)}</h5>
                </div>
                <strong>${escapePopupValue(type.area)}㎡</strong>
            </div>
            <button class="housing-type-image" type="button" data-housing-image="${escapePopupValue(type.image)}" data-housing-label="${escapePopupValue(`${communityName} ${type.rooms}户型图`)}" aria-label="放大查看${escapePopupValue(type.rooms)}户型图" title="点击放大查看户型图">
                <img src="${escapePopupValue(type.image)}" alt="${escapePopupValue(type.rooms)}户型图" loading="lazy">
                <span aria-hidden="true">放大查看</span>
            </button>
            <dl class="housing-type-specs">
                <div><dt>朝向</dt><dd>${escapePopupValue(type.orientation)}</dd></div>
                <div><dt>楼层</dt><dd>${escapePopupValue(type.floor)}</dd></div>
                <div><dt>配置</dt><dd>${escapePopupValue(type.elevator)}</dd></div>
            </dl>
            <p class="housing-type-feature">${escapePopupValue(type.feature)}</p>
            <div class="housing-type-price"><span>参考总价</span><strong>${escapePopupValue(formatHousingTypeTotalPrice(type.area, attributes))}</strong></div>
        </article>
    `).join("");
    return `
        <section class="point-feature-popup__housing-types" aria-labelledby="housingTypesTitle">
            <div class="housing-types-heading">
                <div>
                    <p>UNIT COLLECTION</p>
                    <h4 id="housingTypesTitle">${communityName} · 户型展示</h4>
                </div>
                <span>${types.length} 种户型</span>
            </div>
            <div class="housing-type-grid">${items}</div>
        </section>
    `;
}

function buildRentalHousingShowcase(attributes) {
    const types = staticHousingTypes(attributes);
    const communityNameValue = String(attributes?.name || "当前小区");
    const communityName = escapePopupValue(communityNameValue);
    const rentalPhotos = rentalHousingPhotosForCommunity(`${attributes?.adname || ""}|${communityNameValue}`);
    const items = types.map((type, index) => `
        <article class="rental-type-item">
            <button class="rental-type-photo" type="button" data-housing-image="${escapePopupValue(rentalPhotos[index])}" data-housing-label="${escapePopupValue(`${communityNameValue} ${type.rooms}房源图`)}" aria-label="放大查看${escapePopupValue(type.rooms)}房源图" title="点击放大查看房源图">
                <img src="${escapePopupValue(rentalPhotos[index])}" alt="${escapePopupValue(`${communityNameValue} ${type.rooms}房源室内图`)}" loading="lazy">
            </button>
            <dl class="rental-type-details">
                <div><dt>户型</dt><dd>${escapePopupValue(type.rooms)}</dd></div>
                <div><dt>租金</dt><dd>${escapePopupValue(rentalMonthlyRent(type, attributes))}</dd></div>
                <div><dt>面积</dt><dd>${escapePopupValue(type.area)}㎡</dd></div>
            </dl>
        </article>
    `).join("");
    return `
        <section class="point-feature-popup__rental-types" aria-labelledby="rentalTypesTitle">
            <div class="housing-types-heading">
                <div>
                    <p>RENTAL OPTIONS</p>
                    <h4 id="rentalTypesTitle">${communityName} · 租房信息</h4>
                </div>
                <span>${types.length} 种户型</span>
            </div>
            <div class="rental-type-grid">${items}</div>
        </section>
    `;
}

function buildHousingModeControls() {
    return `
        <section class="point-feature-popup__housing-switch" aria-label="房源信息">
            <p>查看房源信息</p>
            <div role="group" aria-label="选择购房或租房信息">
                <button type="button" data-housing-mode="purchase" aria-controls="pointHousingPanel" aria-expanded="false">购房</button>
                <button type="button" data-housing-mode="rent" aria-controls="pointHousingPanel" aria-expanded="false">租房</button>
            </div>
        </section>
        <div id="pointHousingPanel" class="point-feature-popup__housing-panel" data-housing-panel hidden></div>
    `;
}

function setFeaturePopupChromeHidden(hidden) {
    [".top-shell", ".regional-road-radar", ".explorer-panel", ".assistant-panel"].forEach((selector) => {
        document.querySelector(selector)?.classList.toggle("is-feature-popup-open", hidden);
    });
}

function pointRadarMetricValue(value) {
    if (value == null || (typeof value === "string" && value.trim() === "")) {
        return null;
    }
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
}

function pointRadarOutStatistics() {
    return POINT_RADAR_METRICS.map((metric) => ({
        statisticType: "max",
        onStatisticField: metric.field,
        outStatisticFieldName: metric.statisticName
    }));
}

async function fetchPointRadarLayerMaximums(layerId) {
    const params = new URLSearchParams({
        f: "json",
        where: "1=1",
        returnGeometry: "false",
        outStatistics: JSON.stringify(pointRadarOutStatistics())
    });
    const response = await fetch(`${GEOSCENE_MAP_SERVICE_URL}/${layerId}/query?${params}`);
    if (!response.ok) {
        throw new Error(`住宅指标上限查询失败：HTTP ${response.status}`);
    }
    const payload = await response.json();
    if (payload?.error) {
        throw new Error(payload.error.message || "住宅指标上限查询失败");
    }
    return payload?.features?.[0]?.attributes || {};
}

async function fetchPointRadarMaximums() {
    const layerMaximums = await Promise.all(POINT_LAYER_IDS.map(fetchPointRadarLayerMaximums));
    return Object.fromEntries(POINT_RADAR_METRICS.map((metric) => {
        const maximum = Math.max(
            ...layerMaximums
                .map((attributes) => pointRadarMetricValue(attributes[metric.statisticName]))
                .filter((value) => value != null)
        );
        return [metric.field, Number.isFinite(maximum) && maximum > 0 ? maximum : null];
    }));
}

function loadPointRadarMaximums() {
    if (!pointRadarMaximumsPromise) {
        pointRadarMaximumsPromise = fetchPointRadarMaximums().catch((error) => {
            pointRadarMaximumsPromise = null;
            throw error;
        });
    }
    return pointRadarMaximumsPromise;
}

function formatPointRadarPercent(value, maximum) {
    if (value == null || maximum == null || maximum <= 0) {
        return "暂无";
    }
    const percent = Math.max(0, Math.min(1, value / maximum)) * 100;
    return `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 }).format(percent)}%`;
}

function buildPointRadarAccessibility(attributes, maximums = {}) {
    return POINT_RADAR_METRICS.map((metric) => {
        const value = pointRadarMetricValue(attributes[metric.field]);
        const maximum = pointRadarMetricValue(maximums[metric.field]);
        return `<li><span>${escapePopupValue(metric.label)}</span><strong>${escapePopupValue(formatPointRadarPercent(value, maximum))}</strong></li>`;
    }).join("");
}

function buildPointRadarContent(attributes) {
    return `
        <section class="point-feature-popup__radar" aria-labelledby="pointRadarTitle">
            <div class="point-feature-popup__radar-heading">
                <h4 id="pointRadarTitle">生活便利画像</h4>
                <span>住宅样本峰值 100%</span>
            </div>
            <canvas class="point-feature-popup__radar-canvas" data-point-radar-chart aria-hidden="true"></canvas>
            <ul class="sr-only" data-point-radar-values>${buildPointRadarAccessibility(attributes)}</ul>
            <p class="point-feature-popup__radar-state" data-point-radar-state>指标边界加载中</p>
        </section>
    `;
}

function radarPoint(centerX, centerY, radius, angle) {
    return {
        x: centerX + Math.cos(angle) * radius,
        y: centerY + Math.sin(angle) * radius
    };
}

function drawRadarPolygon(context, points, closePath) {
    let started = false;
    points.forEach((point) => {
        if (!point) {
            started = false;
            return;
        }
        if (!started) {
            context.moveTo(point.x, point.y);
            started = true;
        } else {
            context.lineTo(point.x, point.y);
        }
    });
    if (closePath) {
        context.closePath();
    }
}

function drawPointRadar(canvas, attributes, maximums, options = {}) {
    const metrics = options.metrics || POINT_RADAR_METRICS;
    const valueFormatter = options.valueFormatter || formatPointRadarPercent;
    const progress = Math.max(0, Math.min(1, options.progress == null ? 1 : options.progress));
    const cssWidth = Math.max(220, Math.round(canvas.clientWidth || 252));
    const cssHeight = Math.round(cssWidth * 0.88);
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(cssWidth * pixelRatio);
    canvas.height = Math.round(cssHeight * pixelRatio);
    canvas.style.height = `${cssHeight}px`;

    const context = canvas.getContext("2d");
    if (!context) {
        throw new Error("浏览器不支持 Canvas 2D");
    }
    context.scale(pixelRatio, pixelRatio);
    context.clearRect(0, 0, cssWidth, cssHeight);

    const centerX = cssWidth / 2;
    const centerY = cssHeight / 2 + 3;
    const radius = Math.min(cssWidth * 0.27, cssHeight * 0.31);
    const angleStep = (Math.PI * 2) / metrics.length;
    const angles = metrics.map((_, index) => -Math.PI / 2 + angleStep * index);

    context.lineJoin = "round";
    context.lineCap = "round";
    [0.25, 0.5, 0.75, 1].forEach((ratio, ringIndex) => {
        const points = angles.map((angle) => radarPoint(centerX, centerY, radius * ratio * progress, angle));
        context.beginPath();
        drawRadarPolygon(context, points, true);
        context.fillStyle = ringIndex % 2 === 0 ? "rgba(84, 188, 233, 0.025)" : "rgba(255, 255, 255, 0.12)";
        context.fill();
        context.strokeStyle = ringIndex === 3 ? "rgba(35, 124, 165, 0.34)" : "rgba(35, 124, 165, 0.16)";
        context.lineWidth = ringIndex === 3 ? 1.1 : 0.8;
        context.stroke();
    });

    angles.forEach((angle) => {
        const boundary = radarPoint(centerX, centerY, radius * progress, angle);
        context.beginPath();
        context.moveTo(centerX, centerY);
        context.lineTo(boundary.x, boundary.y);
        context.strokeStyle = "rgba(35, 124, 165, 0.18)";
        context.lineWidth = 0.8;
        context.stroke();
    });

    const plottedPoints = metrics.map((metric, index) => {
        const value = pointRadarMetricValue(attributes[metric.field]);
        const maximum = pointRadarMetricValue(maximums[metric.field]);
        if (value == null || maximum == null || maximum <= 0) {
            return null;
        }
        return radarPoint(centerX, centerY, radius * Math.max(0, Math.min(1, value / maximum)) * progress, angles[index]);
    });
    const complete = plottedPoints.every(Boolean);
    context.beginPath();
    drawRadarPolygon(context, plottedPoints, complete);
    if (complete) {
        context.fillStyle = "rgba(66, 175, 224, 0.24)";
        context.fill();
    }
    context.strokeStyle = "rgba(23, 111, 153, 0.92)";
    context.lineWidth = 2;
    context.stroke();

    plottedPoints.forEach((point) => {
        if (!point) {
            return;
        }
        context.beginPath();
        context.arc(point.x, point.y, 2.7, 0, Math.PI * 2);
        context.fillStyle = "#176f99";
        context.fill();
        context.strokeStyle = "rgba(242, 251, 255, 0.96)";
        context.lineWidth = 1.2;
        context.stroke();
    });

    metrics.forEach((metric, index) => {
        const angle = angles[index];
        const labelPoint = radarPoint(centerX, centerY, radius + 18, angle);
        const value = pointRadarMetricValue(attributes[metric.field]);
        const horizontal = Math.cos(angle);
        context.textAlign = horizontal > 0.28 ? "left" : horizontal < -0.28 ? "right" : "center";
        context.textBaseline = "middle";
        context.font = "700 11px 'Microsoft YaHei', sans-serif";
        context.fillStyle = "#315f74";
        context.fillText(metric.label, labelPoint.x, labelPoint.y - 6);
        context.font = "700 10px 'Microsoft YaHei', sans-serif";
        const maximum = pointRadarMetricValue(maximums[metric.field]);
        context.fillStyle = value == null || maximum == null ? "#9aa8ae" : "#176f99";
        context.fillText(valueFormatter(value, maximum, metric), labelPoint.x, labelPoint.y + 7);
    });
}

function animateRadar(canvas, attributes, maximums, options = {}) {
    const token = options.token;
    const started = performance.now();
    const duration = 560;
    const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
    const paint = (progress) => {
        if (token && token.owner && token.owner.dataset.renderToken !== token.value) {
            return;
        }
        drawPointRadar(canvas, attributes, maximums, { ...options, progress });
        if (progress < 1 && !reduced) {
            requestAnimationFrame((now) => paint(Math.min(1, (now - started) / duration)));
        }
    };
    paint(reduced ? 1 : 0);
}

function renderPointRadar(popup, attributes, maximums) {
    const canvas = popup.querySelector("[data-point-radar-chart]");
    const state = popup.querySelector("[data-point-radar-state]");
    const values = popup.querySelector("[data-point-radar-values]");
    if (!canvas || !state) {
        return;
    }
    animateRadar(canvas, attributes, maximums, { token: { owner: popup, value: popup.dataset.renderToken } });
    if (values) {
        values.innerHTML = buildPointRadarAccessibility(attributes, maximums);
    }
    state.hidden = true;
}

function normalizeLineAttributes(attributes = {}) {
    const normalized = { ...attributes };
    if (normalized["绿视率原始值"] == null && normalized.vegetation != null) {
        normalized["绿视率原始值"] = normalized.vegetation;
    }
    if (normalized["道路噪声原始值"] == null && normalized.noise != null) {
        normalized["道路噪声原始值"] = normalized.noise;
    }
    return normalized;
}

function buildLinePopupContent(attributes) {
    const length = attributes.Shape_Length == null
        ? ""
        : `<div class="map-popup-detail"><span>道路长度</span><strong>${escapePopupValue(formatPopupValue("Shape_Length", attributes.Shape_Length))}</strong></div>`;
    return `
        <div class="map-popup-metrics">
            ${linePopupMetric("GVI", "绿视率", attributes)}
            ${linePopupMetric("NOI", "道路噪声", attributes)}
            ${linePopupMetric("WS归一化", "步行指数", attributes)}
        </div>
        ${length}
    `;
}

function buildLineRadarContent(attributes) {
    return `
        <section class="line-feature-popup__radar" aria-labelledby="lineRadarTitle">
            <div class="line-feature-popup__radar-heading">
                <h4 id="lineRadarTitle">道路指标画像</h4>
                <span>当前道路 · 原始分</span>
            </div>
            <canvas class="line-feature-popup__radar-canvas" data-line-radar-chart aria-hidden="true"></canvas>
            <ul class="sr-only" data-line-radar-values>${buildLineRadarAccessibility(attributes)}</ul>
        </section>
    `;
}

function lineRadarMaximums() {
    return Object.fromEntries(LINE_RADAR_METRICS.map((metric) => [metric.field, metric.maximum]));
}

function formatLineRadarValue(value, _maximum, metric) {
    if (value == null || (typeof value === "string" && value.trim() === "") || !Number.isFinite(Number(value))) {
        return "暂无";
    }
    const maximumFractionDigits = metric?.field === "WS归一化" ? 1 : 2;
    return new Intl.NumberFormat("zh-CN", { maximumFractionDigits }).format(Number(value));
}

function buildLineRadarAccessibility(attributes) {
    return LINE_RADAR_METRICS.map((metric) => (
        `<li><span>${escapePopupValue(metric.label)}</span><strong>${escapePopupValue(formatLineRadarValue(attributes[metric.field], metric.maximum, metric))}</strong></li>`
    )).join("");
}

function renderLineRadar(popup, attributes) {
    const canvas = popup.querySelector("[data-line-radar-chart]");
    if (!canvas) return;
    const maximums = lineRadarMaximums();
    animateRadar(canvas, attributes, maximums, {
        metrics: LINE_RADAR_METRICS,
        valueFormatter: formatLineRadarValue,
        token: { owner: popup, value: popup.dataset.renderToken }
    });
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
        <div class="point-feature-popup__lower">
            <div class="point-feature-popup__details">
                <div class="point-feature-popup__metrics">
                    ${popupMetric("房价", "参考房价", attributes)}
                    ${popupMetric("覆盖度评分", "覆盖评分", attributes)}
                    ${popupMetric("归一化总分", "社区便利度", attributes)}
                </div>
                ${category}
            </div>
            ${buildPointRadarContent(attributes)}
        </div>
        ${buildHousingModeControls()}
    `;
}

function buildPoiWhere(categoryCodes) {
    const selected = [...new Set(Array.isArray(categoryCodes) ? categoryCodes : [])]
        .filter((code) => POI_CATEGORY_BY_CODE.has(code));
    if (selected.length === 0) {
        return "1=0";
    }
    if (selected.length === POI_CATEGORIES.length) {
        return "1=1";
    }
    return `category_code IN (${selected.map((code) => `'${code}'`).join(",")})`;
}

function buildPoiPopupContent(attributes) {
    const category = POI_CATEGORY_BY_CODE.get(attributes.category_code);
    const categoryLabel = attributes.category_name || category?.label || "POI";
    const categoryColor = escapePopupValue(category?.color || "#5b8fc5");
    const name = escapePopupValue(attributes.poi_name || attributes.name || "POI详情");
    const district = String(attributes.district_name || "").trim();
    const address = String(attributes.address || "").trim() || "暂无详细地址";
    const phone = String(attributes.phone || "").trim();
    const phoneValue = phone && phone !== "[]" ? phone : "暂无";
    const rows = [
        ["细分类", attributes.subcategory || "暂无"],
        ["联系电话", phoneValue]
    ].filter(([, value]) => value != null && String(value).trim() !== "");
    return `
        <span class="poi-feature-popup__accent" style="--poi-color:${categoryColor}" aria-hidden="true"></span>
        <div class="poi-feature-popup__body">
            <p class="poi-feature-popup__eyebrow">POINT OF INTEREST</p>
            <h3>${name}</h3>
            <div class="poi-feature-popup__tags">
                <span class="poi-feature-popup__category" style="--poi-color:${categoryColor}">
                    <span class="poi-feature-popup__category-dot" aria-hidden="true"></span>
                    ${escapePopupValue(categoryLabel)}
                </span>
                ${district ? `<span class="poi-feature-popup__district">${escapePopupValue(district)}</span>` : ""}
            </div>
            <div class="poi-feature-popup__location">
                <span>地址</span>
                <p>${escapePopupValue(address)}</p>
            </div>
            <dl class="poi-feature-popup__details">
                ${rows.map(([label, value]) => `
                    <div>
                        <dt>${escapePopupValue(label)}</dt>
                        <dd>${escapePopupValue(value || "暂无")}</dd>
                    </div>
                `).join("")}
            </dl>
        </div>
    `;
}

function closePoiFeaturePopup() {
    const popup = document.getElementById("poiFeaturePopup");
    if (popup) {
        popup.hidden = true;
        popup.classList.remove("is-entering");
    }
    document.getElementById("mapContainer")?.classList.remove("has-feature-popup");
    setFeaturePopupChromeHidden(false);
}

function openPoiFeaturePopup(mapContainer, attributes, screenPoint, view) {
    let popup = document.getElementById("poiFeaturePopup");
    if (!popup) {
        popup = document.createElement("div");
        popup.id = "poiFeaturePopup";
        popup.className = "poi-feature-popup";
        popup.setAttribute("role", "dialog");
        popup.setAttribute("aria-label", "POI详情");
        popup.innerHTML = `
            <button class="poi-feature-popup__close" type="button" aria-label="关闭 POI 详情" title="关闭">×</button>
            <div data-poi-popup-content></div>
            <span class="poi-feature-popup__pointer" aria-hidden="true"></span>
        `;
        popup.querySelector(".poi-feature-popup__close").addEventListener("click", closePoiFeaturePopup);
        mapContainer.appendChild(popup);
    }
    const renderToken = String(++poiPopupRenderToken);
    popup.dataset.renderToken = renderToken;
    popup.querySelector("[data-poi-popup-content]").innerHTML = buildPoiPopupContent(attributes);
    popup.hidden = false;
    popup.classList.remove("is-entering");
    void popup.offsetWidth;
    popup.classList.add("is-entering");
    mapContainer.classList.add("has-feature-popup");
    setFeaturePopupChromeHidden(true);
    positionFeaturePopup(popup, screenPoint, view, 280);
}

function closeLineFeaturePopup() {
    const popup = document.getElementById("lineFeaturePopup");
    if (popup) {
        popup.hidden = true;
    }
    document.getElementById("mapContainer")?.classList.remove("has-feature-popup");
    setFeaturePopupChromeHidden(false);
}

function closePointFeaturePopup() {
    const popup = document.getElementById("pointFeaturePopup");
    if (popup) {
        popup.hidden = true;
    }
    document.getElementById("mapContainer")?.classList.remove("has-feature-popup");
    setFeaturePopupChromeHidden(false);
}

function closeHousingImagePreview() {
    const preview = document.getElementById("housingImagePreview");
    if (!preview) return;
    preview.hidden = true;
    document.body.classList.remove("housing-image-preview-open");
}

function openHousingImagePreview(source, label) {
    let preview = document.getElementById("housingImagePreview");
    if (!preview) {
        preview = document.createElement("div");
        preview.id = "housingImagePreview";
        preview.className = "housing-image-preview";
        preview.hidden = true;
        preview.innerHTML = `
            <div class="housing-image-preview__backdrop" data-housing-preview-close></div>
            <div class="housing-image-preview__dialog" role="dialog" aria-modal="true" aria-labelledby="housingImagePreviewTitle">
                <button class="housing-image-preview__close" type="button" data-housing-preview-close aria-label="关闭户型大图">×</button>
                <p id="housingImagePreviewTitle" class="housing-image-preview__title"></p>
                <img class="housing-image-preview__image" alt="">
            </div>
        `;
        preview.addEventListener("click", (event) => {
            if (event.target.closest("[data-housing-preview-close]")) {
                closeHousingImagePreview();
            }
        });
        document.body.appendChild(preview);
    }
    preview.querySelector(".housing-image-preview__title").textContent = label || "户型图";
    const image = preview.querySelector(".housing-image-preview__image");
    image.src = source;
    image.alt = label || "户型图";
    preview.hidden = false;
    document.body.classList.add("housing-image-preview-open");
    preview.querySelector(".housing-image-preview__close")?.focus();
}

document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeHousingImagePreview();
});

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
        popup.addEventListener("click", (event) => {
            const imageButton = event.target.closest("[data-housing-image]");
            if (imageButton) {
                openHousingImagePreview(imageButton.dataset.housingImage, imageButton.dataset.housingLabel);
                return;
            }
            const modeButton = event.target.closest("[data-housing-mode]");
            if (!modeButton) {
                return;
            }
            const selectedMode = modeButton.dataset.housingMode;
            const nextMode = popup.dataset.housingMode === selectedMode ? "" : selectedMode;
            popup.dataset.housingMode = nextMode;
            popup.classList.toggle("has-housing-panel", Boolean(nextMode));
            popup.querySelectorAll("[data-housing-mode]").forEach((button) => {
                const active = button.dataset.housingMode === nextMode;
                button.classList.toggle("is-active", active);
                button.setAttribute("aria-expanded", String(active));
            });
            const panel = popup.querySelector("[data-housing-panel]");
            panel.hidden = !nextMode;
            panel.innerHTML = nextMode === "purchase"
                ? buildStaticHousingTypeShowcase(popup.__housingAttributes)
                : nextMode === "rent"
                    ? buildRentalHousingShowcase(popup.__housingAttributes)
                    : "";
            window.requestAnimationFrame(() => {
                positionFeaturePopup(popup, popup.__screenPoint, popup.__mapView, 560);
            });
        });
        mapContainer.appendChild(popup);
    }
    const renderToken = String(++pointPopupRenderToken);
    popup.dataset.renderToken = renderToken;
    const name = escapePopupValue(attributes.name || "项目详情");
    popup.querySelector("[data-point-popup-content]").innerHTML = `<h3>${name}</h3>${buildPointPopupContent(attributes)}`;
    popup.__housingAttributes = attributes;
    popup.__screenPoint = screenPoint;
    popup.__mapView = view;
    popup.dataset.housingMode = "";
    popup.classList.remove("has-housing-panel");
    popup.hidden = false;
    mapContainer.classList.add("has-feature-popup");
    setFeaturePopupChromeHidden(true);
    positionFeaturePopup(popup, screenPoint, view, 560);
    loadPointRadarMaximums().then((maximums) => {
        if (popup.hidden || popup.dataset.renderToken !== renderToken) {
            return;
        }
        renderPointRadar(popup, attributes, maximums);
        positionFeaturePopup(popup, screenPoint, view, 560);
    }).catch((error) => {
        console.warn("住宅雷达图边界加载失败", error);
        if (popup.hidden || popup.dataset.renderToken !== renderToken) {
            return;
        }
        const state = popup.querySelector("[data-point-radar-state]");
        if (state) {
            state.textContent = "雷达数据暂不可用";
        }
    });
}

function openLineFeaturePopup(mapContainer, attributes, screenPoint, view) {
    const normalizedAttributes = normalizeLineAttributes(attributes);
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
    const renderToken = String(++linePopupRenderToken);
    popup.dataset.renderToken = renderToken;
    const name = escapePopupValue(normalizedAttributes.name || "道路详情");
    popup.querySelector("[data-line-popup-content]").innerHTML = `<h3>${name}</h3>${buildLinePopupContent(normalizedAttributes)}${buildLineRadarContent(normalizedAttributes)}`;
    popup.hidden = false;
    mapContainer.classList.add("has-feature-popup");
    setFeaturePopupChromeHidden(true);
    positionFeaturePopup(popup, screenPoint, view, 340);
    renderLineRadar(popup, normalizedAttributes);
}

function popupOutFields(layerId, objectIdField) {
    if (layerId >= 3) {
        return [objectIdField || "OBJECTID_12", "name", ...LINE_POPUP_REQUIRED_FIELDS, "Shape_Length"];
    }
    return [
        objectIdField || "OBJECTID",
        "name",
        "address",
        "adname",
        "大类",
        "中类",
        "小类",
        "房价",
        "覆盖度评分",
        ...POINT_RADAR_METRICS.map((metric) => metric.field)
    ];
}

function hasRequiredLinePopupFields(attributes) {
    const normalizedAttributes = normalizeLineAttributes(attributes);
    return LINE_POPUP_REQUIRED_FIELDS.every((field) => (
        Object.prototype.hasOwnProperty.call(normalizedAttributes, field)
    ));
}

async function fetchMapFeature(requestBody, signal) {
    const response = await fetch("/api/map/query-features", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(requestBody),
        signal
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
    const mergedAttributes = feature && feature.attributes ? { ...attributes, ...feature.attributes } : attributes;
    return layerId >= 3 ? normalizeLineAttributes(mergedAttributes) : mergedAttributes;
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

function installMapClickHandler(view, mapContainer, agentLayers, setSelectionHighlight) {
    view.on("click", async (event) => {
        setSelectionHighlight(null);
        const response = await view.hitTest(event);
        const agentHit = response.results.find((item) => (
            (item?.graphic?.layer === agentLayers.housing || item?.graphic?.layer === agentLayers.road)
            && EXPECTED_MAP_SUBLAYERS.has(Number(item.graphic.attributes?.__agentLayerId))
        ));
        const poiHit = response.results.find((item) => item?.graphic?.layer === agentLayers.poi);
        const bufferHit = response.results.find((item) => item?.graphic?.layer === agentLayers.buffer);
        const clusterHit = response.results.find((item) => (
            item?.graphic?.isAggregate
            && resolveMapHit(item)?.layerId < 3
        ));
        if (!agentHit && clusterHit) {
            closeLineFeaturePopup();
            closePointFeaturePopup();
            closePoiFeaturePopup();
            view.closePopup();
            const nextZoom = Math.min(MAX_MAP_ZOOM, Math.ceil(view.zoom) + 2);
            view.goTo({ target: clusterHit.graphic.geometry, zoom: nextZoom }, {
                animate: true,
                duration: 420
            }).catch((error) => {
                if (error?.name !== "AbortError") console.warn("住宅聚合点展开失败", error);
            });
            return;
        }
        const serviceHit = response.results.find((item) => resolveMapHit(item));
        if (!agentHit && bufferHit) {
            closeLineFeaturePopup();
            closePointFeaturePopup();
            closePoiFeaturePopup();
            view.openPopup({
                features: [bufferHit.graphic],
                location: event.mapPoint
            });
            return;
        }
        if (!agentHit && poiHit) {
            closeLineFeaturePopup();
            closePointFeaturePopup();
            view.closePopup();
            setSelectionHighlight(poiHit.graphic, "poi");
            const screenPoint = { x: event.x, y: event.y };
            openPoiFeaturePopup(mapContainer, poiHit.graphic.attributes || {}, screenPoint, view);
            return;
        }
        const hit = agentHit || serviceHit;
        const hitInfo = serviceHit && resolveMapHit(serviceHit);
        let layerId = agentHit ? Number(agentHit.graphic.attributes.__agentLayerId) : hitInfo?.layerId;
        let layerName = hitInfo?.layerName;
        let fallbackAttributes = hit?.graphic?.attributes || {};
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
                closePoiFeaturePopup();
                return;
            }
            layerId = spatialMatch.layerId;
            layerName = EXPECTED_MAP_SUBLAYERS.get(layerId);
            fallbackAttributes = spatialMatch.attributes;
        }
        if (layerId == null) {
            view.closePopup();
            closeLineFeaturePopup();
            closePointFeaturePopup();
            closePoiFeaturePopup();
            return;
        }

        setSelectionHighlight(hit?.graphic || null, layerId);

        layerName = layerName || "地图要素";
        try {
            const shouldQueryDetails = layerId < 3 || !hasRequiredLinePopupFields(fallbackAttributes);
            const attributes = shouldQueryDetails
                ? await queryPopupAttributes(layerId, fallbackAttributes, event.mapPoint)
                : fallbackAttributes;
            if (layerId >= 3) {
                view.closePopup();
                closePointFeaturePopup();
                closePoiFeaturePopup();
                openLineFeaturePopup(mapContainer, attributes, { x: event.x, y: event.y }, view);
            } else {
                view.closePopup();
                closeLineFeaturePopup();
                closePoiFeaturePopup();
                openPointFeaturePopup(mapContainer, attributes, { x: event.x, y: event.y }, view);
            }
        } catch (error) {
            console.warn("地图详情接口不可用，使用命中要素属性展示", error);
            if (layerId >= 3) {
                view.closePopup();
                closePointFeaturePopup();
                closePoiFeaturePopup();
                openLineFeaturePopup(mapContainer, fallbackAttributes, { x: event.x, y: event.y }, view);
            } else {
                view.closePopup();
                closeLineFeaturePopup();
                closePoiFeaturePopup();
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
        "geoscene/widgets/Compass",
        "geoscene/widgets/ScaleBar",
        "geoscene/layers/WebTileLayer",
        "geoscene/layers/MapImageLayer",
        "geoscene/layers/FeatureLayer",
        "geoscene/layers/GraphicsLayer",
        "geoscene/Graphic",
        "geoscene/symbols/SimpleMarkerSymbol",
        "geoscene/geometry/support/jsonUtils"
    ], (geosceneConfig, GeoSceneMap, MapView, Compass, ScaleBar, WebTileLayer, MapImageLayer, FeatureLayer, GraphicsLayer, Graphic, SimpleMarkerSymbol, geometryJsonUtils) => {
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

        const residencePointLayers = POINT_LAYER_IDS.map((layerId) => (
            createResidencePointLayer(FeatureLayer, layerId)
        ));

        const slopeDemLayer = new MapImageLayer({
            url: SLOPE_MAP_SERVICE_URL,
            title: "大连市坡度 DEM",
            sublayers: [
                { id: 0, visible: false },
                { id: 1, visible: false },
                { id: 2, visible: false },
                { id: SLOPE_DEM_LAYER_ID, visible: true }
            ],
            opacity: 0.62,
            visible: false,
            listMode: "hide"
        });

        const slopeRoadLayers = SLOPE_ROAD_LAYER_CONFIGS.map((config) => new FeatureLayer({
            url: `${SLOPE_MAP_SERVICE_URL}/${config.serviceLayerId}`,
            title: config.title,
            outFields: ["*"],
            renderer: createSlopeRoadRenderer(),
            popupEnabled: false,
            visible: false,
            listMode: "hide",
            effect: "drop-shadow(0px, 0px, 1px, #f7fbff)"
        }));

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

        const selectedFeatureLayer = new GraphicsLayer({
            title: "选中高光",
            listMode: "hide"
        });

        const poiGraphicLayer = new GraphicsLayer({
            title: "POI点位",
            listMode: "hide"
        });

        const map = new GeoSceneMap({
            layers: [
                imageryBasemapLayer,
                streetBasemapLayer,
                slopeDemLayer,
                residenceMapLayer,
                ...residencePointLayers,
                ...slopeRoadLayers,
                poiGraphicLayer,
                agentBufferLayer,
                agentRoadLayer,
                agentHousingLayer,
                selectedFeatureLayer
            ]
        });

        const view = new MapView({
            container: mapContainer,
            map,
            zoom: 14,
            center: [121.62, 38.91],
            constraints: {
                maxZoom: MAX_MAP_ZOOM,
                snapToZoom: false
            }
        });

        view.popup.autoOpenEnabled = false;
        view.ui.move("zoom", "bottom-right");
        const compassContainer = document.getElementById("mapCompassSlot");
        const compass = new Compass({
            view,
            container: compassContainer
        });
        const scaleBar = new ScaleBar({
            view,
            unit: "metric",
            style: "ruler"
        });
        view.ui.add(scaleBar, { position: "bottom-right", index: 1 });

        window.userMapDebug = {
            map,
            mapContainer,
            basemapLayer: streetBasemapLayer,
            basemapLayers: {
                imagery: imageryBasemapLayer,
                street: streetBasemapLayer
            },
            residenceMapLayer,
            residencePointLayers,
            slopeDemLayer,
            slopeRoadLayers,
            agentResultLayer: agentHousingLayer,
            agentResultLayers: {
                buffer: agentBufferLayer,
                road: agentRoadLayer,
                housing: agentHousingLayer
            },
            selectedFeatureLayer,
            poiGraphicLayer,
            compass,
            scaleBar,
            view
        };

        const residenceRoadLayerIds = [3, 4, 5];
        let savedResidenceRoadVisibility = null;
        let slopeModeRequestToken = 0;

        const emitSlopeModeState = (status, active, message = "") => {
            window.userMapDebug.slopeModeActive = active;
            window.dispatchEvent(new CustomEvent("slope-mode-state", {
                detail: { status, active, message }
            }));
        };

        const restoreResidenceRoadVisibility = () => {
            if (!savedResidenceRoadVisibility) return;
            savedResidenceRoadVisibility.forEach((visible, layerId) => {
                const sublayer = residenceMapLayer.findSublayerById(layerId);
                if (sublayer) sublayer.visible = visible;
            });
            savedResidenceRoadVisibility = null;
        };

        const setSlopeMode = async (active) => {
            const token = ++slopeModeRequestToken;
            emitSlopeModeState("loading", slopeDemLayer.visible);

            if (!active) {
                slopeDemLayer.visible = false;
                slopeRoadLayers.forEach((layer) => { layer.visible = false; });
                restoreResidenceRoadVisibility();
                emitSlopeModeState("ready", false);
                return;
            }

            try {
                await Promise.all([
                    residenceMapLayer.when(),
                    slopeDemLayer.when(),
                    ...slopeRoadLayers.map((layer) => layer.when())
                ]);
                if (token !== slopeModeRequestToken) return;

                slopeRoadLayers.forEach((layer) => {
                    if (!layer.fields.some((field) => field.name === "WS归一化")) {
                        throw new Error(`${layer.title} 缺少 WS归一化字段`);
                    }
                });

                savedResidenceRoadVisibility = new Map(residenceRoadLayerIds.map((layerId) => {
                    const sublayer = residenceMapLayer.findSublayerById(layerId);
                    return [layerId, sublayer?.visible !== false];
                }));
                residenceRoadLayerIds.forEach((layerId) => {
                    const sublayer = residenceMapLayer.findSublayerById(layerId);
                    if (sublayer) sublayer.visible = false;
                });
                slopeDemLayer.visible = true;
                slopeRoadLayers.forEach((layer) => { layer.visible = true; });
                emitSlopeModeState("ready", true);
            } catch (error) {
                console.error("坡度分析图层加载失败", error);
                slopeDemLayer.visible = false;
                slopeRoadLayers.forEach((layer) => { layer.visible = false; });
                restoreResidenceRoadVisibility();
                emitSlopeModeState("error", false, "坡度图层暂时无法加载，请稍后重试");
            }
        };

        const slopeModeHandler = (event) => {
            setSlopeMode(Boolean(event.detail?.active));
        };
        window.addEventListener("slope-mode-change", slopeModeHandler);
        emitSlopeModeState("ready", false);

        let selectedPoiCategories = [];
        let selectedPoiDistricts = [...POI_TARGET_DISTRICTS];
        let poiRefreshToken = 0;
        let poiRequestController = null;

        const colorToRgba = (hex, alpha) => {
            const value = String(hex || "#5b8fc5").replace("#", "");
            const normalized = value.length === 3
                ? value.split("").map((part) => part + part).join("")
                : value.padEnd(6, "0").slice(0, 6);
            return [
                Number.parseInt(normalized.slice(0, 2), 16),
                Number.parseInt(normalized.slice(2, 4), 16),
                Number.parseInt(normalized.slice(4, 6), 16),
                Math.max(0, Math.min(1, alpha))
            ];
        };

        const poiSymbol = (attributes, size = POI_BASE_MARKER_SIZE, alpha = 1) => {
            const category = POI_CATEGORY_BY_CODE.get(attributes?.category_code);
            return new SimpleMarkerSymbol({
                style: "circle",
                color: colorToRgba(category?.color, alpha),
                size,
                outline: {
                    color: [255, 255, 255, Math.min(1, alpha + 0.12)],
                    width: 1
                }
            });
        };

        const springProgress = (progress) => {
            if (progress <= 0) return 0;
            if (progress >= 1) return 1;
            const eased = 1 - Math.exp(-8.5 * progress) * Math.cos(11 * progress);
            return Math.max(0, Math.min(1.08, eased));
        };

        const animatePoiEntrance = (graphics) => {
            if (!graphics.length) return;
            const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
            const started = performance.now();
            const duration = reduced ? 0 : 520;
            const animatedGraphics = graphics.slice(0, POI_ANIMATION_LIMIT);
            graphics.slice(POI_ANIMATION_LIMIT).forEach((graphic) => {
                const size = POI_BASE_MARKER_SIZE + Math.min(POI_WEIGHT_MARKER_RANGE, Number(graphic.attributes?.weight) || 0);
                graphic.symbol = poiSymbol(graphic.attributes, size, 1);
            });
            const baseSizes = new Map(animatedGraphics.map((graphic) => [
                graphic,
                POI_BASE_MARKER_SIZE + Math.min(POI_WEIGHT_MARKER_RANGE, Number(graphic.attributes?.weight) || 0)
            ]));

            const paint = (now) => {
                const elapsed = now - started;
                let active = false;
                animatedGraphics.forEach((graphic, index) => {
                    const localProgress = duration === 0
                        ? 1
                        : Math.max(0, Math.min(1, (elapsed - Math.min(index * 5, 240)) / duration));
                    const scale = springProgress(localProgress);
                    const alpha = Math.max(0, Math.min(1, localProgress * 1.25));
                    graphic.symbol = poiSymbol(graphic.attributes, Math.max(1, baseSizes.get(graphic) * scale), alpha);
                    if (localProgress < 1) active = true;
                });
                if (active) requestAnimationFrame(paint);
            };
            requestAnimationFrame(paint);
        };

        const fetchPoiJson = async (params, signal) => {
            const response = await fetch(`${POI_MAP_SERVICE_URL}/${POI_LAYER_ID}/query?${params}`, { signal });
            if (!response.ok) throw new Error(`POI 查询失败：HTTP ${response.status}`);
            const payload = await response.json();
            if (payload?.error) throw new Error(payload.error.message || "POI 查询失败");
            return payload;
        };

        const samplePoiObjectIds = (objectIds, limit) => {
            const uniqueIds = [...new Set(Array.isArray(objectIds) ? objectIds : [])];
            if (uniqueIds.length <= limit) return uniqueIds;
            return Array.from({ length: limit }, (_, index) => (
                uniqueIds[Math.floor(index * uniqueIds.length / limit)]
            ));
        };

        const queryPoiGroupObjectIds = async (categoryCode, districtName, signal) => {
            const districtLiteral = districtName.replaceAll("'", "''");
            const params = new URLSearchParams({
                f: "json",
                where: `${buildPoiWhere([categoryCode])} AND district_name = '${districtLiteral}'`,
                returnIdsOnly: "true"
            });
            const payload = await fetchPoiJson(params, signal);
            return {
                categoryCode,
                districtName,
                objectIds: Array.isArray(payload?.objectIds) ? payload.objectIds : []
            };
        };

        const queryPoiFeatureBatch = async (objectIds, signal) => {
            const params = new URLSearchParams({
                f: "json",
                objectIds: objectIds.join(","),
                outFields: POI_OUT_FIELDS.join(","),
                returnGeometry: "true",
                outSR: "4326"
            });
            const payload = await fetchPoiJson(params, signal);
            return Array.isArray(payload?.features) ? payload.features : [];
        };

        const queryPoiFeatures = async (categoryCodes, districtNames, signal) => {
            const categories = [...new Set(categoryCodes)].filter((code) => POI_CATEGORY_BY_CODE.has(code));
            const districts = [...new Set(districtNames)].filter((district) => POI_TARGET_DISTRICTS.includes(district));
            const groupDescriptors = categories.flatMap((categoryCode) => (
                districts.map((districtName) => ({ categoryCode, districtName }))
            ));
            const groupDisplayLimit = Math.min(
                POI_GROUP_DISPLAY_LIMIT,
                Math.max(1, Math.floor(POI_TOTAL_DISPLAY_LIMIT / Math.max(1, groupDescriptors.length)))
            );
            const groups = [];
            for (let index = 0; index < groupDescriptors.length; index += 6) {
                const batch = groupDescriptors.slice(index, index + 6);
                groups.push(...await Promise.all(batch.map(({ categoryCode, districtName }) => (
                    queryPoiGroupObjectIds(categoryCode, districtName, signal)
                ))));
            }
            const sampledObjectIds = [...new Set(groups.flatMap((group) => (
                samplePoiObjectIds(group.objectIds, groupDisplayLimit)
            )))];
            const features = [];
            for (let index = 0; index < sampledObjectIds.length; index += POI_OBJECT_ID_BATCH_SIZE) {
                features.push(...await queryPoiFeatureBatch(
                    sampledObjectIds.slice(index, index + POI_OBJECT_ID_BATCH_SIZE),
                    signal
                ));
            }
            return {
                features,
                totalAvailable: groups.reduce((total, group) => total + group.objectIds.length, 0),
                sampled: sampledObjectIds.length,
                groupDisplayLimit
            };
        };

        const refreshPoiGraphics = async (
            categoryCodes = selectedPoiCategories,
            districtNames = selectedPoiDistricts
        ) => {
            selectedPoiCategories = [...categoryCodes];
            selectedPoiDistricts = [...new Set(districtNames)].filter((district) => POI_TARGET_DISTRICTS.includes(district));
            const token = ++poiRefreshToken;
            poiRequestController?.abort();
            poiRequestController = null;
            poiGraphicLayer.removeAll();
            if (!selectedPoiCategories.length || !selectedPoiDistricts.length) {
                window.userMapDebug.poiState = {
                    categories: [...selectedPoiCategories],
                    districts: [...selectedPoiDistricts],
                    rendered: 0,
                    truncated: false
                };
                return;
            }

            const controller = new AbortController();
            poiRequestController = controller;
            try {
                const queryResult = await queryPoiFeatures(
                    selectedPoiCategories,
                    selectedPoiDistricts,
                    controller.signal
                );
                if (token !== poiRefreshToken || controller.signal.aborted) return;
                const graphics = queryResult.features.map((feature) => {
                    const geometry = geometryJsonUtils.fromJSON(feature.geometry);
                    if (!geometry || geometry.type !== "point") return null;
                    const attributes = feature.attributes || {};
                    return new Graphic({ geometry, symbol: poiSymbol(attributes, 1, 0), attributes });
                }).filter(Boolean);
                poiGraphicLayer.addMany(graphics);
                animatePoiEntrance(graphics);
                window.userMapDebug.poiState = {
                    categories: [...selectedPoiCategories],
                    rendered: graphics.length,
                    totalAvailable: queryResult.totalAvailable,
                    sampled: queryResult.sampled,
                    groupDisplayLimit: queryResult.groupDisplayLimit,
                    districts: [...selectedPoiDistricts],
                    truncated: queryResult.sampled < queryResult.totalAvailable
                };
            } catch (error) {
                if (error?.name !== "AbortError") console.warn("POI 点位加载失败", error);
            } finally {
                if (poiRequestController === controller) poiRequestController = null;
            }
        };

        const poiFilterHandler = (event) => {
            const categories = Array.isArray(event.detail?.categories) ? event.detail.categories : [];
            const districts = Array.isArray(event.detail?.districts) ? event.detail.districts : POI_TARGET_DISTRICTS;
            closePoiFeaturePopup();
            selectedFeatureLayer.removeAll();
            refreshPoiGraphics(categories, districts);
        };
        window.addEventListener("poi-filter-change", poiFilterHandler);
        const poiNavigationHandle = view.watch("stationary", (stationary) => {
            if (!stationary && !document.getElementById("poiFeaturePopup")?.hidden) {
                closePoiFeaturePopup();
                selectedFeatureLayer.removeAll();
            }
        });

        const agentSymbol = (geometryType) => geometryType === "polyline"
            ? {
                type: "simple-line",
                color: [68, 182, 232, 0.96],
                width: 6
            }
            : {
                type: "simple-marker",
                style: "circle",
                color: [73, 188, 235, 0.96],
                size: 16,
                outline: { color: [238, 251, 255, 1], width: 3 }
            };

        const bufferSymbol = {
            type: "simple-fill",
            color: [68, 182, 232, 0.16],
            outline: {
                color: [68, 182, 232, 0.72],
                width: 1.25
            }
        };

        const setSelectionHighlight = (graphic, layerId) => {
            selectedFeatureLayer.removeAll();
            if (!graphic?.geometry || layerId == null) {
                return;
            }
            const symbol = graphic.geometry.type === "polyline"
                ? {
                    type: "simple-line",
                    color: [76, 195, 241, 0.98],
                    width: 8
                }
                : {
                    type: "simple-marker",
                    style: "circle",
                    color: [73, 188, 235, 0.98],
                    size: 19,
                    outline: { color: [238, 251, 255, 1], width: 4 }
                };
            selectedFeatureLayer.add(new Graphic({
                geometry: graphic.geometry,
                symbol,
                attributes: { __selectedLayerId: layerId }
            }));
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
                selectedFeatureLayer.removeAll();
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

        const agentMapFocusHandler = (event) => {
            const featureId = event.detail?.featureId;
            if (!featureId) {
                return;
            }
            const graphic = [agentHousingLayer, agentRoadLayer]
                .flatMap((layer) => layer.graphics.toArray())
                .find((item) => item.attributes?.__agentFeatureId === featureId);
            if (!graphic?.geometry) {
                return;
            }
            setSelectionHighlight(graphic, graphic.attributes?.__agentLayerId);
            view.goTo({ target: graphic.geometry, zoom: 16 }, {
                animate: true,
                duration: 420
            }).then(() => {
                if (graphic.geometry.type !== "point") {
                    return;
                }
                const screenPoint = view.toScreen(graphic.geometry);
                if (screenPoint) {
                    openPointFeaturePopup(mapContainer, graphic.attributes, screenPoint, view);
                }
            }).catch((error) => console.warn("地图定位失败", error));
        };
        window.addEventListener("agent-map-focus-feature", agentMapFocusHandler);

        (async () => {
            try {
                await view.when();
                mapContainer.classList.add("is-loaded");
                removeMapOverlays();

                await Promise.all([
                    imageryBasemapLayer.when(),
                    streetBasemapLayer.when(),
                    residenceMapLayer.when(),
                    ...residencePointLayers.map((layer) => layer.when())
                ]);
                validateMapSublayers(residenceMapLayer);

                residenceMapLayer.allSublayers.forEach((sublayer) => {
                    const isResidencePointLayer = POINT_LAYER_IDS.includes(sublayer.id);
                    sublayer.visible = !isResidencePointLayer;
                    sublayer.popupEnabled = !isResidencePointLayer;
                    sublayer.popupTemplate = {
                        title: `${sublayer.title} 要素详情`,
                        outFields: ["*"]
                    };
                });

                installMapClickHandler(view, mapContainer, {
                    buffer: agentBufferLayer,
                    road: agentRoadLayer,
                    housing: agentHousingLayer,
                    poi: poiGraphicLayer
                }, setSelectionHighlight);

                await Promise.all([
                    view.whenLayerView(imageryBasemapLayer),
                    view.whenLayerView(streetBasemapLayer),
                    view.whenLayerView(residenceMapLayer),
                    ...residencePointLayers.map((layer) => view.whenLayerView(layer))
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

                console.info("GeoScene 底图与业务要素初始化完成", {
                    imageryBasemapLayer,
                    streetBasemapLayer,
                    residenceMapLayer,
                    view
                });
            } catch (error) {
                console.error("GeoScene 底图与业务要素初始化失败:", error);
            }
        })();

        window.addEventListener("pagehide", () => {
            window.removeEventListener("agent-map-result", agentMapResultHandler);
            window.removeEventListener("agent-map-focus-feature", agentMapFocusHandler);
            window.removeEventListener("poi-filter-change", poiFilterHandler);
            window.removeEventListener("slope-mode-change", slopeModeHandler);
            poiNavigationHandle?.remove();
            poiRequestController?.abort();
            view.destroy();
        }, { once: true });
    });
}

function initUserApp() {
    if (!window.Vue) {
        console.error("Vue 未加载，用户页面交互无法初始化");
        return;
    }

    const { createApp, markRaw } = window.Vue;

    createApp({
        data() {
            return {
                assistantCollapsed: true,
                explorerCollapsed: false,
                explorerTab: "filters",
                chatInput: "",
                chatMessages: [],
                currentUser: null,
                districtOptions: [
                    { value: "中山区", label: "中山区" },
                    { value: "西岗区", label: "西岗区" },
                    { value: "沙河口区", label: "沙河口区" }
                ],
                poiCategories: POI_CATEGORIES,
                poiDistricts: POI_TARGET_DISTRICTS,
                selectedPoiCategories: [],
                selectedPoiDistricts: [...POI_TARGET_DISTRICTS],
                appliedPoiCategories: [],
                appliedPoiDistricts: [...POI_TARGET_DISTRICTS],
                poiPanelExpanded: false,
                slopeLegendItems: SLOPE_WALKABILITY_LEVELS,
                slopeModeActive: false,
                slopeModeStatus: "initializing",
                slopeModeError: "",
                filterForm: createDefaultHousingFilters(),
                filterError: "",
                housingNameQuery: "",
                housingNameSearchError: "",
                housingNameSearchRunning: false,
                housingNameSearchController: null,
                hasResultResponse: false,
                housingResults: [],
                lastQuerySummary: "",
                lastFilterLabels: [],
                resultWarnings: [],
                resultStats: { housing: 0, roads: 0, buffers: 0, housingLabel: "住宅点位" },
                conversationId: "",
                activeMessageId: null,
                activeRunId: null,
                activeRequestSource: null,
                activeAbortController: null,
                assistantRunning: false,
                speechRecognition: null,
                speechRecognitionSupported: false,
                speechRecognitionUnavailableReason: "",
                speechListening: false,
                speechStatusMessage: "",
                speechStatusTimer: null,
                speechDraftPrefix: "",
                speechFinalTranscript: "",
                speechCurrentTranscript: "",
                speechRecognitionErrorCode: "",
                hasAgentMapResults: false,
                lastSequenceByRun: {},
                mapResultAppliedHandler: null,
                layoutChangeHandler: null,
                slopeModeStateHandler: null
            };
        },
        computed: {
            filterRunning() {
                return this.assistantRunning && this.activeRequestSource === "filter-panel";
            },
            filterBusy() {
                return this.filterRunning || this.housingNameSearchRunning;
            },
            speechButtonLabel() {
                if (!this.speechRecognitionSupported) {
                    return this.speechRecognitionUnavailableReason || "当前浏览器不支持语音输入";
                }
                return this.speechListening ? "停止语音识别" : "开始语音输入";
            },
            poiSelectionStatus() {
                if (!this.appliedPoiCategories.length || !this.appliedPoiDistricts.length) return "暂未显示";
                const district = this.appliedPoiDistricts.length === this.poiDistricts.length
                    ? "全部区域"
                    : `${this.appliedPoiDistricts.length} 个区域`;
                const category = this.appliedPoiCategories.length === this.poiCategories.length
                    ? "全部类别"
                    : `${this.appliedPoiCategories.length} 类设施`;
                return `${district} · ${category}`;
            },
            poiDistrictSelectionLabel() {
                if (!this.selectedPoiDistricts.length) return "请选择区域";
                if (this.selectedPoiDistricts.length === this.poiDistricts.length) return "全部区域";
                if (this.selectedPoiDistricts.length === 1) return this.selectedPoiDistricts[0];
                return `已选 ${this.selectedPoiDistricts.length} 个区域`;
            },
            poiCategorySelectionLabel() {
                if (!this.selectedPoiCategories.length) return "请选择设施";
                if (this.selectedPoiCategories.length === this.poiCategories.length) return "全部类别";
                if (this.selectedPoiCategories.length === 1) {
                    return this.poiCategories.find((item) => item.code === this.selectedPoiCategories[0])?.label || "1 类设施";
                }
                return `已选 ${this.selectedPoiCategories.length} 类设施`;
            },
            allPoiDistrictsSelected() {
                return this.selectedPoiDistricts.length === this.poiDistricts.length;
            },
            allPoiCategoriesSelected() {
                return this.selectedPoiCategories.length === this.poiCategories.length;
            },
            canApplyPoiFilters() {
                return this.selectedPoiDistricts.length > 0 && this.selectedPoiCategories.length > 0;
            },
            hasAppliedPoiFilters() {
                return this.appliedPoiDistricts.length > 0 && this.appliedPoiCategories.length > 0;
            }
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
            this.layoutChangeHandler = () => {
                if (window.matchMedia("(max-width: 620px)").matches
                        && !this.assistantCollapsed && !this.explorerCollapsed) {
                    this.explorerCollapsed = true;
                }
            };
            window.addEventListener("resize", this.layoutChangeHandler, { passive: true });
            this.slopeModeStateHandler = (event) => {
                this.slopeModeStatus = event.detail?.status || "ready";
                this.slopeModeActive = Boolean(event.detail?.active);
                this.slopeModeError = event.detail?.message || "";
            };
            window.addEventListener("slope-mode-state", this.slopeModeStateHandler);
            this.layoutChangeHandler();
            this.setupSpeechRecognition();
        },
        beforeUnmount() {
            if (this.mapResultAppliedHandler) {
                window.removeEventListener("agent-map-result-applied", this.mapResultAppliedHandler);
            }
            if (this.layoutChangeHandler) {
                window.removeEventListener("resize", this.layoutChangeHandler);
            }
            if (this.slopeModeStateHandler) {
                window.removeEventListener("slope-mode-state", this.slopeModeStateHandler);
            }
            this.activeAbortController?.abort();
            this.housingNameSearchController?.abort();
            this.destroySpeechRecognition();
        },
        methods: {
            formatTime() {
                return new Date().toLocaleTimeString("zh-CN", {
                    hour: "2-digit",
                    minute: "2-digit"
                });
            },

            toggleAssistant() {
                if (this.assistantCollapsed) {
                    this.openAssistant();
                    return;
                }
                if (this.speechListening) {
                    this.stopSpeechRecognition();
                }
                this.assistantCollapsed = true;
            },

            openAssistant() {
                this.assistantCollapsed = false;
                if (window.matchMedia("(max-width: 620px)").matches) {
                    this.explorerCollapsed = true;
                }
            },

            toggleExplorer() {
                const willExpand = this.explorerCollapsed;
                this.explorerCollapsed = !this.explorerCollapsed;
                if (willExpand && window.matchMedia("(max-width: 620px)").matches) {
                    if (this.speechListening) {
                        this.stopSpeechRecognition();
                    }
                    this.assistantCollapsed = true;
                }
            },

            togglePoiPanel() {
                this.poiPanelExpanded = !this.poiPanelExpanded;
                if (this.poiPanelExpanded && window.matchMedia("(max-width: 620px)").matches) {
                    this.explorerCollapsed = true;
                    this.assistantCollapsed = true;
                }
            },

            toggleSlopeMode() {
                if (this.slopeModeStatus === "loading") return;
                this.slopeModeError = "";
                window.dispatchEvent(new CustomEvent("slope-mode-change", {
                    detail: { active: !this.slopeModeActive }
                }));
            },

            toggleAllPoiDistricts() {
                this.selectedPoiDistricts = this.allPoiDistrictsSelected ? [] : [...this.poiDistricts];
            },

            toggleAllPoiCategories() {
                this.selectedPoiCategories = this.allPoiCategoriesSelected
                    ? []
                    : this.poiCategories.map((category) => category.code);
            },

            closePoiDropdowns() {
                document.querySelectorAll("#topPoiControls details[open]").forEach((details) => {
                    details.removeAttribute("open");
                });
            },

            applyPoiFilters() {
                if (!this.canApplyPoiFilters) return;
                const categories = [...new Set(this.selectedPoiCategories)];
                const districts = [...new Set(this.selectedPoiDistricts)];
                this.appliedPoiCategories = [...categories];
                this.appliedPoiDistricts = [...districts];
                window.dispatchEvent(new CustomEvent("poi-filter-change", {
                    detail: { categories, districts }
                }));
                this.closePoiDropdowns();
            },

            clearPoiDisplay() {
                this.selectedPoiCategories = [];
                this.selectedPoiDistricts = [...this.poiDistricts];
                this.appliedPoiCategories = [];
                this.appliedPoiDistricts = [...this.poiDistricts];
                window.dispatchEvent(new CustomEvent("poi-filter-change", {
                    detail: { categories: [], districts: [...this.poiDistricts] }
                }));
                this.closePoiDropdowns();
            },

            setupSpeechRecognition() {
                const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
                if (!SpeechRecognition) {
                    this.speechRecognitionUnavailableReason = "当前浏览器不支持语音输入，请使用 Chrome、Edge 或 Safari";
                    return;
                }
                if (!window.isSecureContext) {
                    this.speechRecognitionUnavailableReason = "语音输入需要在 HTTPS 或本机地址中使用";
                    return;
                }

                try {
                    const recognition = new SpeechRecognition();
                    recognition.lang = "zh-CN";
                    recognition.continuous = false;
                    recognition.interimResults = true;
                    recognition.maxAlternatives = 1;
                    recognition.onstart = () => {
                        this.speechListening = true;
                        this.setSpeechStatus("正在聆听，请开始说话");
                    };
                    recognition.onresult = (event) => {
                        const finalParts = [];
                        const interimParts = [];
                        for (let index = event.resultIndex; index < event.results.length; index += 1) {
                            const result = event.results[index];
                            const transcript = result[0]?.transcript?.trim();
                            if (!transcript) {
                                continue;
                            }
                            if (result.isFinal) {
                                finalParts.push(transcript);
                            } else {
                                interimParts.push(transcript);
                            }
                        }
                        if (finalParts.length) {
                            this.speechFinalTranscript = [this.speechFinalTranscript, finalParts.join(" ")]
                                .filter(Boolean)
                                .join(" ")
                                .trim();
                        }
                        const interimTranscript = interimParts.join(" ").trim();
                        this.speechCurrentTranscript = [this.speechFinalTranscript, interimTranscript]
                            .filter(Boolean)
                            .join(" ")
                            .trim();
                        this.chatInput = [this.speechDraftPrefix, this.speechCurrentTranscript]
                            .filter(Boolean)
                            .join(" ")
                            .trim();
                        this.setSpeechStatus(interimTranscript ? "正在识别您的话" : "识别完成，正在整理文字");
                    };
                    recognition.onerror = (event) => {
                        const errorCode = event.error || "unknown";
                        this.speechRecognitionErrorCode = errorCode;
                        this.speechListening = false;
                        const errorMessages = {
                            "not-allowed": "未获得麦克风权限，请在浏览器设置中允许后重试",
                            "service-not-allowed": "浏览器已禁止语音识别服务",
                            "audio-capture": "未检测到可用麦克风",
                            "no-speech": "没有听清，请再试一次",
                            network: "语音识别服务连接失败，请检查网络",
                            "language-not-supported": "当前浏览器暂不支持中文语音识别",
                            aborted: "语音识别已停止"
                        };
                        this.setSpeechStatus(errorMessages[errorCode] || "语音识别暂时不可用，请稍后重试", 5000);
                    };
                    recognition.onend = () => {
                        this.speechListening = false;
                        if (this.speechRecognitionErrorCode) {
                            return;
                        }
                        if (this.speechCurrentTranscript) {
                            this.setSpeechStatus("已填入识别内容，请确认后发送", 5000);
                            this.$nextTick(() => this.$refs.chatInput?.focus());
                            return;
                        }
                        this.setSpeechStatus("没有听清，请再试一次", 4000);
                    };
                    this.speechRecognition = markRaw(recognition);
                    this.speechRecognitionSupported = true;
                } catch (error) {
                    console.warn("浏览器语音识别初始化失败", error);
                    this.speechRecognitionUnavailableReason = "语音输入初始化失败，请刷新页面后重试";
                }
            },

            toggleSpeechRecognition() {
                if (this.speechListening) {
                    this.stopSpeechRecognition();
                    return;
                }
                this.startSpeechRecognition();
            },

            startSpeechRecognition() {
                if (!this.speechRecognitionSupported || !this.speechRecognition || this.assistantRunning) {
                    return;
                }
                this.clearSpeechStatusTimer();
                this.speechDraftPrefix = this.chatInput.trim();
                this.speechFinalTranscript = "";
                this.speechCurrentTranscript = "";
                this.speechRecognitionErrorCode = "";
                try {
                    this.speechRecognition.start();
                } catch (error) {
                    if (error?.name !== "InvalidStateError") {
                        console.warn("启动语音识别失败", error);
                        this.setSpeechStatus("暂时无法启动语音识别，请稍后重试", 5000);
                    }
                }
            },

            stopSpeechRecognition() {
                if (!this.speechRecognition || !this.speechListening) {
                    return;
                }
                this.setSpeechStatus("正在结束识别");
                try {
                    this.speechRecognition.stop();
                } catch (error) {
                    console.warn("停止语音识别失败", error);
                }
            },

            setSpeechStatus(message, clearAfter = 0) {
                this.clearSpeechStatusTimer();
                this.speechStatusMessage = message;
                if (clearAfter > 0) {
                    this.speechStatusTimer = window.setTimeout(() => {
                        this.speechStatusMessage = "";
                        this.speechStatusTimer = null;
                    }, clearAfter);
                }
            },

            clearSpeechStatusTimer() {
                if (this.speechStatusTimer) {
                    window.clearTimeout(this.speechStatusTimer);
                    this.speechStatusTimer = null;
                }
            },

            destroySpeechRecognition() {
                this.clearSpeechStatusTimer();
                if (!this.speechRecognition) {
                    return;
                }
                try {
                    this.speechRecognition.abort();
                } catch (error) {
                    console.warn("释放语音识别实例失败", error);
                }
                this.speechRecognition.onstart = null;
                this.speechRecognition.onresult = null;
                this.speechRecognition.onerror = null;
                this.speechRecognition.onend = null;
                this.speechRecognition = null;
                this.speechListening = false;
            },

            useSuggestion(value) {
                this.chatInput = value;
                this.openAssistant();
            },

            goToProfile() {
                window.location.href = "/profile.html";
            },

            async sendMessage() {
                if (this.speechListening) {
                    return;
                }
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

            async submitRequirement(message, source, options = {}) {
                if (this.assistantRunning) {
                    this.cancelActiveRun(false);
                }
                if (options.openAssistant !== false) {
                    this.openAssistant();
                }
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
                this.activeRequestSource = source;
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
                        if (source === "filter-panel") {
                            this.filterError = error.message || "筛选服务暂时不可用，请稍后重试。";
                            this.explorerTab = "filters";
                        }
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
                        this.activeRequestSource = null;
                    }
                    this.scrollChatToEnd();
                }
            },

            agentRequestContext() {
                const view = window.userMapDebug?.view;
                const visibleMapImageLayerIds = window.userMapDebug?.residenceMapLayer?.allSublayers
                    ?.filter((layer) => layer.visible)
                    .map((layer) => layer.id) || [...EXPECTED_MAP_SUBLAYERS.keys()];
                const visiblePointLayerIds = (window.userMapDebug?.residencePointLayers || [])
                    .filter((layer) => layer.visible)
                    .map((layer) => Number(String(layer.id).slice(HOUSING_POINT_LAYER_ID_PREFIX.length)))
                    .filter((layerId) => POINT_LAYER_IDS.includes(layerId));
                const visibleLayerIds = [...new Set([...visiblePointLayerIds, ...visibleMapImageLayerIds])];
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
                        this.captureHousingResults(payload);
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
                        // Protocol warnings remain available in the result panel and diagnostics.
                        // The chat bubble only contains the user-facing answer.
                        assistantMessage.warnings = [];
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
                        if (this.activeRequestSource === "filter-panel") {
                            this.filterError = assistantMessage.content;
                            this.explorerTab = "filters";
                        }
                        return true;
                    default:
                        break;
                }
                return false;
            },

            async searchHousingByName() {
                const keyword = String(this.housingNameQuery || "").trim().replace(/\s+/g, " ");
                this.housingNameSearchError = "";
                if (!keyword) {
                    this.housingNameSearchError = "请输入小区名称。";
                    return;
                }
                if (this.filterRunning) {
                    this.housingNameSearchError = "当前筛选尚未完成，请稍后再搜索。";
                    return;
                }
                const layerIds = [...new Set((this.filterForm.districts || [])
                    .map((district) => HOUSING_LAYER_ID_BY_DISTRICT[district])
                    .filter((layerId) => POINT_LAYER_IDS.includes(layerId)))];
                if (layerIds.length === 0) {
                    this.housingNameSearchError = "请至少选择一个行政区。";
                    return;
                }

                this.housingNameSearchController?.abort();
                const controller = new AbortController();
                this.housingNameSearchController = controller;
                this.housingNameSearchRunning = true;
                this.filterError = "";
                this.explorerTab = "info";
                try {
                    const queryResults = await Promise.all(layerIds.map((layerId) => fetchMapFeature({
                        layerId,
                        filters: [{ field: "name", operator: "like", value: keyword }],
                        outFields: ["*"],
                        returnGeometry: true,
                        resultRecordCount: 50,
                        resultOffset: 0,
                        returnCount: true
                    }, controller.signal)));
                    if (controller.signal.aborted) return;

                    const resultSets = queryResults.map((result) => ({
                        role: "PRIMARY_RESULTS",
                        layerId: result.layerId,
                        layerName: result.layerName,
                        geometryType: "point",
                        total: result.total,
                        exceededTransferLimit: Boolean(result.exceededTransferLimit),
                        features: (result.features || []).map((feature, index) => {
                            const attributes = feature.attributes || {};
                            const objectId = getGraphicObjectId(attributes)?.value ?? attributes.id ?? index;
                            return {
                                id: `${result.layerId}:${objectId}`,
                                geometry: feature.geometry,
                                attributes
                            };
                        })
                    }));
                    const total = resultSets.reduce((sum, resultSet) => sum + (Number(resultSet.total) || 0), 0);
                    const rendered = resultSets.reduce((sum, resultSet) => sum + resultSet.features.length, 0);
                    const payload = {
                        mode: "replace",
                        querySummary: total > rendered
                            ? `找到 ${total} 个名称包含“${keyword}”的小区，显示前 ${rendered} 个`
                            : `找到 ${total} 个名称包含“${keyword}”的小区`,
                        resultSets,
                        display: {
                            fitBounds: rendered > 0,
                            maxZoom: 17,
                            paddingPx: 64
                        }
                    };
                    this.lastFilterLabels = [
                        `名称包含“${keyword}”`,
                        this.filterForm.districts.join("、")
                    ];
                    this.captureHousingResults(payload);
                    window.dispatchEvent(new CustomEvent("agent-map-result", { detail: payload }));
                } catch (error) {
                    if (error?.name !== "AbortError") {
                        console.error("小区名称搜索失败", error);
                        this.housingNameSearchError = error.message || "小区搜索暂时不可用，请稍后重试。";
                        this.explorerTab = "filters";
                    }
                } finally {
                    if (this.housingNameSearchController === controller) {
                        this.housingNameSearchController = null;
                        this.housingNameSearchRunning = false;
                    }
                }
            },

            async submitFilters() {
                if (this.housingNameSearchRunning) return;
                this.filterError = "";
                const districts = this.filterForm.districts;
                const priceMin = Number(this.filterForm.priceMin);
                const priceMax = Number(this.filterForm.priceMax);
                if (!Array.isArray(districts) || districts.length === 0) {
                    this.filterError = "请至少选择一个行政区。";
                    return;
                }
                if (this.filterForm.priceMin !== null && this.filterForm.priceMin !== ""
                        && (!Number.isFinite(priceMin) || priceMin < 0)) {
                    this.filterError = "最低房价需要填写有效的非负数值。";
                    return;
                }
                if (this.filterForm.priceMax !== null && this.filterForm.priceMax !== ""
                        && (!Number.isFinite(priceMax) || priceMax < 0)) {
                    this.filterError = "最高房价需要填写有效的非负数值。";
                    return;
                }
                if (Number.isFinite(priceMin) && Number.isFinite(priceMax)
                        && this.filterForm.priceMin !== null && this.filterForm.priceMin !== ""
                        && this.filterForm.priceMax !== null && this.filterForm.priceMax !== ""
                        && priceMin > priceMax) {
                    this.filterError = "最低房价不能高于最高房价。";
                    return;
                }
                this.lastFilterLabels = this.buildFilterLabels();
                this.explorerTab = "info";
                await this.submitRequirement(this.buildFilterPrompt(), "filter-panel", { openAssistant: false });
            },

            buildFilterPrompt() {
                const form = this.filterForm;
                const parts = [`在${form.districts.join("、")}范围内筛选住宅`];
                if (form.priceMin !== null && form.priceMin !== "") {
                    parts.push(`房价不低于 ${Number(form.priceMin)} 元/平方米`);
                }
                if (form.priceMax !== null && form.priceMax !== "") {
                    parts.push(`房价不高于 ${Number(form.priceMax)} 元/平方米`);
                }
                const preferenceText = {
                    PREFER_HIGH: "优先选择便利度较高的小区",
                    HIGH: "小区便利度需达到当前区域前 25%",
                    VERY_HIGH: "小区便利度需达到当前区域前 10%"
                };
                if (preferenceText[form.convenience]) {
                    parts.push(preferenceText[form.convenience]);
                }
                if (form.roadWalkability === "HIGH" || form.roadWalkability === "VERY_HIGH") {
                    const percentile = form.roadWalkability === "VERY_HIGH" ? "前 10%" : "前 25%";
                    parts.push(`住宅需位于道路步行指数达到当前区域${percentile}的道路 ${form.bufferMeters} 米范围内`);
                } else if (form.roadWalkability === "PREFER_HIGH") {
                    parts.push(`优先选择周边 ${form.bufferMeters} 米内道路步行指数较高的住宅`);
                }
                if (form.convenience === "NONE" && form.roadWalkability === "NONE") {
                    parts.push("在符合条件的小区中优先选择房价较低者");
                }
                parts.push(`最多返回 ${form.limit} 个小区`);
                parts.push("在地图上显示候选住宅、相关道路和道路缓冲范围");
                return `请使用现有住宅候选搜索工具，${parts.join("，")}。不要自行放宽条件。`;
            },

            buildFilterLabels() {
                const labels = [this.filterForm.districts.join("、")];
                if (this.filterForm.priceMin !== null && this.filterForm.priceMin !== "") {
                    labels.push(`房价 ≥ ${this.formatNumber(this.filterForm.priceMin)} 元/㎡`);
                }
                if (this.filterForm.priceMax !== null && this.filterForm.priceMax !== "") {
                    labels.push(`房价 ≤ ${this.formatNumber(this.filterForm.priceMax)} 元/㎡`);
                }
                const levelLabels = {
                    PREFER_HIGH: "优先较高",
                    HIGH: "前 25%",
                    VERY_HIGH: "前 10%"
                };
                if (levelLabels[this.filterForm.convenience]) {
                    labels.push(`便利度 ${levelLabels[this.filterForm.convenience]}`);
                }
                if (levelLabels[this.filterForm.roadWalkability]) {
                    labels.push(`步行指数 ${levelLabels[this.filterForm.roadWalkability]}`);
                }
                if (this.filterForm.convenience === "NONE"
                        && this.filterForm.roadWalkability === "NONE") {
                    labels.push("优先低房价");
                }
                labels.push(`道路范围 ${this.filterForm.bufferMeters} 米`);
                return labels;
            },

            resetFilters() {
                this.housingNameSearchController?.abort();
                this.housingNameSearchController = null;
                this.housingNameSearchRunning = false;
                this.housingNameQuery = "";
                this.housingNameSearchError = "";
                this.filterForm = createDefaultHousingFilters();
                this.filterError = "";
                this.clearAgentResults();
                this.explorerTab = "filters";
            },

            captureHousingResults(payload) {
                const resultSets = Array.isArray(payload?.resultSets) ? payload.resultSets : [];
                const housingSets = resultSets.filter((resultSet) => resultSet.role === "HOUSING_CANDIDATES");
                const primaryPointSets = resultSets.filter((resultSet) => (
                    resultSet.role === "PRIMARY_RESULTS" && resultSet.geometryType === "point"
                ));
                const roadSets = resultSets.filter((resultSet) => (
                    resultSet.role === "CONTRIBUTING_ROADS"
                        || (resultSet.role === "PRIMARY_RESULTS" && resultSet.geometryType === "polyline")
                ));
                const displayedHousingSets = [...housingSets, ...primaryPointSets];
                this.housingResults = displayedHousingSets.flatMap((resultSet) => (
                    (resultSet.features || []).map((feature) => ({
                        ...feature,
                        layerId: resultSet.layerId,
                        attributes: feature?.attributes || {},
                        scores: feature?.attributes?.scores || feature?.scores || {}
                    }))
                ));
                this.resultStats = {
                    housing: displayedHousingSets.reduce(
                        (sum, resultSet) => sum + (Number(resultSet.total) || 0),
                        0
                    ),
                    roads: roadSets.reduce((sum, resultSet) => sum + (Number(resultSet.total) || 0), 0),
                    buffers: Array.isArray(payload?.overlays) ? payload.overlays.length : 0,
                    housingLabel: housingSets.length ? "匹配小区" : "住宅点位"
                };
                this.lastQuerySummary = payload?.querySummary || "";
                const actionableWarnings = new Set([
                    "HOUSING_RESULT_TRUNCATED",
                    "ROAD_RESULT_TRUNCATED",
                    "NO_HOUSING_IN_BUFFER",
                    "MISSING_PRICE_METRIC",
                    "MISSING_CONVENIENCE_METRIC",
                    "MISSING_NEARBY_ROAD_METRIC"
                ]);
                this.resultWarnings = (Array.isArray(payload?.warnings) ? payload.warnings : [])
                    .filter((warning) => actionableWarnings.has(warning));
                this.hasResultResponse = true;
                this.explorerTab = "info";
            },

            housingName(housing) {
                return housing?.attributes?.name || "未命名小区";
            },

            housingLocation(housing) {
                const attributes = housing?.attributes || {};
                return attributes.address || attributes.adname || "地址信息暂缺";
            },

            formatNumber(value) {
                const number = Number(value);
                return Number.isFinite(number)
                    ? new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 }).format(number)
                    : "暂无";
            },

            formatHousingPrice(housing) {
                const price = housing?.attributes?.房价;
                return price == null ? "暂无" : `${this.formatNumber(price)} 元/㎡`;
            },

            housingMetricValue(housing, metric) {
                const attributes = housing?.attributes || {};
                if (metric === "convenience") {
                    return attributes["归一化总分"];
                }
                if (metric === "coverage") {
                    return attributes["覆盖度评分"];
                }
                return null;
            },

            formatHousingScore(housing, metric) {
                const rawValue = this.housingMetricValue(housing, metric);
                if (rawValue == null || rawValue === "") {
                    return "暂无";
                }
                const value = Number(rawValue);
                return Number.isFinite(value) ? `${value.toFixed(1).replace(/\.0$/, "")} 分` : "暂无";
            },

            focusHousing(featureId) {
                const focusMapFeature = () => {
                    window.dispatchEvent(new CustomEvent("agent-map-focus-feature", {
                        detail: { featureId }
                    }));
                };
                if (window.matchMedia("(max-width: 620px)").matches) {
                    this.explorerCollapsed = true;
                    this.$nextTick(focusMapFeature);
                    return;
                }
                focusMapFeature();
            },

            resultWarningLabel(warning) {
                return {
                    DEFAULT_BUFFER_APPLIED: "已使用默认道路范围。",
                    DEFAULT_WEIGHTS_APPLIED: "便利度与步行指数使用默认权重。",
                    HOUSING_RESULT_TRUNCATED: "符合条件的小区较多，当前只显示前部分结果。",
                    ROAD_RESULT_TRUNCATED: "相关道路较多，地图只显示前部分道路。",
                    DISPLAY_GEOMETRY_SIMPLIFIED: "为保证地图流畅，缓冲区边界已适度简化。",
                    NO_HOUSING_IN_BUFFER: "符合道路条件，但范围内没有匹配小区。",
                    MISSING_PRICE_METRIC: "部分小区缺少房价数据。",
                    MISSING_CONVENIENCE_METRIC: "部分小区缺少便利度数据。",
                    MISSING_NEARBY_ROAD_METRIC: "部分小区缺少周边道路数据。"
                }[warning] || warning;
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
                this.activeRequestSource = null;
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
                this.hasResultResponse = false;
                this.housingResults = [];
                this.lastQuerySummary = "";
                this.lastFilterLabels = [];
                this.resultWarnings = [];
                this.resultStats = { housing: 0, roads: 0, buffers: 0, housingLabel: "住宅点位" };
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
                this.openAssistant();
                this.scrollChatToEnd();
            }
        }
    }).mount("#user-app");
}

runWhenDomReady(() => {
    initUserApp();
    initUserMap();
});
