# ADR 0017：固定任务的受审工作区验证

## 背景

受审文本补丁已经形成安全写入闭环，但修改后仍需要用户离开 Agent Studio 手工运行测试。直接开放 Shell、允许模型传命令或参数，会把命令注入、目录逃逸和不可解释副作用带入平台；当前又没有 OS 级进程沙箱，因此本阶段只交付最小的固定验证动作。

## 决策

新增内置 `run_workspace_verification`，能力为 `EXECUTE/HIGH`。模型参数只有工作区相对目录 `path` 和固定枚举 `task`；支持 `MAVEN_TEST`、`NPM_TEST`、`NPM_BUILD`，不接受命令文本或额外参数。对应命令固定为 Maven batch test、`npm test` 和 `npm run build`。

工作目录继续经过 `CodingWorkspace` 的路径、符号链接和受保护目录校验。Maven 任务要求安全的 `pom.xml`，npm 任务要求安全的 `package.json`。Maven/npm 启动程序从宿主机 PATH 或管理员配置的 `AGENT_STUDIO_MAVEN_COMMAND`、`AGENT_STUDIO_NPM_COMMAND` 解析为真实绝对路径，并拒绝执行位于授权代码工作区内的启动程序，降低 PATH 被仓库内容劫持的风险。

工具仍由 AgentVersion 快照绑定，经 SafeExecutionGateway 创建参数绑定的一次性 ApprovalRequest；拒绝、过期或取消不会启动进程。子进程环境清除后只保留操作系统、PATH、Java/Maven/npm 缓存和区域设置所需的白名单变量，另设置 `CI=true` 和 `NO_COLOR=1`。工具内部进程预算为 75 秒，外层工具预算为 90 秒；超时或线程中断时强制终止已发现的子进程树。

合并后的标准输出和错误输出最多保留约 16000 字符，超限时保留开头与结尾。正常非零退出码属于验证结果而不是平台异常，返回 `successful=false`、退出码、耗时和截断标志；进程无法启动、超时或安全校验失败才作为工具失败进入审计。

## 安全边界

固定 Maven/npm 命令仍会执行项目自己的插件、测试与 package scripts，也可能生成 `target`、`dist` 等产物。环境变量最小化不能构成文件系统或网络隔离；项目代码仍以当前 Windows 用户权限运行。因此该能力只适用于用户信任的本地仓库，必须逐次审批，不能描述为安全执行任意第三方代码。

本阶段不包含任意 Shell、自定义参数、依赖安装、clean、发布、Git、Docker、长期后台进程、交互式输入、OS 沙箱或资源配额。没有为 Coding 新增旁路执行器、数据库表或 MCP Server。
