package com.example.assistant.web;

import com.example.assistant.orchestration.AgentOrchestrator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
class ChatController {

    private final AgentOrchestrator agent;

    ChatController(AgentOrchestrator agent) {
        this.agent = agent;
    }

    @PostMapping("/chat")
    ChatResponse chat(@RequestBody ChatRequest request) {
        var r = agent.chat(request.message());
        return new ChatResponse(
                r.answer(),
                List.of(),          // citations derived from toolTrace by the caller, if needed
                r.toolTrace(),      // full observability
                "AGENT",
                r.iterations());
    }

    record ChatRequest(String employeeId, String message) {}

    record ChatResponse(String answer,
                        List<Object> citations,
                        List<Map<String, Object>> toolTrace,
                        String mode,
                        int iterations) {}
}