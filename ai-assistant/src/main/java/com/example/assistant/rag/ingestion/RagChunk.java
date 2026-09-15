package com.example.assistant.rag.ingestion;

import java.time.Instant;
import java.util.Map;

public record RagChunk(
        String chunkId,
        String documentId,
        String ticker,
        String companyName,
        DocumentType documentType,
        String title,
        String source,
        String url,
        Instant publishedAt,
        String text,
        Map<String, Object> metadata
) {
}
