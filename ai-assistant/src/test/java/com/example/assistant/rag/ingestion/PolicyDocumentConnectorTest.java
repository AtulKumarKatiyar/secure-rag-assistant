package com.example.assistant.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The previously dead {@code PolicyRetriever} is replaced by a connector, so this verifies the
 * policy corpus actually enters the shared ingestion pipeline with retrievable access metadata.
 */
class PolicyDocumentConnectorTest {

    private final PolicyDocumentConnector connector = new PolicyDocumentConnector();
    private final DocumentFilter filter = new DocumentFilter();

    @Test
    void loadsEveryPolicyResource() {
        var documents = connector.fetch();

        assertThat(documents).isNotEmpty();
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.documentType()).isEqualTo(DocumentType.POLICY);
            assertThat(document.rawText()).isNotBlank();
            assertThat(document.title()).isNotBlank();
        });
    }

    @Test
    void includesThePromptInjectionFixtureSoItIsActuallyRetrievable() {
        var ids = connector.fetch().stream().map(RagDocument::id).toList();

        assertThat(ids).contains("prompt-injection-test");
    }

    @Test
    void marksPoliciesPublicWithNoTenant() {
        assertThat(connector.fetch()).allSatisfy(document -> assertThat(document.metadata())
                .containsEntry(AccessMetadataKeys.VISIBILITY, AccessVisibility.PUBLIC.name())
                .containsEntry(AccessMetadataKeys.TENANT_ID, ""));
    }

    @Test
    void everyLoadedPolicySurvivesTheIngestionFilter() {
        List<RagDocument> documents = connector.fetch();

        assertThat(documents).isNotEmpty();
        assertThat(documents).allMatch(filter::isRelevant);
    }
}
