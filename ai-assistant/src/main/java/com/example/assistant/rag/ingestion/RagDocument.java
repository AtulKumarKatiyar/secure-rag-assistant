package com.example.assistant.rag.ingestion;

import java.time.Instant;
import java.util.Map;

public record RagDocument(
        String id,
        String ticker,
        String companyName,
        DocumentType documentType,
        String title,
        String source,
        String url,
        Instant publishedAt,
        String rawText,
        Map<String, Object> metadata
) {
}
