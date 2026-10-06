package com.example.assistant.tools;

import com.example.assistant.cache.ProductionCache;
import com.example.assistant.orchestration.ToolTraceRecorder;
import com.example.assistant.rag.RagProperties;
import com.example.assistant.rag.ingestion.ChunkDocumentMapper;
import com.example.assistant.web.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagSearchToolTest {

    private static final String TICKER = "AAPL";

    private final ObjectMapper mapper = new ObjectMapper();
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final StockNewsTool stockNewsTool = mock(StockNewsTool.class);
    private final TenantContext tenantContext = new TenantContext();

    private RagSearchTool tool;

    @BeforeEach
    void setUp() {
        tenantContext.setTenantId("clientA");
        tenantContext.setEntitlementGroups(List.of("premium-research"));
        tool = new RagSearchTool(
                vectorStore,
                stockNewsTool,
                new ToolTraceRecorder(),
                new FakeCache(),
                tenantContext,
                new RagProperties(5, 0.75, 720),
                mapper,
                new ChunkDocumentMapper());
    }

    @Test
    void servesAHighScoringIngestedMatch() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(hit("Apple beat revenue expectations.", 0.91)));

        var response = read(tool.searchStockNews(TICKER, "recent apple news"));

        assertThat(response.get("source").asText()).isEqualTo("RAG");
        assertThat(response.get("ticker").asText()).isEqualTo(TICKER);
        assertThat(response.get("chunks")).hasSize(1);
    }

    @Test
    void ignoresMatchesBelowTheMinimumScore() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(hit("Barely related.", 0.10)));
        when(stockNewsTool.getLiveStockNews(TICKER)).thenReturn(liveNews());

        var response = read(tool.searchStockNews(TICKER, "recent apple news"));

        assertThat(response.get("source").asText()).isEqualTo("API");
    }

    /**
     * Regression test for the hand-rolled JSON builder, which escaped quotes but not backslashes
     * or newlines and produced invalid JSON.
     */
    @Test
    void serializesChunkTextThatWouldHaveBrokenTheOldJsonBuilder() throws Exception {
        var hostile = "He said \"revenue\" rose C:\\path\\to\\file\nnewline\ttab";
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(hit(hostile, 0.99)));

        var response = read(tool.searchStockNews(TICKER, "recent apple news"));

        assertThat(response.get("chunks").get(0).asText()).isEqualTo(hostile);
    }

    /**
     * The fallback used to return {@code source="API cached"} with an empty chunk list, telling the
     * model it had data when it had none. It must now return the fetched articles, or nothing.
     */
    @Test
    void returnsTheLiveArticlesInsteadOfAnEmptyCachedClaim() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(stockNewsTool.getLiveStockNews(TICKER)).thenReturn(liveNews());

        var response = read(tool.searchStockNews(TICKER, "recent apple news"));

        assertThat(response.get("source").asText()).isEqualTo("API");
        assertThat(response.get("chunks")).hasSize(1);
        assertThat(response.get("chunks").get(0).asText()).contains("Apple ships new AI features");
    }

    /** Regression test for the empty {@code persist()} stub: the fallback never wrote anything back. */
    @Test
    void writesFetchedArticlesBackIntoTheVectorStore() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(stockNewsTool.getLiveStockNews(TICKER)).thenReturn(liveNews());

        tool.searchStockNews(TICKER, "recent apple news");

        verify(vectorStore).add(anyList());
    }

    /**
     * Regression test for the robustness gap where an embedding outage aborted the tool instead of
     * degrading to the live API.
     */
    @Test
    void degradesToTheLiveApiWhenVectorRetrievalThrows() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("embedding model unreachable"));
        when(stockNewsTool.getLiveStockNews(TICKER)).thenReturn(liveNews());

        var response = read(tool.searchStockNews(TICKER, "recent apple news"));

        assertThat(response.get("source").asText()).isEqualTo("API");
        assertThat(response.get("chunks")).hasSize(1);
    }

    @Test
    void stillReportsAnErrorWhenTheLiveApiAlsoFails() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(stockNewsTool.getLiveStockNews(TICKER)).thenThrow(new IllegalStateException("api down"));

        var response = read(tool.searchStockNews(TICKER, "recent apple news"));

        assertThat(response.get("source").asText()).isEqualTo("ERROR");
    }

    /** A tool that reports an error as JSON must not be presented to the model as fetched data. */
    @Test
    void reportsAnErrorWhenTheLiveToolReturnsAnErrorPayload() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(stockNewsTool.getLiveStockNews(TICKER))
                .thenReturn("{\"source\":\"ERROR\",\"tool\":\"getLiveStockNews\",\"message\":\"403\"}");

        var response = read(tool.searchStockNews(TICKER, "recent apple news"));

        assertThat(response.get("source").asText()).isEqualTo("ERROR");
        assertThat(response.get("chunks")).isEmpty();
    }

    private JsonNode read(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static Document hit(String text, double score) {
        return Document.builder()
                .id("chunk-1")
                .text(text)
                .metadata(Map.of("ticker", TICKER))
                .score(score)
                .build();
    }

    private static String liveNews() {
        return """
                {"ticker":"AAPL","articles":[
                  {"headline":"Apple ships new AI features",
                   "summary":"The services segment grew double-digit.",
                   "publishedAt":"2026-09-15T09:00:00Z"}
                ]}
                """;
    }

    /** Minimal TTL cache so the tool can be exercised without the Spring container. */
    private static final class FakeCache implements ProductionCache {
        private final Map<String, Object> entries = new ConcurrentHashMap<>();

        @Override
        public Optional<Object> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public void put(String key, Object value, Duration ttl) {
            entries.put(key, value);
        }
    }
}
