package com.example.assistant.tools;

import com.example.assistant.orchestration.ToolTraceRecorder;
import com.example.assistant.rag.RagProperties;
import com.example.assistant.rag.access.RagAccessFilter;
import com.example.assistant.rag.ingestion.ChunkDocumentMapper;
import com.example.assistant.rag.ingestion.DocumentType;
import com.example.assistant.web.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Retrieval over curated enterprise policy documents.
 *
 * <p>Replaces the previous standalone {@code PolicyRetriever}, which ranked documents with its own
 * private TF-IDF implementation and was never wired into any agent, so its content was unreachable.
 * Policies are now ingested into the shared vector store and retrieved through the same
 * access predicate as market documents.
 *
 * <p>Policy text is untrusted content. It is returned as context only; nothing in a retrieved
 * document may widen tool scope or change the caller's identity.
 */
@Component
public class PolicySearchTool {

    private static final Logger log = LoggerFactory.getLogger(PolicySearchTool.class);

    private final VectorStore vectorStore;
    private final ToolTraceRecorder trace;
    private final TenantContext tenantContext;
    private final RagProperties properties;
    private final ObjectMapper mapper;

    public PolicySearchTool(VectorStore vectorStore,
                            ToolTraceRecorder trace,
                            TenantContext tenantContext,
                            RagProperties properties,
                            ObjectMapper mapper) {
        this.vectorStore = vectorStore;
        this.trace = trace;
        this.tenantContext = tenantContext;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Tool(description = """
        Search enterprise policy documents (leave, remote work, expense, travel, security,
        code of conduct, data retention and similar). Use for questions about company rules
        or entitlements. Returns a JSON string with: source, chunks[].
        """)
    public String searchPolicies(String query) {
        trace.start("searchPolicies");
        try {
            var access = RagAccessFilter.groupedForCaller(
                    tenantContext.tenantId(), tenantContext.entitlementGroups());
            var policyOnly = new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key(ChunkDocumentMapper.DOCUMENT_TYPE),
                    new Filter.Value(DocumentType.POLICY.name()));

            // Policies are curated rather than time-sensitive, so no freshness window is applied.
            var hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(properties.topK())
                    .filterExpression(new Filter.Expression(Filter.ExpressionType.AND, access, policyOnly))
                    .build());

            var chunks = hits.stream()
                    .map(Document::getText)
                    .toList();

            trace.record("searchPolicies", Map.of("query", query),
                    "policy hit: " + chunks.size() + " chunks", true);
            return mapper.writeValueAsString(new PolicyResponse("RAG", chunks));
        } catch (Exception e) {
            log.warn("Policy retrieval failed for query '{}': {}", query, e.getMessage());
            trace.record("searchPolicies", Map.of("query", query), e.getMessage(), false);
            return "{\"source\":\"ERROR\",\"chunks\":[]}";
        }
    }

    record PolicyResponse(String source, List<String> chunks) {
    }
}
