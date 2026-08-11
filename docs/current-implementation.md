# Silver Residence 当前实现说明

版本基线：`v1.1 / Catalog 2026-07-29.1`  
更新时间：`2026-08-02`  
适用范围：当前 Spring Boot 仓库、浏览器前端及其与 LangGraph/GeoScene 的既有契约

## 1. 产品与系统边界

Silver Residence 是面向适老居住选择的地图与智能问答系统。用户登录后可以用自然语言表达房价、社区便利度、道路步行性和距离需求；系统返回解释文本，并在地图上显示候选住宅、贡献道路和道路缓冲区。

```text
浏览器 -> Spring Boot -> LangGraph -> Spring Boot Agent Tools -> GeoScene
                              \-> RAG
```

- 浏览器只访问 Spring Boot，使用 `JSESSIONID` 保持登录与 Assistant 身份。
- Spring Boot 负责认证、SSE 网关、身份透传、Tool 参数校验、GeoScene 查询、住宅评分、道路空间关系和最终业务数据。
- LangGraph 负责意图路由、Planner、RAG、Tool 选择、SSE 事件编排和回答生成。
- GeoScene 提供 0-5 层真实住宅与道路数据。
- v1.1 只有只读查询、住宅道路联合搜索和 RAG，没有写地图数据的功能。

## 2. 当前模块

| 路径 | 当前职责 |
| --- | --- |
| `controller/UserController` | 注册、登录、注销和 Session 建立 |
| `controller/AssistantGatewayController` | Run 创建、续流、取消和 SSE 代理 |
| `controller/AgentToolController` | 内部 Catalog、Tool invoke、执行状态和 health |
| `controller/MapFeatureController` | 浏览器地图配置与只读要素查询 |
| `agent/` | LangGraph HTTP 客户端、身份和 Run 请求模型 |
| `housing/` | 快照、百分位、住宅偏好、道路米制空间搜索、结果排序 |
| `map/` | GeoScene 图层、结构化过滤、分页、属性与几何转换 |
| `api/` | v1.1 契约错误、用户请求 DTO 和异常映射 |
| `static/` | Vue 登录页、GeoScene 地图、Assistant 面板与结果渲染 |

## 3. 运行要求与配置

### 3.1 基线

- JDK：21。
- Maven：由 Wrapper 固定为 3.9.14。
- Spring Boot：3.3.5。
- MongoDB：默认 `localhost:27017/Silver`。
- Catalog：`2026-07-29.1`。
- Housing policy：`housing-search-policy-2026-07-29.1`。

复制 `.env.local.example` 为 `.env.local`。至少配置：

```properties
LANGGRAPH_ENABLED=true
LANGGRAPH_BASE_URL=http://127.0.0.1:8000
LANGGRAPH_SERVICE_TOKEN=<spring-to-langgraph-token>
AGENT_TOOL_SERVICE_TOKEN=<langgraph-to-spring-token>
```

两个 Token 必须独立，不得写入仓库。当前开发预算：

| 环节 | 默认值 |
| --- | ---: |
| GeoScene 主查询 | 90 秒 |
| GeoScene count | 10 秒 |
| Agent Tool | 120 秒 |
| LangGraph Run | 180 秒 |
| Spring MVC SSE | 210 秒 |
| SSE 事件保留 | 24 小时 |
| SSE heartbeat | 15 秒 |

预算顺序必须保持 `GeoScene < Tool < Run < Spring MVC`。生产环境必须使用有效 GeoScene TLS 证书并关闭 `map.geoscene.trust-all-tls`。

### 3.2 启动与验证

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

健康入口：

- Spring：`GET /actuator/health`。
- Spring Tool：`GET /internal/agent-tools/health`，需要服务身份。
- LangGraph：`GET /healthz` 与 `GET /readyz`。

## 4. 用户与会话

### 4.1 接口

| 方法与路径 | 成功 | 受控失败 |
| --- | --- | --- |
| `POST /user/register` | HTTP 200，`Result.code=1` | 字段非法 400；用户名/邮箱冲突 409，`Result.code=0` |
| `POST /user/login` | HTTP 200，建立 Session，响应密码为 null | 字段非法 400；用户名或密码不正确 401 |
| `POST /user/logout` | HTTP 200，Session 失效 | 幂等成功 |

注册和登录只接收专用 DTO，服务端校验用户名、密码、邮箱和手机号。浏览器会优先显示响应的 `msg`，同时兼容契约错误体中的 `message`。

### 4.2 密码与数据约束

- 新密码使用 BCrypt，不保存或返回明文。
- 历史 32 位 MD5 密码仅用于兼容验证；首次成功登录后立即重编码为 BCrypt。
- 用户名和邮箱在 MongoDB 模型中声明唯一索引；冲突返回 409。
- 未知用户名和错误密码对外使用同一消息，避免账号枚举。
- 登录日志只记录用户名，不记录密码和整个用户对象。

## 5. 对外与内部接口

### 5.1 浏览器调用 Spring

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/assistant/runs/stream` | 创建 Run，返回命名 SSE |
| `GET /api/assistant/runs/{runId}/events?afterSequence=N` | 仅重放 `sequence > N` 的事件并续流 |
| `POST /api/assistant/runs/{runId}/cancel` | 幂等取消 |
| `GET /api/map/config` | 读取地图配置 |
| `POST /api/map/layers/{layerId}/query` | 浏览器结构化只读图层查询 |

Assistant 接口要求已登录 Session。未登录返回 HTTP 401，并以 `preflight.failed` SSE 表达 `AUTHENTICATION_REQUIRED`。

### 5.2 Spring 调用 LangGraph

Spring 调用：

- `POST /api/v1/runs/stream`
- `GET /api/v1/runs/{runId}/events?afterSequence=N`
- `POST /api/v1/runs/{runId}/cancel`

请求携带 Bearer Token、`X-Trace-Id`、`X-Tenant-Id` 和 `X-User-Id`。Header 身份是权威；Body 身份不得覆盖 Session 派生身份。

### 5.3 LangGraph 调用 Spring Tool

| 方法与路径 | 说明 |
| --- | --- |
| `GET /internal/agent-tools/catalog` | 获取当前 Tool Schema |
| `POST /internal/agent-tools/tools/{toolName}/invoke` | 调用 Tool |
| `GET /internal/agent-tools/executions/{toolCallId}` | 查询超时后未知终态 |
| `GET /internal/agent-tools/health` | Catalog 与 Housing snapshot 状态 |

当前 Tool：

- `queryMapFeatures`：图层 0-5。
- `queryMapPoints`：住宅点图层 0-2。
- `queryMapLines`：道路图层 3-5。
- `searchHousingCandidates`：住宅硬过滤、软偏好、道路 WS 证据与缓冲区联合搜索。

内部接口必须使用 `AGENT_TOOL_SERVICE_TOKEN`。`invoke` 和执行状态查询还要求 tenant、user、run 与 trace 身份。原始 `where`/SQL 禁止进入 Tool，只允许 Catalog 声明的结构化过滤器。

## 6. 住宅搜索语义

### 6.1 用户语言映射

| 用户含义 | 权威 Tool 参数 |
| --- | --- |
| 房价不超过/以内 | `hardFilters.priceMax` |
| 价格尽量低 | `preferences.price=PREFER_LOW`，不得猜 `priceMax` |
| 便利度高一点 | `preferences.convenience=PREFER_HIGH`，字段固定为 `归一化总分` |
| 便利度高/很高 | `HIGH` / `VERY_HIGH`，由 Spring 解析 P75/P90 |
| 道路步行性高一点 | `roadWalkability=PREFER_HIGH`，WS 只来自道路层 |
| 高 WS 道路附近 | `BUFFER_FILTER` + `HIGH/VERY_HIGH` |
| 未指定行政区 | `districts=[]`，使用全支持区域统一统计 |
| 未指定距离 | 省略 `bufferMeters`，由 Spring 应用 100 米默认值 |

`新步行`、`归一化总分`、道路 `WS` 是不同字段，不能互相替代。

### 6.2 当前策略

- 模式：`RANK` 和 `BUFFER_FILTER`。
- 显式缓冲距离范围：20-2000 米；超界直接拒绝，不截断。
- 默认缓冲：100 米。
- HIGH/VERY_HIGH：支持区域 P75/P90，不由模型猜绝对阈值。
- 便利度和道路步行性同时启用且未给权重时：`0.5 / 0.5`。
- 默认返回 20 个住宅，最大 50；道路展示最大 50；buffer overlay 最大 20。
- 道路计算默认上限 2000，使用 UTM 51N 等价米制计算与 STRtree 索引。
- 空住宅结果仍可保留合格道路与缓冲区，并返回 `NO_HOUSING_IN_BUFFER`。
- 所有默认、有效权重、阈值来源、截断和缺失指标都通过 `resolvedCriteria`、`appliedFilters` 或 warnings 显式表达，禁止静默放宽。

### 6.3 幂等与重试

- `toolCallId` 必须是 UUID。
- 相同 ID 和完全相同参数返回既有执行。
- 相同 ID 使用不同参数返回 409 `TOOL_CALL_CONFLICT`。
- Tool 超时且终态未知时先查询 execution，不生成新 ID 重算。
- 参数错误、距离错误和冲突不重试。

## 7. 地图、字段与索引

### 7.1 图层

| 图层 | 类型 | 区域 | 业务字段 |
| ---: | --- | --- | --- |
| 0 | point | 沙河口区住宅 | `房价`、`name`、`adname`、`覆盖度评分`、`归一化总分` |
| 1 | point | 西岗区住宅 | 同上 |
| 2 | point | 中山区住宅 | 同上 |
| 3 | polyline | 中山区道路 | `name`、`GVI`、`NOI`、`WS`、`Shape_Length` |
| 4 | polyline | 西岗区道路 | 同上 |
| 5 | polyline | 沙河口区道路 | 同上 |

住宅源表 `shahekou_1`、`xigang_1`、`zhongshan_1` 分别为五个业务字段建立 `idx_<table>_price/name/adname/cover/total` 索引。道路源表 `ZhongShan`、`XiGang`、`ShaHeKou` 分别为五个业务字段建立 `idx_<table>_name/GVI/NOI/WS/Shape_Length` 索引。

### 7.2 前端渲染

- `map.result` 图层顺序：`ROAD_BUFFER`、`CONTRIBUTING_ROADS`、`HOUSING_CANDIDATES`。
- `bufferOverlays` 由 Spring 原样提供；LangGraph 和前端不重新 buffer/dissolve。
- 几何统一保留 `spatialReference.wkid=4326`。
- Housing 的价格百分位、便利度百分位、附近道路 WS、推荐分和有效权重必须复制到地图属性，但不能覆盖原始属性。
- 清除结果时三类临时图层必须同时清空。

## 8. SSE 与错误规则

- 所有 Agent 事件使用 `schemaVersion=1.1`。
- 事件具有递增 `sequence`，一个 Run 只能出现一个终态。
- 成功终态为 `run.completed`；失败为 `run.failed`；预检失败为 `preflight.failed`。
- `tool.completed` 必须包含 `durationMs`。
- heartbeat 是 SSE 注释，不参与序号。
- `map.result` 必须先于依赖地图证据的最终回答。
- Spring 透传 LangGraph 的状态与事件，不把下游失败伪装成 Spring 成功结论。

字段级结构、状态码和错误体以 OpenAPI 为准。关键稳定错误包括：

| 错误码 | HTTP | 含义 |
| --- | ---: | --- |
| `AUTHENTICATION_REQUIRED` | 401 | 浏览器 Session 缺失 |
| `INVALID_SERVICE_IDENTITY` | 401 | 内部服务 Token 无效 |
| `RAW_WHERE_NOT_ALLOWED` | 400 | 提交原始 where |
| `INVALID_BUFFER_DISTANCE` | 400 | 距离不在 20-2000 米 |
| `INVALID_HOUSING_SEARCH_ARGUMENT` | 400 | Housing 参数未知、冲突或非法 |
| `TOOL_CALL_CONFLICT` | 409 | 同 toolCallId 参数变化 |
| `METRIC_STATISTICS_UNAVAILABLE` | 503 | 无可用统计快照 |

## 9. 快照与可用性

- Housing 六层快照在后台预热和定时刷新，不阻塞用户请求线程。
- 刷新使用有界执行器和原子替换；已有快照可在有限陈旧窗口内继续服务。
- health 状态为 `READY`、`WARMING`、`DEGRADED` 或 `STALE`。
- 无可用快照或超过最大陈旧时间时失败关闭，不以当前页大小、旧 Catalog 或无百分位查询伪装成功。
- 生产保持 `snapshot-prewarm-enabled=true`，最大陈旧时间必须按数据更新频率设置，不得无限延长。

## 10. 当前测试与发布门禁

### 10.1 当前自动化结果

Spring 在 JDK 21 下：`58 tests, 0 failures, 0 errors, 1 skipped`，即 57 passed、1 skipped。新增回归覆盖：

- 错误密码返回 401，且不建立 Session。
- 空登录体和非法注册字段返回 400。
- 重复注册返回 409 并保留准确 `msg`。
- 成功登录建立 Assistant Session 且不返回密码。
- 新注册密码为 BCrypt；错误密码和异常存量哈希受控失败；旧 MD5 成功登录后升级。

`spring-comprehensive-test-report-2026-08-02.md` 保留优化前的 49 项基线和真实场景证据，不覆盖为优化后的结果。

### 10.2 真实数据基线

- GeoScene 0-5 层 metadata、空 count、真实 count 和完整数据共 24/24 检查通过。
- 六层数量为 `564 / 288 / 483 / 542 / 445 / 650`，总计 2972，无传输截断。
- A01-A11 Tool 验收通过。
- 本轮热路径：P75 Tool P95 21 ms；P90 Tool P95 17 ms；目标小于 3000 ms。
- 当前证据保存在 `outputs/spring-comprehensive-2026-08-02/`，不得清理。

### 10.3 发布前必须执行

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
.\mvnw.cmd test
.\scripts\geoscene-query-probe.ps1 -SpringBaseUrl http://127.0.0.1:8080 -TimeoutSeconds 90 -IncludeRealData
.\scripts\housing-v1.1-acceptance.ps1
```

还必须检查：

1. `/actuator/health`、Tool health、LangGraph `/readyz` 都满足发布条件。
2. A01-A11 请求/响应、性能汇总和真实用户 SSE 被归档。
3. 桌面与 390x844 移动端显示、清除 buffer/道路/住宅三层无误，控制台无未解释错误或资源 404。
4. P75/P90 Tool P95 继续小于 3 秒。
5. LangGraph 按独立优化提案完成自然语言行为门禁；Spring Tool 通过不能替代 LangGraph 验收。

## 11. 证据与协议样例

- 机器契约：`docs/agent-api-v1.openapi.yaml`。
- Catalog 固定样例：`docs/examples/agent-tool-catalog-2026-07-29.1.json`。
- SSE 成功、空结果、RAG、澄清、失败、取消和重放：`docs/examples/agent-sse-*.txt`。
- 综合测试报告：`docs/spring-comprehensive-test-report-2026-08-02.md`。
- 最小优化验证汇总：`outputs/spring-minimal-optimization/verification-summary.json`。
- 用户场景原始请求/SSE：`outputs/spring-comprehensive-2026-08-02/user-scenarios/`。
- Housing A01-A11：`outputs/spring-comprehensive-2026-08-02/housing-acceptance/`。
