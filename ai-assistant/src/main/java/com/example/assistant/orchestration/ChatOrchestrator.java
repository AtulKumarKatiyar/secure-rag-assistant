package com.example.assistant.orchestration;

import com.example.assistant.routing.QueryRouter;
import com.example.assistant.routing.RouteDecision;
import com.example.assistant.tools.StockNewsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatOrchestrator {

    private final QueryRouter router;
    private final VectorStore vectorStore;
    private final StockNewsTool stockNewsTool;
    private final ChatClient composer;

    public ChatOrchestrator(QueryRouter router,
                            VectorStore vectorStore,
                            StockNewsTool stockNewsTool,
                            ChatClient.Builder builder) {
        this.router = router;
        this.vectorStore = vectorStore;
        this.stockNewsTool = stockNewsTool;
        this.composer = builder.defaultSystem("""
            You are a stock-news assistant.
            Answer using ONLY the provided context and tool results.
            Always cite source headlines/tickers when you use RAG context.
            Never invent prices or headlines.
            """).build();
    }

    public OrchestrationResult chat(String message) {
        RouteDecision decision = router.decide(message);

        List<Document> ragChunks = List.of();
        Object liveData = null;

        if (decision.route() == RouteDecision.Route.RAG
                || decision.route() == RouteDecision.Route.BOTH) {
            ragChunks = vectorStore.similaritySearch(
                SearchRequest.builder().query(message).topK(5).build());
        }
        if ((decision.route() == RouteDecision.Route.API
                || decision.route() == RouteDecision.Route.BOTH)
                && decision.ticker() != null) {
            liveData = stockNewsTool.getLiveStockNews(decision.ticker());
        }

        String context = ragChunks.stream()
            .map(Document::getText)
            .reduce("", (a, b) -> a + "\n---\n" + b);

        String answer = composer.prompt()
            .user(u -> u.text("""
                Question: {q}
                RAG context: {ctx}
                Live API result: {live}
                """)
                .param("q", message)
                .param("ctx", context.isBlank() ? "(none)" : context)
                .param("live", liveData == null ? "(none)" : liveData.toString()))
            .call()
            .content();

        return new OrchestrationResult(answer, decision, ragChunks, liveData);
    }

    public record OrchestrationResult(String answer,
                                      RouteDecision decision,
                                      List<Document> ragChunks,
                                      Object liveData) {}
}