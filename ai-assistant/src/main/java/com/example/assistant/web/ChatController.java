package com.example.assistant.web;

import com.example.assistant.orchestration.AgentOrchestrator;
import com.example.assistant.orchestration.AgentSelection;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
class ChatController {

    private final AgentOrchestrator agent;
    private final TenantContext tenantContext;

    ChatController(AgentOrchestrator agent, TenantContext tenantContext) {
        this.agent = agent;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/chat")
    ChatResponse chat(@RequestBody ChatRequest request) {
        tenantContext.setTenantId(request.tenantId());
        tenantContext.setEntitlementGroups(request.entitlementGroups());
        var r = agent.chat(request.message());
        return new ChatResponse(
                r.answer(),
                List.of(),          // citations derived from toolTrace by the caller, if needed
                r.toolTrace(),      // full observability
                "AGENT",
                r.iterations(),
                r.agentSelections(),
                r.agentAnswers());
    }

    record ChatRequest(String tenantId, String employeeId, String message, List<String> entitlementGroups) {}

    record ChatResponse(String answer,
                        List<Object> citations,
                        List<Map<String, Object>> toolTrace,
                        String mode,
                        int iterations,
                        List<AgentSelection> agentSelections,
                        List<AgentOrchestrator.AgentAnswer> agentAnswers) {}
}
