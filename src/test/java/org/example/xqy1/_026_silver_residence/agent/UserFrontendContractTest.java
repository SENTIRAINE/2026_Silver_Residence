package org.example.xqy1._026_silver_residence.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserFrontendContractTest {
    private static final Path USER_JS = Path.of("src", "main", "resources", "static", "JS", "user.js");
    private static final Path USER_HTML = Path.of("src", "main", "resources", "static", "user.html");
    private static final Path USER_CSS = Path.of("src", "main", "resources", "static", "CSS", "user.css");
    private static final Path INDEX_HTML = Path.of("src", "main", "resources", "static", "index.html");
    private static final Path INDEX_CSS = Path.of("src", "main", "resources", "static", "CSS", "style.css");
    private static final Path PROFILE_HTML = Path.of("src", "main", "resources", "static", "profile.html");
    private static final Path PROFILE_CSS = Path.of("src", "main", "resources", "static", "CSS", "profile.css");
    private static final Path PROFILE_JS = Path.of("src", "main", "resources", "static", "JS", "profile.js");
    private static final Path HOUSING_TYPE_2BR = Path.of("src", "main", "resources", "static", "images", "housing-type-2br.png");
    private static final Path HOUSING_TYPE_3BR = Path.of("src", "main", "resources", "static", "images", "housing-type-3br.png");
    private static final Path HOUSING_TYPE_3BR_STUDY = Path.of("src", "main", "resources", "static", "images", "housing-type-3br-study.png");
    private static final Path RENTAL_PHOTO_DIR = Path.of("src", "main", "resources", "static", "photo");

    @Test
    void housingPanelAcceptsCandidateAndPrimaryPointResultSets() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);

        assertTrue(script.contains("resultSet.role === \"HOUSING_CANDIDATES\""));
        assertTrue(script.contains("resultSet.role === \"PRIMARY_RESULTS\" && resultSet.geometryType === \"point\""));
        assertTrue(script.contains("resultSet.role === \"PRIMARY_RESULTS\" && resultSet.geometryType === \"polyline\""));
        assertTrue(script.contains("const displayedHousingSets = [...housingSets, ...primaryPointSets]"));
    }

    @Test
    void housingCardsUseHousingMetricsAndExcludeRoadWalkability() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String template = Files.readString(USER_HTML, StandardCharsets.UTF_8);
        String housingCard = between(template, "<div class=\"housing-data\">", "</article>");

        assertTrue(script.contains("return attributes[\"归一化总分\"]"));
        assertTrue(script.contains("return attributes[\"覆盖度评分\"]"));
        assertTrue(housingCard.contains("<span>房价</span>"));
        assertTrue(housingCard.contains("<span>便利度</span>"));
        assertTrue(housingCard.contains("<span>覆盖评分</span>"));
        assertFalse(housingCard.contains("步行指数"));
    }

    @Test
    void protocolWarningsAreNotRenderedInChat() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String template = Files.readString(USER_HTML, StandardCharsets.UTF_8);

        assertTrue(script.contains("assistantMessage.warnings = []"));
        assertFalse(template.contains("message.warnings"));
        assertFalse(template.contains("chat-warning"));
    }

    @Test
    void housingPointPopupRendersRadarAgainstGlobalFieldMaximums() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String styles = Files.readString(USER_CSS, StandardCharsets.UTF_8);
        String pointPopup = between(script, "function buildPointPopupContent", "function closeLineFeaturePopup");
        String linePopup = between(script, "function buildLinePopupContent", "function buildPointPopupContent");

        assertTrue(script.contains("{ field: \"餐饮密度\", label: \"餐饮\""));
        assertTrue(script.contains("{ field: \"风景密度\", label: \"风景\""));
        assertTrue(script.contains("{ field: \"科教密度\", label: \"科教\""));
        assertTrue(script.contains("{ field: \"购物密度\", label: \"购物\""));
        assertTrue(script.contains("{ field: \"金融密度\", label: \"金融\""));
        assertTrue(script.contains("{ field: \"归一化总分\", label: \"便利度\""));
        assertFalse(script.contains("{ field: \"覆盖度评分\", label: \"覆盖度\""));
        assertTrue(script.contains("statisticType: \"max\""));
        assertTrue(script.contains("value / maximum"));
        assertTrue(script.contains("function formatPointRadarPercent"));
        assertTrue(script.contains("return `${new Intl.NumberFormat(\"zh-CN\", { maximumFractionDigits: 1 }).format(percent)}%`"));
        assertTrue(script.contains("if (value == null || maximum == null || maximum <= 0)"));
        assertTrue(script.contains("const shouldQueryDetails = layerId < 3 || !hasRequiredLinePopupFields(fallbackAttributes)"));
        assertTrue(pointPopup.contains("buildPointRadarContent(attributes)"));
        assertFalse(linePopup.contains("buildPointRadarContent"));
        assertTrue(styles.contains("grid-template-columns: minmax(0, 1fr) 252px"));
        assertTrue(styles.contains(".point-feature-popup__radar"));
    }

    @Test
    void linePopupUsesCurrentRoadGradesAndRawScoreRadar() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String styles = Files.readString(USER_CSS, StandardCharsets.UTF_8);

        assertFalse(script.contains("/api/map/line-regional-stats/${layerId}"));
        assertFalse(script.contains("lineRegionalStatsCache"));
        assertTrue(script.contains("{ field: \"绿视率原始值\", label: \"绿视率\", maximum: 1 }"));
        assertTrue(script.contains("{ field: \"道路噪声原始值\", label: \"道路噪声\", maximum: 100 }"));
        assertTrue(script.contains("{ field: \"WS归一化\", label: \"步行指数\", maximum: 100 }"));
        assertTrue(script.contains("const LINE_GVI_LEVELS = Object.freeze({ 0: \"高\", 1: \"较高\", 3: \"中等\", 5: \"低\" })"));
        assertTrue(script.contains("const LINE_NOI_LEVELS = Object.freeze({ 0: \"低\", 1.25: \"较低\", 2.5: \"中\", 3.75: \"较高\", 5: \"高\" })"));
        assertTrue(script.contains("return formatLineGrade(field, attributes[field])"));
        assertFalse(script.contains("原始分 ${formatPopupValue"));
        assertTrue(script.contains("typeof value === \"string\" && value.trim() === \"\""));
        assertTrue(script.contains("linePopupMetric(\"WS归一化\", \"步行指数\", attributes)"));
        assertTrue(script.contains("\"WS归一化\", \"绿视率原始值\", \"道路噪声原始值\""));
        assertTrue(script.contains("function normalizeLineAttributes(attributes = {})"));
        assertTrue(script.contains("normalized[\"绿视率原始值\"] = normalized.vegetation"));
        assertTrue(script.contains("normalized[\"道路噪声原始值\"] = normalized.noise"));
        assertTrue(script.contains("Object.prototype.hasOwnProperty.call(normalizedAttributes, field)"));
        assertTrue(script.contains("requestAnimationFrame"));
        assertTrue(script.contains("prefers-reduced-motion: reduce"));
        assertTrue(script.contains("popup.dataset.renderToken !== renderToken"));
        assertTrue(script.contains("buildLineRadarContent(attributes)"));
        assertTrue(script.contains("renderLineRadar(popup, attributes)"));
        assertTrue(script.contains("当前道路 · 原始分"));
        assertTrue(styles.contains(".line-feature-popup__radar"));
    }

    @Test
    void topCardHostsSlopeModeAndExpandablePoiControls() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String template = Files.readString(USER_HTML, StandardCharsets.UTF_8);
        String styles = Files.readString(USER_CSS, StandardCharsets.UTF_8);

        assertTrue(template.contains("class=\"regional-road-radar map-tools-card\""));
        assertTrue(template.contains("@click=\"toggleSlopeMode\""));
        assertTrue(template.contains(":aria-pressed=\"slopeModeActive\""));
        assertTrue(template.contains("@click=\"togglePoiPanel\""));
        assertTrue(template.contains("id=\"topPoiControls\""));
        assertTrue(template.contains("v-model=\"selectedPoiDistricts\""));
        assertTrue(template.contains("v-model=\"selectedPoiCategories\""));
        assertTrue(template.contains(":checked=\"allPoiDistrictsSelected\""));
        assertTrue(template.contains("@change=\"toggleAllPoiDistricts\""));
        assertTrue(template.contains(":checked=\"allPoiCategoriesSelected\""));
        assertTrue(template.contains("@change=\"toggleAllPoiCategories\""));
        assertTrue(template.contains("@click=\"applyPoiFilters\">显示</button>"));
        assertTrue(template.contains("class=\"slope-analysis-legends\""));
        assertTrue(template.contains("坡度 DEM"));
        assertTrue(template.contains("坡度由低到高"));
        assertFalse(template.contains("regionalRoadRadarCanvas"));
        assertFalse(template.contains("三区域道路对比"));
        assertFalse(template.contains(":aria-selected=\"explorerTab === 'poi'\""));
        assertFalse(script.contains("REGIONAL_ROAD_RADAR_SERIES"));
        assertFalse(script.contains("initRegionalRoadRadar"));
        assertFalse(script.contains("drawRegionalRoadRadar"));
        assertTrue(styles.contains(".regional-road-radar"));
        assertTrue(styles.contains("min-height: 78px"));
        assertTrue(styles.contains(".top-poi-controls__fields"));
        assertTrue(styles.contains("min-height: 48px"));
        assertTrue(styles.contains("left: 380px"));
        assertTrue(styles.contains("--spring-ease: cubic-bezier(0.22, 1.18, 0.36, 1)"));
        assertTrue(styles.contains(".explorer-panel.is-collapsed"));
        assertTrue(styles.contains(".assistant-panel.is-collapsed .assistant-card"));
    }

    @Test
    void slopeModeUsesDemAndFiveFixedWalkabilityLevelsWithoutChangingView() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String slopeMode = between(script, "const setSlopeMode = async", "let selectedPoiCategories");

        assertTrue(script.contains("const SLOPE_MAP_SERVICE_URL = \"/api/map/slope\""));
        assertTrue(script.contains("const SLOPE_DEM_LAYER_ID = 3"));
        assertTrue(script.contains("serviceLayerId: 0, mapLayerId: 4, title: \"XiGang\""));
        assertTrue(script.contains("serviceLayerId: 1, mapLayerId: 5, title: \"ShaHeKou\""));
        assertTrue(script.contains("serviceLayerId: 2, mapLayerId: 3, title: \"ZhongShan\""));
        assertTrue(script.contains("\"geoscene/layers/FeatureLayer\""));
        assertTrue(script.contains("{ id: SLOPE_DEM_LAYER_ID, visible: true }"));
        assertTrue(script.contains("{ id: 0, visible: false }"));
        assertTrue(script.contains("{ id: 1, visible: false }"));
        assertTrue(script.contains("{ id: 2, visible: false }"));
        assertTrue(script.contains("valueExpression: SLOPE_WALKABILITY_EXPRESSION"));
        assertTrue(script.contains("var value = Number(rawValue)"));
        for (String color : List.of("#d9b36c", "#d7864f", "#bd5d49", "#934348", "#653342")) {
            assertTrue(script.contains("color: \"" + color + "\""), "Missing slope road color: " + color);
        }
        assertTrue(script.contains("function createSlopeRoadSymbol(color, style = \"solid\", width = 3.5)"));
        assertTrue(script.contains("createSlopeRoadSymbol(\"#a99b91\", \"short-dot\", 2.5)"));
        for (String threshold : List.of("value < 20", "value < 40", "value < 60", "value < 80", "value <= 100")) {
            assertTrue(script.contains(threshold), "Missing walkability threshold: " + threshold);
        }
        assertTrue(slopeMode.contains("residenceMapLayer.findSublayerById(layerId)"));
        assertTrue(slopeMode.contains("slopeDemLayer.visible = true"));
        assertTrue(slopeMode.contains("restoreResidenceRoadVisibility()"));
        assertFalse(slopeMode.contains("view.goTo"));
    }

    @Test
    void poiLayerUsesDedicatedCategoryFilteringAndSpringPopupContract() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String template = Files.readString(USER_HTML, StandardCharsets.UTF_8);
        String styles = Files.readString(USER_CSS, StandardCharsets.UTF_8);

        assertTrue(script.contains("const POI_MAP_SERVICE_URL = \"/api/map/poi\""));
        assertTrue(script.contains("const POI_LAYER_ID = 0"));
        assertTrue(script.contains("const POI_TARGET_DISTRICTS = Object.freeze([\"中山区\", \"西岗区\", \"沙河口区\"])"));
        assertTrue(script.contains("const POI_GROUP_DISPLAY_LIMIT = 220"));
        assertTrue(script.contains("const POI_TOTAL_DISPLAY_LIMIT = 3000"));
        assertTrue(script.contains("const POI_OBJECT_ID_BATCH_SIZE = 300"));
        assertTrue(script.contains("const POI_BASE_MARKER_SIZE = 11"));
        assertTrue(script.contains("const POI_WEIGHT_MARKER_RANGE = 4"));
        assertTrue(script.contains("const POI_OUT_FIELDS = Object.freeze"));
        for (String field : List.of("OBJECTID", "poi_id", "poi_name", "category_code", "category_name", "subcategory", "district_name", "phone", "source_layer", "address", "wgs84_x", "wgs84_y", "weight")) {
            assertTrue(script.contains("\"" + field + "\""), "Missing POI outField: " + field);
        }
        for (String category : List.of("RESTAURANT", "SCENIC", "PUBLIC_SERVICE", "SHOPPING", "EDUCATION", "FINANCIAL", "MEDICAL", "SPORTS", "GOVERNMENT")) {
            assertTrue(script.contains("code: \"" + category + "\""), "Missing POI category: " + category);
        }
        assertTrue(script.contains("function buildPoiWhere(categoryCodes)"));
        assertTrue(script.contains("category_code IN"));
        assertTrue(script.contains("district_name = '${districtLiteral}'"));
        assertTrue(script.contains("returnIdsOnly: \"true\""));
        assertTrue(script.contains("const samplePoiObjectIds"));
        assertTrue(script.contains("queryPoiGroupObjectIds(categoryCode, districtName, signal)"));
        assertTrue(script.contains("categories.flatMap((categoryCode)"));
        assertTrue(script.contains("districts.map((districtName)"));
        assertTrue(script.contains("Math.floor(POI_TOTAL_DISPLAY_LIMIT / Math.max(1, groupDescriptors.length))"));
        assertTrue(script.contains("queryPoiFeatureBatch"));
        assertTrue(script.contains("sampledObjectIds.length; index += POI_OBJECT_ID_BATCH_SIZE"));
        assertTrue(script.contains("${POI_MAP_SERVICE_URL}/${POI_LAYER_ID}/query?${params}"));
        assertTrue(script.contains("const poiGraphicLayer = new GraphicsLayer"));
        assertTrue(script.contains("\"geoscene/symbols/SimpleMarkerSymbol\""));
        assertTrue(script.contains("return new SimpleMarkerSymbol"));
        assertTrue(script.contains("const poiSymbol = (attributes, size = POI_BASE_MARKER_SIZE"));
        assertTrue(script.contains("poiGraphicLayer.removeAll()"));
        assertTrue(script.contains("window.addEventListener(\"poi-filter-change\", poiFilterHandler)"));
        assertFalse(script.contains("schedulePoiRefresh"));
        assertFalse(script.contains("webMercatorUtils"));
        assertFalse(script.contains("refreshPoiGraphics(selectedPoiCategories, selectedPoiDistricts)"));
        assertTrue(script.contains("function openPoiFeaturePopup"));
        assertTrue(script.contains("function buildPoiPopupContent"));
        assertTrue(script.contains("prefers-reduced-motion: reduce"));
        assertFalse(template.contains(":aria-selected=\"explorerTab === 'poi'\""));
        assertFalse(template.contains("@change=\"emitPoiFilters\""));
        assertTrue(template.contains("top-poi-controls"));
        assertTrue(template.contains("v-model=\"selectedPoiDistricts\""));
        assertTrue(template.contains("v-model=\"selectedPoiCategories\""));
        assertTrue(template.contains("选择行政区，可多选"));
        assertTrue(template.contains("选择设施类别，可多选"));
        assertTrue(template.contains("@click=\"applyPoiFilters\">显示</button>"));
        assertTrue(template.contains("@click=\"clearPoiDisplay\">清除</button>"));
        assertTrue(script.contains("selectedPoiCategories: []"));
        assertTrue(script.contains("selectedPoiDistricts: [...POI_TARGET_DISTRICTS]"));
        assertTrue(script.contains("applyPoiFilters()"));
        assertTrue(script.contains("toggleAllPoiCategories()"));
        assertTrue(script.contains("toggleAllPoiDistricts()"));
        assertTrue(styles.contains("@keyframes poi-popup-spring"));
        assertTrue(styles.contains(".poi-feature-popup.is-entering"));
        assertTrue(styles.contains(".poi-feature-popup__accent"));
        assertTrue(styles.contains(".poi-multi-select summary"));
        assertTrue(styles.contains(".poi-multi-select__option input"));
        assertTrue(styles.contains(".poi-display-button"));
    }

    @Test
    void mapProvidesCompassAndMetricScaleBar() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String template = Files.readString(USER_HTML, StandardCharsets.UTF_8);
        String styles = Files.readString(USER_CSS, StandardCharsets.UTF_8);

        assertTrue(script.contains("\"geoscene/widgets/Compass\""));
        assertTrue(script.contains("\"geoscene/widgets/ScaleBar\""));
        assertTrue(template.contains("id=\"mapCompassSlot\""));
        assertTrue(script.contains("const compassContainer = document.getElementById(\"mapCompassSlot\")"));
        assertTrue(script.contains("container: compassContainer"));
        assertTrue(script.contains("unit: \"metric\""));
        assertTrue(script.contains("style: \"ruler\""));
        assertTrue(script.contains("view.ui.add(scaleBar"));
        assertTrue(styles.contains(".map-compass-slot"));
        assertTrue(styles.contains("width: 54px"));
        assertTrue(styles.contains(".map-compass-slot .esri-icon-compass"));
        assertTrue(styles.contains("flex: 0 0 54px"));
    }

    @Test
    void profileEntryOpensStaticProfileWithoutBackendRequests() throws IOException {
        String userScript = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String profile = Files.readString(PROFILE_HTML, StandardCharsets.UTF_8);
        String profileScript = Files.readString(PROFILE_JS, StandardCharsets.UTF_8);

        assertTrue(userScript.contains("window.location.href = \"/profile.html\""));
        assertTrue(profile.contains("href=\"/user.html\""));
        assertTrue(profile.contains("data-profile-view=\"overview\""));
        assertTrue(profile.contains("data-profile-view=\"preferences\""));
        assertTrue(profile.contains("data-profile-view=\"favorites\""));
        assertTrue(profile.contains("data-profile-view=\"settings\""));
        assertTrue(profileScript.contains("data-profile-tab"));
        assertFalse(profileScript.contains("fetch("));
        assertFalse(profileScript.contains("XMLHttpRequest"));
    }

    @Test
    void projectTitleAndSubtitleFollowPageSpecificBrandContract() throws IOException {
        String index = Files.readString(INDEX_HTML, StandardCharsets.UTF_8);
        String indexStyles = Files.readString(INDEX_CSS, StandardCharsets.UTF_8);
        String user = Files.readString(USER_HTML, StandardCharsets.UTF_8);
        String userStyles = Files.readString(USER_CSS, StandardCharsets.UTF_8);
        String profile = Files.readString(PROFILE_HTML, StandardCharsets.UTF_8);
        String profileStyles = Files.readString(PROFILE_CSS, StandardCharsets.UTF_8);
        String loginCard = between(index, "<main class=\"glass-card\"", "</main>");
        String explorerHeader = between(user, "<div class=\"explorer-header\">", "</div>");
        String profileBrand = between(profile, "<a class=\"brand-mark\"", "</a>");

        assertTrue(loginCard.contains("银龄安居"));
        assertTrue(loginCard.contains("面向适老生活选址的智能分析决策平台"));
        assertTrue(explorerHeader.contains("银龄安居"));
        assertFalse(explorerHeader.contains("面向适老生活选址的智能分析决策平台"));
        assertFalse(explorerHeader.contains("<small>"));
        assertTrue(profileBrand.contains("银龄安居"));
        assertTrue(profileBrand.contains("面向适老生活选址的智能分析决策平台"));
        assertFalse(profile.contains("Silver Residence"));
        assertTrue(profileStyles.contains("--font-ui: \"PingFang SC\", \"Hiragino Sans GB\", \"Microsoft YaHei\", sans-serif"));
        assertTrue(profileStyles.contains("--font-brand: Georgia, \"Songti SC\", \"STSong\", serif"));
        assertTrue(profileStyles.contains(".brand-mark strong"));
        assertTrue(profileStyles.contains("font-family: var(--font-brand)"));
        assertTrue(profileStyles.contains("font-family: var(--font-ui)"));
        assertTrue(indexStyles.contains(".project-heading"));
        assertTrue(userStyles.contains(".explorer-project-title"));
    }

    @Test
    void housingPointPopupLoadsPurchaseOrRentalCardsOnlyAfterModeSelection() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String styles = Files.readString(USER_CSS, StandardCharsets.UTF_8);
        String staticTypes = between(script, "const STATIC_HOUSING_TYPE_TEMPLATES", "const LINE_RADAR_METRICS");
        String pointPopup = between(script, "function buildPointPopupContent", "function closeLineFeaturePopup");

        assertTrue(staticTypes.contains("\"中山区\""));
        assertTrue(staticTypes.contains("\"西岗区\""));
        assertTrue(staticTypes.contains("\"沙河口区\""));
        assertTrue(staticTypes.contains("2室1厅1卫"));
        assertTrue(staticTypes.contains("3室2厅2卫"));
        assertFalse(staticTypes.contains("fetch("));
        assertTrue(script.contains("function buildStaticHousingTypeShowcase"));
        assertTrue(script.contains("(unitPrice * area) / 10000"));
        assertTrue(script.contains("images/housing-type-2br.png"));
        assertTrue(script.contains("images/housing-type-3br.png"));
        assertTrue(script.contains("images/housing-type-3br-study.png"));
        assertTrue(script.contains("function openHousingImagePreview"));
        assertTrue(script.contains("data-housing-preview-close"));
        assertTrue(script.contains("event.key === \"Escape\""));
        assertTrue(pointPopup.contains("buildHousingModeControls()"));
        assertFalse(pointPopup.contains("buildStaticHousingTypeShowcase(attributes)"));
        assertTrue(script.contains("data-housing-mode=\"purchase\""));
        assertTrue(script.contains("data-housing-mode=\"rent\""));
        assertTrue(script.contains("data-housing-panel hidden"));
        assertTrue(script.contains("popup.dataset.housingMode === selectedMode ? \"\" : selectedMode"));
        assertTrue(script.contains("buildStaticHousingTypeShowcase(popup.__housingAttributes)"));
        assertTrue(script.contains("buildRentalHousingShowcase(popup.__housingAttributes)"));
        assertTrue(script.contains("<dt>户型</dt>"));
        assertTrue(script.contains("<dt>租金</dt>"));
        assertTrue(script.contains("<dt>面积</dt>"));
        assertTrue(script.contains("const RENTAL_HOUSING_PHOTOS = Object.freeze"));
        assertTrue(script.contains("Array.from({ length: 15 }"));
        assertTrue(script.contains("rentalHousingPhotosForCommunity"));
        assertTrue(script.contains("photoPool.slice(0, 3)"));
        assertTrue(script.contains("Math.random()"));
        assertTrue(script.contains("const DISTRICT_RENT_UNIT_AVERAGES = Object.freeze"));
        assertTrue(script.contains("\"中山区\": 39.17"));
        assertTrue(script.contains("\"西岗区\": 36.35"));
        assertTrue(script.contains("\"沙河口区\": 35.14"));
        assertTrue(script.contains("const DISTRICT_SALE_UNIT_AVERAGES = Object.freeze"));
        assertTrue(script.contains("\"中山区\": 18034"));
        assertTrue(script.contains("\"西岗区\": 13587"));
        assertTrue(script.contains("\"沙河口区\": 14200"));
        assertTrue(script.contains("asOf: \"2026-07\""));
        assertTrue(script.contains("https://m.creprice.cn/city/dl.html?type=lease"));
        assertTrue(script.contains("function isReasonableHousingUnitPrice"));
        assertTrue(script.contains("HOUSING_UNIT_PRICE_REFERENCE_RANGE.minimumRatio"));
        assertTrue(script.contains("HOUSING_UNIT_PRICE_REFERENCE_RANGE.maximumRatio"));
        assertTrue(script.contains("rentalMonthlyRent(type, attributes)"));
        assertFalse(script.contains("<div><dt>租金</dt><dd>暂无</dd></div>"));
        assertFalse(pointPopup.contains("模拟"));
        assertTrue(styles.contains(".point-feature-popup__housing-types"));
        assertTrue(styles.contains(".point-feature-popup__housing-switch"));
        assertTrue(styles.contains(".point-feature-popup__housing-panel[hidden]"));
        assertTrue(styles.contains(".rental-type-photo"));
        assertTrue(styles.contains("object-fit: cover"));
        assertTrue(styles.contains(".housing-type-grid"));
        assertFalse(script.contains("function buildHousingTypePlan"));
        assertFalse(pointPopup.contains("buildHousingTypePlan"));
        assertFalse(styles.contains(".housing-plan"));
        assertFalse(staticTypes.contains("bedrooms:"));
        assertFalse(staticTypes.contains("extraRoom:"));
        assertFalse(staticTypes.contains("plan:"));
        assertTrue(styles.contains(".housing-type-image:hover img"));
        assertTrue(styles.contains(".housing-image-preview"));
        assertTrue(Files.exists(HOUSING_TYPE_2BR));
        assertTrue(Files.exists(HOUSING_TYPE_3BR));
        assertTrue(Files.exists(HOUSING_TYPE_3BR_STUDY));
        for (int index = 1; index <= 15; index++) {
            assertTrue(Files.exists(RENTAL_PHOTO_DIR.resolve(index + ".png")), "Missing rental photo: " + index);
        }
    }

    @Test
    void residencePointsClusterByScaleAndShowNamesAtDeepZoom() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String clusterLayer = between(script, "function createResidencePointLayer", "function formatPopupValue");
        String clickHandler = between(script, "function installMapClickHandler", "function initUserMap");
        String mapInitialization = between(script, "function initUserMap", "function initUserApp");

        assertTrue(script.contains("const HOUSING_CLUSTER_MAX_SCALE = 6000"));
        assertTrue(script.contains("const HOUSING_LABEL_MIN_SCALE = HOUSING_CLUSTER_MAX_SCALE"));
        assertTrue(clusterLayer.contains("url: `${GEOSCENE_MAP_SERVICE_URL}/${layerId}`"));
        assertTrue(clusterLayer.contains("type: \"cluster\""));
        assertTrue(clusterLayer.contains("clusterRadius: \"96px\""));
        assertTrue(clusterLayer.contains("labelPlacement: \"center-center\""));
        assertTrue(clusterLayer.contains("$feature.cluster_count"));
        assertTrue(clusterLayer.contains("field: \"cluster_count\""));
        assertTrue(clusterLayer.contains("type: \"size\""));
        assertTrue(clusterLayer.contains("drop-shadow(0px, 2px, 3px"));
        assertTrue(clusterLayer.contains("color: \"#4f8f72\""));
        assertTrue(clusterLayer.contains("maxScale: HOUSING_CLUSTER_MAX_SCALE"));
        assertTrue(clusterLayer.contains("labelsVisible: true"));
        assertTrue(clusterLayer.contains("minScale: HOUSING_LABEL_MIN_SCALE"));
        assertTrue(clusterLayer.contains("labelPlacement: \"above-center\""));
        assertTrue(clusterLayer.contains("deconflictionStrategy: \"none\""));
        assertTrue(clusterLayer.contains("expression: \"$feature.name\""));
        assertTrue(mapInitialization.contains("const residencePointLayers = POINT_LAYER_IDS.map"));
        assertTrue(mapInitialization.contains("...residencePointLayers"));
        assertTrue(mapInitialization.contains("sublayer.visible = !isResidencePointLayer"));
        assertTrue(clickHandler.contains("item?.graphic?.isAggregate"));
        assertTrue(clickHandler.contains("view.goTo({ target: clusterHit.graphic.geometry, zoom: nextZoom }"));
        assertTrue(clickHandler.contains("return;"));
        assertTrue(script.contains("visiblePointLayerIds"));
    }

    @Test
    void filterPanelProvidesDeterministicFuzzyHousingNameSearch() throws IOException {
        String script = Files.readString(USER_JS, StandardCharsets.UTF_8);
        String template = Files.readString(USER_HTML, StandardCharsets.UTF_8);
        String styles = Files.readString(USER_CSS, StandardCharsets.UTF_8);
        String filterForm = between(template, "<form", "</form>");
        String nameSearch = between(filterForm, "<section class=\"housing-name-search\"", "</section>");

        assertTrue(filterForm.indexOf("housing-name-search") < filterForm.indexOf("行政区"));
        assertTrue(nameSearch.contains("v-model=\"housingNameQuery\""));
        assertTrue(nameSearch.contains("type=\"search\""));
        assertTrue(nameSearch.contains("@keydown.enter.prevent=\"searchHousingByName\""));
        assertTrue(nameSearch.contains("@click=\"searchHousingByName\""));
        assertTrue(script.contains("const HOUSING_LAYER_ID_BY_DISTRICT = Object.freeze"));
        assertTrue(script.contains("\"沙河口区\": 0"));
        assertTrue(script.contains("\"西岗区\": 1"));
        assertTrue(script.contains("\"中山区\": 2"));
        assertTrue(script.contains("async searchHousingByName()"));
        assertTrue(script.contains("filters: [{ field: \"name\", operator: \"like\", value: keyword }]"));
        assertTrue(script.contains("Promise.all(layerIds.map"));
        assertTrue(script.contains("role: \"PRIMARY_RESULTS\""));
        assertTrue(script.contains("this.captureHousingResults(payload)"));
        assertTrue(script.contains("new CustomEvent(\"agent-map-result\", { detail: payload })"));
        assertTrue(script.contains("this.housingNameSearchController?.abort()"));
        assertTrue(styles.contains(".housing-name-search__controls"));
        assertTrue(styles.contains("grid-template-columns: minmax(0, 1fr) 76px"));
        assertTrue(styles.contains("height: 46px"));
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue(startIndex >= 0, "Missing start marker: " + start);
        assertTrue(endIndex > startIndex, "Missing end marker: " + end);
        return source.substring(startIndex, endIndex);
    }
}
