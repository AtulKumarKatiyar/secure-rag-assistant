package com.example.assistant.tools;

import com.example.assistant.orchestration.ToolTraceRecorder;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class RagSearchTool {

    private static final double MIN_SCORE = 0.75;

    private final VectorStore vectorStore;
    private final StockNewsTool stockNewsTool;
    private final ToolTraceRecorder trace;

    public RagSearchTool(VectorStore vectorStore,
                         StockNewsTool stockNewsTool,
                         ToolTraceRecorder trace) {
        this.vectorStore = vectorStore;
        this.stockNewsTool = stockNewsTool;
        this.trace = trace;
    }

    @Tool(description = """
        Search already-ingested stock news, filings and summaries.
        Use for historical / 'what happened' / 'summarize recent' questions.
        Automatically falls back to the live secured API if no fresh match is found.
        Returns a JSON string with: source, ticker, chunks[].
        """)
    public String searchStockNews(String ticker, String query) {
        trace.start("searchStockNews");
        try {
            var upper = ticker.toUpperCase();
            var hits = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(5)
                            .filterExpression("ticker == '" + upper + "' && "
                                    + "publishedAt >= '" + Instant.now().minusSeconds(6 * 3600) + "'")
                            .build());

            if (!hits.isEmpty() && score(hits.get(0)) >= MIN_SCORE) {
                trace.record("searchStockNews",
                        Map.of("ticker", upper, "query", query),
                        "RAG hit: " + hits.size() + " chunks",
                        true);
                return serialize("RAG", upper, hits);
            }

            // ---- fallback: live API + write-through cache ----
            var live = stockNewsTool.getLiveStockNews(upper);
            persist(upper, live);   // best-effort; don't fail the call if caching fails

            trace.record("searchStockNews",
                    Map.of("ticker", upper, "query", query, "fallback", true),
                    "API fallback",
                    true);
            return serialize("API→cached", upper, List.of());

        } catch (Exception e) {
            trace.record("searchStockNews", Map.of("ticker", ticker), e.getMessage(), false);
            return "{\"source\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    private static double score(Document d) {
        Object s = d.getMetadata().get("score");
        return s instanceof Number n ? n.doubleValue() : 0.0;
    }

    private void persist(String ticker, Object live) {
        try {
            // Pseudo-code: convert live response → Documents and upsert.
            // Your existing IngestionService/DocumentChunker should be wired here.
            // Deduplicate by content hash before add().
        } catch (Exception e) {
            // Swallow — caching must never break the read path
        }
    }

    private static String serialize(String source, String ticker, List<Document> chunks) {
        var sb = new StringBuilder("{\"source\":\"").append(source)
                .append("\",\"ticker\":\"").append(ticker).append("\",\"chunks\":[");
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(chunks.get(i).getText().replace("\"", "\\\"")).append("\"");
        }
        return sb.append("]}").toString();
    }
}