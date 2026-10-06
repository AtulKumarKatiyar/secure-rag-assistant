package com.example.assistant.rag.ingestion;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

@Component
public class DocumentFilter {
    private static final int MIN_DOCUMENT_LENGTH = 300;

    private static final Set<String> TRUSTED_SOURCES = Set.of(
            "Reuters",
            "Bloomberg",
            "SEC",
            "Company Investor Relations",
            "Stock Exchange",
            "Internal Research",
            "Mock Financial News",
            "Mock Exchange Disclosure",
            "Client Research Portal",
            "Premium Vendor Research");

    private static final Set<String> TRACKED_TICKERS = Set.of(
            "AAPL", "MSFT", "GOOGL", "TSLA", "NVDA", "RELIANCE", "INFY",
            "NIFTY50", "SENSEX", "SP500", "NASDAQ100", "GOLD", "SILVER", "BRENT", "WTI", "OIL");

    private final Clock clock;

    public DocumentFilter() {
        this(Clock.systemUTC());
    }

    DocumentFilter(Clock clock) {
        this.clock = clock;
    }

    public boolean isRelevant(RagDocument document) {
        if (document.rawText() == null || document.rawText().length() < MIN_DOCUMENT_LENGTH) {
            return false;
        }
        if (document.ticker() == null || !TRACKED_TICKERS.contains(document.ticker().toUpperCase(Locale.ROOT))) {
            return false;
        }
        if (document.source() == null || !TRUSTED_SOURCES.contains(document.source())) {
            return false;
        }
        if (!hasValidAccessMetadata(document)) {
            return false;
        }
        if (isStale(document)) {
            return false;
        }
        return hasUsefulFinancialSignal(document.rawText());
    }

    private boolean hasValidAccessMetadata(RagDocument document) {
        var visibilityValue = String.valueOf(document.metadata().getOrDefault(AccessMetadataKeys.VISIBILITY, ""));
        if (visibilityValue.isBlank()) {
            return false;
        }
        AccessVisibility visibility;
        try {
            visibility = AccessVisibility.valueOf(visibilityValue);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        var tenantId = String.valueOf(document.metadata().getOrDefault(AccessMetadataKeys.TENANT_ID, ""));
        var entitlementGroup = String.valueOf(document.metadata().getOrDefault(AccessMetadataKeys.ENTITLEMENT_GROUP, ""));

        return switch (visibility) {
            case PUBLIC -> tenantId.isBlank();
            case TENANT_PRIVATE -> !tenantId.isBlank();
            case ENTITLEMENT_RESTRICTED -> !entitlementGroup.isBlank();
        };
    }

    private boolean isStale(RagDocument document) {
        if (document.documentType() == DocumentType.POLICY) {
            return false;
        }
        return document.publishedAt() == null
                || document.publishedAt().isBefore(clock.instant().minus(180, ChronoUnit.DAYS));
    }

    private boolean hasUsefulFinancialSignal(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("revenue")
                || lower.contains("profit")
                || lower.contains("guidance")
                || lower.contains("earnings")
                || lower.contains("margin")
                || lower.contains("order book")
                || lower.contains("promoter")
                || lower.contains("shareholding")
                || lower.contains("management commentary")
                || lower.contains("regulatory filing")
                || lower.contains("acquisition")
                || lower.contains("merger")
                || lower.contains("dividend")
                || lower.contains("buyback")
                || lower.contains("capex")
                || lower.contains("debt")
                || lower.contains("commodity")
                || lower.contains("price")
                || lower.contains("valuation")
                || lower.contains("sector");
    }
}
