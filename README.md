# 银龄安居

面向适老生活选址的智能分析决策平台。项目把 Spring Boot、LangGraph、GeoScene 和浏览器地图整合为一个只读分析系统：用户可以用自然语言描述购房、生活便利度和道路步行需求，系统返回可解释的候选小区、相关道路和空间缓冲区，并在地图上完成可视化核对。

## 项目定位

```text
浏览器（Vue + GeoScene JS）
        |
        v
Spring Boot（登录、SSE 网关、契约校验、空间计算）
        |
        +--> LangGraph（意图路由、Planner、RAG、回答生成）
        |
        +--> GeoScene MapServer（住宅、道路、POI、坡度 DEM）
```

Spring 是浏览器的唯一后端入口，也是住宅评分、道路空间关系和字段解释的权威来源。LangGraph 只通过受控 Tool 调用 Spring，不直接查询 GeoScene，也不重复计算 Spring 已返回的指标。

## 已实现能力

- 用户注册、登录、注销和基于 Session 的 Assistant 访问控制。
- 0-2 层住宅点图层、3-5 层道路线图层的结构化查询。
- 住宅价格、便利度、道路步行性和道路缓冲区联合搜索，支持硬条件、软偏好、百分位阈值、幂等重试和冲突检测。
- 道路卡片保留 GVI/NOI 等级，雷达图使用每条道路的原始 `vegetation`、`noise` 和步行原始分；`WS归一化` 空值显示“暂无”。
- GVI 等级映射：`0/1/3/5 = 高/较高/中等/低`；NOI 等级映射：`0/1.25/2.5/3.75/5 = 低/较低/中/较高/高`。
- POI 按类别和行政区多选后点击“显示”，通过独立 POI MapServer 代理加载，按组抽样避免点位过密。
- 小区聚集点随缩放级别变化，深度缩放时显示小区名称；弹窗分为“购房”和“租房”两个按需展开的卡片，租房卡片展示户型、租金、面积及图片。
- 坡度分析模式：显示坡度 DEM 图层，并按步行指数五级高亮道路，同时提供 DEM 和道路图例；切换保持用户当前视角。
- 地图指南针、比例尺、道路区域统计、道路/小区雷达图和静态个人中心。
- LangGraph SSE 事件流、事件重放、取消、RAG 引用和跨消息会话记忆。

## 技术栈与前置条件

- JDK 21
- Maven Wrapper（推荐 `mvnw.cmd`）
- Spring Boot 3.3.5
- MongoDB `localhost:27017/Silver`
- LangGraph 服务（开发默认 `http://127.0.0.1:8000`）
- 可访问项目配置中的 GeoScene HTTPS 服务

复制环境模板并填写本地密钥：

```powershell
Copy-Item .env.local.example .env.local
```

至少需要：

```properties
LANGGRAPH_ENABLED=true
LANGGRAPH_BASE_URL=http://127.0.0.1:8000
LANGGRAPH_SERVICE_TOKEN=<spring-to-langgraph-token>
AGENT_TOOL_SERVICE_TOKEN=<langgraph-to-spring-token>
OPENAI_API_KEY=<provider-key>
```

两个服务 Token 必须不同，真实密钥不得提交到 Git。开发环境当前允许 GeoScene 不受信任证书；生产环境必须使用有效证书并关闭 `map.geoscene.trust-all-tls`。

## 启动

先启动 MongoDB 和 LangGraph，再启动 Spring：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
.\mvnw.cmd spring-boot:run
```

浏览器入口：

- 登录/注册：`http://127.0.0.1:8080/index.html`
- 地图分析：`http://127.0.0.1:8080/user.html`
- 静态个人中心：`http://127.0.0.1:8080/profile.html`

健康检查：

```text
GET /actuator/health
GET /internal/agent-tools/health       （需要服务身份）
GET http://127.0.0.1:8000/healthz
GET http://127.0.0.1:8000/readyz
```

## 主要接口

| 接口 | 用途 |
| --- | --- |
| `POST /user/register` | 注册 |
| `POST /user/login` | 登录并建立 Session |
| `POST /user/logout` | 注销 |
| `POST /api/assistant/runs/stream` | 创建 Assistant SSE Run |
| `GET /api/assistant/runs/{runId}/events` | 按序号重放并续流 |
| `POST /api/assistant/runs/{runId}/cancel` | 取消 Run |
| `GET /api/map/config` | 地图配置 |
| `POST /api/map/layers/{layerId}/query` | 结构化地图查询 |
| `GET /api/map/line-regional-stats/{layerId}` | 道路区域统计（3-5） |
| `GET /api/map/poi/{*path}` | POI MapServer 只读代理 |
| `GET /internal/agent-tools/catalog` | 当前 Tool Catalog |
| `POST /internal/agent-tools/tools/{toolName}/invoke` | 调用受控 Tool |
| `GET /internal/agent-tools/executions/{toolCallId}` | 查询执行终态 |

Agent Tool 目前包括 `queryMapFeatures`、`queryMapPoints`、`queryMapLines` 和 `searchHousingCandidates`。原始 `where`/SQL 不允许进入 Tool；请求必须带服务身份、tenant、user、run 和 trace 信息。

## 数据与字段约定

| 图层 | 区域 | 关键字段 |
| ---: | --- | --- |
| 0 | 沙河口区住宅 | `房价`、`name`、`adname`、`覆盖度评分`、`归一化总分` |
| 1 | 西岗区住宅 | 同上 |
| 2 | 中山区住宅 | 同上 |
| 3 | 中山区道路 | `GVI`、`NOI`、`vegetation`、`noise`、`WS归一化` |
| 4 | 西岗区道路 | 同上 |
| 5 | 沙河口区道路 | 同上 |

道路筛选参数 `gviMin` 和 `noiMax` 使用原始分 `vegetation`、`noise`；步行筛选使用 0-100 的 `WS归一化`。展示层不得把等级值当作原始分，也不得把 `归一化总分` 当作道路步行指数。

## 测试与验证

运行 Spring 全量测试：

```powershell
.\mvnw.cmd -q test
```

前端语法检查：

```powershell
node --check src/main/resources/static/JS/user.js
node --check src/main/resources/static/JS/profile.js
```

最近一次记录的 Spring 结果为 `65 tests, 0 failures, 0 errors, 1 skipped`；前端契约测试覆盖道路字段、雷达图、POI、多选显示、坡度模式、指南针、个人中心和地图结果图层。详细门禁状态、数据基线、已知限制及 LangGraph 后续事项见 [docs/version-report-2026-08-21.md](docs/version-report-2026-08-21.md)。

## 目录说明

```text
src/main/java/                         Spring Boot 后端
src/main/resources/static/             登录、地图、个人中心及静态资源
src/test/                              单元、集成和前端静态契约测试
scripts/                               本地验收与静态检查脚本
photo/                                 房源图片原始资源
docs/version-report-2026-08-21.md     唯一最新版本报告
```
