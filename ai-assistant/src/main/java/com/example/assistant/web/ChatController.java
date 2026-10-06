package com.example.assistant.web;

import com.example.assistant.orchestration.AgentOrchestrator;
import com.example.assistant.orchestration.AgentSelection;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Chat entry point.
 *
 * <p>The caller identity is taken from the verified JWT, never from the request body. The body
 * carries the message and nothing else, so a caller cannot assert someone else's tenant or grant
 * itself an entitlement group.
 */
@RestController
class ChatController {

    private final AgentOrchestrator agent;
    private final TenantContext tenantContext;

    ChatController(AgentOrchestrator agent, TenantContext tenantContext) {
        this.agent = agent;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/chat")
    ChatResponse chat(@RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        tenantContext.setFromToken(jwt);
        var r = agent.chat(request.message());
        return new ChatResponse(
                r.answer(),
                r.toolTrace(),          // full observability
                "AGENT",
                r.agentCount(),
                r.needsClarification(),
                r.agentSelections(),
                r.agentAnswers());
    }

    record ChatRequest(String message) {
    }

    record ChatResponse(String answer,
                        List<Map<String, Object>> toolTrace,
                        String mode,
                        int agentCount,
                        boolean needsClarification,
                        List<AgentSelection> agentSelections,
                        List<AgentOrchestrator.AgentAnswer> agentAnswers) {
    }
}
