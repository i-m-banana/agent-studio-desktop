# Agent Studio Desktop：受控 SSH Exec 固定任务

## 当前结论

2026-09-16，在远程只读与受审文本补丁之后，项目新增 `run_remote_workspace_task`。工具只允许选择 Git 状态、Git 差异摘要、Maven test、npm test 和 npm build 五种固定任务。全量后端 63 个测试通过，前端 29 个模块的生产构建通过。2026-09-17 至 2026-09-18，真实 DeepSeek 模型和 Ubuntu/OpenSSH 目标完成五种固定任务的正向验证；期间 Maven 从命令缺失、编译级别错误、首次依赖下载超时逐步收口到 1 个测试通过，也证明非零退出作为可审计结果返回、75 秒超时会关闭通道、错误项目标记会安全失败。因此准确口径是“自动化和真实正向主链均已验收”。

## 执行链

AgentVersion 只能绑定 `SSH/EXECUTE/HIGH` 工具描述，模型参数只有相对 `path` 和固定 `task`。SafeExecutionGateway 先执行 Schema 校验，再创建与完整参数及具体 SSH 目标绑定、限时且一次性的 ApprovalRequest。拒绝、过期、危险额外参数和运行取消都不能进入远程执行。

批准后，每次调用只建立一个短生命周期认证会话：同一会话先开 SFTP 子系统，逐级 `lstat` 远程根、工作目录和项目标记，拒绝越界与符号链接；随后开非交互 Exec Channel 运行平台固定映射。Git 任务要求真实 `.git` 目录，Maven/npm 要求普通 `pom.xml`/`package.json`，工作目录只允许保守的 POSIX 安全字符，模型文本不会拼入命令。

## 结果与终止

stdout/stderr 明确合并并有界保留开头与结尾。结果返回 task、具体远程目标、相对工作目录、successful、exitCode、durationMs、output 和 outputTruncated。非零退出码是可审计的任务结果，不冒充连接失败；连接、通道、超时、缺失退出码和安全校验失败才作为工具失败。内部预算 75 秒、外层 90 秒，超时或线程中断会关闭远程命令通道。

## 边界

固定 Maven/npm 脚本仍会以远程 SSH 用户权限运行项目代码，可能生成构建产物或访问网络；审批不等于 OS 沙箱。本阶段没有 sudo、PTY、交互式或后台 Shell、任意命令、模型自定义参数/环境变量、Git 修改、依赖安装、Docker/Nginx、部署或回滚，也没有旁路执行接口和 MCP 包装。人工验收清单见 `docs/SSH_EXEC_ACCEPTANCE.md`。
