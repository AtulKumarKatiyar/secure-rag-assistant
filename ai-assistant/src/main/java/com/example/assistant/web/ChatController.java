package com.example.assistant.web;

import com.example.assistant.orchestration.AgentOrchestrator;
import com.example.assistant.orchestration.ChatOrchestrator;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class ChatController {

    private final ChatOrchestrator routerOrchestrator;
    private final AgentOrchestrator agentOrchestrator;

    @Value("${assistant.mode:router}")
    private String mode;

    ChatController(ChatOrchestrator routerOrchestrator,
                   AgentOrchestrator agentOrchestrator) {
        this.routerOrchestrator = routerOrchestrator;
        this.agentOrchestrator = agentOrchestrator;
    }

    @PostMapping("/chat")
    ChatResponse chat(@RequestBody ChatRequest request) {
        if ("agent".equalsIgnoreCase(mode)) {
            var answer = agentOrchestrator.chat(request.message());
            return new ChatResponse(answer, List.of(), List.of(), "AGENT", "agent-mode");
        }

        var r = routerOrchestrator.chat(request.message());

        var citations = r.ragChunks().stream()
            .map(ChatController::toCitation)
            .toList();
        var tools = r.liveData() == null
            ? List.<ToolResult>of()
            : List.of(new ToolResult("stock-news-api", r.liveData()));

        return new ChatResponse(
            r.answer(),
            citations,
            tools,
            r.decision().route().name(),
            r.decision().reason());
    }

    private static Citation toCitation(Document d) {
        var meta = d.getMetadata();
        return new Citation(
            String.valueOf(meta.getOrDefault("id", "unknown")),
            String.valueOf(meta.getOrDefault("title", "chunk")),
            0.0);
    }

    record ChatRequest(String employeeId, String message) {}

    record ChatResponse(String answer,
                        List<Citation> citations,
                        List<ToolResult> toolResults,
                        String route,
                        String reason) {}

    record Citation(String documentId, String title, double score) {}

    record ToolResult(String name, Object result) {}
}