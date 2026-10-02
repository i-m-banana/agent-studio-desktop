package com.agentstudio.conversation;

import java.util.List;
import com.agentstudio.runtime.AgentRun;
import com.agentstudio.runtime.RunRepository;
import com.agentstudio.system.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationRepository conversations;
    private final RunRepository runs;
    public ConversationController(ConversationRepository conversations, RunRepository runs) {
        this.conversations = conversations; this.runs = runs;
    }
    public record Detail(String id, String agentVersionId, List<ConversationRepository.HistoryMessage> messages,
                         List<AgentRun> runs) {}
    @GetMapping
    List<ConversationRepository.Summary> list(@RequestParam(defaultValue="50") int limit,
                                              @RequestParam(defaultValue="0") int offset,
                                              @RequestParam(defaultValue="false") boolean deleted) {
        return conversations.list(limit,offset,deleted);
    }
    public record Selection(List<String> ids) {}
    @PostMapping("/trash")
    java.util.Map<String,Boolean> trash(@RequestBody Selection selection) {
        synchronized (conversations) { conversations.moveToTrash(selection.ids(),true); }
        return java.util.Map.of("successful",true);
    }
    @PostMapping("/restore")
    java.util.Map<String,Boolean> restore(@RequestBody Selection selection) {
        synchronized (conversations) { conversations.moveToTrash(selection.ids(),false); }
        return java.util.Map.of("successful",true);
    }
    @GetMapping("/{id}")
    Detail detail(@PathVariable String id) {
        var version = conversations.findAgentVersionId(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"会话不存在"));
        return new Detail(id,version,conversations.historyMessages(id),runs.forConversation(id));
    }
}
