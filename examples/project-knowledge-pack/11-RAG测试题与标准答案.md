# Agent Studio Desktop：RAG 测试题与标准答案

本文提供一组跨文档问题。第一轮测试时不要把本文件上传到知识库，只上传 00 到 10；提问时也不要把标准答案一起粘贴到对话中。这样可以观察智能体是否真正从多份项目文档中检索和组合答案。完成盲测后，可再上传本文件测试 FAQ 精确召回。

MCP 三类核心能力收口后的最终 00–10 评测快照为 11 份文档、42 个 chunk，SHA-256 `35639a984b8a9607645ea4b7447d4643dd63161431b7a66e0c2026ebdae5e3a8`。同语料离线检索结果：Qwen Recall@5 100%、MRR 0.8542、原题 MRR 0.9167、改写题 MRR 0.7917、p95 275.724 ms；LocalHash Recall@5 87.50%、MRR 0.6507、改写题 MRR 0.5903。两者无答案误召回率仍为 100%。结果文件还保存逐文档哈希；00–10 继续修改后必须重新生成快照。本文件不参与语料构建，因此记录这些数值不会造成评测循环。

## 基础事实题

问题：Agent Studio Desktop 当前使用哪些端口？

标准答案：前端 5173，后端 8080，MySQL 宿主机 23306、容器内 3306，pgvector 宿主机 15432、容器内 5432。

问题：当前 RAG 的 chunk 参数是什么？

标准答案：目标 900 字符、最大 1100 字符、重叠 120 字符，优先在换行和中英文句子边界切分。

问题：项目是否已经支持 SSH 和 ReAct？

标准答案：显式 ReAct 已经支持，包括两个内置工具、MCP Streamable HTTP 与 stdio 动态工具、版本快照、AgentRun/RunStep、统一安全执行网关、Schema 校验、参数审批、AuditEvent、主动取消、总超时和历史详情；MCP Resources/Prompts 也已支持，但 OAuth、sampling、elicitation、roots、resource templates、订阅通知、stdio OS 沙箱、SSH、Coding、身份和断点续跑仍未实现。因此不能回答成“完全没有 ReAct”，也不能回答成“已经是完整工具平台”。

问题：RunStep 和 AuditEvent 为什么不能合成一张表？

标准答案：RunStep 还原模型—工具—观察的运行序列，会保留供调试的输入输出；AuditEvent 记录安全网关的判定和执行事实，只保存参数 SHA-256 与结果概况，减少敏感数据复制。两者关联同一 runId，但服务于运行调试和安全追溯两个不同关注点。

问题：点击停止运行后，系统为什么不只是让前端停止等待？

标准答案：取消接口先持久化 CANCEL_REQUESTED，再中断 runId 对应的执行线程；后端在模型、审批和工具边界检查信号，写入 CANCELLED 和 RUN_TERMINATION，并通过 terminated SSE 通知前端。因此执行侧与记录侧都停止，而不只是隐藏加载动画。

问题：应用重启后看到 INTERRUPTED 是否表示可以从原步骤继续？

标准答案：不表示。启动清理只会关闭上一进程遗留的非终态运行和待决审批，避免悬挂；当前没有持久化可重放检查点、幂等副作用协议和外部调用去重，所以不能断点续跑。

问题：为什么发布后的 Agent 不会自动获得新工具？

标准答案：工具集合与模型、提示词、知识库一样，在发布时快照到 AgentVersion。修改草稿绑定只影响下一次发布，历史版本的行为保持可复现。

问题：工具执行失败后系统如何处理？

标准答案：失败会写成 FAILED 的 TOOL_RESULT RunStep，并作为 Observation 返回模型，让模型有机会解释失败；越权工具请求或超过运行上限会终止运行并返回 error。

问题：高风险工具批准后，模型能否偷偷换一组参数执行？

标准答案：不能。ApprovalRequest 保存用户看到的完整参数 JSON 和 SHA-256，执行前必须以同一哈希把 APPROVED 原子转换为 CONSUMED。参数被替换、批准被重复使用、用户拒绝或等待超时都不会执行。

问题：write_workspace_note 为什么比任意文件写入更适合作为首个高风险样例？

标准答案：它有真实副作用，足以验证审批链路，但影响面被限制在 `data/tool-workspace`。文件名和扩展名有白名单，规范化路径必须仍在根目录内，内容长度受限并且不能覆盖已有文件，因此风险可观察、可验证、可回收。

问题：给已有 Agent 增加工具时，为什么不能再次用同名创建？

标准答案：Agent 名称有唯一约束。应点击 Agent 卡片的“编辑草稿”，保存后再发布新版本；再次创建同名 Agent 会得到明确的重名提示。编辑草稿不会改变历史 AgentVersion。

问题：创建全新 Agent 且不绑定知识库时，曾经为什么触发数据库约束错误？

标准答案：前端曾用空字符串表示“不使用知识库”，后端跳过校验后又把空字符串写入可选外键，MySQL 因找不到该知识库 ID 而拒绝写入。现在前端传 null，后端也统一把空值归一化为 null。

## 原理题

问题：为什么更新 ModelProfile 不会改变已经发布的 AgentVersion？

标准答案：发布时把模型名称、API 地址、模型名、密钥环境变量名、温度、提示词和知识库标识复制进版本快照；会话绑定版本，所以后续草稿或模型配置修改不影响历史版本。

问题：为什么 sources 已经返回但最后仍然 error？

标准答案：检索和模型调用是两个阶段。服务先检索并发送 sources，再调用外部模型。独立验收进程没设置 DEEPSEEK_API_KEY 时，会出现 `run → sources → error`，说明 RAG 成功而模型鉴权失败。

问题：本地词法哈希向量有哪些优缺点？

标准答案：优点是离线、确定性、零成本、不需要 embedding API；缺点是依赖词法重合，不具备真正语义理解，同义改写和跨语言召回有限。

## 故障诊断题

问题：Docker 显示容器 Created，但 MySQL 没启动，报端口已分配，应怎么判断？

标准答案：这是宿主机端口绑定冲突，不一定是容器内 MySQL 失败。检查 `docker compose ps` 的 STATUS 和 PORTS，换宿主机端口并同步后端配置；项目最终选择 23306。

问题：为什么加了 pgvector 后，查询 model_profile 却报 PostgreSQL relation 不存在？

标准答案：多个 DataSource/JdbcTemplate 自动装配选错连接。需要把 MySQL 标为 primary 和 FlywayDataSource，显式建立 primary JDBC 模板，并在业务 Repository 使用 Qualifier；向量 Repository 单独限定 vectorJdbcTemplate。

## 综合设计题

问题：如果要把当前 RAG 升级成真正语义检索，不能只替换哪一行代码？

标准答案：不能只替换 embed 函数并复用旧索引。需要抽象 EmbeddingGateway，记录 embedding 模型、维度和索引版本，建立新向量表或新版本索引并重建全部 chunk；还应通过离线评测比较 Recall@K、MRR，再考虑 BM25 和 rerank。

问题：删除文档为什么是一个值得讨论的工程问题？

标准答案：同一逻辑文档同时存在于本地文件、MySQL 元数据和 pgvector chunk。当前顺序清理三处，但没有分布式事务，中途失败可能不一致，因此后续需要幂等删除、补偿任务和孤儿数据巡检。

## 反幻觉题

问题：这个项目是否是企业级高并发、多租户智能体平台？

标准答案：不是。它定位为 Windows 个人电脑上的单机开发与调试工具，没有多租户、RBAC、微服务、高可用或分布式任务。参数绑定审批是最小本地安全闭环，不代表已经具备企业身份与合规体系。

问题：项目的 MCP 是怎样接入的，为什么没有绕过原有安全链？

标准答案：平台实现 MCP 2025-06-18 Streamable HTTP 的初始化、工具发现和调用，发现结果以稳定平台名进入原 ToolRegistry。ChatService 仍只做显式 ReAct，MCP 调用和内置工具一样经过 ToolInputValidator、SafeExecutionGateway、参数绑定审批、超时、取消、RunStep 与 AuditEvent，不存在 MCP 专用的旁路执行入口。

问题：MCP 的 Resource 和 Prompt 如何使用，为什么不自动塞给模型？

标准答案：Resource 属于应用控制的上下文，用户可以预览或显式导入当前知识库，导入继续复用大小限制、Tika、切块、embedding 和 pgvector；Prompt 属于用户控制的模板，用户选择并填写必填参数后才调用 prompts/get。它们不会暗中修改 Agent 系统提示词或自动进入 ReAct。

问题：MCP Server 的同步与配置迁移怎样追溯且不泄露密钥？

标准答案：mcp_sync_event 保存每次 READY/FAILED、协议版本、Tools/Resources/Prompts 数量、工具差异和错误；配置 JSON 可导入导出，但平台本来只保存 Bearer Token 环境变量名或 stdio 环境变量名称映射，所以导出不含秘密值。

问题：MCP 工具名称或 Schema 更新后如何避免历史版本漂移？

标准答案：平台名包含 Server 命名空间以及远端名称、标题、描述和 Schema 的 SHA-256 指纹。描述不变时同步复用修订，影响模型选择或参数的变化会生成新工具名；旧 AgentVersion 保留旧引用，新草稿只看到当前 active 修订。

问题：stdio MCP 的启动和停止边界是什么？

标准答案：平台用“启动程序+参数数组”直接启动短生命周期进程，不经过 shell；完成 initialize/initialized 后发现或调用工具。结束时关闭 stdin 并等待，随后 terminate，仍不退出则强杀进程树；超时前还会发送 notifications/cancelled。stdout 只能放单行 JSON-RPC，日志放 stderr。

问题：为什么删除已被 AgentVersion 引用的 MCP Server 会失败？

标准答案：历史版本保存的是稳定工具平台名。物理删除 Server 和工具目录会让历史配置及运行记录失去解释依据，所以平台只允许停用已引用 Server；完全未引用时才允许删除。

问题：项目是否直接复制了 ai-rag、WaLiSSH 和 WaLiCode 的业务代码？

标准答案：没有。三个项目是只读参考来源，新项目在独立仓库中从骨架开始建设；当前强调的是重新做出的架构取舍、实现和验证，来源项目代码不计为个人原创。

## 语义向量升级题

问题：当前生产默认使用哪一个 embedding 模型、多少维、什么索引版本？

标准答案：本机 Ollama `qwen3-embedding:0.6b`，1024 维，索引版本 `qwen3-0.6b-v1`，写入独立的 `knowledge_chunk_v2`；LocalHash 384 维作为回退保留。

问题：为什么生产查询默认没有添加中文检索指令？

标准答案：在知识包扩充前的同语料消融中，添加统一中文前缀后 Recall@5 仍为 100%，但 MRR 从 0.8160 降到 0.6917。项目保留可配置能力，但默认采用实测排序更好的无前缀方案；当前加入 MCP 材料后的无前缀 MRR 为 0.8278。

问题：Qwen 升级解决无答案误召回了吗？

标准答案：没有。6 道无答案题仍全部返回片段；提高固定阈值会严重损害 Recall，因此该问题留给独立的相关性判断、rerank 或回答阶段证据充分性策略。

问题：加入 MCP stdio 材料后的最终知识包，LocalHash 与 Qwen 对比结果是什么？

标准答案：盲测语料是 00–10，共 11 份文档、38 个 chunk，SHA-256 为 `686a21f66cf2d931e298d5a77e25d5d0658909e7bd5201708d79273befd098cd`。LocalHash 的 Recall@5/MRR 为 87.50%/0.6542，Qwen 为 100%/0.8958；改写题 MRR 从 0.5306 升至 0.8333，Qwen p95 为 260.366 ms。无答案误召回率两者都是 100%。
