package com.example.assistant.rag.ingestion;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs every {@link DataSourceConnector} through filter → chunk → embed → store.
 *
 * <p>Documents are written to the same {@link VectorStore} that {@code RagSearchTool} and
 * {@code PolicySearchTool} read from. Ingestion and retrieval must share one store: if they
 * diverge, every query silently falls back and the RAG path is inert.
 */
@Service
public class RagIngestionService {

    private final List<DataSourceConnector> connectors;
    private final DocumentFilter documentFilter;
    private final DocumentChunker documentChunker;
    private final ChunkDocumentMapper documentMapper;
    private final VectorStore vectorStore;
    private final Set<String> storedChunkIds = ConcurrentHashMap.newKeySet();

    public RagIngestionService(List<DataSourceConnector> connectors,
                               DocumentFilter documentFilter,
                               DocumentChunker documentChunker,
                               ChunkDocumentMapper documentMapper,
                               VectorStore vectorStore) {
        this.connectors = connectors;
        this.documentFilter = documentFilter;
        this.documentChunker = documentChunker;
        this.documentMapper = documentMapper;
        this.vectorStore = vectorStore;
    }

    /**
     * Synchronized because the startup seed, the scheduler and the manual endpoint can all fire
     * concurrently, and a half-written ingestion report is not useful.
     */
    public synchronized IngestionReport ingest() {
        var fetched = 0;
        var accepted = 0;
        var saved = 0;

        for (DataSourceConnector connector : connectors) {
            for (RagDocument document : connector.fetch()) {
                fetched++;
                if (!documentFilter.isRelevant(document)) {
                    continue;
                }
                accepted++;

                var documents = documentChunker.chunk(document).stream()
                        .map(documentMapper::toDocument)
                        .toList();
                if (documents.isEmpty()) {
                    continue;
                }

                // SimpleVectorStore rejects an empty batch, hence the guard above.
                vectorStore.add(documents);
                documents.forEach(added -> storedChunkIds.add(added.getId()));
                saved += documents.size();
            }
        }

        return new IngestionReport(fetched, accepted, saved, storedChunkIds.size());
    }

    public int storedChunkCount() {
        return storedChunkIds.size();
    }

    public record IngestionReport(int fetchedDocuments, int acceptedDocuments, int savedChunks, int totalStoredChunks) {
    }
}
