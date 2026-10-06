package com.example.assistant.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOrchestratorTest {

    private final List<MarketAgent> agents = List.of(
            new StubAgent("equity-agent"),
            new StubAgent("commodity-agent"));

    /** Same-thread executor keeps the fan-out deterministic for the test. */
    private final Executor sameThread = Runnable::run;

    @Test
    void runsEverySelectedAgentAndComposesTheirAnswers() {
        var selector = mock(AgentSelector.class);
        when(selector.select(anyString(), any())).thenReturn(RoutingDecision.routed(List.of(
                new AgentSelection("equity-agent", 5.0, "test"),
                new AgentSelection("commodity-agent", 4.0, "test"))));

        var result = orchestrator(selector).chat("Compare AAPL with gold");

        assertThat(result.agentCount()).isEqualTo(2);
        assertThat(result.needsClarification()).isFalse();
        assertThat(result.agentAnswers()).extracting(AgentOrchestrator.AgentAnswer::agentName)
                .containsExactly("equity-agent", "commodity-agent");
        assertThat(result.answer())
                .contains("[equity-agent]")
                .contains("[commodity-agent]");
    }

    @Test
    void returnsASingleAgentAnswerWithoutMultiAgentWrapping() {
        var selector = mock(AgentSelector.class);
        when(selector.select(anyString(), any())).thenReturn(RoutingDecision.routed(List.of(
                new AgentSelection("equity-agent", 5.0, "test"))));

        var result = orchestrator(selector).chat("What is AAPL trading at?");

        assertThat(result.agentCount()).isEqualTo(1);
        assertThat(result.answer()).isEqualTo("answer from equity-agent");
    }

    /**
     * Regression test. The supervisor prompt may answer {@code needsClarification=true}; that
     * signal used to be discarded and the request silently answered by the default agent.
     */
    @Test
    void surfacesAClarificationRequestInsteadOfAnsweringWithTheWrongAgent() {
        var selector = mock(AgentSelector.class);
        when(selector.select(anyString(), any())).thenReturn(RoutingDecision.clarify());

        var result = orchestrator(selector).chat("what about that thing");

        assertThat(result.needsClarification()).isTrue();
        assertThat(result.agentCount()).isZero();
        assertThat(result.agentAnswers()).isEmpty();
        assertThat(result.agentSelections()).isEmpty();
        assertThat(result.answer()).isEqualTo(AgentOrchestrator.CLARIFICATION_ANSWER);
    }

    @Test
    void reportsAnAgentFailureWithoutFailingTheWholeRequest() {
        var selector = mock(AgentSelector.class);
        when(selector.select(anyString(), any())).thenReturn(RoutingDecision.routed(List.of(
                new AgentSelection("failing-agent", 5.0, "test"))));

        var orchestrator = new AgentOrchestrator(
                List.of(new FailingAgent("failing-agent")),
                selector,
                sameThread,
                new AgentExecutionProperties(5000),
                new ToolTraceRecorder());

        var result = orchestrator.chat("anything");

        assertThat(result.answer()).contains("Agent failed: boom");
    }

    @Test
    void ignoresSelectionsThatDoNotMatchAKnownAgent() {
        var selector = mock(AgentSelector.class);
        when(selector.select(anyString(), any())).thenReturn(RoutingDecision.routed(List.of(
                new AgentSelection("ghost-agent", 9.0, "test"))));

        var result = orchestrator(selector).chat("anything");

        assertThat(result.agentCount()).isZero();
        assertThat(result.agentSelections()).hasSize(1);
    }

    private AgentOrchestrator orchestrator(AgentSelector selector) {
        return new AgentOrchestrator(agents, selector, sameThread,
                new AgentExecutionProperties(5000), new ToolTraceRecorder());
    }

    private static final class StubAgent implements MarketAgent {
        private final String name;

        private StubAgent(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public AgentCapability capability() {
            return new AgentCapability(name, "stub", List.of());
        }

        @Override
        public String answer(String message) {
            return "answer from " + name;
        }
    }

    private static final class FailingAgent implements MarketAgent {
        private final String name;

        private FailingAgent(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public AgentCapability capability() {
            return new AgentCapability(name, "stub", List.of());
        }

        @Override
        public String answer(String message) {
            throw new IllegalStateException("boom");
        }
    }
}
