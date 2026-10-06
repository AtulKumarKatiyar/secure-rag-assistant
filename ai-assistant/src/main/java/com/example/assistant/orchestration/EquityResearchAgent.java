package com.example.assistant.orchestration;

import com.example.assistant.tools.RagSearchTool;
import com.example.assistant.tools.StockNewsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EquityResearchAgent implements MarketAgent {
    private static final AgentCapability CAPABILITY = new AgentCapability(
            "equity-agent",
            "Answers listed-company, stock, filing, earnings and equity-news questions.",
            List.of("equity", "stock", "share", "company", "ticker"),
            List.of(
                    "stock", "stocks", "share", "shares", "equity", "ticker", "company", "listed company",
                    "price", "current price", "quote", "market price", "earnings", "filing", "filings",
                    "annual report", "quarterly result", "transcript",
                    "promoter", "promoter activity", "promoter commentary", "analyst", "analyst commentary",
                    "buyback", "dividend", "market cap", "revenue", "margin"
            ),
            List.of(
                    "Summarize recent news for a listed company ticker",
                    "What is HSBC current price?",
                    "What did the latest earnings transcript say about margins?",
                    "Show promoter activity for a company"
            )
    );
    private final ChatClient agent;

    public EquityResearchAgent(ChatClient.Builder builder,
                               RagSearchTool ragSearchTool,
                               StockNewsTool stockNewsTool) {
        this.agent = builder
                .defaultSystem("""
                You are the equity research agent.
                Use stock-news tools for equity tickers, company news, equity prices,
                filings, earnings transcripts, promoter activity and equity commentary.
                Prefer RAG for context and the live secured API for current/latest values.
                Never invent prices, headlines or sources.
                """)
                .defaultTools(ragSearchTool, stockNewsTool)
                .build();
    }

    @Override
    public String name() {
        return "equity-agent";
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
