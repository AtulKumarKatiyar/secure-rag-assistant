package com.example.assistant.orchestration;

import com.example.assistant.tools.RagSearchTool;
import com.example.assistant.tools.StockNewsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AgentOrchestrator {

    private final ChatClient agent;
    private final ToolTraceRecorder trace;

    public AgentOrchestrator(ChatClient.Builder builder,
                             RagSearchTool ragSearchTool,
                             StockNewsTool stockNewsTool,
                             ToolTraceRecorder trace) {
        this.trace = trace;
        this.agent = builder
                .defaultSystem("""
                You are a stock-news assistant.

                Tools available:
                - searchStockNews(ticker, query): historical / context / 'recent' questions.
                  Automatically falls back to the live API if the RAG index has no fresh data.
                - getLiveStockNews(ticker): breaking / 'right now' news.
                - getLiveStockPrice(ticker): current price.

                Rules:
                - Prefer searchStockNews for anything that isn't explicitly asking for
                  a live value. It handles freshness on its own.
                - Use getLiveStockPrice / getLiveStockNews for 'current', 'now', 'latest'.
                - If a question needs both context and a live value, call both tools.
                - Always extract the ticker symbol (e.g. AAPL). If none is present,
                  ask the user which ticker they mean.
                - Cite sources (RAG chunks carry source metadata).
                - NEVER invent prices or headlines.
                - If a tool returns an error, tell the user what failed — do not guess.
                """)
                .defaultTools(ragSearchTool, stockNewsTool)
                .build();
    }

    public AgentResult chat(String message) {
        ChatResponse response = agent.prompt()
                .user(message)
                .call()
                .chatClientResponse()
                .chatResponse();

        String answer = response.getResult().getOutput().getText();
        int iterations = response.getMetadata() != null
                ? 1 + response.getMetadata().keySet().size()  // heuristic; refine if you log the loop
                : 1;

        return new AgentResult(answer, trace.trace(), iterations);
    }

    public record AgentResult(String answer,
                              List<Map<String, Object>> toolTrace,
                              int iterations) {}
}