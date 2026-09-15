package com.example.assistant.rag.ingestion;

import com.example.assistant.rag.store.MarketRagStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagIngestionService {
    private final List<DataSourceConnector> connectors;
    private final DocumentFilter documentFilter;
    private final DocumentChunker documentChunker;
    private final MarketRagStore marketRagStore;

    public RagIngestionService(List<DataSourceConnector> connectors,
                               DocumentFilter documentFilter,
                               DocumentChunker documentChunker,
                               MarketRagStore marketRagStore) {
        this.connectors = connectors;
        this.documentFilter = documentFilter;
        this.documentChunker = documentChunker;
        this.marketRagStore = marketRagStore;
    }

    public IngestionReport ingest() {
        var fetched = 0;
        var accepted = 0;
        var chunkCount = 0;

        for (DataSourceConnector connector : connectors) {
            for (RagDocument document : connector.fetch()) {
                fetched++;
                if (!documentFilter.isRelevant(document)) {
                    continue;
                }
                accepted++;
                var chunks = documentChunker.chunk(document);
                chunkCount += chunks.size();
                marketRagStore.upsert(chunks);
            }
        }

        return new IngestionReport(fetched, accepted, chunkCount, marketRagStore.size());
    }

    public record IngestionReport(int fetchedDocuments, int acceptedDocuments, int savedChunks, int totalStoredChunks) {
    }
}
