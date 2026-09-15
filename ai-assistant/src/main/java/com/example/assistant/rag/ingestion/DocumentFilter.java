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
            "Mock Exchange Disclosure");

    private static final Set<String> TRACKED_TICKERS = Set.of(
            "AAPL", "MSFT", "GOOGL", "TSLA", "NVDA", "RELIANCE", "INFY");

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
        if (isStale(document)) {
            return false;
        }
        return hasUsefulFinancialSignal(document.rawText());
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
                || lower.contains("debt");
    }
}
