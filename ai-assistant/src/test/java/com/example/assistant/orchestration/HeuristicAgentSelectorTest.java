package com.example.assistant.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicAgentSelectorTest {

    private final List<MarketAgent> agents = List.of(
            new StubAgent("equity-agent"),
            new StubAgent("index-agent"),
            new StubAgent("commodity-agent"));

    @Test
    void fastPathsAnUnambiguousEquityQuery() {
        var selector = selector(3, 3);

        var selected = selector.fastPath("What is the current price of AAPL?", agents);

        assertThat(selected).extracting(AgentSelection::agentName).containsExactly("equity-agent");
    }

    @Test
    void fastPathsAnUnambiguousCommodityQuery() {
        var selector = selector(3, 3);

        var selected = selector.fastPath("Give me the latest gold and crude oil data", agents);

        assertThat(selected).extracting(AgentSelection::agentName).containsExactly("commodity-agent");
    }

    @Test
    void fastPathsAnExplicitComparisonAcrossTwoDomains() {
        var selector = selector(3, 3);

        var selected = selector.fastPath("Compare NIFTY 50 with gold today", agents);

        assertThat(selected).extracting(AgentSelection::agentName)
                .containsExactlyInAnyOrder("index-agent", "commodity-agent");
    }

    /**
     * "Apple vs gold" names a company rather than a ticker, so only the commodity domain scores.
     * A comparison term with a single matched domain is ambiguous and must reach the LLM router
     * instead of being silently narrowed to commodities.
     */
    @Test
    void leavesAComparisonWithOneMatchedDomainToTheLlmRouter() {
        var selector = selector(3, 3);

        assertThat(selector.fastPath("Apple vs gold", agents)).isEmpty();
    }

    @Test
    void leavesUnrecognisedQueriesToTheLlmRouter() {
        var selector = selector(3, 3);

        assertThat(selector.fastPath("How is HSBC doing?", agents)).isEmpty();
    }

    /**
     * The configured threshold must actually be read. This is the regression test for
     * {@code assistant.agent-routing.min-score} being ignored in favour of a hardcoded value.
     */
    @Test
    void honoursTheConfiguredMinimumScore() {
        var permissive = selector(3, 3);
        var strict = selector(4, 3);

        // An equity term alone scores 3.0, below the strict threshold.
        assertThat(permissive.fastPath("What is the earnings outlook?", agents)).isNotEmpty();
        assertThat(strict.fastPath("What is the earnings outlook?", agents)).isEmpty();
    }

    /**
     * Regression test for {@code fanout-score-gap}: with two domains matched on equal scores and
     * no comparison term, the query is ambiguous and must reach the LLM router. Only when the
     * configured gap is small enough may the fast path pick the leading domain alone.
     */
    @Test
    void requiresAScoreGapBeforePickingOneOfSeveralMatchedDomains() {
        // "NIFTY and gold" scores index and commodity equally; "and" is a stop word, not a fanout term.
        assertThat(selector(3, 3).fastPath("NIFTY and gold", agents)).isEmpty();

        assertThat(selector(3, 0).fastPath("NIFTY and gold", agents))
                .extracting(AgentSelection::agentName)
                .containsExactly("index-agent");
    }

    @Test
    void fallsBackToTheDefaultAgentWhenNothingMatches() {
        var selector = selector(3, 3);

        var selected = selector.fallback("hello there", agents);

        assertThat(selected).extracting(AgentSelection::agentName).containsExactly("equity-agent");
        assertThat(selected.get(0).reason()).isEqualTo("heuristic-fallback-default");
    }

    private static HeuristicAgentSelector selector(double minScore, double fanoutScoreGap) {
        return new HeuristicAgentSelector(
                new AgentRoutingProperties(minScore, fanoutScoreGap, 3, "equity-agent"));
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
}
