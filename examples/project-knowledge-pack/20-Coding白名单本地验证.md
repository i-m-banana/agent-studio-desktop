# Agent Studio Desktop：Coding 白名单本地验证

## 当前状态

2026-09-15，在只读工作区和受审文本补丁之后，项目实现并验收固定 Maven/npm 本地验证工具。自动化、真实 Maven Test、真实 npm Build 和审批拒绝链均已通过；它仍不能表述为通用终端。

## 能力与运行链

`run_workspace_verification` 是内置 `EXECUTE/HIGH` 工具，不属于 MCP。模型只能提交相对工作目录和 `MAVEN_TEST`、`NPM_TEST`、`NPM_BUILD` 三种任务之一，不能提交命令字符串或附加参数。工具由 AgentVersion 固化，继续经过 ToolRegistry、SafeExecutionGateway、ApprovalRequest、RunStep 和 AuditEvent。

批准后，工具确认工作目录仍在授权根内，并检查 `pom.xml` 或 `package.json`。Maven/npm 程序解析为宿主机真实绝对路径，不能来自代码工作区。进程只继承必要的白名单环境变量；输出合并并限制约 16000 字符，超限保留首尾；75 秒超时或取消会终止进程树。测试失败以 `successful=false` 和非零退出码返回，让 Agent 能基于真实结果解释失败。

## 为什么仍是高风险

白名单限制的是启动入口，不是项目代码本身。Maven plugin、测试和 npm scripts 都可能读写文件、访问网络或启动其他程序，并会生成构建产物。当前没有 Windows Job Object、低权限账户、容器或 VM 沙箱，所以该工具只适用于用户信任的本地项目，并且每次必须审批。环境变量最小化可以降低秘密被无意继承的概率，但不能冒充完整隔离。

## 自动化与未完成项

专项测试在 Windows 上真实启动固定脚本，覆盖成功、非零退出码、大输出截断、75 秒预算的缩短测试版本与子进程终止、项目标记缺失、非法任务和工作区内启动程序拒绝。会话集成测试验证 AgentVersion 绑定、HIGH 审批以及拒绝后 TOOL_EXECUTION_SKIPPED。全量后端为 46 个测试通过、0 失败、0 错误、0 跳过；真实验收记录见 `docs/CODING_VERIFICATION_ACCEPTANCE.md`。

真实运行证据为：`c86216ab-02fe-40b7-90af-0f19b3b665eb` 完成 Maven Test，`94773e95-f1fd-4490-87e0-ea247ea2a8e1` 完成 npm Build，`f87a5e91-04d2-467d-adc0-5c92cd706378` 在拒绝后形成 TOOL_EXECUTION_SKIPPED。当前没有任意 Shell、自定义参数、依赖安装、clean/package/deploy、Git、Docker、交互式任务、后台服务、OS 沙箱、SSH/SFTP 或部署。下一步可评估 Git 只读状态或 SSH/SFTP。
