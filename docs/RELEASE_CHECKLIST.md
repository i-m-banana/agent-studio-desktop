# 1.0.0 正式版发布清单

发布结论：`1.0.0` 已于 2026-09-13 完成人工主链验收。下列检查继续作为正式标签与后续版本发布的质量门禁。

## 自动检查

在工程根目录运行：

```powershell
.\scripts\release-check.ps1
```

通过条件：运行环境预检通过；Docker Compose 配置有效；后端 31 个测试全部通过；前端依赖可复现安装并完成 TypeScript/Vite 生产构建；`VERSION`、Maven 和 npm 版本号一致。

## 真实环境验收

1. 运行 `.\scripts\start.ps1`，打开 `http://localhost:5173`。
2. 打开“系统诊断”，MySQL、pgvector、Embedding、模型密钥和数据目录均为 READY；不用 MCP 时 MCP 的 WARNING 可接受。
3. 使用已有 Agent 完成普通对话、知识库问答和一次无答案问题。无召回问题应直接显示“知识库中没有足够证据回答该问题”。
4. 完成一次 LOW 工具调用和一次 HIGH 工具批准/拒绝，运行记录与安全审计完整。
5. 完成 MCP HTTP 或 stdio 的同步、Resource 预览/导入、Prompt 生成和工具调用。
6. 同时发起两个独立会话，确认会话标识和回答不串线。

## 数据保护验收

运行 `.\scripts\backup.ps1`。最新备份目录必须包含非空的 `mysql.sql`、`pgvector.sql`、`data.zip` 和 `manifest.json`。恢复会覆盖数据库，不在日常回归中自动执行；需要在隔离环境中使用：

```powershell
.\scripts\restore.ps1 -BackupDirectory .\backups\yyyyMMdd-HHmmss -ConfirmRestore -RestoreFiles
```

恢复脚本必须在缺少 `-ConfirmRestore` 时立即拒绝，且拒绝读取当前工程 `backups` 目录之外的路径。

## 发布决策

- 2026-09-13 用户确认网站主要功能人工测试通过，同意形成 `1.0.0` 正式代码锚点；

- 不把 Flyway 对 MySQL 8.4 的“尚未测试”警告误判为迁移失败；升级 Flyway 单独排期。
- `1.0.0` 口径是单机本地正式版，不包含登录/RBAC、SSH、Coding、断点续跑、MCP 全协议扩展或多实例部署。
- 发布前备份当前用户数据，审阅工作区变更，提交后再打标签；不要把 `.env`、backups、`.run` 或密钥加入 Git。
