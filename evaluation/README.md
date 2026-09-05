# RAG 离线评测

本目录提供一套不写入生产数据库的可重复基线。评测只把 `examples/project-knowledge-pack` 中的 00–10 作为语料，强制排除盲测答案文件 11 和系统提示词文件 12。

## 题集

`rag-eval-dataset.json` 共 30 题：

- `rag-001`–`rag-012`：原盲测题的机器可读版本；
- `rag-013`–`rag-024`：相同事实的低词面重合改写，用于观察语义召回收益；
- `rag-025`–`rag-030`：语料没有答案的负例，用于测误召回和拒答。

每道可回答题都有 `expectedSources`。原题带 `referenceAnswer` 和逐项 `keyFacts`；改写题通过 `answerKeyCaseId` 继承对应原题的答案与关键事实。无答案题的 `expectedSources` 和 `keyFacts` 为空，`referenceAnswer` 明确要求信息不足时拒答。

## 运行当前基线

在仓库根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/run-rag-baseline.ps1
```

默认结果写入 `evaluation/results/local-hash-baseline.json`。也可另存结果：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/run-rag-baseline.ps1 `
  -Output evaluation/results/local-hash-candidate.json
```

运行已下载的 Ollama 候选模型（不写入数据库）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/run-rag-baseline.ps1 `
  -Embedding ollama `
  -OllamaModel qwen3-embedding:0.6b `
  -OllamaDimensions 1024 `
  -Output evaluation/results/qwen3-embedding-0.6b-candidate.json
```

生产默认配置使用同一模型和维度；加入 MCP 材料后的最新冻结结果保存在 `results/qwen3-embedding-0.6b-after-mcp-docs.json`，LocalHash 对照为 `results/local-hash-after-mcp-docs.json`，总结见 `PRODUCTION_QWEN_UPGRADE_REPORT_2026-09-02.md`。评测工具的 query instruction 默认与生产一致为空；如需做指令消融，可通过 Maven 系统属性 `rag.eval.queryInstruction` 单独传入，不能把消融结果覆盖为生产结论。

本地基线直接复用生产代码中的 Apache Tika 解析、`TextChunker` 和 `LocalHashEmbedding`。Ollama 评测模式复用前两者，以 `/api/embed` 替换向量生成，确认维度后按同一余弦口径取前 5 条并过滤 `score <= 0.05`。两种模式都使用内存精确排序，不经过 pgvector；评测运行不会修改生产索引，生产是否切换由应用配置和显式重建决定。

## 指标口径

- `Recall@5`：可回答问题中，过滤后的前 5 个 chunk 至少有一个来自 `expectedSources` 的比例。
- `MRR`：可回答问题中，第一个相关 chunk 排名倒数的平均值；前 5 无相关 chunk 记 0。
- `noAnswerFalsePositiveRate`：无答案问题中，仍返回任一过滤后 chunk 的比例。该指标越低越好。
- `p50/p95 retrieval latency`：预热后，查询向量化加内存精确排序的耗时，仅用于同机同语料回归。
- `answerCitationCoverage`：可回答问题中，最终回答至少引用一个来源文件的比例。
- `expectedSourceCitationHitRate`：可回答问题中，最终回答至少引用一个 `expectedSources` 文件的比例。
- `citationPrecision`：全部引用中，文件属于该题 `expectedSources` 的比例；无答案题产生的引用计为不相关。
- `keyFactCoverage`：回答文本命中的关键事实项数 / 应覆盖关键事实项总数。当前采用透明的规范化字面匹配，适合作为回归信号，不等价于语义事实判定。
- `noAnswerAbstentionRate`：无答案题中明确标记为拒答的比例。
- `endToEndP50/P95`：从发问到完整回答结束的外部实测耗时。

离线基线不会调用聊天模型，因此答案、引用和端到端指标会明确显示 `not_measured`，不能填 0。要补算这些指标，按 `answer-run.schema.json` 保存一次完整回答采样，然后执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/run-rag-baseline.ps1 `
  -Answers evaluation/runs/answer-run.json
```

`citedSources` 应来自回答实际展示或明确引用的文件名，不能直接把检索器返回的全部 `sources` 当作模型已引用；`abstained` 只在回答明确说明资料不足、无法判断时设为 `true`。

## 升级对比规则

升级到 `qwen3-embedding:0.6b`、1024 维和结构感知切块时，应保持同一份语料、题集、Top K 和报告字段，并建立全新的索引版本、完整重建 00–10。不要把新旧维度写进同一索引，也不要把 11 或 12 入库。

余弦分数分布会随 embedding 模型变化，`0.05` 不能直接当作新模型的合理拒答阈值。若要调阈值，应另建开发集；本盲测集只用于冻结后的前后比较。当前结果中的 `thresholdSweep` 是诊断信息，不应反过来在同一盲测集上选最优参数。

推荐先看以下升级门槛：原题 Recall@5 不回退；改写题 Recall@5 和 MRR 上升；无答案误召回率显著下降；端到端 p95 在预先约定的本机预算内。结构感知切块还需要额外的长文跨章节样本，当前短文题集主要衡量来源文件召回，不能单独证明切块升级收益。

填写 `RAG_COMPARISON_TEMPLATE.md` 时，只把同一环境中真实运行的数值列为“已测”；未运行的 pgvector、Ollama、聊天模型和答案引用指标必须保留为 `N/A`。
