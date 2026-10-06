package com.example.assistant.orchestration;

import com.example.assistant.tools.IndexManagementTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IndexResearchAgent implements MarketAgent {
    private static final AgentCapability CAPABILITY = new AgentCapability(
            "index-agent",
            "Answers index, benchmark, constituent, sector-weight and index-performance questions.",
            List.of("index", "benchmark", "constituent", "weight"),
            List.of(
                    "index", "indices", "benchmark", "nifty", "nifty50", "nifty 50", "sensex",
                    "nasdaq", "s&p", "s&p 500", "sp500", "dow", "dow jones", "ftse",
                    "constituent", "constituents", "sector weight", "sector weights",
                    "index level", "index performance", "rebalance", "rebalancing"
            ),
            List.of(
                    "What are the top constituents of NIFTY50?",
                    "Compare S&P 500 sector weights with Nasdaq",
                    "What changed in the latest index rebalance?"
            )
    );
    private final ChatClient agent;

    public IndexResearchAgent(ChatClient.Builder builder, IndexManagementTool indexManagementTool) {
        this.agent = builder
                .defaultSystem("""
                You are the index research agent.
                Use the index management tool for index composition, levels, top constituents,
                sector weights and index performance. Keep answers grounded in tool output.
                """)
                .defaultTools(indexManagementTool)
                .build();
    }

    @Override
    public String name() {
        return "index-agent";
    }

    @Override
    public AgentCapability capability() {
        return CAPABILITY;
    }

    @Override
    public String answer(String message) {
        return agent.prompt().user(message).call().content();
    }
}
