package com.agentstudio.conversation;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

import com.agentstudio.agent.AgentService;
import com.agentstudio.approval.ApprovalService;
import com.agentstudio.execution.SafeExecutionGateway;
import com.agentstudio.model.ModelMessage;
import com.agentstudio.model.ReActMessage;
import com.agentstudio.model.StreamingModelGateway;
import com.agentstudio.knowledge.KnowledgeRetriever;
import com.agentstudio.knowledge.RagSource;
import com.agentstudio.system.ApiException;
import com.agentstudio.runtime.RunRepository;
import com.agentstudio.runtime.RunControlService;
import com.agentstudio.runtime.RunTerminatedException;
import com.agentstudio.runtime.RunStep;
import com.agentstudio.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatService {
    private static final java.util.Set<String> FIXED_TASK_TOOLS = java.util.Set.of(
            "inspect_remote_deployment", "run_remote_workspace_task", "prepare_remote_deployment_backup",
            "verify_remote_deployment_backup_restore", "prepare_release_candidate",
            "build_release_candidate_image", "adopt_remote_database_baseline", "publish_remote_release");

    private final AgentService agents;
    private final ConversationRepository conversations;
    private final StreamingModelGateway modelGateway;
    private final KnowledgeRetriever knowledgeRetriever;
    private final TaskExecutor taskExecutor;
    private final RunRepository runs;
    private final ToolRegistry tools;
    private final ApprovalService approvals;
    private final SafeExecutionGateway executionGateway;
    private final RunControlService controls;
    private final Duration totalTimeout;
    private final Duration releaseCandidateTimeout;
    private final int maxRounds;
    private final int maxCallsPerRound;

    public ChatService(AgentService agents, ConversationRepository conversations,
                       StreamingModelGateway modelGateway, KnowledgeRetriever knowledgeRetriever,
                       @Qualifier("chatTaskExecutor") TaskExecutor taskExecutor,
                       RunRepository runs, ToolRegistry tools, ApprovalService approvals,
                       SafeExecutionGateway executionGateway, RunControlService controls,
                       @Value("${agent-studio.runtime.max-tool-rounds:4}") int maxRounds,
                       @Value("${agent-studio.runtime.max-tool-calls-per-round:4}") int maxCallsPerRound,
                       @Value("${agent-studio.runtime.total-timeout:120s}") Duration totalTimeout,
                       @Value("${agent-studio.runtime.release-candidate-timeout:900s}") Duration releaseCandidateTimeout) {
        this.agents = agents;
        this.conversations = conversations;
        this.modelGateway = modelGateway;
        this.knowledgeRetriever = knowledgeRetriever;
        this.taskExecutor = taskExecutor;
        this.runs = runs;
        this.tools = tools;
        this.approvals = approvals;
        this.executionGateway = executionGateway;
        this.controls = controls;
        this.maxRounds = maxRounds;
        this.maxCallsPerRound = maxCallsPerRound;
        if (totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("运行总超时必须大于 0");
        }
        this.totalTimeout = totalTimeout;
        if (releaseCandidateTimeout.compareTo(Duration.ofSeconds(650)) < 0) {
            throw new IllegalArgumentException("候选发布运行总时限至少为 650 秒，以容纳工具和审批预算");
        }
        this.releaseCandidateTimeout = releaseCandidateTimeout;
    }

    public SseEmitter stream(ChatStreamRequest request) {
        var version = agents.getVersion(request.agentVersionId());
        if (request.requestedTool() != null) {
            var requested = request.requestedTool();
            if (!FIXED_TASK_TOOLS.contains(requested.name()) || !version.toolNames().contains(requested.name()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "固定任务工具未绑定到当前 Agent 版本或不受支持");
            if (!requested.arguments().isObject() || requested.arguments().toString().length() > 20000)
                throw new ApiException(HttpStatus.BAD_REQUEST, "固定任务参数必须为有界 JSON 对象");
        }
        if (version.archived() && (request.conversationId() == null || request.conversationId().isBlank())) {
            throw new ApiException(HttpStatus.CONFLICT, "该 Agent 版本已归档，不能创建新会话");
        }
        var conversationId = resolveConversation(request.conversationId(), version.id());
        conversations.addMessage(conversationId, "user", request.message().trim());

        var emitter = new SseEmitter(runTimeout(version.toolNames()).plusSeconds(10).toMillis());
        taskExecutor.execute(() -> executeStream(emitter, conversationId, version, request.requestedTool()));
        return emitter;
    }

    Duration runTimeout(java.util.List<String> toolNames) {
        if (toolNames.contains("build_release_candidate_image") || toolNames.contains("publish_remote_release")) {
            var imageBudget = Duration.ofSeconds(1200);
            if (releaseCandidateTimeout.compareTo(imageBudget) > 0) imageBudget = releaseCandidateTimeout;
            return totalTimeout.compareTo(imageBudget) > 0 ? totalTimeout : imageBudget;
        }
        return (toolNames.contains("prepare_release_candidate") || toolNames.contains("build_release_candidate_image"))
                && releaseCandidateTimeout.compareTo(totalTimeout) > 0
                ? releaseCandidateTimeout : totalTimeout;
    }

    private String resolveConversation(String requestedId, String versionId) {
        if (requestedId == null || requestedId.isBlank()) {
            return conversations.create(versionId);
        }
        var boundVersion = conversations.findAgentVersionId(requestedId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (!boundVersion.equals(versionId)) {
            throw new ApiException(HttpStatus.CONFLICT, "会话已绑定其他 Agent 版本");
        }
        return requestedId;
    }

    private void executeStream(SseEmitter emitter, String conversationId,
                               com.agentstudio.agent.AgentVersion version, ChatStreamRequest.RequestedTool requestedTool) {
        var answer = new StringBuilder();
        var run = runs.start(conversationId, version.id());
        controls.register(run.id(), Thread.currentThread(), runTimeout(version.toolNames()));
        try {
            send(emitter, "run", Map.of(
                    "runId", run.id(),
                    "conversationId", conversationId,
                    "agentVersionId", version.id(),
                    "versionNumber", version.versionNumber()));
            controls.check(run.id());
            var modelMessages = new ArrayList<ModelMessage>();
            modelMessages.add(new ModelMessage("system", version.systemPrompt()));
            var sources = knowledgeRetriever.retrieve(version.knowledgeBaseId(),
                    conversations.messages(conversationId).getLast().content());
            controls.check(run.id());
            if (version.knowledgeBaseId() != null && sources.isEmpty() && version.toolNames().isEmpty()) {
                var noEvidence = "知识库中没有足够证据回答该问题。";
                send(emitter, "evidence", Map.of("status", "INSUFFICIENT", "message", noEvidence));
                answer.append(noEvidence);
                send(emitter, "delta", Map.of("content", noEvidence));
                emitStep(emitter, runs.addStep(run.id(), "EVIDENCE_CHECK", "INSUFFICIENT",
                        null, null, null, noEvidence, null));
            } else {
                if (!sources.isEmpty()) {
                    send(emitter, "sources", Map.of("items", sources));
                    send(emitter, "evidence", Map.of("status", "REVIEW_REQUIRED",
                            "message", "已召回候选片段，最终答案必须逐项核对证据。"));
                    modelMessages.add(new ModelMessage("system", knowledgeContext(sources)));
                } else if (version.knowledgeBaseId() != null) {
                    modelMessages.add(new ModelMessage("system", noEvidenceContext()));
                }
                modelMessages.addAll(conversations.messages(conversationId));
                if (version.toolNames().isEmpty()) {
                    runs.updateStatus(run.id(), "THINKING");
                    modelGateway.stream(version, modelMessages, delta -> {
                        controls.check(run.id());
                        answer.append(delta);
                        sendUnchecked(emitter, "delta", Map.of("content", delta));
                    });
                    emitStep(emitter, runs.addStep(run.id(), "MODEL_CALL", "COMPLETED",
                            null, null, null, truncate(answer.toString()), null));
                } else {
                    executeReAct(emitter, run.id(), conversationId, version, modelMessages, answer, requestedTool);
                }
            }
            controls.check(run.id());
            if (!runs.finish(run.id(), "COMPLETED", null)) {
                controls.check(run.id());
                throw new IllegalStateException("运行状态已在完成前发生变化");
            }
            conversations.addMessage(conversationId, "assistant", answer.toString());
            send(emitter, "done", Map.of("conversationId", conversationId));
            emitter.complete();
        } catch (Exception exception) {
            var termination = exception instanceof RunTerminatedException terminated
                    ? terminated.termination() : controls.termination(run.id());
            if (termination != null) {
                approvals.cancelPending(run.id(), termination.reason());
                runs.addStep(run.id(), "RUN_TERMINATION", termination.status(), null, null,
                        null, truncate(termination.reason()), null);
                runs.finish(run.id(), termination.status(), truncate(termination.reason()));
            } else {
                runs.finish(run.id(), "FAILED", truncate(safeMessage(exception)));
            }
            try {
                if (termination == null) {
                    send(emitter, "error", Map.of("message", safeMessage(exception)));
                } else {
                    send(emitter, "terminated", Map.of(
                            "runId", run.id(), "status", termination.status(), "message", termination.reason()));
                }
                emitter.complete();
            } catch (IOException ignored) {
                emitter.completeWithError(exception);
            }
        } finally {
            controls.unregister(run.id());
        }
    }

    private void executeReAct(SseEmitter emitter, String runId, String conversationId,
                              com.agentstudio.agent.AgentVersion version,
                              java.util.List<ModelMessage> sourceMessages,
                              StringBuilder answer, ChatStreamRequest.RequestedTool requestedTool) throws Exception {
        var messages = new ArrayList<ReActMessage>();
        sourceMessages.forEach(message -> messages.add(ReActMessage.text(message.role(), message.content())));
        messages.add(ReActMessage.text("system", """
                工具调用应保持必要且最少：已有结果足以回答时立即生成最终答案，不要用语义相近的查询重复验证同一事实。
                你最多拥有 %d 轮工具调用预算；预算耗尽后必须依据已经获得的结果作答。
                """.formatted(maxRounds).trim()));
        var descriptors = tools.descriptors(version.toolNames());
        boolean hasExecutionEvidence = false;
        var explicitToolRequest = requestsToolExecution(sourceMessages.getLast().content(), version.toolNames());
        for (int round = 1; round <= maxRounds; round++) {
            controls.check(runId);
            runs.updateStatus(runId, "THINKING");
            var turn = requestedTool == null ? modelGateway.complete(version, messages, descriptors)
                    : new com.agentstudio.model.ModelTurn("用户明确选择固定任务", java.util.List.of(
                            new com.agentstudio.model.ModelToolCall("fixed-" + java.util.UUID.randomUUID(),
                                    requestedTool.name(), requestedTool.arguments().toString())));
            controls.check(runId);
            emitStep(emitter, runs.addStep(runId, requestedTool == null ? "MODEL_CALL" : "USER_TOOL_REQUEST",
                    turn.toolCalls().isEmpty() ? "COMPLETED" : "TOOL_REQUESTED",
                    null, null, null, truncate(turn.content()), null));
            if (turn.toolCalls().isEmpty()) {
                if (explicitToolRequest && !hasExecutionEvidence) {
                    var noExecution = "本次没有工具执行结果，任务未执行；模型文字不能作为诊断或操作完成证据。请使用对应固定任务按钮并完成审批。";
                    emitStep(emitter, runs.addStep(runId, "EXECUTION_EVIDENCE_CHECK", "NOT_EXECUTED",
                            null, null, null, noExecution, null));
                    answer.append(noExecution);
                    send(emitter, "delta", Map.of("content", noExecution));
                    return;
                }
                if (turn.content() == null || turn.content().isBlank()) {
                    throw new IllegalStateException("模型未返回最终答案");
                }
                answer.append(turn.content());
                send(emitter, "delta", Map.of("content", turn.content()));
                return;
            }
            if (turn.toolCalls().size() > maxCallsPerRound) {
                throw new IllegalStateException("单轮工具调用超过上限 " + maxCallsPerRound + "，运行已安全停止");
            }
            messages.add(ReActMessage.assistant(turn));
            for (var call : turn.toolCalls()) {
                controls.check(runId);
                if (!version.toolNames().contains(call.name())) {
                    throw new IllegalStateException("模型请求了未绑定工具：" + call.name());
                }
                emitStep(emitter, runs.addStep(runId, "TOOL_CALL", "RUNNING", call.id(),
                        call.name(), truncate(call.argumentsJson()), null, null));
                runs.updateStatus(runId, "TOOL_RUNNING");
                try {
                    var result = executionGateway.execute(runId, conversationId, version.id(), call, approval -> {
                        emitStep(emitter, runs.addStep(runId, "APPROVAL_REQUEST", "WAITING", call.id(),
                                call.name(), truncate(call.argumentsJson()), null, null));
                        runs.updateStatus(runId, "WAITING_APPROVAL");
                        send(emitter, "approval_required", approval);
                    }, () -> runs.updateStatus(runId, "TOOL_RUNNING"), () -> controls.check(runId));
                    if (result.approvalOutcome() != null) {
                        emitStep(emitter, runs.addStep(runId, "APPROVAL_RESULT", result.approvalOutcome().status(),
                                call.id(), call.name(), null, truncate(result.approvalOutcome().reason()), null));
                    }
                    if (!result.executed()) {
                        messages.add(ReActMessage.observation(call.id(), result.output()));
                        runs.updateStatus(runId, "OBSERVING");
                        if (requestedTool != null) {
                            answer.append(result.output());
                            send(emitter, "delta", Map.of("content", result.output()));
                            return;
                        }
                        continue;
                    }
                    var rawOutput = result.output();
                    var output = toolRecord(rawOutput);
                    emitStep(emitter, runs.addStep(runId, "TOOL_RESULT", "COMPLETED", call.id(),
                            call.name(), null, output, result.durationMs()));
                    messages.add(ReActMessage.observation(call.id(), toolContext(rawOutput)));
                    hasExecutionEvidence = true;
                    runs.updateStatus(runId, "OBSERVING");
                    if (requestedTool != null) {
                        var receipt = "固定任务工具已执行；以下为原始工具结果（是否成功以 successful、exitCode 为准），不是模型推测：\n\n```json\n"
                                + toolRecord(rawOutput) + "\n```";
                        answer.append(receipt);
                        send(emitter, "delta", Map.of("content", receipt));
                        return;
                    }
                } catch (Exception exception) {
                    controls.check(runId);
                    var failure = "工具执行失败：" + safeMessage(exception);
                    emitStep(emitter, runs.addStep(runId, "TOOL_RESULT", "FAILED", call.id(),
                            call.name(), null, toolRecord(failure), null));
                    messages.add(ReActMessage.observation(call.id(), failure));
                    hasExecutionEvidence = true;
                    runs.updateStatus(runId, "OBSERVING");
                    if (requestedTool != null) {
                        answer.append(failure);
                        send(emitter, "delta", Map.of("content", failure));
                        return;
                    }
                }
            }
        }
        controls.check(runId);
        runs.updateStatus(runId, "THINKING");
        if (explicitToolRequest && !hasExecutionEvidence) {
            var noExecution = "本次没有工具执行结果，任务未执行；工具调用预算已用完，不能以模型文字代替执行证据。";
            emitStep(emitter, runs.addStep(runId, "EXECUTION_EVIDENCE_CHECK", "NOT_EXECUTED",
                    null, null, null, noExecution, null));
            answer.append(noExecution);
            send(emitter, "delta", Map.of("content", noExecution));
            return;
        }
        messages.add(ReActMessage.text("system", """
                工具调用预算已经用完。现在不得再调用任何工具；请仅依据前面已经返回的工具结果生成最终答案。
                若现有证据仍不足，应明确说明不足之处。不要声称执行了尚未执行的操作。
                """.trim()));
        var finalTurn = modelGateway.complete(version, messages, java.util.List.of());
        controls.check(runId);
        if (!finalTurn.toolCalls().isEmpty()) {
            throw new IllegalStateException("工具预算耗尽后模型仍请求工具，运行已安全停止");
        }
        if (finalTurn.content() == null || finalTurn.content().isBlank()) {
            throw new IllegalStateException("工具预算耗尽后模型未返回最终答案");
        }
        emitStep(emitter, runs.addStep(runId, "MODEL_FINALIZATION", "COMPLETED",
                null, null, "{\"reason\":\"MAX_TOOL_ROUNDS\"}", truncate(finalTurn.content()), null));
        answer.append(finalTurn.content());
        send(emitter, "delta", Map.of("content", finalTurn.content()));
    }

    static boolean requestsToolExecution(String message, java.util.List<String> names) {
        if (message == null || !java.util.regex.Pattern.compile("请(?:只|实际|仅|立即|务必|先|重新|你)?(?:使用|调用|运行|执行)")
                .matcher(message).find()) return false;
        return names.stream().anyMatch(message::contains);
    }

    private void emitStep(SseEmitter emitter, RunStep step) throws IOException {
        send(emitter, "step", step);
    }

    private void sendUnchecked(SseEmitter emitter, String event, Object data) {
        try {
            send(emitter, event, data);
        } catch (IOException exception) {
            throw new IllegalStateException("客户端连接已断开", exception);
        }
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private String safeMessage(Exception exception) {
        var message = exception.getMessage();
        return message == null || message.isBlank() ? "模型调用失败" : message;
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 4000 ? value : value.substring(0, 4000) + "…";
    }

    // Tool adapters already bound their output. Keep complete JSON and the final diagnostics in audit history.
    static String toolRecord(String value) {
        if (value == null || value.length() <= 128000) return value;
        return value.substring(0, 64000) + "\n[运行记录过长，已省略中间内容；以下为末尾诊断]\n"
                + value.substring(value.length() - 64000);
    }

    private String toolContext(String value) {
        if (value == null) return "";
        int limit = 16000;
        return value.length() <= limit ? value : value.substring(0, limit)
                + "\n[工具结果过长，模型上下文已在 16000 字符处截断]";
    }

    private String knowledgeContext(java.util.List<RagSource> sources) {
        var context = new StringBuilder("""
                以下内容只是检索候选片段，不代表其中一定有答案。请先执行证据充分性判断，再回答。
                规则：
                1. 只陈述片段直接支持的事实，不得用常识、猜测或相近概念补全缺失信息。
                2. 若问题要求具体数字、名称、版本、人员、许可、平台支持或承诺，而片段未明确给出，回答“知识库中没有足够证据回答该问题。”
                3. 有充分证据时，在关键结论后标注来源文件名；相关但不能回答问题的片段不算证据。

                """);
        for (var source : sources) {
            context.append("[来源：").append(source.fileName()).append("，chunk ")
                    .append(source.chunkIndex()).append("]\n")
                    .append(source.content()).append("\n\n");
        }
        return context.toString();
    }

    private String noEvidenceContext() {
        return "本次知识库检索没有得到满足阈值的片段。不得使用模型自身知识猜测；若工具也不能提供证据，回答“知识库中没有足够证据回答该问题。”";
    }
}
