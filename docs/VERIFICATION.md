# 验证记录

## 2026-09-21：LLM 消息安全 Markdown 渲染

“对话测试台”和“远程工作台 → 操作助手”的 Agent 消息改用 `react-markdown + remark-gfm` 渲染标题、段落、列表、强调、引用、行内代码、代码块、链接和表格；流式生成中的未闭合 Markdown 可继续按普通文本显示。用户消息、ApprovalRequest 参数、RunStep 工具输入/输出及原始日志不进入 Markdown 渲染器。

渲染器未启用原始 HTML；链接只允许锚点、HTTP、HTTPS 和 mailto，外部链接附带 `noopener/noreferrer` 并在新窗口打开。Markdown 远程图片降级为文字占位，不向模型指定的地址发起请求。代码块与表格限制在消息卡内部横向滚动，保留工作台固定高度和内部滚动边界。Vitest 4 项测试覆盖 GFM 表格/代码、原始 HTML、危险链接、远程图片、外部链接隔离和流式未闭合文本；前端生产构建通过，282 个模块。用户通过真实 `COMPOSE_STATUS` 运行 `9bafe710-5d27-456c-92e5-9bad2066aec7` 确认项目列表、行内代码和表格渲染符合预期，批次 2.5 已人工验收。

## 2026-09-21：部署诊断长审批目标兼容修复

真实模型首次逐项请求五种部署诊断时，五项都在创建 `ApprovalRequest` 阶段失败，数据库报错为 `Data too long for column 'target_environment'`。审计只到 `TOOL_REQUEST_VALIDATED`，没有 `APPROVAL_REQUIRED` 或 `TOOL_EXECUTION_STARTED`，因此本次失败没有连接 SSH、没有运行 Docker/Nginx/HTTP 命令，也没有影响网站。原因是 V5 将审批目标定义为 `VARCHAR(160)`，而部署诊断会把 SSH 身份、主机指纹、部署根、Compose 项目/文件和健康地址共同绑定到审批快照，合法目标可能超过 160 字符。

Flyway V13 将 `approval_request.target_environment` 前向扩展为 `VARCHAR(4096)`，不修改既有迁移。会话集成回归现在实际写入并核对超过 160 字符的审批目标。后端全量 `mvn test` 为 71 个测试通过、0 失败、0 错误、0 跳过，Flyway 空库 V1–V13 通过；前端 TypeScript/Vite 生产构建通过，29 个模块。

重启后端并完成 V13 真实 MySQL 迁移后，用户逐项请求并批准五项真实生产只读诊断，均完成 `TOOL_REQUEST_VALIDATED → APPROVAL_REQUIRED → APPROVAL_DECIDED → TOOL_EXECUTION_STARTED → TOOL_EXECUTION_COMPLETED` 审计链：`COMPOSE_VALIDATE` 运行 `8c0d0ba0-9427-44c7-8ee8-454406ef5a94`（720 ms），`COMPOSE_STATUS` 运行 `aea5d640-8a0c-4649-b814-5721094d911e`（318 ms），`NGINX_VALIDATE` 运行 `4d486b06-6735-4f85-a5d3-1f2c7035d1e0`（303 ms），`SITE_HEALTH` 运行 `ec8f2e73-3698-4c9c-875a-d7b6908f134b`（HTTP 200，117 ms），`RELEASE_FINGERPRINT` 运行 `2c7f32d1-88a6-4c08-bc7b-39911969cfb2`（214 ms）。五项均 `successful=true`、退出码 0、输出未截断；用户确认结果符合预期。准确口径更新为“远程部署五项只读诊断正向链已人工验收”，仍不能声称已经执行部署、备份或回滚。

## 2026-09-19：独立生产 Profile 与只读部署诊断

Flyway V12 新增单一部署 Profile，保存本地源码根、远程部署/备份根、Compose 文件/项目、Nginx 配置相对路径和固定回环健康地址。SSH 主机、端口、用户、密码凭据和固定 SHA-256 主机指纹继续引用既有 SSH 工作区；普通 `remoteRoot` 没有放宽。工作台“部署”标签现在可保存并只读检查 Profile，显示四个固定服务、五张诊断卡和有界结果。

新增 `inspect_remote_deployment`（`SSH/EXECUTE/HIGH`），Schema 只有五种枚举 task：`COMPOSE_VALIDATE`、`COMPOSE_STATUS`、`NGINX_VALIDATE`、`SITE_HEALTH`、`RELEASE_FINGERPRINT`。工具不接受 path、命令、服务名、URL、参数或环境变量。每次调用在同一 SSH 会话先用 SFTP 逐级拒绝符号链接并检查固定清单，再运行静态映射的 Exec Channel；`.env` 仅对固定路径执行 `lstat`，不读取内容。内部时限 30 秒，输出约 16 KB 首尾保留，stdout/stderr 合并，超时或中断关闭通道；非零退出码作为工具结果返回。

`RemoteDeploymentToolTests` 覆盖工具注册、同会话、五种命令映射、`.env` 不进入命令/输出、成功、非零退出、超时关闭、输出截断、未知任务和危险额外参数拒绝；控制器测试覆盖 V12 Profile 保存/读取和非回环健康地址拒绝。全量后端 `mvn test` 为 71 个测试通过、0 失败、0 错误、0 跳过，Flyway 空库 V1–V12 通过；前端 TypeScript/Vite 生产构建通过，29 个模块。真实生产服务器与真实模型尚未验收，当前不能声称部署、备份或回滚完成。

## 2026-09-18：受控远程工作台与审批目标绑定

新增“远程工作台”一级页面，采用远程文件区、文件/变更/任务/部署/输出标签区和 Agent/审批/步骤区三栏布局。初版人工验收反馈表明，让文件树和文本预览也等待模型会造成明显延迟；因此人工浏览改为专用只读 API，直接复用既有目录/文本工具及其固定指纹、受限根、逐级符号链接和受保护路径校验，不创建 AgentRun、会话或模型调用。该 API 不提供写入、补丁、搜索或命令能力。Agent 自主读取、远程补丁和五种固定 SSH 任务仍继续通过 AgentVersion、ToolRegistry、SafeExecutionGateway、ApprovalRequest、RunStep 与 AuditEvent，没有新增旁路执行接口或任意终端。

工作台使用视口固定高度和三栏内部滚动；右侧操作助手只展示当前 RunStep 和最近六条消息，更早消息收起并保留在运行记录，连续操作不再撑高整页。任务区只允许选择五个固定 task 和相对项目路径；本批次完成时部署区仅为无执行能力的占位说明，后续只读诊断见上一节。

SafeExecutionGateway 现在会在 HIGH 审批通过后、工具启动前重新解析目标。目标与 ApprovalRequest 快照不一致时记录 `TOOL_TARGET_CHANGED/REJECTED` 并阻止执行；SSH 目标快照同时包含主机、端口、用户名、远程根和固定 SHA-256 主机指纹。新增会话集成测试在审批等待期间改变 SSH 配置，确认旧审批不能启动新目标工具。专项浏览控制器与会话测试为 15 个测试通过；该批次全量后端 `mvn test` 为 65 个测试通过，Flyway 从空库验证 V1–V11；前端生产构建通过。2026-09-19 用户确认多项固定任务、无模型文件浏览、父目录导航和操作助手内部滚动符合预期，工作台阶段已人工验收；未提供具体运行 ID，完整证据仍以运行库为准。

## 锚点后阶段 6：受控 SSH Exec（2026-09-16）

新增 `run_remote_workspace_task`，标记为 `SSH/EXECUTE/HIGH`，只接受远程根内相对 `path` 和 `GIT_STATUS`、`GIT_DIFF_SUMMARY`、`MAVEN_TEST`、`NPM_TEST`、`NPM_BUILD` 固定枚举。模型不能提供命令、参数、环境变量或 Shell 文本。每次工具调用在一个短生命周期已认证 SSH 会话中复用 SFTP 与 Exec Channel：先逐级 `lstat` 校验目录、符号链接和项目标记，再执行固定映射；工作目录另受保守字符集约束。

`RemoteSshExecToolTests` 的进程内 Apache MINA SSHD 服务器同时配置 SFTP Subsystem 与 CommandFactory，覆盖同会话复用、五种固定命令映射、工具注册与具体目标、成功、非零退出、stdout/stderr 合并、75 秒内部超时关闭、约 16000 字节首尾截断、越界、符号链接、缺失项目标记和不安全工作目录。会话集成回归验证 AgentVersion 绑定、SafeExecutionGateway 的参数绑定一次性 HIGH 审批、拒绝后不连接服务器，以及 `command` 类额外参数在审批前由 Schema 拒绝并形成安全审计。

本阶段沿用 AgentVersion、ToolRegistry、ApprovalRequest、RunStep 与 AuditEvent，没有新增表、旁路执行接口或 MCP Server。全量后端 `mvn test` 为 63 个测试通过、0 失败、0 错误、0 跳过；H2 仍从空库校验 Flyway V1–V11，既有 RAG、MCP、运行控制、Coding 与 SSH/SFTP 回归未破坏。前端 TypeScript 与 Vite 生产构建通过，29 个模块完成打包。2026-09-17 至 2026-09-18，真实 DeepSeek 模型在真实 Ubuntu/OpenSSH 目标上完成 Git 状态、Git 差异摘要、Maven test、npm test 和 npm build 五种固定任务的正向运行；真实链路还验证退出码 127/1 作为完成结果返回、75 秒超时关闭远程通道，以及错误项目标记安全失败。审批拒绝、危险额外参数、输出截断、越界和符号链接绕过由自动化覆盖，本轮没有在真实服务器重复制造这些副作用。运行 ID 和输出见 `SSH_EXEC_ACCEPTANCE.md`。仍不支持 sudo、PTY、交互式/后台/任意 Shell、Git 修改、自定义参数或环境变量、依赖安装、Docker/Nginx、部署与回滚。

## 锚点后阶段 5：SSH/SFTP 受审远程文本补丁（2026-09-15）

新增 `apply_remote_workspace_text_patch`，标记为 `SSH/WRITE/HIGH`。它只修改授权远程根内既有 UTF-8 普通文件，要求读取时 SHA-256 一致、每段旧文本唯一匹配；批准后先写同目录独占临时文件、保留原权限、再次核对摘要，再要求 SFTP Server 原子覆盖。服务器不支持原子替换时失败关闭，不退化为直接覆盖。

`RemoteSftpWorkspaceTests` 使用进程内真实 SSH/SFTP Server 验证工具注册及具体 SSH 目标、摘要绑定成功补丁、权限保留、临时文件清理，以及陈旧摘要、缺失/重复文本、越界、`.env`、二进制和不存在文件失败不改。首次真实 Ubuntu/OpenSSH 验收暴露：OpenSSH 固定协商 SFTP v3，而标准 rename 的 `Atomic/Overwrite` 选项要求 v5+，客户端在发送请求前即抛出 `UnsupportedOperationException`。修复后优先检测并调用 OpenSSH `posix-rename@openssh.com` 扩展，高版本才使用标准选项；v3 又没有该扩展时继续失败关闭。专项测试现强制协商 v3，并覆盖扩展成功与扩展缺失拒绝两条分支。

HIGH 审批、一次性参数绑定、拒绝跳过与审计由既有 SafeExecutionGateway/ApprovalRequest 集成回归共同覆盖。修复后全量后端为 54 个测试通过、0 失败、0 错误、0 跳过；Flyway V1–V11、RAG、MCP、运行控制与既有 Coding 回归均未破坏。前端 TypeScript 与 Vite 生产构建通过，29 个模块完成打包。

真实 Ubuntu/OpenSSH 服务器与真实模型已经完成批准修改、批准恢复、审批拒绝和陈旧摘要四条分支：运行 `c02f0f09...` 将 `remote-before` 改为 `remote-after`，运行 `1349ba57...` 恢复原文，两次都返回 `updated=true` 且形成完整 HIGH 审批及完成审计；运行 `3381d12b...` 被拒绝并形成 TOOL_EXECUTION_SKIPPED；运行 `e139b076...` 在审批等待期间由人工连续修改文件，两次旧摘要执行均形成 TOOL_EXECUTION_FAILED，人工新内容未被覆盖。修复前失败运行 `33853a3f...` 作为 SFTP v3 兼容问题的发现证据保留。准确结论是远程受审单文件文本补丁已验收；远程 Shell、Git、构建测试、文件新建/删除、Docker/Nginx 和部署仍未实现。

## 锚点后阶段 4：SSH/SFTP 远程只读工作区（2026-09-15）

新增 `list_remote_workspace_directory`、`search_remote_workspace_files`、`read_remote_workspace_text_file` 三个 `SSH/READ/LOW` 工具。连接要求应用外核验的 SHA-256 主机指纹，密码由环境变量或 DPAPI 安全凭据提供；远程路径限制在单一授权根，逐级拒绝符号链接并保护密钥路径。工具继续通过 AgentVersion、ToolRegistry、SafeExecutionGateway、RunStep 与 AuditEvent。

`RemoteSftpWorkspaceTests` 使用进程内真实 SSH/SFTP Server 验证握手、密码认证、目录浏览、剪枝搜索、UTF-8 分段读取、SHA-256、具体远程目标，以及错误主机指纹、越界和 `.env` 拒绝。指纹探测会先触发不携带身份的 SSH 密钥交换，再读取主机公钥，避免只建立 TCP 会话时误报“服务器未提供主机公钥”。V11 和 `SshWorkspaceControllerTests` 进一步验证页面连接配置持久化、密码不进入配置表、根目录拒绝和工具动态读取已保存配置。加入本阶段后全量后端为 51 个测试通过、0 失败、0 错误、0 跳过，前端 TypeScript 与 Vite 生产构建通过。

真实 Ubuntu 服务器和模型主链已完成三次正向、两次反向验收：目录浏览 `b8edbcb9...`、搜索 `60767fb6...`、读取 `44a6c0d4...` 均成功；越界请求 `6dc7b118...` 由模型在工具调用前拒绝；错误指纹 `e6ac26e2...` 真实形成 FAILED ToolResult 和 TOOL_EXECUTION_FAILED 审计。完整证据见 `SSH_SFTP_READONLY_ACCEPTANCE.md`。远程 Shell、写入、Git、构建、日志命令和部署仍不在本阶段。

## 锚点后阶段 3：Coding 白名单本地验证（2026-09-15）

已实现 `EXECUTE/HIGH` 的 `run_workspace_verification`，只接受工作区相对目录以及 `MAVEN_TEST`、`NPM_TEST`、`NPM_BUILD` 固定任务。程序路径解析、项目标记、受限环境、75 秒进程预算、子进程树终止、约 16000 字符输出和非零退出码结果均在工具内部收口；调用继续通过 AgentVersion、ToolRegistry、SafeExecutionGateway、ApprovalRequest、RunStep 与 AuditEvent。

专项测试在 Windows 实际启动固定测试脚本，覆盖成功结果、非零退出、大输出首尾截断、超时终止、标记缺失、非法任务和工作区启动程序拒绝。会话集成测试确认该工具必须进入 HIGH 审批，拒绝后只形成 TOOL_EXECUTION_SKIPPED，不启动验证进程。最终全量后端 `mvn test` 为 46 个测试通过、0 失败、0 错误、0 跳过；H2 从空库校验 V1–V10，既有 RAG、MCP、审批、运行控制与 Coding 回归均通过。

真实 Maven/npm 与浏览器审批链已经通过。运行 `c86216ab-02fe-40b7-90af-0f19b3b665eb` 在 `backend` 执行 `MAVEN_TEST`，批准后 22085 ms、退出码 0；运行 `94773e95-f1fd-4490-87e0-ea247ea2a8e1` 在 `web` 执行 `NPM_BUILD`，批准后 4054 ms、退出码 0；运行 `f87a5e91-04d2-467d-adc0-5c92cd706378` 被拒绝后仅记录 TOOL_EXECUTION_SKIPPED。固定入口仍会执行项目自带代码，当前没有 OS 级文件系统或网络沙箱，仅适用于信任的本地仓库；不能宣称支持任意 Shell 或安全执行不可信代码。

## 锚点后阶段 2：Coding 受审文本补丁（2026-09-15）

已实现 `WRITE/HIGH` 的 `apply_workspace_text_patch`。读取工具返回完整原始文件的 SHA-256；补丁把该摘要、相对路径和精确 replacements 绑定到既有一次性 ApprovalRequest，继续通过 AgentVersion、ToolRegistry、SafeExecutionGateway、RunStep 与 AuditEvent，没有新增数据库迁移或旁路写接口。

单元测试覆盖摘要输出、工具风险注册、成功原子替换、陈旧摘要、重复匹配和越界拒绝，并确认失败时原文件保持不变。会话集成测试用可控模型发起补丁，真实经过审批请求、批准、执行、文件变更、运行步骤和审计完成事件。全量后端 `mvn test` 为 41 个测试通过、0 失败、0 错误、0 跳过；H2 从空库校验 V1–V10，既有 AgentVersion、审批、RAG、MCP、运行控制和安全凭据测试均通过。

真实模型与浏览器验收已确认批准写入与拒绝跳过：运行 `3cc5b2f0-e54f-424a-804c-6cc801fa112f` 完成 `target-before → target-after`，运行 `10c216d9-3c49-433f-9cf9-f5b3cbdaace9` 完成反向恢复，运行 `814047b0-54f8-43af-ac9d-9b53622079fc` 在审批拒绝后记录 TOOL_EXECUTION_SKIPPED。陈旧摘要由用户确认通过并有自动化证据，但运行库没有可独立识别的对应失败记录。准确结论是受审文本补丁子阶段已验收；统一 diff、预览页、撤销、文件新建/删除、命令、构建测试和 SSH/SFTP 仍未实现。

## 锚点后阶段 1：Coding 只读工作区（2026-09-14）

已实现三个 `READ/LOW` 工具：`list_workspace_directory`、`search_workspace_files`、`read_workspace_text_file`。它们由 AgentTool 自动注册进入原 ToolRegistry，并继续通过 SafeExecutionGateway；没有新建数据库表或绕过 AgentVersion、RunStep、AuditEvent、审批、RAG、MCP 的既有代码路径。

路径安全自动化覆盖相对路径约束、`..` 和绝对路径拒绝、Windows 数据流语法拒绝、受保护目录/`.env`/常见密钥文件拒绝、二进制拒绝、严格 UTF-8、文件大小/行数/结果数量边界、目录浏览、文件路径搜索和分段读取。集成测试让可控模型调用目录浏览工具，验证 AgentVersion 绑定、RunStep 的 TOOL_RESULT，以及 AuditEvent 的校验/开始/完成事件；审计响应不包含文件名或文件内容。

2026-09-15 搜索性能修复后全量后端执行结果：38 个测试全部通过，0 失败、0 错误、0 跳过。Windows 路径测试在普通符号链接不可用时创建目录联接，验证指向工作区外的目标仍被拒绝；新增测试验证生成目录默认剪枝、显式指定剪枝目录仍可搜索，以及达到结果上限后提前停止。前端 `npm run build` 通过，29 个模块完成生产打包。

人工验收已确认用户日常目录中的真实模型目录浏览、文本读取和优化后的根目录搜索通过，浏览器 RunStep 与安全审计一致。负向路径和 Windows 目录联接由自动化验证，本轮没有重复全部负向浏览器操作。证据见 `CODING_WORKSPACE_ACCEPTANCE.md`。本阶段不包含内容搜索、补丁、写入、命令、构建测试、SSH/SFTP 或部署，不能据此宣称通用 Coding 已完成。

首轮真实验收中，运行 `b70a18b6-f47e-48b2-9d59-a710a50d7532` 的两次根目录 `search_workspace_files` 均在 10 秒超时；模型第三次把 `path` 缩小到 `backend` 后扫描 647 项、1995 ms 完成。该证据推动了轻量剪枝和重复路径解析优化。修复后运行 `e435a565-737a-45ab-ab42-64c2f544c11e` 不指定 `path`，首次调用扫描 333 项、跳过 7 个目录、98 ms 完成，返回两个源码文件且未包含 `target` 生成物。优化前失败记录继续保留，不能从项目历史中抹去。

## 阶段 12：1.0.0-rc1 正式版前加固（2026-09-06）

- 版本统一为 `1.0.0-rc1`，新增系统诊断 API 与前端第 07 页，检查 MySQL、pgvector、Embedding 实际调用、模型密钥变量、data 写权限和 MCP；
- RAG 空召回且无工具时固定拒答并跳过模型，有候选片段时注入严格证据契约；
- 后端测试由 24 个增至 31 个，新增并发会话隔离、知识库 chunk/删除隔离、MCP 配置与 Resource HTTP 入口、空召回拒答、系统诊断和 Windows DPAPI 覆盖；最终 31 个通过、0 失败、0 错误；
- 前端 `npm run build` 通过，TypeScript 和 Vite 生产构建成功；
- Tavily 真实多轮检索暴露的审批不可见与工具轮数收尾问题已修复：审批卡固定显示、标题提醒和秒级倒计时；第四轮工具返回后以无工具模型调用完成 `MODEL_FINALIZATION`，不再直接把已有成功结果标为失败；
- PowerShell 运维脚本通过 Windows PowerShell 兼容解析；预检在本机确认 Maven 使用 Java 21.0.4、Docker、Node、npm、Ollama 和 data 目录可用；
- 发布检查最初因运行中的 Vite 锁住 esbuild 而使原地 `npm ci` 失败；改为经过路径前缀校验的随机临时副本后，干净安装和生产构建通过，不干扰开发服务器；
- 首次用户执行一键启动时，前述中断的原地 `npm ci` 已把日常 node_modules 留在不完整状态，前端日志明确显示找不到 vite；重新 `npm install` 后恢复。启动脚本因此增加本地 Vite 完整性检查和自动补装，并改用隐藏 cmd 子进程，避免隐藏 PowerShell Host 在 Maven 设置控制台标题时抛出异常；复测还发现 Windows 可能把 `localhost` 解析到 IPv6，造成 Vite 已启动而 IPv4 健康检查误报超时，现已把 Vite 明确绑定到 `127.0.0.1`，并让停止脚本容忍清理瞬间的进程退出竞态。最终实测启动脚本约 14 秒成功返回，后端状态为 `UP`、版本为 `1.0.0-rc1`，前端返回 HTTP 200，停止脚本随后完整关闭两个进程树；
- Docker 真实备份演练成功，生成非空 MySQL SQL、pgvector SQL、data.zip 和带 `1.0.0-rc1` 的 manifest；恢复脚本的强制确认保护已验证，未在用户当前数据库执行破坏性恢复；
- 备份演练先后发现并修复旧版 PowerShell 解析差异、MySQL 应用账号缺少 PROCESS 权限、管道覆盖原始退出码三个问题。最终使用 `--no-tablespaces` 并在写文件前固定保存退出码，避免半份备份误报成功。

尚待用户集中人工验收：重启日常后端后查看“系统诊断”，完成真实 DeepSeek 普通/无答案问答、一次并发双会话、HIGH 工具批准与拒绝，以及 HTTP/stdio MCP 集中链路。通过后才从 rc1 升为 `1.0.0`。

## 阶段 13：真实网络检索后的运行收口（2026-09-07）

- Tavily Streamable HTTP 使用 Bearer 环境变量成功同步并执行。运行 `ddcba638` 中首次 search 已完成并产生额度，后续 extract 审批未在视口内被发现，90 秒过期后又触发重试，最终碰到 120 秒总时限。
- 运行 `4b7783e1` 的四个工具轮次全部获批并完成，包含多次 search 与 extract；最后已取得 MCP 官方规范和 GitHub Release 证据，却因循环结束后没有最终模型调用而失败。
- 修复后审批卡在消息滚动区内 sticky 显示，浏览器标题提示待审批，页面显示实时剩余秒数，过期后按钮禁用。
- ReAct 在正常轮次中加入“最少必要调用”约束；工具预算耗尽后移除全部工具并追加一次强制总结，记录为 `MODEL_FINALIZATION/COMPLETED`。模型若仍返回工具请求或空答案才安全失败。
- 运行记录继续只保留 4000 字符工具输出，模型观察上下文单独提高到 16000 字符，避免 Tavily 多来源结果因展示截断而迫使模型反复搜索。
- 回归测试把原“达到轮数即失败”用例改为“第五次无工具总结并完成”；目标测试 6 个通过，全量后端 30 个测试通过、0 失败、0 错误，前端 TypeScript 与 Vite 生产构建通过。

## 阶段 14：安全凭据与 Agent 版本生命周期（2026-09-07）

- 新增统一 SecretResolver，模型调用、HTTP MCP、stdio MCP 和系统诊断都按“进程环境变量优先、Windows DPAPI 次之”解析；
- 前端仅提交密钥新值并展示 ENVIRONMENT/SECURE_STORE/NONE 状态，后端 API、MySQL、AgentVersion 和 MCP 配置均不返回或持久化明文；
- SecretStore 专项测试真实执行 DPAPI 写入、解密与删除，并确认磁盘文件不含原始字符串；
- Flyway V10 为 AgentVersion 增加 archived_at；对话默认只显示每个 Agent 最新版本，历史版本可显式展开，归档版本不能通过 API 创建新会话；
- 永久删除要求“非最新版 + 已归档 + Conversation/AgentRun 零引用”，避免破坏历史运行；Agent 集成测试覆盖归档隐藏、管理查询、零引用删除和最新版拒绝归档；
- H2 从空库顺序执行 V1–V10，最终全量后端 31 个测试通过、0 失败、0 错误；前端 TypeScript 与 Vite 生产构建通过；`git diff --check` 无空白错误。

尚待用户本机人工验收：启动后由 MySQL 执行 V10，在页面保存 DeepSeek/Tavily 凭据并重启验证持久可用；发布两个测试版本，确认默认下拉、历史开关、归档/恢复和有引用版本禁止删除均符合提示。

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

阶段 9 当时尚待人工验证用户本机 MySQL 的 V6 迁移，以及 DeepSeek 对动态 MCP 工具的真实 tool_call、批准和拒绝分支；当时也不支持 stdio。后续用户已提供 HTTP MCP 批准调用成功的运行记录，stdio 与管理能力则在阶段 10 补齐。

知识包加入 MCP 材料后再次执行冻结 30 题回归：00–10 为 11 份文档、35 个 chunk，语料 SHA-256 `a1970fceb87a09ad2044ef027b1de0cd3086b510842f3d8817e0ab79bac46259`。LocalHash Recall@5 87.50%、MRR 0.6854；Qwen Recall@5 100%、MRR 0.8278、改写题 MRR 0.8361、p95 233.375 ms。两者无答案误召回率仍为 100%，MCP 文档扩充没有解决证据充分性判断。结果 JSON 保存每份文档哈希；后续文字修订应以新快照复测，不能把本段哈希当作始终不变的目录标识。

## 阶段 10：MCP stdio 与日常管理（2026-09-05）

- Flyway V7 增加 transport、stdio 启动参数/工作目录/环境变量引用和单工具 enabled 策略；原 HTTP Server 自动保持 `STREAMABLE_HTTP`；
- 新增 stdio 客户端，覆盖进程启动、初始化、换行 JSON-RPC、tools/list、tools/call、stderr 诊断、取消通知和分级进程清理；
- 启动参数不经过 shell，拒绝直接配置命令行解释器；密钥只按宿主环境变量名注入，不写入数据库；
- MCP 页面支持 HTTP/stdio 条件配置、编辑、启停、受保护删除、同步差异，以及单工具启用、风险和超时策略；
- 被 Agent 草稿或历史版本引用的 Server 不能物理删除，保证运行记录与版本引用仍可解释；
- 零依赖 stdio Node fixture 可用于人工全链路验收。

自动化证据：H2 成功应用 V1 至 V7；真实子进程测试完成 stdio 发现和调用；HTTP 协议与统一注册表回归继续通过；管理集成测试覆盖同步差异、工具停用、Server 停用和未引用删除。全量后端 24 个测试通过，前端 TypeScript 与 Vite 生产构建通过。

尚待人工验证：用户本机 MySQL 应在后端重启时从 V6 升至 V7，并在页面使用 stdio fixture 完成真实 DeepSeek tool_call。当前未实现 OS 沙箱、OAuth、resources、prompts、sampling、roots、elicitation、list_changed 订阅和 session 池。

知识包完成 stdio 与 Server 管理材料后，重新运行同一冻结 30 题：00–10 仍为 11 份文档，切分后为 38 个 chunk，语料 SHA-256 `686a21f66cf2d931e298d5a77e25d5d0658909e7bd5201708d79273befd098cd`。LocalHash Recall@5 87.50%、MRR 0.6542、改写题 MRR 0.5306、p95 0.272 ms；Qwen Recall@5 100%、MRR 0.8958、改写题 MRR 0.8333、p95 260.366 ms。两者无答案误召回率仍为 100%。结果保存在 `local-hash-after-mcp-stdio.json` 与 `qwen3-embedding-0.6b-after-mcp-stdio.json`。

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

## 阶段 11：MCP Resources、Prompts 与集中收口（2026-09-05）

- Flyway V8 增加 Resource 与 Prompt 目录，分别保存 URI、媒体类型、大小、参数描述、active 状态和稳定标识；V9 增加 MCP 同步历史；
- Streamable HTTP 与 stdio 都按协商 capability 分页发现 tools、resources 和 prompts，不要求 Server 同时支持三者；
- Resource 支持按需读取、预览及导入当前知识库。导入复用 20 MB 限制、Tika、切块、EmbeddingGateway 和 pgvector；多段文本合并，混合或多段二进制拒绝；
- Prompt 支持必填参数校验和 `prompts/get`，保留 Server 返回的角色与内容；它只由用户主动使用，不会自动替换 Agent 系统提示词；
- 每次同步保存 READY/FAILED、协议版本、三类能力数量、工具差异与错误；管理台可查看最近记录；
- Server 配置支持 JSON 导入导出，只包含 Bearer Token 环境变量名或 stdio 环境变量名称映射，不包含密钥值；
- HTTP 与 stdio 的零依赖 fixture 均提供工具、Resource 和 Prompt，集中人工验收不依赖第三方 MCP 服务。

自动化证据：H2 从空库成功执行 V1–V9；HTTP 协议测试在独立短 session 中完成三类目录发现、resources/read、prompts/get 和 tools/call；stdio 测试真实启动 Node 子进程完成同一组能力；服务集成测试验证目录持久化、读取委托、Prompt 参数、同步历史和安全配置导出。全量后端 24 个测试全部通过，前端 TypeScript 与 Vite 生产构建通过。

尚待集中人工验收：用户本机 MySQL 从 V7 迁移到 V9，页面分别连接 HTTP/stdio fixture，完成 Resource 预览与知识库导入、Prompt 参数生成、工具批准/拒绝、同步历史和配置导入导出。当前仍不支持 OAuth 授权码/PKCE 与安全令牌存储、sampling、elicitation、roots、resource templates、subscriptions/list_changed、旧版 HTTP+SSE 或长 session 池；stdio 仍无 OS 级沙箱。以上是明确的扩展边界，不能描述为“兼容全部 MCP Server”。

知识包同步回归：冻结的 00–10 为 11 份文档、42 个 chunk，语料 SHA-256 `35639a984b8a9607645ea4b7447d4643dd63161431b7a66e0c2026ebdae5e3a8`。LocalHash Recall@5 87.50%、MRR 0.6507、改写题 MRR 0.5903、p95 0.296 ms；Qwen Recall@5 100%、MRR 0.8542、原题 MRR 0.9167、改写题 MRR 0.7917、p95 275.724 ms。两者无答案误召回率仍为 100%。结果保存在 `local-hash-after-mcp-primitives.json` 和 `qwen3-embedding-0.6b-after-mcp-primitives.json`；知识扩充后 Qwen 仍保持全召回，但无答案判断依旧是明确短板。
