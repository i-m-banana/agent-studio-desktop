# Agent Studio Desktop 验收资料

项目代号为 **青岚**。

青岚的第一条验收主线是科研知识助手。系统采用模块化单体架构，后端使用 Spring Boot，前端使用 React，业务配置保存在 MySQL，知识片段与向量保存在 PostgreSQL + pgvector。

Agent 发布后生成不可变版本。一次会话固定绑定一个 AgentVersion，后续修改草稿不会改变历史版本。模型密钥不保存到数据库，数据库只记录环境变量名称。

知识库文档需要经过上传、文本解析、清洗、切分、向量化和存储。回答时应展示来源文件、chunk 编号和相似度。删除文档时必须同时删除本地文件、MySQL 元数据和 pgvector 中的相关 chunk。

当前阶段不包含 SSH/SFTP、Coding 补丁或命令、多智能体、工作流画布、Redis 或消息队列；Coding 只读工作区正在锚点后分支验收。
