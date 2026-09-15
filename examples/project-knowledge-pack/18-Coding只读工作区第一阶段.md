# Agent Studio Desktop：Coding 只读工作区第一阶段

## 阶段结论

2026-09-14，项目从正式标签 `v1.0.0` 指向的提交 `3488f08` 创建 `feat/coding-workspace`。第一阶段只实现受控本地代码工作区、目录浏览、文件路径搜索和文本读取。代码、自动化和真实正向主链已经完成；首轮根目录搜索暴露性能问题，轻量修复后真实复验通过。

## 用户与运行链

后端用 `AGENT_STUDIO_CODING_WORKSPACE` 配置一个授权根目录。Agent Builder 会通过既有工具目录看到三个新工具：`list_workspace_directory`、`search_workspace_files`、`read_workspace_text_file`。用户把它们绑定到 Agent 草稿并发布后，AgentVersion 保存精确工具快照；模型提出调用，SafeExecutionGateway 完成 Schema 校验、取消检查和限时执行，ChatService 继续保存 TOOL_CALL/TOOL_RESULT，AuditEvent 只保存参数摘要与执行摘要。

三个工具都是 `READ/LOW`，不会生成审批请求。目录工具只看一层；搜索工具只匹配文件名或相对路径，不搜索正文；读取工具提供起始行和最大行数。不存在直接文件 REST API，也没有为 Coding 新建旁路注册表、审批表或审计表。

## 路径与数据安全

所有模型参数必须是授权根内的相对路径。共享边界拒绝绝对路径、`..`、冒号与 Windows 数据流、符号链接路径和真实路径解析后的根外目标。`.git`、`.ssh`、`.gnupg`、`.aws`、`data/secrets`、真实 `.env`、常见私钥和密钥库文件不会被浏览、搜索或读取；公开模板 `.env.example` 允许读取。

搜索最多递归 30 层、扫描 10000 项、返回 100 个结果，不跟随链接。首轮真实验收发现根目录内依赖和构建产物会耗尽 10 秒时限，因此默认剪枝 `node_modules`、`target`、`dist`、`build`、`out`、`coverage`、`.idea`、`.run`，只对目录和候选结果做真实路径检查，并在结果足够后提前停止；显式指定这些目录时仍可搜索。读取只接受普通文件、严格 UTF-8、最大 1 MiB、单次最大 500 行，工具输出最多 16000 字符。限制同时保护模型上下文、单工具时限和 MySQL RunStep 的 TEXT 存储边界。

## 自动化证据

`CodingWorkspaceToolsTests` 验证三工具注册与风险、正向浏览/搜索/读取，以及越界、绝对路径、Windows 数据流、敏感文件和二进制拒绝。当前 Windows 普通符号链接权限不可用时，测试创建目录联接指向外部临时目录，逃逸拒绝分支已实际执行。

集成测试让可控模型通过已发布 AgentVersion 调用目录浏览工具，确认 RunStep 保存工具结果，AuditEvent 保存校验、开始与完成事件且不复制文件名或正文。搜索专项测试覆盖默认剪枝、显式目录搜索与提前停止。全量 Maven 结果是 38 个测试全部通过、0 失败、0 错误、0 跳过；前端 TypeScript 与 Vite 生产构建通过。

## 未完成边界

真实 DeepSeek 的目录浏览和文本读取已通过。运行 `b70a18b6-f47e-48b2-9d59-a710a50d7532` 记录了优化前两次根搜索超时和缩小到 `backend` 后成功；优化后运行 `e435a565-737a-45ab-ab42-64c2f544c11e` 首次根搜索扫描 333 项、跳过 7 个目录、98 ms 完成。负向路径和 Windows 目录联接由自动化执行通过，本轮未重复全部负向浏览器操作。尚未实现文件内容搜索、多授权根、Git 状态/差异、补丁生成、补丁预览与批准、写入/删除、白名单命令、构建测试、OS 沙箱、SSH/SFTP 或部署。准确表述是“Coding 只读第一阶段已完成并验证”，不能表述为“已经具备通用 Coding Agent”。
