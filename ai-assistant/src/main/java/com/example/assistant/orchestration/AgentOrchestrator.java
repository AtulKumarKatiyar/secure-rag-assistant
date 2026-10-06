package com.example.assistant.orchestration;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class AgentOrchestrator {

    static final String CLARIFICATION_ANSWER =
            "I could not tell which market domain this question belongs to. "
                    + "Could you name the instrument or asset class, for example a ticker, an index "
                    + "such as NIFTY 50, or a commodity such as gold or Brent crude?";

    private final List<MarketAgent> agents;
    private final AgentSelector selector;
    private final Executor agentExecutor;
    private final AgentExecutionProperties executionProperties;
    private final ToolTraceRecorder trace;

    public AgentOrchestrator(List<MarketAgent> agents,
                             AgentSelector selector,
                             @Qualifier("agentExecutor") Executor agentExecutor,
                             AgentExecutionProperties executionProperties,
                             ToolTraceRecorder trace) {
        this.agents = agents;
        this.selector = selector;
        this.agentExecutor = agentExecutor;
        this.executionProperties = executionProperties;
        this.trace = trace;
    }

    public AgentResult chat(String message) {
        var decision = selector.select(message, agents);
        if (decision.needsClarification()) {
            return new AgentResult(CLARIFICATION_ANSWER, trace.trace(), 0, true, List.of(), List.of());
        }

        var selectedAgents = decision.selections().stream()
                .map(selection -> findAgent(selection.agentName()))
                .filter(Objects::nonNull)
                .toList();

        var answerFutures = selectedAgents.stream()
                .map(agent -> CompletableFuture
                        .supplyAsync(() -> agent.answer(message), agentExecutor)
                        .orTimeout(executionProperties.timeoutMs(), TimeUnit.MILLISECONDS)
                        .handle((answer, failure) -> toAgentAnswer(agent.name(), answer, failure)))
                .toList();
        CompletableFuture.allOf(answerFutures.toArray(CompletableFuture[]::new)).join();

        var agentAnswers = answerFutures.stream()
                .map(CompletableFuture::join)
                .toList();

        return new AgentResult(compose(agentAnswers), trace.trace(), selectedAgents.size(), false,
                decision.selections(), agentAnswers);
    }

    private static AgentAnswer toAgentAnswer(String agentName, String answer, Throwable failure) {
        if (failure == null) {
            return new AgentAnswer(agentName, answer);
        }
        return new AgentAnswer(agentName, "Agent failed: " + rootCause(failure).getMessage());
    }

    private static Throwable rootCause(Throwable failure) {
        var current = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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

    /**
     * @param agentCount fan-out width, i.e. how many specialist agents answered. This was
     *                   previously exposed as {@code iterations}, which it never was.
     */
    public record AgentResult(String answer,
                              List<Map<String, Object>> toolTrace,
                              int agentCount,
                              boolean needsClarification,
                              List<AgentSelection> agentSelections,
                              List<AgentAnswer> agentAnswers) {
    }

    public record AgentAnswer(String agentName, String answer) {
    }
}
