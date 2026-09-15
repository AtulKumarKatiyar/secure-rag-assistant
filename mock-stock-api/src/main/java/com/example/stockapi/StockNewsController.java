package com.example.stockapi;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stocks")
public class StockNewsController {

    @GetMapping("/{ticker}/news")
    @PreAuthorize("hasAuthority('SCOPE_stock-news:read')")
    public StockNewsResponse getNews(@PathVariable String ticker) {
        var upper = ticker.toUpperCase();
        return new StockNewsResponse(upper, List.of(
            new Article(
                upper + " announces new enterprise AI features",
                "Mock Financial News",
                Instant.parse("2026-09-15T09:00:00Z"),
                upper + " expanded its enterprise AI tooling with new APIs targeting large enterprises."),
            new Article(
                upper + " reports stronger-than-expected services revenue",
                "Mock Financial News",
                Instant.parse("2026-09-14T13:30:00Z"),
                "Services segment grew double-digit, driven by subscriptions and cloud.")
        ));
    }

    @GetMapping("/{ticker}/price")
    @PreAuthorize("hasAuthority('SCOPE_stock-news:read')")
    public Map<String, Object> getPrice(@PathVariable String ticker) {
        return Map.of(
            "ticker", ticker.toUpperCase(),
            "price", 231.45,
            "currency", "USD",
            "asOf", Instant.now().toString()
        );
    }

    public record StockNewsResponse(String ticker, List<Article> articles) {}
    public record Article(String headline, String source, Instant publishedAt, String summary) {}
}