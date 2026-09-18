# ADR 0020：受控 SSH Exec 固定任务

## 背景

SSH/SFTP 已能只读浏览和受审修改远程文本，但修改后仍需要用户离开 Agent Studio 执行远程诊断与测试。开放任意 Shell、命令、参数或环境变量会把命令注入和不可解释副作用带入模型工具面，因此本阶段只增加固定任务选择。

## 决策

新增内置 `run_remote_workspace_task`，标记为 `SSH/EXECUTE/HIGH`。模型参数只有授权根内的相对 `path` 和固定 `task`；任务为 `GIT_STATUS`、`GIT_DIFF_SUMMARY`、`MAVEN_TEST`、`NPM_TEST`、`NPM_BUILD`。平台分别映射到固定的只读 Git 状态/差异命令、Maven batch test、npm test 和 npm build；Schema 禁止额外字段，模型不能提供命令、参数、环境变量或 Shell 文本。

连接继续复用现有 SSH 页面配置、密码解析、连接超时和固定 SHA-256 主机指纹。每次工具调用建立一个短生命周期已认证会话，在同一会话中先通过 SFTP 对远程根、工作目录各级和项目标记执行 `lstat`，拒绝越界、符号链接、缺失标记及不安全工作目录，再打开一个非交互 Exec Channel。Git 任务要求真实 `.git` 目录，Maven/npm 分别要求普通 `pom.xml`/`package.json`；Exec 工作目录额外限制为保守的 POSIX 安全字符集合。

工具继续由 AgentVersion 固化、ToolRegistry 注册并经 SafeExecutionGateway 执行。每次调用都创建与完整参数和具体 SSH 目标绑定、限时且一次性的 ApprovalRequest；RunStep 与 AuditEvent 沿用既有链路，不增加数据库表、旁路接口或 MCP Server。

内部命令预算为 75 秒，外层工具预算为 90 秒。stdout/stderr 在通道打开前明确合并，最多保留约 16000 字节的开头和结尾。超时或线程中断立即关闭 Exec Channel；连接、通道、超时、缺失退出码与安全校验失败作为工具失败。远端非零退出码仍作为可审计结果返回，`successful=false` 并保留 `exitCode` 和有界输出。

## 边界

固定 Maven/npm 任务会运行远程项目自己的插件、测试和 package scripts，仍可能生成构建产物或访问网络；HIGH 审批不是远程 OS 沙箱。当前没有 sudo、PTY、交互式或后台 Shell、模型自定义命令/参数/环境变量、Git 修改、依赖安装、Docker/Nginx、部署或回滚。自动化通过后只能表述为“受控 SSH Exec 已自动化验证”，真实 Ubuntu/OpenSSH 与真实模型清单完成前不得写成已人工验收。
