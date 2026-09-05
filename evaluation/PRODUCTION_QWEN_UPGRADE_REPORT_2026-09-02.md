# Qwen 语义向量生产升级报告

> 2026-09-05 MCP 三类核心能力材料收口后，冻结语料扩充为 11 份文档、42 个 chunk，SHA-256 为 `35639a984b8a9607645ea4b7447d4643dd63161431b7a66e0c2026ebdae5e3a8`。最新同语料结果：Qwen Recall@5 100%、MRR 0.8542、改写题 MRR 0.7917、p95 275.724 ms；LocalHash Recall@5 87.50%、MRR 0.6507。两者无答案误召回率仍为 100%。完整机器结果见 `results/*after-mcp-primitives.json`；下文保留升级当时的历史实验口径。

- 最后刷新：2026-09-05（加入 MCP 核心模块材料后重跑同语料评测）
- 模型：本机 Ollama `qwen3-embedding:0.6b`
- 维度：1024
- 索引版本：`qwen3-0.6b-v1`
- 生产表：`knowledge_chunk_v2`
- Qwen 最新结果：`evaluation/results/qwen3-embedding-0.6b-after-mcp-primitives.json`
- 同语料 LocalHash 结果：`evaluation/results/local-hash-after-mcp-docs.json`
- 语料：00–10，共 11 份文档、35 个 chunk，SHA-256 `a1970fceb87a09ad2044ef027b1de0cd3086b510842f3d8817e0ab79bac46259`

## 冻结题集结果

| 指标 | LocalHash | Qwen 生产配置 | 变化 |
|---|---:|---:|---:|
| Recall@5 | 87.50% | 100.00% | +12.50 个百分点 |
| MRR | 0.6854 | 0.8278 | +0.1424 |
| 原题 Recall@5 | 100.00% | 100.00% | 无变化 |
| 原题 MRR | 0.7458 | 0.8194 | +0.0736 |
| 改写题 Recall@5 | 75.00% | 100.00% | +25.00 个百分点 |
| 改写题 MRR | 0.6250 | 0.8361 | +0.2111 |
| 无答案误召回率 | 100.00% | 100.00% | 无改善 |
| p95 查询延迟 | 0.213 ms | 233.375 ms | 增加约 233 ms |

延迟包含本机 Ollama HTTP query embedding 和 35 个 chunk 的内存精确排序，不是完整聊天延迟。知识包加入 MCP 材料后，LocalHash 和 Qwen 都在同一新语料上重新运行，不能直接拿历史报告数值作当前对照。

## 查询指令消融

生产接入最初配置了统一中文检索指令。在知识包扩充前的同一冻结语料消融中，Recall@5 仍为 100%，但 MRR 从无前缀的 0.8160 下降到 0.6917。因此最终默认不添加 query instruction，只保留环境变量配置能力；本次加入 MCP 材料后的无前缀 MRR 为 0.8278。

## 真实存储验收

已有知识库通过重建 API 成功迁移。PostgreSQL 查询确认 `embedding_model=qwen3-embedding:0.6b`、`embedding_version=qwen3-0.6b-v1`、向量维度 1024。改写问题“资料从上传到能够辅助回答，要经过哪些处理步骤？”在最终无查询前缀配置下成功命中验收文档，cosine similarity 约 0.40。

## 结论

语义 embedding 继续保持 Recall@5 100%，并在 Recall、整体 MRR 和改写题 MRR 上优于 LocalHash，MRR 为 0.8278，p95 低于 400 ms 预算。它仍低于 27 个 chunk 时的历史 MRR 0.8854，说明语料扩充会改变切块和排序，后续仍应评估结构化切块与 rerank，而不能沿用旧指标。无答案拒答仍未通过。
