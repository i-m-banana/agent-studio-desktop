package com.agentstudio.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.agentstudio.knowledge.DocumentTextExtractor;
import com.agentstudio.knowledge.LocalHashEmbedding;
import com.agentstudio.knowledge.TextChunker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

/**
 * Offline retrieval benchmark that reuses the production parser, chunker and embedding.
 * It deliberately replaces pgvector/HNSW with exact in-memory cosine ranking so it can
 * run without databases and without mutating production data.
 */
class RagRetrievalEvaluationTest {

    private static final int TOP_K = 5;
    private static final double SCORE_THRESHOLD = 0.05;
    private static final int WARMUP_ROUNDS = 8;

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();
    private final TextChunker chunker = new TextChunker();
    private final LocalHashEmbedding embedding = new LocalHashEmbedding();

    @Test
    void evaluateCurrentLocalHashBaseline() throws Exception {
        var repositoryRoot = Path.of(System.getProperty("rag.eval.root", "..")).toAbsolutePath().normalize();
        var datasetPath = repositoryRoot.resolve(System.getProperty(
                "rag.eval.dataset", "evaluation/rag-eval-dataset.json")).normalize();
        var outputPath = repositoryRoot.resolve(System.getProperty(
                "rag.eval.output", "backend/target/rag-evaluation/current-baseline.json")).normalize();

        var dataset = json.readValue(datasetPath.toFile(), Dataset.class);
        validateDataset(dataset, repositoryRoot);

        long indexingStarted = System.nanoTime();
        var corpus = buildCorpus(repositoryRoot, dataset.corpus());
        double indexingMs = elapsedMs(indexingStarted);

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            for (var testCase : dataset.cases()) search(corpus.chunks(), testCase.question());
        }

        var results = new ArrayList<CaseResult>();
        var latencies = new ArrayList<Double>();
        for (var testCase : dataset.cases()) {
            long started = System.nanoTime();
            var hits = search(corpus.chunks(), testCase.question());
            double latencyMs = elapsedMs(started);
            latencies.add(latencyMs);
            results.add(resultFor(testCase, hits, latencyMs));
        }

        var metrics = metrics(dataset.cases(), results);
        var report = new Report(
                dataset.schemaVersion(),
                Instant.now().toString(),
                System.getProperty("rag.eval.gitCommit", "unknown"),
                new Evaluator("offline-production-components", System.getProperty("java.version"),
                        "Tika + production TextChunker + production LocalHashEmbedding; exact in-memory cosine ranking"),
                new RetrievalConfig("local-hash", LocalHashEmbedding.DIMENSIONS, "cosine", TOP_K,
                        SCORE_THRESHOLD, 900, 1100, 120),
                new CorpusSummary(corpus.documents().size(), corpus.chunks().size(),
                        corpus.sha256(), corpus.documents(), indexingMs),
                metrics,
                thresholdSweep(results),
                latency(latencies),
                answerMetrics(dataset, repositoryRoot),
                results,
                List.of(
                        "Recall@5 and MRR use the first returned chunk whose fileName is in expectedSources.",
                        "No-answer false-positive rate counts any post-threshold returned chunk as a false positive.",
                        "Latency is local JVM exact-scan latency on this small corpus; it is not pgvector/HNSW or end-to-end chat latency.",
                        "Files 11 and 12 are excluded by an enforced corpus invariant."
                ));

        Files.createDirectories(outputPath.getParent());
        json.writeValue(outputPath.toFile(), report);
        System.out.printf(Locale.ROOT,
                "RAG_EVAL output=%s answerable=%d recall@5=%.4f mrr=%.4f no_answer_fpr=%.4f p50_ms=%.3f p95_ms=%.3f%n",
                outputPath, metrics.answerableCases(), metrics.recallAt5(), metrics.mrr(),
                metrics.noAnswerFalsePositiveRate(), report.latency().p50Ms(), report.latency().p95Ms());
    }

    private void validateDataset(Dataset dataset, Path repositoryRoot) {
        assertThat(dataset.schemaVersion()).isEqualTo("1.0");
        assertThat(dataset.cases()).isNotEmpty();
        assertThat(dataset.cases().stream().map(EvalCase::id)).doesNotHaveDuplicates();
        assertThat(dataset.cases()).anyMatch(EvalCase::answerable).anyMatch(item -> !item.answerable());
        assertThat(dataset.cases()).allMatch(item -> item.answerable() != item.expectedSources().isEmpty());
        var casesById = new LinkedHashMap<String, EvalCase>();
        dataset.cases().forEach(item -> casesById.put(item.id(), item));
        assertThat(dataset.cases()).allSatisfy(item -> {
            if (item.answerKeyCaseId() == null) {
                assertThat(item.referenceAnswer()).as("reference answer for %s", item.id()).isNotBlank();
            } else {
                assertThat(casesById).as("answer key for %s", item.id()).containsKey(item.answerKeyCaseId());
                assertThat(casesById.get(item.answerKeyCaseId()).keyFacts())
                        .as("inherited key facts for %s", item.id()).isNotEmpty();
            }
        });
        var corpusDirectory = repositoryRoot.resolve(dataset.corpus().directory()).normalize();
        assertThat(corpusDirectory).isDirectory();
        assertThat(dataset.corpus().excludedFiles()).containsExactly(
                "11-RAG测试题与标准答案.md", "12-推荐Agent系统提示词-不要入库.md");
    }

    private Corpus buildCorpus(Path repositoryRoot, CorpusConfig config) throws Exception {
        var directory = repositoryRoot.resolve(config.directory()).normalize();
        var include = Pattern.compile(config.includeFilePattern());
        var excluded = Set.copyOf(config.excludedFiles());
        var paths = Files.list(directory)
                .filter(Files::isRegularFile)
                .filter(path -> include.matcher(path.getFileName().toString()).matches())
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();

        assertThat(paths).hasSize(11);
        assertThat(paths).noneMatch(path -> excluded.contains(path.getFileName().toString()));

        var documents = new ArrayList<DocumentSummary>();
        var chunks = new ArrayList<IndexedChunk>();
        var corpusDigest = MessageDigest.getInstance("SHA-256");
        for (var path : paths) {
            String fileName = path.getFileName().toString();
            var bytes = Files.readAllBytes(path);
            corpusDigest.update(fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            corpusDigest.update((byte) 0);
            corpusDigest.update(bytes);
            String text;
            try (var input = new ByteArrayInputStream(bytes)) {
                text = extractor.extract(input, fileName);
            }
            var documentChunks = chunker.chunk(text);
            documents.add(new DocumentSummary(fileName,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    text.length(), documentChunks.size()));
            for (int index = 0; index < documentChunks.size(); index++) {
                var content = documentChunks.get(index);
                chunks.add(new IndexedChunk(fileName, index, content, embedding.embed(content)));
            }
        }
        return new Corpus(documents, chunks, HexFormat.of().formatHex(corpusDigest.digest()));
    }

    private List<SearchHit> search(List<IndexedChunk> corpus, String question) {
        var queryVector = embedding.embed(question);
        return corpus.stream()
                .map(chunk -> new SearchHit(chunk.fileName(), chunk.chunkIndex(),
                        cosine(queryVector, chunk.vector()), chunk.content()))
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed()
                        .thenComparing(SearchHit::fileName)
                        .thenComparingInt(SearchHit::chunkIndex))
                .limit(TOP_K)
                .filter(hit -> hit.score() > SCORE_THRESHOLD)
                .toList();
    }

    private CaseResult resultFor(EvalCase testCase, List<SearchHit> hits, double latencyMs) {
        int firstRelevantRank = 0;
        for (int index = 0; index < hits.size(); index++) {
            if (testCase.expectedSources().contains(hits.get(index).fileName())) {
                firstRelevantRank = index + 1;
                break;
            }
        }
        var returnedSources = new LinkedHashSet<String>();
        hits.stream().map(SearchHit::fileName).forEach(returnedSources::add);
        boolean falsePositive = !testCase.answerable() && !hits.isEmpty();
        return new CaseResult(testCase.id(), testCase.category(), testCase.variant(), testCase.question(),
                testCase.answerable(), testCase.expectedSources(), firstRelevantRank,
                firstRelevantRank > 0, falsePositive, latencyMs,
                List.copyOf(returnedSources), rankedHits(hits));
    }

    private List<HitView> rankedHits(List<SearchHit> hits) {
        var views = new ArrayList<HitView>();
        for (int index = 0; index < hits.size(); index++) {
            var hit = hits.get(index);
            var preview = hit.content().replaceAll("\\s+", " ").trim();
            if (preview.length() > 240) preview = preview.substring(0, 240) + "…";
            views.add(new HitView(index + 1, hit.fileName(), hit.chunkIndex(), hit.score(), preview));
        }
        return views;
    }

    private MetricSummary metrics(List<EvalCase> cases, List<CaseResult> results) {
        int answerable = (int) cases.stream().filter(EvalCase::answerable).count();
        int noAnswer = cases.size() - answerable;
        long hits = results.stream().filter(CaseResult::answerable).filter(CaseResult::hitAt5).count();
        double reciprocalRanks = results.stream().filter(CaseResult::answerable)
                .mapToDouble(item -> item.firstRelevantRank() == 0 ? 0.0 : 1.0 / item.firstRelevantRank()).sum();
        long falsePositives = results.stream().filter(CaseResult::falsePositive).count();

        var byVariant = new LinkedHashMap<String, VariantMetrics>();
        cases.stream().map(EvalCase::variant).distinct().forEach(variant -> {
            var variantResults = results.stream().filter(item -> item.variant().equals(variant)).toList();
            int variantAnswerable = (int) variantResults.stream().filter(CaseResult::answerable).count();
            int variantNoAnswer = variantResults.size() - variantAnswerable;
            long variantHits = variantResults.stream().filter(CaseResult::answerable).filter(CaseResult::hitAt5).count();
            double variantRr = variantResults.stream().filter(CaseResult::answerable)
                    .mapToDouble(item -> item.firstRelevantRank() == 0 ? 0.0 : 1.0 / item.firstRelevantRank()).sum();
            long variantFp = variantResults.stream().filter(CaseResult::falsePositive).count();
            byVariant.put(variant, new VariantMetrics(variantResults.size(), variantAnswerable, variantNoAnswer,
                    ratio(variantHits, variantAnswerable), ratio(variantRr, variantAnswerable),
                    variantNoAnswer == 0 ? null : ratio(variantFp, variantNoAnswer)));
        });

        return new MetricSummary(cases.size(), answerable, noAnswer, ratio(hits, answerable),
                ratio(reciprocalRanks, answerable), ratio(falsePositives, noAnswer), byVariant);
    }

    private List<ThresholdMetrics> thresholdSweep(List<CaseResult> results) {
        return List.of(0.05, 0.10, 0.20, 0.25, 0.30, 0.35, 0.40).stream().map(threshold -> {
            int answerable = (int) results.stream().filter(CaseResult::answerable).count();
            int noAnswer = results.size() - answerable;
            int hits = 0;
            double reciprocalRanks = 0;
            int falsePositives = 0;
            int returned = 0;
            for (var result : results) {
                var kept = result.hits().stream().filter(hit -> hit.score() > threshold).toList();
                returned += kept.size();
                if (result.answerable()) {
                    int rank = 0;
                    for (var hit : kept) {
                        if (result.expectedSources().contains(hit.fileName())) {
                            rank = hit.rank();
                            break;
                        }
                    }
                    if (rank > 0) {
                        hits++;
                        reciprocalRanks += 1.0 / rank;
                    }
                } else if (!kept.isEmpty()) {
                    falsePositives++;
                }
            }
            return new ThresholdMetrics(threshold, ratio(hits, answerable),
                    ratio(reciprocalRanks, answerable), ratio(falsePositives, noAnswer),
                    ratio(returned, results.size()));
        }).toList();
    }

    private LatencySummary latency(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        return new LatencySummary(values.size(), WARMUP_ROUNDS,
                percentile(sorted, 0.50), percentile(sorted, 0.95), sorted.get(sorted.size() - 1));
    }

    private AnswerMetrics answerMetrics(Dataset dataset, Path repositoryRoot) throws Exception {
        var configuredPath = System.getProperty("rag.eval.answers", "").trim();
        if (configuredPath.isEmpty()) {
            return new AnswerMetrics("not_measured",
                    "This offline run does not call the chat model. Pass -Answers with a captured answer-run JSON file to calculate answer metrics.",
                    null, null, null, null, null, null, null, null);
        }

        var answerPath = repositoryRoot.resolve(configuredPath).normalize();
        var answerRun = json.readValue(answerPath.toFile(), AnswerRun.class);
        assertThat(answerRun.responses().stream().map(AnswerResponse::caseId)).doesNotHaveDuplicates();
        assertThat(answerRun.responses()).hasSameSizeAs(dataset.cases());

        var casesById = new LinkedHashMap<String, EvalCase>();
        dataset.cases().forEach(item -> casesById.put(item.id(), item));
        var responsesById = new LinkedHashMap<String, AnswerResponse>();
        answerRun.responses().forEach(item -> responsesById.put(item.caseId(), item));
        assertThat(responsesById.keySet()).containsExactlyInAnyOrderElementsOf(casesById.keySet());

        int answerable = 0;
        int withCitation = 0;
        int withExpectedCitation = 0;
        int totalCitations = 0;
        int relevantCitations = 0;
        int facts = 0;
        int matchedFacts = 0;
        int noAnswer = 0;
        int abstained = 0;
        var endToEndLatencies = new ArrayList<Double>();

        for (var testCase : dataset.cases()) {
            var response = responsesById.get(testCase.id());
            var citedSources = response.citedSources() == null ? List.<String>of() : response.citedSources();
            totalCitations += citedSources.size();
            relevantCitations += (int) citedSources.stream()
                    .filter(testCase.expectedSources()::contains).count();
            if (response.latencyMs() != null) endToEndLatencies.add(response.latencyMs());

            if (testCase.answerable()) {
                answerable++;
                if (!citedSources.isEmpty()) withCitation++;
                if (citedSources.stream().anyMatch(testCase.expectedSources()::contains)) withExpectedCitation++;
                var factOwner = testCase.answerKeyCaseId() == null
                        ? testCase : casesById.get(testCase.answerKeyCaseId());
                var normalizedAnswer = normalizeForMatch(response.answer());
                for (var fact : factOwner.keyFacts()) {
                    facts++;
                    if (fact.anyOf().stream().map(this::normalizeForMatch)
                            .anyMatch(normalizedAnswer::contains)) matchedFacts++;
                }
            } else {
                noAnswer++;
                if (response.abstained()) abstained++;
            }
        }

        var sortedLatency = endToEndLatencies.stream().sorted().toList();
        return new AnswerMetrics("measured", "Literal key-fact matching plus structured citedSources/abstained fields.",
                answerRun.responses().size(), ratio(withCitation, answerable),
                ratio(withExpectedCitation, answerable), ratio(relevantCitations, totalCitations),
                ratio(matchedFacts, facts), ratio(abstained, noAnswer),
                sortedLatency.isEmpty() ? null : percentile(sortedLatency, 0.50),
                sortedLatency.isEmpty() ? null : percentile(sortedLatency, 0.95));
    }

    private String normalizeForMatch(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s`*_，。；：、,.!！?？()（）\\[\\]{}]+", "");
    }

    private double percentile(List<Double> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftSquared = 0;
        double rightSquared = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftSquared += left[index] * left[index];
            rightSquared += right[index] * right[index];
        }
        if (leftSquared == 0 || rightSquared == 0) return 0;
        return dot / (Math.sqrt(leftSquared) * Math.sqrt(rightSquared));
    }

    private double elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private double ratio(double numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator / denominator;
    }

    record Dataset(String schemaVersion, String name, String description, CorpusConfig corpus,
                   List<EvalCase> cases) {}
    record CorpusConfig(String directory, String includeFilePattern, List<String> excludedFiles) {}
    record EvalCase(String id, String category, String variant, String question, String referenceAnswer,
                    String answerKeyCaseId, boolean answerable, List<String> expectedSources,
                    List<KeyFact> keyFacts) {}
    record KeyFact(String id, List<String> anyOf) {}
    record IndexedChunk(String fileName, int chunkIndex, String content, float[] vector) {}
    record SearchHit(String fileName, int chunkIndex, double score, String content) {}
    record Corpus(List<DocumentSummary> documents, List<IndexedChunk> chunks, String sha256) {}

    record Report(String schemaVersion, String generatedAt, String gitCommit, Evaluator evaluator,
                  RetrievalConfig retrievalConfig, CorpusSummary corpus, MetricSummary metrics,
                  List<ThresholdMetrics> thresholdSweep, LatencySummary latency,
                  AnswerMetrics answerMetrics, List<CaseResult> cases,
                  List<String> interpretationNotes) {}
    record Evaluator(String mode, String javaVersion, String fidelity) {}
    record RetrievalConfig(String embeddingModel, int dimensions, String distance, int topK,
                           double scoreThreshold, int targetChunkCharacters,
                           int maximumChunkCharacters, int overlapCharacters) {}
    record CorpusSummary(int documentCount, int chunkCount, String corpusSha256,
                         List<DocumentSummary> documents, double indexingLatencyMs) {}
    record DocumentSummary(String fileName, String rawSha256, int extractedCharacters,
                           int chunkCount) {}
    record MetricSummary(int totalCases, int answerableCases, int noAnswerCases, double recallAt5,
                         double mrr, double noAnswerFalsePositiveRate,
                         Map<String, VariantMetrics> byVariant) {}
    record VariantMetrics(int totalCases, int answerableCases, int noAnswerCases, double recallAt5,
                          double mrr, Double noAnswerFalsePositiveRate) {}
    record ThresholdMetrics(double threshold, double recallAt5, double mrr,
                            double noAnswerFalsePositiveRate, double meanReturnedChunks) {}
    record LatencySummary(int measuredQueries, int warmupRounds, double p50Ms, double p95Ms,
                          double maximumMs) {}
    record AnswerMetrics(String status, String reason, Integer responseCount,
                         Double answerCitationCoverage, Double expectedSourceCitationHitRate,
                         Double citationPrecision, Double keyFactCoverage,
                         Double noAnswerAbstentionRate, Double endToEndP50Ms,
                         Double endToEndP95Ms) {}
    record AnswerRun(String systemUnderTest, String capturedAt, List<AnswerResponse> responses) {}
    record AnswerResponse(String caseId, String answer, List<String> citedSources,
                          boolean abstained, Double latencyMs) {}
    record CaseResult(String id, String category, String variant, String question, boolean answerable,
                      List<String> expectedSources, int firstRelevantRank, boolean hitAt5,
                      boolean falsePositive, double latencyMs, List<String> returnedSources,
                      List<HitView> hits) {}
    record HitView(int rank, String fileName, int chunkIndex, double score, String contentPreview) {}
}
