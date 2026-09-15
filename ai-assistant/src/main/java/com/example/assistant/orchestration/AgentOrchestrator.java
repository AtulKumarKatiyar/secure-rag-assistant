package com.example.assistant.orchestration;

import com.example.assistant.tools.RagSearchTool;
import com.example.assistant.tools.StockNewsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AgentOrchestrator {

    private final ChatClient agent;

    public AgentOrchestrator(ChatClient.Builder builder,
                             RagSearchTool ragSearchTool,
                             StockNewsTool stockNewsTool) {
        this.agent = builder
            .defaultSystem("""
                You are a stock-news assistant with tools.
                - searchStockNews(query): historical / context questions
                - getLiveStockNews(ticker): fresh news
                - getLiveStockPrice(ticker): current price
                Cite sources. Never invent numbers.
                """)
            .defaultTools(ragSearchTool, stockNewsTool)
            .build();
    }

    public String chat(String message) {
        return agent.prompt().user(message).call().content();
    }
}