package com.example.assistant.tools;

import com.example.assistant.cache.ProductionCache;
import com.example.assistant.orchestration.ToolTraceRecorder;
import com.example.assistant.rag.RagProperties;
import com.example.assistant.rag.access.RagAccessFilter;
import com.example.assistant.rag.ingestion.AccessMetadataKeys;
import com.example.assistant.rag.ingestion.AccessVisibility;
import com.example.assistant.rag.ingestion.ChunkDocumentMapper;
import com.example.assistant.rag.ingestion.DocumentType;
import com.example.assistant.rag.ingestion.RagChunk;
import com.example.assistant.web.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval tool over the ingested market corpus.
 *
 * <p>Every query is constrained by two things that must never be dropped:
 * <ul>
 *   <li>the caller's access predicate ({@link RagAccessFilter}), so tenant-private and
 *       entitlement-restricted chunks are unreachable without the matching claim; and</li>
 *   <li>the ticker and freshness window, so an answer is grounded in the requested instrument.</li>
 * </ul>
 *
 * <p>When retrieval yields nothing the tool falls back to the live secured API and writes what it
 * fetched back into the vector store. The fallback also degrades gracefully if the embedding model
 * is unavailable: an embedding outage must not turn into a tool error when the live API works.
 *
 * <p>Returns JSON text rather than a domain object; see {@link StockNewsTool} for why that matters
 * in Spring AI 1.0.0.
 */
@Component
public class RagSearchTool {

    private static final Logger log = LoggerFactory.getLogger(RagSearchTool.class);

    private final VectorStore vectorStore;
    private final StockNewsTool stockNewsTool;
    private final ToolTraceRecorder trace;
    private final ProductionCache cache;
    private final TenantContext tenantContext;
    private final RagProperties properties;
    private final ObjectMapper mapper;
    private final ChunkDocumentMapper documentMapper;

    public RagSearchTool(VectorStore vectorStore,
                         StockNewsTool stockNewsTool,
                         ToolTraceRecorder trace,
                         ProductionCache cache,
                         TenantContext tenantContext,
                         RagProperties properties,
                         ObjectMapper mapper,
                         ChunkDocumentMapper documentMapper) {
        this.vectorStore = vectorStore;
        this.stockNewsTool = stockNewsTool;
        this.trace = trace;
        this.cache = cache;
        this.tenantContext = tenantContext;
        this.properties = properties;
        this.mapper = mapper;
        this.documentMapper = documentMapper;
    }

    @Tool(description = """
        Search already-ingested stock news, filings, transcripts and summaries.
        Use for historical / 'what happened' / 'summarize recent' questions.
        Automatically falls back to the live secured API if no relevant ingested match is found.
        Returns a JSON string with: source, ticker, chunks[].
        """)
    public String searchStockNews(String ticker, String query) {
        trace.start("searchStockNews");
        try {
            var upper = ticker.toUpperCase();
            var key = cacheKey("rag", upper, query);
            var cached = cache.get(key);
            if (cached.isPresent()) {
                trace.record("searchStockNews", Map.of("ticker", upper, "query", query, "cache", "hit"),
                        cached.get(), true);
                return String.valueOf(cached.get());
            }

            var hits = searchIngested(upper, query);
            if (!hits.isEmpty() && score(hits.get(0)) >= properties.minScore()) {
                var response = serialize("RAG", upper, hits.stream().map(Document::getText).toList());
                cache.put(key, response, Duration.ofMinutes(5));
                trace.record("searchStockNews", Map.of("ticker", upper, "query", query),
                        "RAG hit: " + hits.size() + " chunks", true);
                return response;
            }

            return liveFallback(upper, query, key);
        } catch (Exception e) {
            trace.record("searchStockNews", Map.of("ticker", ticker), e.getMessage(), false);
            return ToolJson.error(mapper, "searchStockNews", Map.of("ticker", ticker), e);
        }
    }

    /**
     * Embedding and vector-store failures degrade to the live API instead of failing the tool.
     */
    private List<Document> searchIngested(String ticker, String query) {
        try {
            return vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(properties.topK())
                    .filterExpression(marketFilter(ticker))
                    .build());
        } catch (Exception e) {
            log.warn("Vector retrieval failed for ticker {}; falling back to the live API: {}",
                    ticker, e.getMessage());
            return List.of();
        }
    }

    private Filter.Expression marketFilter(String ticker) {
        var access = RagAccessFilter.groupedForCaller(
                tenantContext.tenantId(), tenantContext.entitlementGroups());
        var tickerMatch = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(ChunkDocumentMapper.TICKER),
                new Filter.Value(ticker));
        var freshEnough = new Filter.Expression(
                Filter.ExpressionType.GTE,
                new Filter.Key(ChunkDocumentMapper.PUBLISHED_AT_EPOCH_MS),
                new Filter.Value(Instant.now().minus(Duration.ofHours(properties.maxAgeHours())).toEpochMilli()));

        return new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(Filter.ExpressionType.AND, access, tickerMatch),
                freshEnough);
    }

    private String liveFallback(String ticker, String query, String cacheKey) {
        var liveJson = stockNewsTool.getLiveStockNews(ticker);
        if (isError(liveJson)) {
            trace.record("searchStockNews", Map.of("ticker", ticker, "query", query, "fallback", true),
                    "Live API returned an error", false);
            return serialize("ERROR", ticker, List.of());
        }

        persistLiveNews(ticker, liveJson);

        // The source label describes what actually happened. Claiming "cached" while returning an
        // empty chunk list invites the model to invent the missing content.
        var response = serialize("API", ticker, extractLiveArticleTexts(liveJson));

        trace.record("searchStockNews", Map.of("ticker", ticker, "query", query, "fallback", true),
                "API fallback", true);
        cache.put(cacheKey, response, Duration.ofMinutes(1));
        return response;
    }

    /**
     * Best-effort write-through of freshly fetched news so the next query is served from RAG.
     * Failure here must never break the read path.
     */
    private void persistLiveNews(String ticker, String liveJson) {
        try {
            var articles = articles(liveJson);
            if (articles.isEmpty()) {
                return;
            }
            var now = Instant.now();
            var documents = new ArrayList<Document>();
            for (int index = 0; index < articles.size(); index++) {
                var article = articles.get(index);
                var headline = text(article.get("headline"));
                var summary = text(article.get("summary"));
                var body = (headline + "\n\n" + summary).trim();
                if (body.isBlank()) {
                    continue;
                }

                var metadata = new HashMap<String, Object>();
                metadata.put(AccessMetadataKeys.VISIBILITY, AccessVisibility.PUBLIC.name());
                metadata.put(AccessMetadataKeys.TENANT_ID, "");

                var chunk = new RagChunk(
                        liveChunkId(ticker, index),
                        "live-" + ticker,
                        ticker,
                        ticker,
                        DocumentType.NEWS,
                        headline,
                        "Secured Market API",
                        "",
                        publishedAt(article, now),
                        body,
                        metadata);
                documents.add(documentMapper.toDocument(chunk));
            }
            if (!documents.isEmpty()) {
                vectorStore.add(documents);
            }
        } catch (Exception e) {
            log.warn("Could not persist live news for ticker {} into the vector store: {}",
                    ticker, e.getMessage());
        }
    }

    /**
     * Stable per-ticker, per-position id so a repeated fetch overwrites rather than duplicating.
     */
    private static String liveChunkId(String ticker, int index) {
        return "live-" + ticker + "-" + index;
    }

    private List<Map<String, Object>> articles(String liveJson) {
        if (liveJson == null || liveJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode articlesNode = mapper.readTree(liveJson).path("articles");
            if (!articlesNode.isArray()) {
                return List.of();
            }
            var out = new ArrayList<Map<String, Object>>();
            for (JsonNode article : articlesNode) {
                out.add(mapper.convertValue(article, new TypeReference<Map<String, Object>>() {
                }));
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not parse the live news payload: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> extractLiveArticleTexts(String liveJson) {
        var texts = new ArrayList<String>();
        for (Map<String, Object> article : articles(liveJson)) {
            var body = (text(article.get("headline")) + "\n\n" + text(article.get("summary"))).trim();
            if (!body.isBlank()) {
                texts.add(body);
            }
        }
        return texts;
    }

    private boolean isError(String liveJson) {
        if (liveJson == null || liveJson.isBlank()) {
            return true;
        }
        try {
            return "ERROR".equals(mapper.readTree(liveJson).path("source").asText(""));
        } catch (Exception e) {
            return true;
        }
    }

    private static Instant publishedAt(Map<String, Object> article, Instant fallback) {
        try {
            var raw = article.get("publishedAt");
            return raw == null ? fallback : Instant.parse(String.valueOf(raw));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double score(Document document) {
        return document.getScore() == null ? 0.0 : document.getScore();
    }

    private String cacheKey(String type, String ticker, String query) {
        return tenantContext.tenantId() + ":" + String.join(",", tenantContext.entitlementGroups())
                + ":" + type + ":" + ticker + ":" + query.toLowerCase().trim();
    }

    private String serialize(String source, String ticker, List<String> chunks) {
        try {
            return mapper.writeValueAsString(new RagResponse(source, ticker, chunks));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize RAG response", e);
        }
    }

    record RagResponse(String source, String ticker, List<String> chunks) {
    }
}
