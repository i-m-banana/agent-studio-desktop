# 验证记录

## 阶段 0：工程基线（2026-09-02）

验证结果：

- 后端 `mvn test`：通过，2 个测试、0 失败、0 错误；
- 后端真实启动：通过，Spring Boot 3.4.3 使用 Java 21 启动于 8080；
- `GET /api/system/status`：通过，返回 `application=agent-studio-backend`、`status=UP`；
- 前端 `npm run build`：通过，TypeScript 检查与 Vite 生产构建完成；
- `docker compose -f docker/compose.yml config`：通过；
- MySQL 与 pgvector 镜像拉取、网络、数据卷和容器创建：用户环境已验证；
- 容器实启：pgvector 在 `15432` 已健康；MySQL 先后在 `3306`、`13306` 遇到宿主机端口占用，当前默认端口已调整为 `23306`，等待复验。

环境事实：

- Maven 3.9.11 使用 JDK 21.0.4；
- 系统 `java` 命令当前指向 Java 8，但 Maven 使用已配置的 Java 21；
- Node.js 22.13.1，npm 10.9.2；
- Docker CLI 29.2.0、Compose 5.0.2；当前开发会话无权访问 Docker Desktop 引擎，因此容器运行状态待在可访问引擎的环境验证。

真实异常与修复：

- 初次后端构建因依赖尚未下载且沙箱禁止联网而失败；授权下载后测试与启动均通过；
- 初次前端构建因 `tsconfig.node.json` 的 `allowImportingTsExtensions` 缺少 `noEmit` 而失败；补充 `noEmit: true` 后构建通过。
- Compose 实启先后发现本机已有服务占用 `3306` 和 `13306`；MySQL 默认映射调整为 `23306`，pgvector 保持 `15432`，并允许通过 `.env` 覆盖。

本阶段所有业务骨架均为本次新增，未迁移来源项目业务代码。

## 阶段 1：Agent Builder 最小主链（2026-09-02）

已实现：

- Flyway V1：模型配置、Agent 草稿、不可变版本、会话和消息表；
- 模型配置创建、更新与列表 API；
- Agent 草稿创建、更新、发布与版本列表 API；
- OpenAI Chat Completions 兼容流式调用；
- `run`、`delta`、`done`、`error` SSE 协议；
- 模型配置、Agent Builder 和对话测试台页面。

验证结果：

- `mvn test`：4 个测试通过，0 失败、0 错误；
- 版本隔离测试：模型配置和草稿更新后，v1 快照保持不变，v2 使用新配置；
- SSE 集成测试：验证 `run → delta → done` 事件及增量内容；
- `npm run build`：TypeScript 与 Vite 生产构建通过；
- Compose 配置解析：MySQL 默认宿主机端口为 `23306`，pgvector 为 `15432`。

真实异常与修复：

- SSE 集成测试最初受 Windows 测试响应字符集影响，中文断言显示为问号；事件结构无误，改用与字符集无关的 ASCII 增量断言后全部通过。

后续已经完成真实 MySQL、DeepSeek 和 RAG 验证，见阶段 2、3；工具运行时第一阶段见阶段 5。阶段 1 当时尚未实现工具、运行步骤、审批和审计。

## 阶段 2：真实模型与数据库（2026-09-02）

- 用户确认 MySQL `23306` 与 pgvector `15432` 两个容器均为 healthy；
- Spring Boot 真实连接 MySQL 8.4，Flyway V1、V2 校验及迁移通过；
- 使用用户配置的 DeepSeek OpenAI 兼容端点完成真实 SSE 调用，收到中文 `delta` 并以 `done` 正常结束；
- 模型密钥仍仅从启动后端的 `DEEPSEEK_API_KEY` 环境变量读取，未写入数据库。

## 阶段 3：知识库与检索增强（2026-09-02）

已验证：

- 文档上传、Tika 解析、切块和 pgvector 写入完成，示例 Markdown 状态为 `READY`；
- Agent 发布版本正确快照知识库标识，草稿 API 的 `status` 字段正常返回；
- SSE 顺序实测为 `run → sources → error`：`sources` 返回文件名、chunk 编号、内容和相似度；独立 8081 验收进程未设置 DeepSeek 密钥，因此模型阶段按设计返回明确 `error`；
- 删除文档后 MySQL 文档列表为空、本地文件不存在，重复检索不再返回 `sources`，随后重新上传恢复为 `READY`；
- 后端 `mvn test`：6 个测试通过，0 失败、0 错误；
- 前端 `npm run build`：TypeScript 检查与 Vite 生产构建通过。
- 浏览器冒烟检查：四个导航入口及新增知识库创建、文档上传控件均正常渲染；由于 5173 当时仍连接旧的 8080 后端进程，完整新接口交互改由独立 8081 实例完成。重启日常后端后前端即可使用新接口。

真实异常与修复：

- 引入 pgvector 数据源后，Flyway 和业务 JDBC 曾自动选择 PostgreSQL，导致 MySQL 表查询落到错误数据库；现已显式声明 primary MySQL DataSource/JdbcTemplate，并给四个业务仓储加限定绑定，向量仓储只使用 PostgreSQL。

当前限制：

- 内置 384 维向量为词法哈希基线，不是语义 embedding；
- 暂无跨存储分布式事务和失败补偿任务；
- 本阶段当时尚未实现工具调用、ReAct、审批、SSH 和 Coding 扩展；低风险工具与 ReAct 后续已在阶段 5 落地。

## 阶段 4：Qwen 语义向量生产升级（2026-09-02）

已实现：

- 新增 `EmbeddingGateway`，默认实现为 Ollama `qwen3-embedding:0.6b`，1024 维；LocalHash 作为环境变量可切换的回退；
- 新增版本化 `knowledge_chunk_v2`，保存模型和 `qwen3-0.6b-v1` 索引版本，旧 384 维表未删除；
- 新增知识库重建 API 和前端“使用当前模型重建索引”入口；
- Qwen 索引采用按知识库和版本过滤的精确 cosine 搜索，暂不使用 HNSW；
- 删除文档会同时清理旧、新两套向量表，重建失败只清理当前索引版本。

验证结果：

- 后端 `mvn test`：8 个测试通过，包括 Ollama 批量文档 embedding、查询前缀隔离和维度校验；
- 前端 `npm run build`：通过；
- 阶段 4 当时冻结语料（11 份文档、23 个 chunk）：Qwen Recall@5 100%、MRR 0.8000、改写题 MRR 0.7250、p95 358.656 ms；知识包后续扩充后的最新同语料结果见阶段 5；
- 真实 MySQL、pgvector 和 Ollama：知识库重建成功，`knowledge_chunk_v2` 实际保存 `qwen3-embedding:0.6b`、1024 维、`qwen3-0.6b-v1`；
- 真实 SSE：改写问题命中正确来源，最终无查询前缀配置下 score 约 0.40；独立 8081 进程未设置 DeepSeek 密钥，随后按预期返回模型环境变量错误。

消融与限制：

- 在知识包扩充前的同语料消融中，加入统一中文 query instruction 后 MRR 从 0.8160 降到 0.6917，因此默认关闭、保留可配置；
- 知识包内容变化会改变 chunk 数和指标，扩充后已同时重跑 LocalHash 与 Qwen，生产报告不混用历史语料结果；
- 阶段 4 的 Qwen Recall 和延迟达到约定目标，MRR 为 0.8000；扩充语料后已升至 0.8472，但仍保留 rerank 优化项；
- 2026-09-04 复现并修复 Ollama 进程继承空 `D:\ollama-models` 导致的索引失败：用已有模型目录启动后，`/api/embed` 返回 1024 维向量，失败文档通过重建 API 恢复为 READY；后端新增可操作的连接错误和完整异常日志；
- 无答案误召回率仍为 100%，固定阈值无法在当前题集上同时维持高召回和可靠拒答；
- 尚未进行结构感知切块、混合检索或 rerank，这些留作独立评测，不与 embedding 收益混在同一次改造中。

## 阶段 5：低风险工具与显式 ReAct（2026-09-04）

已实现：

- 内置 LOW/READ 工具 `current_time`，支持可选 IANA 时区并拒绝未知参数；
- Agent 草稿工具绑定和 AgentVersion 不可变工具快照；
- 显式“模型 tool call → 平台执行 → Observation → 模型续答”循环；
- 默认最大 4 轮、单轮最多 4 次工具调用和单工具超时；
- `AgentRun`、`RunStep` 持久化，以及 `run`/`step` SSE 与前端步骤面板；
- 工具失败会作为 Observation 反馈模型，越权调用和达到上限会安全终止。

验证结果：

- 后端 `mvn test`：12 个测试通过，0 失败、0 错误；
- 自动化覆盖普通流式路径、工具版本快照、成功工具循环、运行步骤查询、重复工具调用上限和工具超时；
- 前端 `npm run build`：通过；
- 真实 MySQL 8.4：Flyway V3 从版本 2 升至 3，创建工具绑定、运行与步骤表；
- 真实 API：`GET /api/tools` 返回 `current_time`，历史 Agent 正常读取且工具集合为空。

尚未验证与限制：

- 独立验收进程没有继承用户日常 8080 进程中的 `DEEPSEEK_API_KEY`，因此本阶段的真实 DeepSeek tool-calling 兼容性尚待用户重启后端后验证；自动化测试使用可控模型网关验证了完整循环；
- 工具版最终答案当前以单个 `delta` 返回，不是 token 级流式；
- 高风险审批、MCP、SSH、Coding、运行取消、总运行超时和崩溃恢复不在本阶段范围。

本阶段当时的知识包同步回归（已由阶段 6 新结果替代）：

- 更新后的 00–10 为 11 份文档、26 个 chunk，SHA-256 `67b0b2e5394d2687e663b913bce8d53d1bf908a5e616bc6ba10ccdded1503325`；
- Qwen Recall@5 100%、MRR 0.8333、改写题 MRR 0.8333、p95 234.978 ms；
- 同语料 LocalHash Recall@5 83.33%、MRR 0.6021、改写题 MRR 0.4958；
- 两者无答案误召回率仍为 100%，新增工具文档没有解决既有拒答问题。

## 阶段 5.1：Agent 草稿编辑与可选外键修复（2026-09-05）

- 真实运行状态检查：Flyway V3、MySQL、pgvector、模型、知识库和 `current_time` 均正常；数据库已有“科研知识助手”和“chat-test”；
- 首次判断曾误以为是同名创建；用户截图显示名称为全新的“查时间”，该判断被纠正；
- 实际根因：前端用空字符串表示“不使用知识库”，后端跳过校验后仍将空字符串写入可选外键，触发 MySQL 外键约束；
- 修复：前端提交 null，后端把 null、空字符串和纯空格统一归一化为 null；测试覆盖空字符串创建并断言知识库标识为 null；
- 修复：Agent 卡片新增编辑草稿，表单回填现有配置，保存调用 PUT，取消编辑可恢复空表单；
- 服务层主动检查 Agent 名称，重复时返回明确的“Agent 名称已存在，请换一个名称或编辑已有 Agent”；
- 回归：后端 12 个测试通过，前端生产构建通过；重名断言加入现有版本隔离测试。
- 真实 MySQL 验收：临时 8081 新代码对已有“科研知识助手”发起同名 POST，返回 HTTP 409 和精确提示；请求前后 Agent 数量均为 2，没有产生脏数据；验收进程随后关闭。

## 阶段 6：高风险工具审批最小闭环（2026-09-05）

- 真实 DeepSeek 工具调用已通过数据库步骤复核：上海和伦敦请求均形成 `MODEL_CALL → TOOL_CALL → TOOL_RESULT → MODEL_CALL`；伦敦参数为 `Europe/London`，结果正确包含夏令时 UTC+1；
- 新增 Flyway V4 `approval_request`，保存运行、工具调用、原参数、SHA-256、状态、理由和有效期；
- HIGH 工具执行前进入 `WAITING_APPROVAL`，通过 SSE 展示参数；批准只能按原摘要消费一次，拒绝/过期不执行；
- 新增受控样例 `write_workspace_note`：只在 `data/tool-workspace` 新建 `.md/.txt`，禁止覆盖、路径穿越、空内容和超长内容；
- 前端提供批准一次与拒绝操作，并显示审批步骤；默认审批有效期 90 秒；
- 后端 15 个测试通过，覆盖批准摘要消费、拒绝端到端循环、受控写入和路径穿越；前端生产构建通过。
- 临时 8081 真实启动验证：MySQL 8.4 从 V3 成功迁移到 V4，`GET /api/tools` 同时返回 `current_time` 与 HIGH/WRITE 的 `write_workspace_note`，健康状态为 UP；验收进程随后关闭。

当前限制：等待中的运行不能跨应用重启恢复，尚无登录/RBAC、审批人身份、主动取消、SSH、MCP 或通用命令执行。

用户人工验收补充：拒绝 `approval-reject-test.md` 后未执行写入；批准 `approval-accept-test.md` 后文件创建成功，真实审批双分支通过。

知识包最终同步回归：

- 更新后的 00–10 为 11 份文档、27 个 chunk，SHA-256 `e3338b860bd32c6bd6891c2ff981a81f3f1cba0446b22af153a0ea4c7a479ccb`；
- Qwen Recall@5 100%、MRR 0.8854、改写题 MRR 0.8958、p95 245.068 ms；
- 同语料 LocalHash Recall@5 87.50%、MRR 0.6632、改写题 MRR 0.5903；
- 两者无答案误召回率仍为 100%，审批材料扩充没有改变既有拒答短板。

## 阶段 7：运行取消、总时限与历史详情（2026-09-05）

- 新增 RunControlService，以 runId 绑定执行线程和默认 120 秒截止时间；配置项为 `AGENT_RUN_TIMEOUT`；
- `POST /api/runs/{id}/cancel` 先持久化 CANCEL_REQUESTED，再中断执行线程；最终区分 CANCELLED 与 TIMED_OUT；
- 模型流、ReAct、审批等待和工具失败边界均检查终止信号，终止写入 RUN_TERMINATION 并发送 `terminated` SSE；
- `GET /api/runs` 返回最近运行摘要，`GET /api/runs/{id}` 返回完整步骤；前端新增停止按钮和运行记录页面；
- 应用启动时把旧进程遗留的非终态运行关闭为 INTERRUPTED、待决审批改为 EXPIRED；这是状态收口，不是断点续跑；
- 后端 17 个测试通过，其中阻塞模型测试覆盖主动取消、300 ms 总超时、历史摘要和终止步骤；前端生产构建通过。
- 提交前并发复核补上最终状态的条件更新，防止 `COMPLETED`/`FAILED` 在取消竞态中覆盖 `CANCEL_REQUESTED`；完整 17 测试与前端构建再次通过。
- 用户在真实 DeepSeek、MySQL 和浏览器环境完成运行治理验收：运行 `86918b87` 主动停止后为 `CANCELLED`、记录 4 步并显示“用户主动取消运行”；运行 `f72e72ab` 不干预直至默认 120 秒截止后为 `TIMED_OUT`、记录 7 步并显示“运行超过总时限 120 秒”。

当前限制：Java 中断是协作式取消；第三方工具仍需提供自己的取消能力。系统按单实例设计，多实例共享数据库前需要实例租约。客户端直接断开还不会自动提交取消，应用重启也不会从 RunStep 继续。

## 阶段 9：MCP Streamable HTTP 核心闭环（2026-09-05）

- 新增 Flyway V6，持久化 MCP Server、协议协商结果、同步错误和带描述指纹的工具目录；
- 实现 MCP `2025-06-18` initialize/initialized、tools/list 分页和 tools/call，支持 application/json 与 POST 返回的 text/event-stream，并传递 session id 和协议版本头；
- 工具生成平台稳定名称，避免跨 Server 重名；描述或 Schema 改变产生新修订，旧记录不覆盖；
- 动态 MCP 工具进入原 ToolRegistry，并继续经过 Schema 校验、HIGH 一次性审批、目标绑定、单工具超时、运行取消和 AuditEvent；
- MCP 凭据只保存环境变量名。HTTP 仅允许回环地址，远程 Endpoint 强制 HTTPS；
- 前端新增 MCP 连接页，支持保存并连接、失败原因、重新同步和发现工具展示；
- 随项目新增零依赖 Node.js fixture Server，可人工验证完整链路。

自动化证据：H2 成功应用 V1 至 V6；协议测试覆盖有状态初始化、协议版本头、工具发现、tools/call 和 JSON-RPC 错误；集成测试覆盖动态工具进入统一注册表、稳定命名、HIGH 风险、目标环境和执行委托。全量后端共 23 个测试通过，前端生产构建通过。

尚待人工验证：用户本机 MySQL 的 V6 迁移，以及 DeepSeek 对动态 MCP 工具的真实 tool_call、批准和拒绝分支。当前不支持 stdio、OAuth、resources、prompts、sampling、通知订阅或 MCP session 复用。

知识包加入 MCP 材料后再次执行冻结 30 题回归：00–10 为 11 份文档、35 个 chunk，语料 SHA-256 `a1970fceb87a09ad2044ef027b1de0cd3086b510842f3d8817e0ab79bac46259`。LocalHash Recall@5 87.50%、MRR 0.6854；Qwen Recall@5 100%、MRR 0.8278、改写题 MRR 0.8361、p95 233.375 ms。两者无答案误召回率仍为 100%，MCP 文档扩充没有解决证据充分性判断。结果 JSON 保存每份文档哈希；后续文字修订应以新快照复测，不能把本段哈希当作始终不变的目录标识。

知识包同步回归：

- 00–10 扩充为 11 份文档、32 个 chunk，SHA-256 `954f15a497955490510433e99c0878eb21df9975dba56644a506648f6aabb482`；
- Qwen Recall@5 100%、MRR 0.8542、改写题 MRR 0.8056、p95 244.784 ms；
- LocalHash Recall@5 79.17%、MRR 0.6333；两者无答案误召回率仍为 100%；
- 语料增长后 Qwen MRR 低于上一版 0.8854，已如实记录为结构化切块与 rerank 的后续输入，没有用旧语料指标覆盖。

## 阶段 8：统一安全执行网关与结构化审计（2026-09-05）

- Flyway V5 新增 `audit_event`，审批记录补充会话、AgentVersion、能力、风险和目标环境；H2 从空库顺序执行 V1–V5 通过；
- ChatService 把工具执行委托给 SafeExecutionGateway，固定执行 Schema 校验、风险审批、取消检查、限时执行和审计；
- AuditEvent 只保存参数 SHA-256 和输出长度/耗时等摘要，不复制原始参数或完整工具输出；
- `GET /api/audit-events?runId=...` 返回单次运行审计，运行详情页新增安全审计区；
- 自动化覆盖 LOW 工具的校验与执行审计、HIGH 工具的审批与跳过审计，以及缺失字段、额外字段和错误类型三类 Schema 拒绝；
- 后端 20 个测试通过，0 失败、0 错误；前端 TypeScript 与 Vite 生产构建通过。

尚待真实环境验证：用户重启后端后由 MySQL 执行 V5，并分别运行一次 current_time 和 write_workspace_note，确认历史详情出现安全审计。当前审计没有用户身份、审批人、签名防篡改、租户隔离和保留策略，只能称为本地结构化审计。
