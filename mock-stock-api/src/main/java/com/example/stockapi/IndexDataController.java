package com.example.stockapi;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/indexes")
public class IndexDataController {

    @GetMapping("/{indexSymbol}/data")
    @PreAuthorize("hasAuthority('SCOPE_index-data:read')")
    public IndexSnapshot getIndexData(@PathVariable String indexSymbol) {
        var upper = normalize(indexSymbol);
        return new IndexSnapshot(
                upper,
                displayName(upper),
                22450.35,
                0.82,
                "INR",
                Instant.now(),
                List.of(
                        new Constituent("RELIANCE", "Reliance Industries Ltd", 10.4),
                        new Constituent("HDFCBANK", "HDFC Bank Ltd", 8.7),
                        new Constituent("INFY", "Infosys Ltd", 5.9)),
                Map.of(
                        "Financial Services", 33.2,
                        "Information Technology", 14.8,
                        "Energy", 12.1,
                        "Consumer Goods", 9.4));
    }

    private static String normalize(String indexSymbol) {
        return indexSymbol.toUpperCase().replace("-", "").replace("_", "");
    }

    private static String displayName(String indexSymbol) {
        return switch (indexSymbol) {
            case "NIFTY50", "NIFTY" -> "NIFTY 50";
            case "SENSEX" -> "BSE Sensex";
            case "NASDAQ100" -> "NASDAQ 100";
            case "SP500" -> "S&P 500";
            default -> indexSymbol;
        };
    }

        public record IndexSnapshot(
            String indexSymbol,
            String displayName,
            double level,
            double dayChangePercent,
            String currency,
            Instant asOf,
            List<Constituent> topConstituents,
            Map<String, Double> sectorWeights) {
    }

    public record Constituent(String ticker, String companyName, double weightPercent) {
    }
}
