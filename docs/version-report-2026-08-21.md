# 银龄安居版本报告

版本：`v1.1 / Catalog 2026-08-21.1`

报告日期：`2026-08-21`

覆盖范围：Spring Boot、浏览器前端，以及与 LangGraph、GeoScene 的现行接口边界

## 1. 版本结论

Spring 侧已完成道路字段迁移、地图功能扩展和前端契约更新。当前权威 Catalog 为 `2026-08-21.1`，Housing policy 为 `housing-search-policy-2026-08-21.1`，Schema 指纹为 `02eb5defc547d96bf0edc31f84dcd93f442a9977cbce5c6afeb82a62b7ffe6d9`。

Spring 健康检查和 Housing snapshot 可用；最近一次 Spring 自动化记录为 `65 tests, 0 failures, 0 errors, 1 skipped`。LangGraph 仍需完成受控 Regenerate：其旧 Fixture/Catalog 继续报告 `TOOL_CATALOG_VERSION_MISMATCH` 时，不应静默修改 Fixture，而应先升级 Catalog 版本并重新验证兼容性。

## 2. 系统边界

```text
浏览器 -> Spring Boot -> LangGraph -> Spring Agent Tools -> GeoScene
                                      \-> RAG
```

- Spring 负责 Session、SSE 网关、请求校验、GeoScene 查询、住宅评分、道路空间计算和最终字段语义。
- LangGraph 负责意图路由、Planner、Tool 选择、RAG、SSE 编排和回答生成。
- GeoScene 提供住宅、道路、独立 POI、坡度 DEM 数据；除只读查询外不写回地图。
- LangGraph 不得直接访问 GeoScene，不得重算价格、便利度、WS、百分位、距离或缓冲区。

## 3. 运行基线

| 项目 | 当前值 |
| --- | --- |
| JDK | 21 |
| Spring Boot | 3.3.5 |
| MongoDB | `localhost:27017/Silver` |
| Spring | `http://127.0.0.1:8080` |
| LangGraph | `http://127.0.0.1:8000` |
| Catalog | `2026-08-21.1` |
| Housing policy | `housing-search-policy-2026-08-21.1` |
| Schema fingerprint | `02eb5defc547d96bf0edc31f84dcd93f442a9977cbce5c6afeb82a62b7ffe6d9` |
| Housing snapshot | `READY`（以 `/internal/agent-tools/health` 实时结果为准） |

预算顺序必须保持 GeoScene < Tool < LangGraph Run < Spring MVC SSE。默认值为 90 秒、120 秒、180 秒和 210 秒；SSE 事件保留 24 小时，heartbeat 间隔 15 秒。

## 4. 数据字段更新

### 4.1 道路图层 3-5

道路业务字段为 `name`、`GVI`、`NOI`、`vegetation`、`noise`、`WS归一化` 和 `Shape_Length`。索引已按现行字段建立。

| 展示字段 | 展示规则 | 原始筛选字段 |
| --- | --- | --- |
| `GVI` | `0=高`、`1=较高`、`3=中等`、`5=低` | `vegetation`（绿视率原始分） |
| `NOI` | `0=低`、`1.25=较低`、`2.5=中`、`3.75=较高`、`5=高` | `noise`（道路噪声原始分） |
| `WS归一化` | 0-100；空值前端显示“暂无” | `WS归一化` |

道路卡片第一行显示 GVI/NOI 等级，单条道路雷达图使用该条道路的原始分。区域统计接口 `GET /api/map/line-regional-stats/{layerId}` 只接受 3-5 层，统计由 Spring 执行。

### 4.2 住宅图层 0-2

住宅查询仍使用 `房价`、`name`、`adname`、`覆盖度评分` 和 `归一化总分`。便利度映射到 `归一化总分`；不得用道路 `WS归一化` 或道路等级替代住宅指标。

## 5. Spring 接口与 Tool

浏览器接口：

- `POST /api/assistant/runs/stream`
- `GET /api/assistant/runs/{runId}/events?afterSequence=N`
- `POST /api/assistant/runs/{runId}/cancel`
- `GET /api/map/config`
- `POST /api/map/layers/{layerId}/query`
- `GET /api/map/line-regional-stats/{layerId}`
- `GET /api/map/poi` 和 `GET /api/map/poi/{*path}`

LangGraph 调用 Spring：

- `GET /internal/agent-tools/catalog`
- `POST /internal/agent-tools/tools/{toolName}/invoke`
- `GET /internal/agent-tools/executions/{toolCallId}`
- `GET /internal/agent-tools/health`

当前 Tool 为 `queryMapFeatures`、`queryMapPoints`、`queryMapLines`、`searchHousingCandidates`。所有调用使用 `AGENT_TOOL_SERVICE_TOKEN`，并校验 tenant、user、run、trace 身份。`toolCallId` 幂等；同 ID 改参数返回 `TOOL_CALL_CONFLICT`；超时未知终态先查 execution，不重新生成调用。

住宅搜索支持 `RANK` 和 `BUFFER_FILTER`，道路缓冲距离为 20-2000 米，默认 100 米；`HIGH`/`VERY_HIGH` 由支持区域 P75/P90 统计解析，条件不得静默放宽。默认住宅 20 条、最大 50 条，道路最大 50 条，缓冲区最大 20 个。

## 6. 前端功能现状

- 登录页、地图页和个人中心使用统一项目标题；地图页不展示副标题，登录页和个人中心保留副标题。
- 住宅点支持聚集显示，深度缩放显示小区名称；弹窗的购房/租房内容按按钮切换，租房户型图片从 `src/main/resources/static/photo/` 读取。
- POI 默认不显示；用户在顶部卡片中用两个多选下拉框选择行政区和类别，点击“显示”后加载，支持清空。每个“类别 × 行政区”最多 220 个点，全局最多 3000 个，每批最多 300 个 ObjectID。
- POI 使用独立 GraphicsLayer 和 GeoScene 风格点符号；地图移动/缩放只关闭弹窗，不自动重新查询。
- 坡度模式通过独立坡度 MapServer 加载 DEM，同时将 0-2 道路按步行指数五级暖色高亮。提供 DEM 渐变图例和道路五级图例，不改变用户视角。
- 地图提供官方 Compass、metric ScaleBar、道路详情雷达图和地图结果图层；结果图层顺序为 `ROAD_BUFFER`、`CONTRIBUTING_ROADS`、`HOUSING_CANDIDATES`。
- `WS归一化`、房价、原始道路分和缺失指标统一使用“暂无”或受控 warning，不补造数值。

## 7. SSE、错误和记忆

Agent 事件使用 `schemaVersion=1.1`、递增 `sequence` 和唯一终态。成功为 `run.completed`，失败为 `run.failed`，预检失败为 `preflight.failed`；`map.result` 必须先于依赖地图证据的最终回答。LangGraph 会话记忆按 `tenant_id + user_id + conversation_id` 隔离，最多保留最近 6 轮，不继承历史 layerId、字段或地图事实。

稳定错误包括 `AUTHENTICATION_REQUIRED`（401）、`INVALID_SERVICE_IDENTITY`（401）、`RAW_WHERE_NOT_ALLOWED`（400）、`INVALID_BUFFER_DISTANCE`（400）、`INVALID_HOUSING_SEARCH_ARGUMENT`（400）、`TOOL_CALL_CONFLICT`（409）和 `METRIC_STATISTICS_UNAVAILABLE`（503）。

## 8. 验证状态

已记录通过或可复现的验证：

- Spring：65 项记录，0 failures、0 errors、1 skipped；覆盖认证、密码迁移、住宅搜索、道路统计、SSE、对话记忆和前端契约。
- GeoScene：0-5 层 metadata、count、完整属性/几何探针共 24/24 通过；历史数据量为 `564 / 288 / 483 / 542 / 445 / 650`。
- Housing Tool：A01-A11 正常、拒绝、幂等和冲突场景通过；P75/P90 Tool P95 记录低于 3 秒目标。
- 前端：`node --check src/main/resources/static/JS/user.js` 和 `profile.js` 通过；POI、坡度、雷达图、指南针、个人中心静态断言已覆盖。
- LangGraph：记忆定向回归 6 项、稳定非 RAG 集合 58 项通过；RAG/ready 用例曾受 SQLite 可访问性影响，须在发布环境重新执行。

## 9. LangGraph 必须更新的事项

1. 从 Spring `GET /internal/agent-tools/catalog` 拉取并固定 Catalog `2026-08-21.1`。
2. 将 Housing policy 更新为 `housing-search-policy-2026-08-21.1`，并校验 Schema fingerprint 与 Spring 返回值一致。
3. 更新道路字段映射：道路步行只读 `WS归一化`；GVI/NOI 展示等级不能用于 `gviMin/noiMax`，后两者必须对应 `vegetation`/`noise` 原始分。
4. 保证价格上限进入 `hardFilters.priceMax`，未指定行政区时传 `districts=[]`，未指定距离时使用 Spring 默认 100 米。
5. 受控 Regenerate 后重跑 A01-A11、SSE 重放/取消、自然语言场景和契约指纹门禁。旧 Fixture 与新 Spring Catalog 不兼容时必须显式失败，禁止静默改 Fixture。

## 10. 发布前检查

```powershell
.\mvnw.cmd -q test
node --check src/main/resources/static/JS/user.js
node --check src/main/resources/static/JS/profile.js
```

上线前还要确认 Spring `/actuator/health`、Tool health 和 LangGraph `/readyz` 可用，完成真实浏览器桌面/移动端检查，并确保 Token、MongoDB 文件、Cookie、日志和验收中间产物不进入源码包。

本报告是 `docs/` 中唯一保留的版本报告；历史文档、OpenAPI 副本、Catalog/SSE/RAG 样例和临时验收材料已移除。运行时契约以 Spring Catalog 接口和源码 DTO 为准。
