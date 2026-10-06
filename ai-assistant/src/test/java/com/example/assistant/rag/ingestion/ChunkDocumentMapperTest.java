package com.example.assistant.rag.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkDocumentMapperTest {

    private final ChunkDocumentMapper mapper = new ChunkDocumentMapper();

    @Test
    void usesTheChunkIdAsDocumentIdSoReingestionOverwrites() {
        var document = mapper.toDocument(chunk(publicMetadata()));

        assertThat(document.getId()).isEqualTo("doc-1-chunk-0");
    }

    @Test
    void preservesTheConnectorAssignedAccessMetadata() {
        var metadata = new HashMap<String, Object>();
        metadata.put(AccessMetadataKeys.VISIBILITY, AccessVisibility.TENANT_PRIVATE.name());
        metadata.put(AccessMetadataKeys.TENANT_ID, "clientA");

        var mapped = mapper.toDocument(chunk(metadata)).getMetadata();

        assertThat(mapped)
                .containsEntry(AccessMetadataKeys.VISIBILITY, "TENANT_PRIVATE")
                .containsEntry(AccessMetadataKeys.TENANT_ID, "clientA");
    }

    @Test
    void preservesTheEntitlementGroupOnRestrictedChunks() {
        var metadata = new HashMap<String, Object>();
        metadata.put(AccessMetadataKeys.VISIBILITY, AccessVisibility.ENTITLEMENT_RESTRICTED.name());
        metadata.put(AccessMetadataKeys.TENANT_ID, "");
        metadata.put(AccessMetadataKeys.ENTITLEMENT_GROUP, "premium-research");

        var mapped = mapper.toDocument(chunk(metadata)).getMetadata();

        assertThat(mapped).containsEntry(AccessMetadataKeys.ENTITLEMENT_GROUP, "premium-research");
    }

    @Test
    void storesPublicationTimeAsANumberForReliableRangeFiltering() {
        var document = mapper.toDocument(chunk(publicMetadata()));

        assertThat(document.getMetadata())
                .containsEntry(ChunkDocumentMapper.PUBLISHED_AT_EPOCH_MS, 1_789_000_000_000L);
        assertThat(document.getMetadata().get(ChunkDocumentMapper.PUBLISHED_AT_EPOCH_MS))
                .isInstanceOf(Long.class);
    }

    @Test
    void writesTheFieldsRetrievalFiltersOn() {
        var document = mapper.toDocument(chunk(publicMetadata()));

        assertThat(document.getMetadata())
                .containsEntry(ChunkDocumentMapper.TICKER, "AAPL")
                .containsEntry(ChunkDocumentMapper.DOCUMENT_TYPE, "NEWS")
                .containsEntry(ChunkDocumentMapper.CHUNK_ID, "doc-1-chunk-0");
        assertThat(document.getText()).isEqualTo("Apple reported revenue growth.");
    }

    @Test
    void toleratesNullOptionalFields() {
        var chunk = new RagChunk("doc-1-chunk-0", "doc-1", "AAPL", null, DocumentType.NEWS,
                null, null, null, Instant.ofEpochMilli(1_789_000_000_000L), "text", publicMetadata());

        Document document = mapper.toDocument(chunk);

        assertThat(document.getMetadata())
                .containsEntry(ChunkDocumentMapper.COMPANY_NAME, "")
                .containsEntry(ChunkDocumentMapper.TITLE, "");
    }

    private static RagChunk chunk(Map<String, Object> metadata) {
        return new RagChunk(
                "doc-1-chunk-0",
                "doc-1",
                "AAPL",
                "Apple Inc.",
                DocumentType.NEWS,
                "Apple news",
                "Mock Financial News",
                "https://example.com/aapl",
                Instant.ofEpochMilli(1_789_000_000_000L),
                "Apple reported revenue growth.",
                metadata);
    }

    private static Map<String, Object> publicMetadata() {
        return Map.of(
                AccessMetadataKeys.VISIBILITY, AccessVisibility.PUBLIC.name(),
                AccessMetadataKeys.TENANT_ID, "");
    }
}
