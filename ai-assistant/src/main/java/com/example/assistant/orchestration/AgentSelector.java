package com.example.assistant.orchestration;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Central routing entry point.
 *
 * <p>Order: a deterministic fast path for unambiguous queries, then the LLM supervisor, then a
 * heuristic fallback, then the configured default. Only the fast path may pre-empt the LLM router;
 * every other case is allowed to reach it.
 */
@Service
public class AgentSelector {
    private final HeuristicAgentSelector heuristicSelector;
    private final LlmAgentSelector llmAgentSelector;
    private final AgentRoutingProperties properties;

    public AgentSelector(HeuristicAgentSelector heuristicSelector,
                         LlmAgentSelector llmAgentSelector,
                         AgentRoutingProperties properties) {
        this.heuristicSelector = heuristicSelector;
        this.llmAgentSelector = llmAgentSelector;
        this.properties = properties;
    }

    public RoutingDecision select(String message, List<MarketAgent> agents) {
        var fastPath = heuristicSelector.fastPath(message, agents);
        if (!fastPath.isEmpty()) {
            return RoutingDecision.routed(fastPath);
        }

        var llmDecision = llmAgentSelector.select(message, agents);
        if (llmDecision.needsClarification()) {
            return llmDecision;
        }
        if (!llmDecision.selections().isEmpty()) {
            return llmDecision;
        }

        var fallback = heuristicSelector.fallback(message, agents);
        if (!fallback.isEmpty()) {
            return RoutingDecision.routed(fallback);
        }

        return RoutingDecision.routed(List.of(new AgentSelection(
                properties.defaultAgent(),
                0.0,
                "No selector produced a route; using configured default agent."
        )));
    }
}
