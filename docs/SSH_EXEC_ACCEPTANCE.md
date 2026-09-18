# 受控 SSH Exec 人工验收

适用范围：`feat/coding-workspace` 的固定远程诊断与构建测试子阶段。自动化和真实服务器/真实模型正向主链均已通过，真实运行还覆盖非零退出、超时关闭和错误项目标记；未重复手工执行的安全项按自动化证据保留说明。

## 准备

1. 确认“模型配置—SSH 远程工作区”仍使用已独立核对的 SHA-256 主机指纹，连接测试通过。
2. 在授权 `remoteRoot` 内准备三个可信项目目录：Git 仓库、带 `pom.xml` 的 Maven 项目、带 `package.json` 且已有可运行 test/build scripts 的 npm 项目。不要为验收临时执行依赖安装。
3. 在 Agent Builder 绑定 `run_remote_workspace_task` 并发布新 AgentVersion，确认工具显示 `SSH/EXECUTE/HIGH`。

## 正向与非零退出

1. 请求 Agent 对 Git 项目运行 `GIT_STATUS`；审批前核对卡片只有 `path`、`task`、参数摘要、具体 SSH 目标和过期时间。批准后确认返回 `task`、`target`、相对 `path`、`successful=true`、`exitCode=0`、`durationMs`、`output`、`outputTruncated`。
2. 对同一目录运行 `GIT_DIFF_SUMMARY`，确认只返回差异摘要，没有修改工作树。
3. 分别在可信 Maven/npm 项目运行 `MAVEN_TEST`、`NPM_TEST`、`NPM_BUILD`。成功任务应返回退出码 0；人为准备一个测试失败但命令可正常启动的分支，确认非零退出码返回为 `successful=false` 的工具结果，而不是平台连接失败。
4. 在运行详情确认 TOOL_CALL/TOOL_RESULT 存在，安全审计包含 TOOL_REQUEST_VALIDATED、APPROVAL_REQUIRED、APPROVAL_DECIDED、TOOL_EXECUTION_STARTED、TOOL_EXECUTION_COMPLETED，目标为具体 `SSH:user@host:port/root`。

## 拒绝与安全边界

1. 发起任一任务后拒绝审批，确认远程命令未启动，并形成 REJECTED 与 TOOL_EXECUTION_SKIPPED。
2. 尝试让模型附带 `command`、`args`、`env` 或 Shell 文本；Schema 应在审批前拒绝，不能生成可执行审批。
3. 分别尝试 `../`、绝对路径、含空格/分号/引号/换行的目录、符号链接目录、缺少对应项目标记的目录；均应安全失败且不打开 Exec Channel。
4. 可在专门的可信测试脚本中制造超过 75 秒的前台任务，确认工具失败提示已关闭远程命令通道，运行最终可由既有总时限/取消链收口。
5. 可在专门测试脚本输出超过 16000 字节，确认 `outputTruncated=true` 且输出保留开头、截断标记和结尾。

## 真实运行证据

- 验收时间：2026-09-17 至 2026-09-18（Asia/Shanghai）
- Agent：`受控ssh运行`，AgentVersion `7bf214cf-4ebd-4c3c-80b8-6f599b1b05bd`（v1），真实模型 `DeepSeek V4 Pro / deepseek-v4-pro`
- 远程目标：`root@117.72.84.129:22/root/zzh`
- `GIT_STATUS`：运行 `6e37d67b-5321-42e6-95c5-a8ce30048fb7`，`ssh-exec-git-project`，审批后退出码 0，返回 `main` 分支和 `README.md` 已修改
- `GIT_DIFF_SUMMARY`：运行 `6e37d67b-5321-42e6-95c5-a8ce30048fb7` 与复测 `94e633eb-e088-4404-954e-8019e009f906`，审批后退出码 0，返回 1 个文件、2 行新增
- `NPM_TEST`：运行 `b974f2d9-6c9a-435f-9e9a-bb5b292deb1e`，`ssh-exec-npm-project`，审批后退出码 0，测试脚本通过
- `NPM_BUILD`：运行 `65d7820d-619b-4417-a991-b575371d6ad4`，`ssh-exec-npm-project`，审批后退出码 0，生成 `dist/artifact.txt`
- `MAVEN_TEST` 环境诊断：运行 `fcea63e8-542b-421d-b475-ecb900c3e057` 返回退出码 127 和 `mvn: command not found`；安装 Maven 后，运行 `9952e88f-7acc-493a-837a-d54452f7ec93` 因样例 Java 编译级别不兼容返回退出码 1。两次均按设计返回 `successful=false` 的已完成工具结果
- `MAVEN_TEST` 正向成功：修正样例编译级别后，运行 `c65fe4a9-cf95-4688-b089-ba5306e2ade0` 在 `ssh-exec-maven-project` 审批后退出码 0，1 个测试通过、0 失败/错误/跳过，`BUILD SUCCESS`，任务耗时 44311ms，`outputTruncated=false`
- 缺失项目标记：运行 `3877cbbb-1089-44f9-a9d9-e81db1c3dac3` 在 Maven 目录请求 `GIT_DIFF_SUMMARY`，审批后因缺少或无法安全访问 `.git` 失败，形成 `TOOL_EXECUTION_FAILED`
- 超时关闭：运行 `c4ebae39-98fe-4676-84a0-32fd7160f939` 首次下载 Maven 依赖超过 75 秒，工具失败明确报告已关闭远程命令通道，并形成 `TOOL_EXECUTION_FAILED`；同一运行中的错误 Git 项目标记请求也被安全阻止
- 审计：上述成功调用及退出码 127 调用均包含 `TOOL_REQUEST_VALIDATED`、`APPROVAL_REQUIRED`、`APPROVAL_DECIDED`、`TOOL_EXECUTION_STARTED`、`TOOL_EXECUTION_COMPLETED`
- 自动化补充：审批拒绝后不建立 SSH 会话、`command` 等危险额外参数在审批前拒绝、输出截断、越界与符号链接绕过均由集成/单元测试覆盖；本轮没有为了重复这些结果而在真实服务器制造额外副作用或超大输出

当前准确结论：受控 SSH Exec 的五种固定任务已完成自动化和真实服务器/真实模型正向验收；真实运行同时验证非零退出、75 秒超时关闭和缺失项目标记，审批拒绝、危险参数、输出截断及其余路径边界由自动化覆盖。不能据此扩写为任意远程 Shell、依赖安装、Git 修改或部署能力。
