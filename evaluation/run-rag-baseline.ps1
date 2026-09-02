param(
    [string]$Output = "evaluation/results/local-hash-baseline.json",
    [string]$Answers = "",
    [ValidateSet("local-hash", "ollama")]
    [string]$Embedding = "local-hash",
    [string]$OllamaModel = "qwen3-embedding:0.6b",
    [int]$OllamaDimensions = 1024
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$gitSafeRoot = $repositoryRoot.Replace("\", "/")
$backendDirectory = Join-Path $repositoryRoot "backend"
$gitCommit = git -c "safe.directory=$gitSafeRoot" -C $repositoryRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) {
    $gitCommit = "unknown"
}

Push-Location $backendDirectory
try {
    $mavenArguments = @(
        "-Dtest=RagRetrievalEvaluationTest",
        "-Drag.eval.root=$repositoryRoot",
        "-Drag.eval.output=$Output",
        "-Drag.eval.gitCommit=$gitCommit",
        "-Drag.eval.embedding=$Embedding"
    )
    if ($Embedding -eq "ollama") {
        $mavenArguments += "-Drag.eval.ollama.model=$OllamaModel"
        $mavenArguments += "-Drag.eval.ollama.dimensions=$OllamaDimensions"
    }
    if ($Answers) {
        $mavenArguments += "-Drag.eval.answers=$Answers"
    }
    $mavenArguments += "test"
    mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "RAG evaluation failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$resolvedOutput = Join-Path $repositoryRoot $Output
Write-Host "RAG evaluation report: $resolvedOutput"
