# Coding 白名单本地验证人工验收

适用范围：`feat/coding-workspace` 的固定 Maven/npm 验证子阶段。代码、自动化以及真实 Maven/npm 批准与拒绝链路均已完成。只在你信任的本地仓库执行，因为构建和测试会运行项目代码，当前没有 OS 沙箱。

## 准备

1. 保持 `AGENT_STUDIO_CODING_WORKSPACE` 指向 `agent-studio-desktop`，确认 Maven、npm 可从启动后端时的 PATH 找到，然后重启后端。
2. 若 PATH 不稳定，可分别设置 `AGENT_STUDIO_MAVEN_COMMAND` 和 `AGENT_STUDIO_NPM_COMMAND` 为宿主机 `mvn.cmd`、`npm.cmd` 的绝对路径后重启。
3. 在 Agent Builder 创建新版本，绑定 `run_workspace_verification`；确认页面显示 `EXECUTE/HIGH`。为形成完整 Coding 链，也可同时绑定读取和补丁工具。

## 正向验证

1. 发送：“调用 `run_workspace_verification`，在 `backend` 运行 `MAVEN_TEST`，需要审批时停下。”审批前不应出现新的 TOOL_EXECUTION_STARTED。
2. 检查审批参数只能包含 `path=backend` 和 `task=MAVEN_TEST`，批准后应返回 `successful=true`、`exitCode=0`、耗时与测试输出。
3. 发送：“在 `web` 运行 `NPM_BUILD`。”批准后应返回成功，输出包含 Vite 构建结果。
4. 在运行详情确认 APPROVAL_REQUEST/RESULT 与 TOOL_RESULT；安全审计应包含 TOOL_REQUEST_VALIDATED、APPROVAL_REQUIRED、APPROVAL_DECIDED、TOOL_EXECUTION_STARTED、TOOL_EXECUTION_COMPLETED，且不复制 path、task 或完整构建输出。

## 拒绝与边界

1. 再发起一次 `NPM_BUILD` 并拒绝，审计应记录 TOOL_EXECUTION_SKIPPED，不能启动构建。
2. 请求任务 `SHELL`、`MAVEN_PACKAGE` 或附加命令参数，应在执行前被拒绝。
3. 对 `web` 请求 `MAVEN_TEST`、对 `backend` 请求 `NPM_BUILD`，应分别因缺少 `pom.xml` 或 `package.json` 被拒绝。
4. 请求在 `../`、绝对路径、受保护目录或符号链接目录运行，应被路径边界拒绝。
5. 若项目验证本身返回非零退出码，工具应正常返回 `successful=false`，不能把失败测试伪装成平台执行成功结论。

## 验收记录

- 验收日期：2026-09-15
- AgentVersion：`703cf7b6-46e5-4f6b-ba81-57097da41ff1`
- Maven 正向运行：`c86216ab-02fe-40b7-90af-0f19b3b665eb`。`backend/MAVEN_TEST` 经批准后 22085 ms 完成，`successful=true`、`exitCode=0`；RunStep 和 AuditEvent 完整。
- npm Build 正向运行：`94773e95-f1fd-4490-87e0-ea247ea2a8e1`。`web/NPM_BUILD` 经批准后 4054 ms 完成，29 个模块构建成功，`successful=true`、`exitCode=0`。
- 拒绝运行：`f87a5e91-04d2-467d-adc0-5c92cd706378`。审批状态 REJECTED，AuditEvent 记录 TOOL_EXECUTION_SKIPPED，没有 TOOL_EXECUTION_STARTED。
- 结论：Coding 白名单本地验证的真实 Maven Test、npm Build、HIGH 批准/拒绝、RunStep 和 AuditEvent 主链通过。当前仍不包含任意 Shell、命令参数、依赖安装、Git、Docker 或 OS 沙箱。
