package com.agentstudio.conversation;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

import com.agentstudio.agent.AgentService;
import com.agentstudio.approval.ApprovalService;
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

    private final AgentService agents;
    private final ConversationRepository conversations;
    private final StreamingModelGateway modelGateway;
    private final KnowledgeRetriever knowledgeRetriever;
    private final TaskExecutor taskExecutor;
    private final RunRepository runs;
    private final ToolRegistry tools;
    private final ApprovalService approvals;
    private final RunControlService controls;
    private final Duration totalTimeout;
    private final int maxRounds;
    private final int maxCallsPerRound;

    public ChatService(AgentService agents, ConversationRepository conversations,
                       StreamingModelGateway modelGateway, KnowledgeRetriever knowledgeRetriever,
                       @Qualifier("chatTaskExecutor") TaskExecutor taskExecutor,
                       RunRepository runs, ToolRegistry tools, ApprovalService approvals, RunControlService controls,
                       @Value("${agent-studio.runtime.max-tool-rounds:4}") int maxRounds,
                       @Value("${agent-studio.runtime.max-tool-calls-per-round:4}") int maxCallsPerRound,
                       @Value("${agent-studio.runtime.total-timeout:120s}") Duration totalTimeout) {
        this.agents = agents;
        this.conversations = conversations;
        this.modelGateway = modelGateway;
        this.knowledgeRetriever = knowledgeRetriever;
        this.taskExecutor = taskExecutor;
        this.runs = runs;
        this.tools = tools;
        this.approvals = approvals;
        this.controls = controls;
        this.maxRounds = maxRounds;
        this.maxCallsPerRound = maxCallsPerRound;
        if (totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("运行总超时必须大于 0");
        }
        this.totalTimeout = totalTimeout;
    }

    public SseEmitter stream(ChatStreamRequest request) {
        var version = agents.getVersion(request.agentVersionId());
        var conversationId = resolveConversation(request.conversationId(), version.id());
        conversations.addMessage(conversationId, "user", request.message().trim());

        var emitter = new SseEmitter(totalTimeout.plusSeconds(10).toMillis());
        taskExecutor.execute(() -> executeStream(emitter, conversationId, version));
        return emitter;
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
                               com.agentstudio.agent.AgentVersion version) {
        var answer = new StringBuilder();
        var run = runs.start(conversationId, version.id());
        controls.register(run.id(), Thread.currentThread(), totalTimeout);
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
            if (!sources.isEmpty()) {
                send(emitter, "sources", Map.of("items", sources));
                modelMessages.add(new ModelMessage("system", knowledgeContext(sources)));
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
                executeReAct(emitter, run.id(), version, modelMessages, answer);
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

    private void executeReAct(SseEmitter emitter, String runId,
                              com.agentstudio.agent.AgentVersion version,
                              java.util.List<ModelMessage> sourceMessages,
                              StringBuilder answer) throws Exception {
        var messages = new ArrayList<ReActMessage>();
        sourceMessages.forEach(message -> messages.add(ReActMessage.text(message.role(), message.content())));
        var descriptors = tools.descriptors(version.toolNames());
        for (int round = 1; round <= maxRounds; round++) {
            controls.check(runId);
            runs.updateStatus(runId, "THINKING");
            var turn = modelGateway.complete(version, messages, descriptors);
            controls.check(runId);
            emitStep(emitter, runs.addStep(runId, "MODEL_CALL",
                    turn.toolCalls().isEmpty() ? "COMPLETED" : "TOOL_REQUESTED",
                    null, null, null, truncate(turn.content()), null));
            if (turn.toolCalls().isEmpty()) {
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
                if ("HIGH".equals(tools.descriptor(call.name()).riskLevel())) {
                    var approval = approvals.request(runId, call);
                    emitStep(emitter, runs.addStep(runId, "APPROVAL_REQUEST", "WAITING", call.id(),
                            call.name(), truncate(call.argumentsJson()), null, null));
                    runs.updateStatus(runId, "WAITING_APPROVAL");
                    send(emitter, "approval_required", approval);
                    var outcome = approvals.await(approval);
                    controls.check(runId);
                    emitStep(emitter, runs.addStep(runId, "APPROVAL_RESULT", outcome.status(), call.id(),
                            call.name(), null, truncate(outcome.reason()), null));
                    if (!outcome.approved()) {
                        var denied = "工具未执行：审批" + ("EXPIRED".equals(outcome.status()) ? "已过期" : "被拒绝")
                                + (outcome.reason() == null ? "" : "（" + outcome.reason() + "）");
                        messages.add(ReActMessage.observation(call.id(), denied));
                        runs.updateStatus(runId, "OBSERVING");
                        continue;
                    }
                    runs.updateStatus(runId, "TOOL_RUNNING");
                }
                try {
                    var result = tools.execute(call.name(), call.argumentsJson());
                    var output = truncate(result.output());
                    emitStep(emitter, runs.addStep(runId, "TOOL_RESULT", "COMPLETED", call.id(),
                            call.name(), null, output, result.durationMs()));
                    messages.add(ReActMessage.observation(call.id(), output));
                    runs.updateStatus(runId, "OBSERVING");
                } catch (Exception exception) {
                    controls.check(runId);
                    var failure = "工具执行失败：" + safeMessage(exception);
                    emitStep(emitter, runs.addStep(runId, "TOOL_RESULT", "FAILED", call.id(),
                            call.name(), null, truncate(failure), null));
                    messages.add(ReActMessage.observation(call.id(), failure));
                    runs.updateStatus(runId, "OBSERVING");
                }
            }
        }
        throw new IllegalStateException("达到最大工具轮数 " + maxRounds + "，运行已安全停止");
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

    private String knowledgeContext(java.util.List<RagSource> sources) {
        var context = new StringBuilder("以下是本次问题检索到的知识库片段。优先依据片段回答；若证据不足请明确说明。\n\n");
        for (var source : sources) {
            context.append("[来源：").append(source.fileName()).append("，chunk ")
                    .append(source.chunkIndex()).append("]\n")
                    .append(source.content()).append("\n\n");
        }
        return context.toString();
    }
}
