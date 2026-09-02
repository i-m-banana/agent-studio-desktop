# Agent Studio Desktop：RAG 全链路与检索算法

## 上传和元数据

用户在前端选择知识库并上传文档，单文件上限 20 MB。后端先校验知识库和非空文件，清理文件名，读取字节，计算 SHA-256，然后把文件写入 `data/knowledge/{knowledgeBaseId}/{documentId}.{extension}`。所有路径都会归一化并检查必须位于 dataRoot 下，防止路径越界。

MySQL 文档记录最初是 PROCESSING。解析和向量写入成功后更新为 READY，并记录 chunkCount；失败则更新为 FAILED 和安全截断后的失败原因。

## 文本解析与切分

Apache Tika 根据文件内容和名称提取文本，因此可以处理 TXT、Markdown、PDF、DOCX 等常见格式。TextChunker 统一换行、压缩空白后切分：目标长度 900 字符，最大 1100 字符，相邻块重叠 120 字符。切分优先在换行、中文句号、感叹号、问号、英文句点或空格处结束。

重叠用于降低关键信息恰好跨 chunk 边界造成的召回损失。chunkIndex 从 0 开始，来源展示使用文件名和 chunkIndex。

## 本地词法哈希向量

当前没有调用外部 embedding API，而是生成 384 维本地向量。算法先做 NFKC Unicode 规范化和小写化，再提取词项与去空白后的字符二元组。每个特征通过 hashCode 映射到一个维度，并按哈希奇偶增加正一或负一，最后做 L2 归一化。

该算法的优点是离线、零成本、确定性强，DeepSeek 只有 Chat API 时也能运行。缺点是它主要衡量词法重合，不真正理解语义；“数据库端口冲突”和“端口被占用”可能有一定字符重合，但完全不同措辞或跨语言表达的召回会明显变差。

## pgvector 检索

向量表使用 `vector(384)`，并建立基于 cosine 运算符的 HNSW 索引。查询在指定 knowledgeBaseId 内按余弦距离排序，最多取 5 条，然后过滤 score 不大于 0.05 的弱结果。score 在代码中是 `1 - cosine distance`。

检索结果一方面通过 SSE `sources` 发送给前端，另一方面拼成系统上下文注入模型。这样用户能看到证据，模型也能据此作答。

## 删除闭环

删除文档时先删除 pgvector 中对应 documentId 的 chunk，再删除 MySQL 文档元数据，最后删除本地文件。验收时确认删除后文档列表为空、本地文件不存在，并且再次提问不再出现 sources；随后重新上传又恢复为 READY。

## 后续优化

下一步应抽象 EmbeddingGateway，接入真正的多语言语义 embedding，并给索引增加 embeddingModel、dimension 和 indexVersion。替换算法时不能把新旧向量混在同一索引里，需要通过新版本重建。之后可增加关键词 BM25、向量混合召回、rerank、章节和页码 metadata、离线评测集。

