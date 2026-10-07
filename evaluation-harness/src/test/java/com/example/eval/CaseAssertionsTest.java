package com.example.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness previously ignored {@code expectTool}, {@code expectAnyTool} and {@code forbidTool},
 * so its security case passed without asserting anything. These tests pin each expectation.
 */
class CaseAssertionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void enforcesExpectTool() {
        var expectation = json("{\"expectTool\":\"searchStockNews\"}");

        assertThat(CaseAssertions.evaluate(expectation, traceOf("searchStockNews")).passed()).isTrue();
        assertThat(CaseAssertions.evaluate(expectation, traceOf("getLiveStockPrice")).passed()).isFalse();
    }

    @Test
    void enforcesExpectAnyTool() {
        var expectation = json("{\"expectAnyTool\":[\"getLiveStockPrice\",\"searchStockNews\"]}");

        assertThat(CaseAssertions.evaluate(expectation, traceOf("searchStockNews")).passed()).isTrue();
        assertThat(CaseAssertions.evaluate(expectation, traceOf("getIndexData")).passed()).isFalse();
    }

    @Test
    void enforcesForbidTool() {
        var expectation = json("{\"forbidTool\":[\"getLiveStockPrice\",\"getLiveStockNews\"]}");

        assertThat(CaseAssertions.evaluate(expectation, traceOf("searchStockNews")).passed()).isTrue();
        assertThat(CaseAssertions.evaluate(expectation, traceOf("getLiveStockPrice")).passed()).isFalse();
    }

    @Test
    void reportsWhichExpectationFailed() {
        var expectation = json("{\"expectTool\":\"searchStockNews\"}");

        var verdict = CaseAssertions.evaluate(expectation, traceOf("getLiveStockPrice"));

        assertThat(verdict.reason()).contains("searchStockNews");
    }

    @Test
    void enforcesExpectedAgents() {
        var expectation = json("{\"expectAgents\":[\"equity-agent\",\"commodity-agent\"]}");

        assertThat(CaseAssertions.evaluate(expectation, agentsOf("equity-agent", "commodity-agent")).passed()).isTrue();
        assertThat(CaseAssertions.evaluate(expectation, agentsOf("equity-agent")).passed()).isFalse();
    }

    @Test
    void enforcesExpectedTickerAgainstToolArguments() {
        var expectation = json("{\"expectTicker\":\"AAPL\"}");

        assertThat(CaseAssertions.evaluate(expectation, traceWithArgs("getLiveStockPrice", "{ticker=AAPL}")).passed()).isTrue();
        assertThat(CaseAssertions.evaluate(expectation, traceWithArgs("getLiveStockPrice", "{ticker=MSFT}")).passed()).isFalse();
    }

    @Test
    void enforcesForbiddenContentForTenantIsolation() {
        var expectation = json("{\"forbidText\":[\"clientB-tech\"]}");

        assertThat(CaseAssertions.evaluate(expectation, json("{\"answer\":\"public data only\"}")).passed()).isTrue();
        assertThat(CaseAssertions.evaluate(expectation, json("{\"answer\":\"portfolio clientB-tech\"}")).passed()).isFalse();
    }

    @Test
    void passesWhenNoExpectationsAreDeclared() {
        assertThat(CaseAssertions.evaluate(json("{\"q\":\"anything\"}"), json("{}")).passed()).isTrue();
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode traceOf(String... tools) {
        var entries = new StringBuilder();
        for (String tool : tools) {
            if (entries.length() > 0) {
                entries.append(",");
            }
            entries.append("{\"tool\":\"").append(tool).append("\",\"args\":\"{}\"}");
        }
        return json("{\"toolTrace\":[" + entries + "]}");
    }

    private static com.fasterxml.jackson.databind.JsonNode traceWithArgs(String tool, String args) {
        return json("{\"toolTrace\":[{\"tool\":\"" + tool + "\",\"args\":\"" + args + "\"}]}");
    }

    private static com.fasterxml.jackson.databind.JsonNode agentsOf(String... agents) {
        var entries = new StringBuilder();
        for (String agent : agents) {
            if (entries.length() > 0) {
                entries.append(",");
            }
            entries.append("{\"agentName\":\"").append(agent).append("\"}");
        }
        return json("{\"agentSelections\":[" + entries + "]}");
    }
}
