package com.example.assistant.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Routing thresholds.
 *
 * <p>These values are consumed by {@link HeuristicAgentSelector}. They previously existed as
 * configuration but were never read, while the selector hardcoded its own thresholds, so editing
 * {@code assistant.agent-routing.*} silently changed nothing.
 */
@Component
public class AgentRoutingProperties {
    private final double minScore;
    private final double fanoutScoreGap;
    private final int maxAgents;
    private final String defaultAgent;

    public AgentRoutingProperties(@Value("${assistant.agent-routing.min-score:3}") double minScore,
                                  @Value("${assistant.agent-routing.fanout-score-gap:3}") double fanoutScoreGap,
                                  @Value("${assistant.agent-routing.max-agents:3}") int maxAgents,
                                  @Value("${assistant.agent-routing.default-agent:equity-agent}") String defaultAgent) {
        this.minScore = minScore;
        this.fanoutScoreGap = fanoutScoreGap;
        this.maxAgents = maxAgents;
        this.defaultAgent = defaultAgent;
    }

    /** Minimum heuristic score for a single agent to be routed without consulting the LLM. */
    public double minScore() {
        return minScore;
    }

    /**
     * When more than one domain matches, the leader must be ahead by at least this much before the
     * fast path picks it alone. Below the gap the query is treated as genuinely cross-domain and
     * handed to the LLM router.
     */
    public double fanoutScoreGap() {
        return fanoutScoreGap;
    }

    public int maxAgents() {
        return maxAgents;
    }

    public String defaultAgent() {
        return defaultAgent;
    }
}
