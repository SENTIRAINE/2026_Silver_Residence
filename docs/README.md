# Silver Residence 当前文档

更新时间：`2026-08-02`

本目录只保留当前实现、机器契约、测试基线和待办优化，不再同时维护设计稿、阶段发布说明和重复的 Agent 契约。

## 文档入口

| 文档 | 定位 |
| --- | --- |
| [current-implementation.md](./current-implementation.md) | 当前已实现功能、系统边界、运行配置、Tool 语义、数据规则和发布门禁的人读权威说明 |
| [agent-api-v1.openapi.yaml](./agent-api-v1.openapi.yaml) | HTTP、DTO 与 SSE 的机器可读权威契约 |
| [spring-comprehensive-test-report-2026-08-02.md](./spring-comprehensive-test-report-2026-08-02.md) | 优化前的 Spring 综合测试快照、问题证据和修复依据，按要求永久保留 |
| [langgraph-optimization-proposal-2026-08-02.md](./langgraph-optimization-proposal-2026-08-02.md) | 从真实用户与老年语言场景提炼的 LangGraph 优化需求及验收标准 |
| [examples/](./examples/) | OpenAPI 引用并由测试读取的当前 Catalog、SSE 和 RAG 协议样例 |

## 权威顺序

1. 运行时 `GET /internal/agent-tools/catalog` 决定 Tool Schema 与 Catalog 版本。
2. `agent-api-v1.openapi.yaml` 决定 HTTP、DTO、错误体和 SSE 信封结构。
3. `current-implementation.md` 解释当前职责、业务语义、默认值和运维门禁。
4. `examples/` 用于契约回归，不替代运行时 Catalog。
5. 综合测试报告是 `2026-08-02` 优化前快照；当前状态以实现说明和最新测试结果为准。

## 最短启动路径

前置条件：JDK 21、MongoDB、可访问的 GeoScene 服务；启用智能问答时还需要正在运行的 LangGraph。

```powershell
Copy-Item .env.local.example .env.local
# 编辑 .env.local，配置两个相互独立的服务 Token
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

浏览器只访问 Spring Boot：`http://127.0.0.1:8080/`。不要把 LangGraph、内部 Agent Tool 或 GeoScene 直接暴露给浏览器。
