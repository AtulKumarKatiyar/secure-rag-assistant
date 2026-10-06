package com.example.assistant.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DocumentFilter filter = new DocumentFilter(CLOCK);

    @Test
    void acceptsAWellFormedPublicMarketDocument() {
        assertThat(filter.isRelevant(marketDocument("AAPL", "Reuters", longText(), publicMetadata(), NOW))).isTrue();
    }

    @Test
    void rejectsDocumentsBelowTheMinimumLength() {
        assertThat(filter.isRelevant(marketDocument("AAPL", "Reuters", "too short", publicMetadata(), NOW))).isFalse();
    }

    @Test
    void rejectsUntrackedTickers() {
        assertThat(filter.isRelevant(marketDocument("ZZZZ", "Reuters", longText(), publicMetadata(), NOW))).isFalse();
    }

    @Test
    void rejectsUntrustedSources() {
        assertThat(filter.isRelevant(marketDocument("AAPL", "Random Blog", longText(), publicMetadata(), NOW))).isFalse();
    }

    @Test
    void rejectsStaleMarketDocuments() {
        assertThat(filter.isRelevant(
                marketDocument("AAPL", "Reuters", longText(), publicMetadata(), NOW.minusSeconds(200L * 86400)))).isFalse();
    }

    @Test
    void rejectsDocumentsWithoutAccessMetadata() {
        var metadata = new HashMap<String, Object>();
        metadata.put("sector", "Technology");
        assertThat(filter.isRelevant(marketDocument("AAPL", "Reuters", longText(), metadata, NOW))).isFalse();
    }

    @Test
    void rejectsTenantPrivateDocumentsWithoutATenant() {
        var metadata = new HashMap<String, Object>();
        metadata.put(AccessMetadataKeys.VISIBILITY, AccessVisibility.TENANT_PRIVATE.name());
        metadata.put(AccessMetadataKeys.TENANT_ID, "");
        assertThat(filter.isRelevant(marketDocument("AAPL", "Reuters", longText(), metadata, NOW))).isFalse();
    }

    /**
     * Policies carry no ticker and are not market data, so they bypass the ticker allowlist,
     * source allowlist, staleness window and financial-signal heuristic.
     */
    @Test
    void acceptsPolicyDocumentsWithoutATickerOrMarketSource() {
        var document = new RagDocument(
                "remote-work-policy", "", "", DocumentType.POLICY,
                "Remote Work Policy", "Policy Repository", "policy://remote-work-policy",
                NOW, longText(), publicMetadata());

        assertThat(filter.isRelevant(document)).isTrue();
    }

    @Test
    void stillRequiresAccessMetadataOnPolicyDocuments() {
        var document = new RagDocument(
                "remote-work-policy", "", "", DocumentType.POLICY,
                "Remote Work Policy", "Policy Repository", "policy://remote-work-policy",
                NOW, longText(), Map.of());

        assertThat(filter.isRelevant(document)).isFalse();
    }

    private static RagDocument marketDocument(String ticker,
                                              String source,
                                              String text,
                                              Map<String, Object> metadata,
                                              Instant publishedAt) {
        return new RagDocument("doc-" + ticker, ticker, ticker + " Inc", DocumentType.NEWS,
                "title", source, "https://example.com/" + ticker, publishedAt, text, metadata);
    }

    private static Map<String, Object> publicMetadata() {
        return Map.of(
                AccessMetadataKeys.VISIBILITY, AccessVisibility.PUBLIC.name(),
                AccessMetadataKeys.TENANT_ID, "");
    }

    private static String longText() {
        return "The company reported revenue growth and raised guidance for the next quarter. "
                + "Management commentary covered margin expansion, the order book and capex plans, "
                + "while the regulatory filing discussed capital allocation, dividend policy and a "
                + "renewed share buyback authorization alongside its debt reduction programme. "
                + "Analysts also flagged valuation, sector rotation and foreign exchange pressure.";
    }
}
