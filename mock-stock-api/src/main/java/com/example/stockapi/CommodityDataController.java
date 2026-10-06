package com.example.stockapi;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/commodities")
public class CommodityDataController {

    @GetMapping("/{commoditySymbol}/data")
    @PreAuthorize("hasAuthority('SCOPE_commodity-data:read')")
    public CommoditySnapshot getCommodityData(@PathVariable String commoditySymbol) {
        var symbol = normalize(commoditySymbol);
        var market = market(symbol);
        return new CommoditySnapshot(
                symbol,
                market.name(),
                market.price(),
                market.currency(),
                market.unit(),
                market.dayChangePercent(),
                Instant.now(),
                Map.of(
                        "demand", "Industrial demand and macro risk appetite remain key drivers.",
                        "supply", "Supply discipline and inventory levels are being watched closely.",
                        "risk", "Geopolitical shocks and currency movement may change pricing quickly."));
    }

    private static String normalize(String commoditySymbol) {
        return commoditySymbol.toUpperCase().replace("-", "").replace("_", "");
    }

    private static CommodityMarket market(String symbol) {
        return switch (symbol) {
            case "GOLD", "XAU" -> new CommodityMarket("Gold Spot", 3875.20, "USD", "troy ounce", 0.31);
            case "SILVER", "XAG" -> new CommodityMarket("Silver Spot", 46.10, "USD", "troy ounce", -0.12);
            case "BRENT", "OIL" -> new CommodityMarket("Brent Crude Oil", 86.45, "USD", "barrel", 1.18);
            case "WTI" -> new CommodityMarket("WTI Crude Oil", 82.30, "USD", "barrel", 0.95);
            case "NATURALGAS", "NG" -> new CommodityMarket("Natural Gas", 3.82, "USD", "MMBtu", -0.44);
            default -> new CommodityMarket(symbol, 100.00, "USD", "unit", 0.0);
        };
    }

    private record CommodityMarket(String name, double price, String currency, String unit, double dayChangePercent) {
    }

    public record CommoditySnapshot(
            String commoditySymbol,
            String name,
            double price,
            String currency,
            String unit,
            double dayChangePercent,
            Instant asOf,
            Map<String, String> drivers) {
    }
}
