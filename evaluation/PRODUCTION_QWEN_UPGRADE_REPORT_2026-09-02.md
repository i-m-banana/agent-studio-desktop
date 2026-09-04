# Qwen 语义向量生产升级报告

- 最后刷新：2026-09-04（加入显式 ReAct 工具阶段文档后重跑同语料评测）
- 模型：本机 Ollama `qwen3-embedding:0.6b`
- 维度：1024
- 索引版本：`qwen3-0.6b-v1`
- 生产表：`knowledge_chunk_v2`
- Qwen 原始结果：`evaluation/results/qwen3-embedding-0.6b-production.json`
- 同语料 LocalHash 结果：`evaluation/results/local-hash-after-qwen-docs.json`
- 语料：00–10，共 11 份文档、25 个 chunk，SHA-256 `56de2a0f24cf34a0270a133cd5be1f18acfa0f0d690b97353a77e7a0ed7a5f69`

## 冻结题集结果

| 指标 | LocalHash | Qwen 生产配置 | 变化 |
|---|---:|---:|---:|
| Recall@5 | 83.33% | 100.00% | +16.67 个百分点 |
| MRR | 0.5743 | 0.8542 | +0.2799 |
| 原题 Recall@5 | 91.67% | 100.00% | +8.33 个百分点 |
| 原题 MRR | 0.6528 | 0.8750 | +0.2222 |
| 改写题 Recall@5 | 75.00% | 100.00% | +25.00 个百分点 |
| 改写题 MRR | 0.4958 | 0.8333 | +0.3375 |
| 无答案误召回率 | 100.00% | 100.00% | 无改善 |
| p95 查询延迟 | 0.238 ms | 284.911 ms | 增加约 285 ms |

延迟包含本机 Ollama HTTP query embedding 和 25 个 chunk 的内存精确排序，不是完整聊天延迟。知识包加入工具运行时的架构、数据、测试和面试材料后，LocalHash 和 Qwen 都在同一新语料上重新运行，不能直接拿历史报告数值作当前对照。

## 查询指令消融

生产接入最初配置了统一中文检索指令。在知识包扩充前的同一冻结语料消融中，Recall@5 仍为 100%，但 MRR 从无前缀的 0.8160 下降到 0.6917。因此最终默认不添加 query instruction，只保留环境变量配置能力；本次扩充语料的无前缀 MRR 为 0.8542。

## 真实存储验收

已有知识库通过重建 API 成功迁移。PostgreSQL 查询确认 `embedding_model=qwen3-embedding:0.6b`、`embedding_version=qwen3-0.6b-v1`、向量维度 1024。改写问题“资料从上传到能够辅助回答，要经过哪些处理步骤？”在最终无查询前缀配置下成功命中验收文档，cosine similarity 约 0.40。

## 结论

语义 embedding 的生产升级投入使用：Recall@5 从 83.33% 提升到 100%，MRR 提升 0.2799，p95 低于 400 ms 预算，并保留旧索引回退。最终 MRR 为 0.8542，超过原先的 `> 0.80` 挑战目标，但这只证明当前冻结语料的排序质量，不能宣称检索优化已经完成。无答案拒答仍未通过，记录为后续 rerank 与相关性判断任务。
