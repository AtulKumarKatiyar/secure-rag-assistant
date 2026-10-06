package com.example.assistant.orchestration;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AgentOrchestrator {

    private final List<MarketAgent> agents;
    private final AgentSelector selector;
    private final ToolTraceRecorder trace;

    public AgentOrchestrator(List<MarketAgent> agents, AgentSelector selector, ToolTraceRecorder trace) {
        this.agents = agents;
        this.selector = selector;
        this.trace = trace;
    }

    public AgentResult chat(String message) {
        var selections = selector.select(message, agents);
        var selectedAgents = selections.stream()
                .map(selection -> findAgent(selection.agentName()))
                .filter(Objects::nonNull)
                .toList();

        var agentAnswers = selectedAgents.stream()
                .map(agent -> new AgentAnswer(agent.name(), agent.answer(message)))
                .toList();

        return new AgentResult(compose(agentAnswers), trace.trace(), selectedAgents.size(), selections, agentAnswers);
    }

    private MarketAgent findAgent(String agentName) {
        return agents.stream()
                .filter(agent -> Objects.equals(agent.name(), agentName))
                .findFirst()
                .orElse(null);
    }

    private static String compose(List<AgentAnswer> answers) {
        if (answers.size() == 1) {
            return answers.get(0).answer();
        }
        var out = new StringBuilder("Multi-agent answer:\n");
        answers.forEach(answer -> out
                .append("\n[")
                .append(answer.agentName())
                .append("]\n")
                .append(answer.answer())
                .append("\n"));
        return out.toString().trim();
    }

    public record AgentResult(String answer,
                              List<Map<String, Object>> toolTrace,
                              int iterations,
                              List<AgentSelection> agentSelections,
                              List<AgentAnswer> agentAnswers) {}

    public record AgentAnswer(String agentName, String answer) {}
}
