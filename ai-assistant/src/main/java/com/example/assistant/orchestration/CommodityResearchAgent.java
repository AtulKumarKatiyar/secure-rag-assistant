package com.example.assistant.orchestration;

import com.example.assistant.tools.CommodityDataTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CommodityResearchAgent implements MarketAgent {
    private static final AgentCapability CAPABILITY = new AgentCapability(
            "commodity-agent",
            "Answers commodity, energy, metals and macro-commodity market questions.",
            List.of("commodity", "energy", "metal", "oil", "gold"),
            List.of(
                    "commodity", "commodities", "oil", "crude", "crude oil", "brent", "wti",
                    "gold", "xau", "silver", "xag", "natural gas", "lng", "copper",
                    "energy", "metals", "barrel", "inventory", "opec", "geopolitical risk"
            ),
            List.of(
                    "What is the latest Brent crude view?",
                    "How is gold reacting to rate expectations?",
                    "Compare oil with energy stocks"
            )
    );
    private final ChatClient agent;

    public CommodityResearchAgent(ChatClient.Builder builder, CommodityDataTool commodityDataTool) {
        this.agent = builder
                .defaultSystem("""
                You are the commodity research agent.
                Use the commodity data tool for oil, gold, silver, natural gas and related
                macro/commodity questions. Keep answers grounded in tool output.
                """)
                .defaultTools(commodityDataTool)
                .build();
    }

    @Override
    public String name() {
        return "commodity-agent";
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
