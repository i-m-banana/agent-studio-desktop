package com.agentstudio.conversation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import com.agentstudio.agent.AgentService;
import com.agentstudio.model.ModelMessage;
import com.agentstudio.model.StreamingModelGateway;
import com.agentstudio.knowledge.KnowledgeRetriever;
import com.agentstudio.knowledge.RagSource;
import com.agentstudio.system.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
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

    public ChatService(AgentService agents, ConversationRepository conversations,
                       StreamingModelGateway modelGateway, KnowledgeRetriever knowledgeRetriever,
                       @Qualifier("chatTaskExecutor") TaskExecutor taskExecutor) {
        this.agents = agents;
        this.conversations = conversations;
        this.modelGateway = modelGateway;
        this.knowledgeRetriever = knowledgeRetriever;
        this.taskExecutor = taskExecutor;
    }

    public SseEmitter stream(ChatStreamRequest request) {
        var version = agents.getVersion(request.agentVersionId());
        var conversationId = resolveConversation(request.conversationId(), version.id());
        conversations.addMessage(conversationId, "user", request.message().trim());

        var emitter = new SseEmitter(120_000L);
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
        try {
            send(emitter, "run", Map.of(
                    "conversationId", conversationId,
                    "agentVersionId", version.id(),
                    "versionNumber", version.versionNumber()));
            var modelMessages = new ArrayList<ModelMessage>();
            modelMessages.add(new ModelMessage("system", version.systemPrompt()));
            var sources = knowledgeRetriever.retrieve(version.knowledgeBaseId(),
                    conversations.messages(conversationId).getLast().content());
            if (!sources.isEmpty()) {
                send(emitter, "sources", Map.of("items", sources));
                modelMessages.add(new ModelMessage("system", knowledgeContext(sources)));
            }
            modelMessages.addAll(conversations.messages(conversationId));
            modelGateway.stream(version, modelMessages, delta -> {
                answer.append(delta);
                sendUnchecked(emitter, "delta", Map.of("content", delta));
            });
            conversations.addMessage(conversationId, "assistant", answer.toString());
            send(emitter, "done", Map.of("conversationId", conversationId));
            emitter.complete();
        } catch (Exception exception) {
            try {
                send(emitter, "error", Map.of("message", safeMessage(exception)));
                emitter.complete();
            } catch (IOException ignored) {
                emitter.completeWithError(exception);
            }
        }
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
