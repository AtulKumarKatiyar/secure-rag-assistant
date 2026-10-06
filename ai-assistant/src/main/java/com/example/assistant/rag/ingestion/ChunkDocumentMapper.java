package com.example.assistant.rag.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;

/**
 * Converts an ingested {@link RagChunk} into the Spring AI {@link Document} that is written
 * to the vector store.
 *
 * <p>The document id is the chunk id, so re-ingesting the same source overwrites the previous
 * vector instead of accumulating duplicates.
 *
 * <p>Access metadata ({@code visibility}, {@code tenantId}, {@code entitlementGroup}) is carried
 * through from the connector untouched. Retrieval filters on these exact keys, so dropping them
 * here would either leak documents or make them permanently unreachable.
 *
 * <p>{@code publishedAtEpochMs} is stored as a {@link Long} on purpose. Spring AI's simple vector
 * store converts ISO-8601 looking strings into date literals in the filter expression, and
 * {@link java.time.Instant#toString()} emits variable precision, so string time comparisons are
 * unreliable. A numeric epoch is unambiguous and compares correctly.
 */
@Component
public class ChunkDocumentMapper {

    public static final String CHUNK_ID = "chunkId";
    public static final String DOCUMENT_ID = "documentId";
    public static final String TICKER = "ticker";
    public static final String COMPANY_NAME = "companyName";
    public static final String DOCUMENT_TYPE = "documentType";
    public static final String TITLE = "title";
    public static final String SOURCE = "source";
    public static final String URL = "url";
    public static final String PUBLISHED_AT = "publishedAt";
    public static final String PUBLISHED_AT_EPOCH_MS = "publishedAtEpochMs";

    public Document toDocument(RagChunk chunk) {
        var metadata = new HashMap<String, Object>(chunk.metadata());
        metadata.put(CHUNK_ID, chunk.chunkId());
        metadata.put(DOCUMENT_ID, chunk.documentId());
        metadata.put(TICKER, blankIfNull(chunk.ticker()));
        metadata.put(COMPANY_NAME, blankIfNull(chunk.companyName()));
        metadata.put(DOCUMENT_TYPE, chunk.documentType().name());
        metadata.put(TITLE, blankIfNull(chunk.title()));
        metadata.put(SOURCE, blankIfNull(chunk.source()));
        metadata.put(URL, blankIfNull(chunk.url()));
        metadata.put(PUBLISHED_AT, chunk.publishedAt() == null ? "" : chunk.publishedAt().toString());
        metadata.put(PUBLISHED_AT_EPOCH_MS, chunk.publishedAt() == null ? 0L : chunk.publishedAt().toEpochMilli());

        return Document.builder()
                .id(chunk.chunkId())
                .text(chunk.text())
                .metadata(metadata)
                .build();
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
