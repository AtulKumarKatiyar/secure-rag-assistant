package com.example.assistant.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentRoutingProperties {
    private final int minScore;
    private final int fanoutScoreGap;
    private final int maxAgents;
    private final String defaultAgent;

    public AgentRoutingProperties(@Value("${assistant.agent-routing.min-score:3}") int minScore,
                                  @Value("${assistant.agent-routing.fanout-score-gap:3}") int fanoutScoreGap,
                                  @Value("${assistant.agent-routing.max-agents:3}") int maxAgents,
                                  @Value("${assistant.agent-routing.default-agent:equity-agent}") String defaultAgent) {
        this.minScore = minScore;
        this.fanoutScoreGap = fanoutScoreGap;
        this.maxAgents = maxAgents;
        this.defaultAgent = defaultAgent;
    }

    public int minScore() {
        return minScore;
    }

    public int fanoutScoreGap() {
        return fanoutScoreGap;
    }

    public int maxAgents() {
        return maxAgents;
    }

    public String defaultAgent() {
        return defaultAgent;
    }
}
