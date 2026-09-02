# Agent Studio Desktop：RAG 全链路与检索算法

## 上传和元数据

用户在前端选择知识库并上传文档，单文件上限 20 MB。后端先校验知识库和非空文件，清理文件名，读取字节，计算 SHA-256，然后把文件写入 `data/knowledge/{knowledgeBaseId}/{documentId}.{extension}`。所有路径都会归一化并检查必须位于 dataRoot 下，防止路径越界。

MySQL 文档记录最初是 PROCESSING。解析和向量写入成功后更新为 READY，并记录 chunkCount；失败则更新为 FAILED 和安全截断后的失败原因。

## 文本解析与切分

Apache Tika 根据文件内容和名称提取文本，因此可以处理 TXT、Markdown、PDF、DOCX 等常见格式。TextChunker 统一换行、压缩空白后切分：目标长度 900 字符，最大 1100 字符，相邻块重叠 120 字符。切分优先在换行、中文句号、感叹号、问号、英文句点或空格处结束。

重叠用于降低关键信息恰好跨 chunk 边界造成的召回损失。chunkIndex 从 0 开始，来源展示使用文件名和 chunkIndex。

## 从词法哈希到 Qwen 语义向量

第一版生成 384 维本地词法向量：做 NFKC 规范化、词项和字符二元组哈希，再 L2 归一化。该方案用于无 embedding 服务时跑通闭环，现在通过 `EMBEDDING_PROVIDER=local-hash` 作为回退保留。

生产默认由本机 Ollama `qwen3-embedding:0.6b` 生成 1024 维语义向量。文档批量调用 `/api/embed`，查询单独调用；网关校验返回数量、维度和有限数值。索引版本是 `qwen3-0.6b-v1`。DeepSeek 仍只负责最终回答，两个模型职责独立。

## pgvector 检索

旧表使用 `vector(384)` 和 HNSW。新表使用 `vector(1024)`，按 knowledgeBaseId、embeddingVersion 过滤后做精确余弦排序，最多取 5 条，再过滤 score 不大于 0.05 的结果。score 是 `1 - cosine distance`。新模型的 0.05 仅保留现有召回行为，不代表有效拒答阈值。

检索结果一方面通过 SSE `sources` 发送给前端，另一方面拼成系统上下文注入模型。这样用户能看到证据，模型也能据此作答。

## 删除闭环

删除文档时先删除 pgvector 中对应 documentId 的 chunk，再删除 MySQL 文档元数据，最后删除本地文件。验收时确认删除后文档列表为空、本地文件不存在，并且再次提问不再出现 sources；随后重新上传又恢复为 READY。

索引重建重新读取本地原件、用 Tika 解析和现有 TextChunker 切块，再用当前 EmbeddingGateway 写入当前版本。重建 Qwen 失败时只清理当前版本，不删除旧 LocalHash 回退数据；真正删除文档时则清理两代表。

## 语义升级的实测结果

冻结题集有 24 道可回答题和 6 道无答案题。知识包扩充后，LocalHash 与 Qwen 在完全相同的语料、题集、Top K 和阈值下重新运行；Qwen 保持全部可回答题 Recall@5 命中，并显著改善整体与改写题排序，延迟仍在本机约定预算内。最终数值与语料哈希保存在评测产物，避免正文调整后继续引用失效数字。

知识包扩充前曾给 query 添加统一中文检索前缀，MRR 从 0.8160 降到 0.6917，所以最终默认不加指令。无答案误召回仍为 100%，说明不能靠换 embedding 或抬高单一阈值解决拒答。

## 后续优化

下一步不是继续无边界调 embedding，而是用独立开发集研究无答案判别。结构感知切块、BM25 混合召回、rerank、章节和页码 metadata 必须分别做消融实验，避免无法归因收益。
