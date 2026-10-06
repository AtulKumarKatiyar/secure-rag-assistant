package com.example.assistant.orchestration;

import java.util.List;

/**
 * Outcome of routing a user message.
 *
 * <p>{@code needsClarification} exists because the supervisor prompt is allowed to answer with
 * {@code needsClarification=true}. The previous implementation discarded that signal and silently
 * fell through to the heuristic/default agent, which turned "I am not sure what you mean" into a
 * confident answer from the wrong specialist.
 */
public record RoutingDecision(List<AgentSelection> selections, boolean needsClarification) {

    public static RoutingDecision routed(List<AgentSelection> selections) {
        return new RoutingDecision(List.copyOf(selections), false);
    }

    public static RoutingDecision clarify() {
        return new RoutingDecision(List.of(), true);
    }
}
