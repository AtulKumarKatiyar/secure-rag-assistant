package com.example.assistant.rag.store;

import com.example.assistant.rag.ingestion.DocumentType;

import java.time.Instant;
import java.util.Map;

public record MarketRetrievedChunk(
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
        Map<String, Object> metadata,
        double score
) {
}
