package com.example.assistant.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM supervisor router. This is the primary router; the heuristic selector only pre-empts it for
 * unambiguous queries and catches it when it fails.
 */
@Component
public class LlmAgentSelector {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentSelector.class);

    private final ChatClient supervisorClient;
    private final ObjectMapper mapper;

    public LlmAgentSelector(@Qualifier("supervisorClient") ChatClient supervisorClient, ObjectMapper mapper) {
        this.supervisorClient = supervisorClient;
        this.mapper = mapper;
    }

    public RoutingDecision select(String query, List<MarketAgent> availableAgents) {
        var availableAgentNames = availableAgents.stream()
                .map(MarketAgent::name)
                .collect(Collectors.toSet());
        var catalogue = availableAgents.stream()
                .map(agent -> Map.of(
                        "name", agent.capability().name(),
                        "description", agent.capability().description(),
                        "exampleQueries", agent.capability().exampleQueries()))
                .toList();

        var userPrompt = """
                Agent catalogue:
                %s

                User query:
                %s
                """.formatted(mapper.valueToTree(catalogue), query);

        try {
            var raw = supervisorClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content();
            var response = parse(raw);
            if (response.needsClarification()) {
                log.info("Supervisor requested clarification for query '{}': {}", query, response.reason());
                return RoutingDecision.clarify();
            }
            return RoutingDecision.routed(response.agents().stream()
                    .filter(availableAgentNames::contains)
                    .distinct()
                    .map(agentName -> new AgentSelection(
                            agentName,
                            response.confidence(),
                            "llm-router: " + response.reason()))
                    .toList());
        } catch (Exception e) {
            // Empty selection lets AgentSelector fall through to the heuristic router, but the
            // failure must be visible: silently degrading hides a broken model endpoint.
            log.warn("LLM supervisor routing failed; falling back to heuristics: {}", e.toString());
            return RoutingDecision.routed(List.of());
        }
    }

    private RouterResponse parse(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return new RouterResponse(List.of(), true, 0.0, "empty-router-response");
        }
        var json = raw.replaceAll("(?s)```json|```", "").trim();
        var node = mapper.readTree(json);
        var agents = mapper.convertValue(node.path("agents"), new TypeReference<List<String>>() {
        });
        return new RouterResponse(
                agents == null ? List.of() : agents,
                node.path("needsClarification").asBoolean(false),
                node.path("confidence").asDouble(0.8),
                node.path("reason").asText("selected-by-llm-router")
        );
    }

    private record RouterResponse(List<String> agents,
                                  boolean needsClarification,
                                  double confidence,
                                  String reason) {
    }
}
