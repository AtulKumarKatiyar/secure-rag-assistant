package com.example.assistant.tools;

import com.example.assistant.cache.ProductionCache;
import com.example.assistant.orchestration.ToolTraceRecorder;
import com.example.assistant.web.TenantContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class StockNewsTool {

    private static final String SCOPE = "stock-news:read";

    private final TokenBrokerClient broker;
    private final RestClient stockApi;
    private final ToolTraceRecorder trace;
    private final ProductionCache cache;
    private final TenantContext tenantContext;

    public StockNewsTool(TokenBrokerClient broker,
                         @Qualifier("stockApiClient") RestClient stockApi,
                         ToolTraceRecorder trace,
                         ProductionCache cache,
                         TenantContext tenantContext) {
        this.broker = broker;
        this.stockApi = stockApi;
        this.trace = trace;
        this.cache = cache;
        this.tenantContext = tenantContext;
    }

    @Tool(description = "Fetch the LATEST live stock news for a ticker like AAPL from the secured API. Use for 'right now' / breaking news.")
    public Object getLiveStockNews(String ticker) {
        trace.start("getLiveStockNews");
        try {
            var key = cacheKey("news", ticker);
            var cached = cache.get(key);
            if (cached.isPresent()) {
                trace.record("getLiveStockNews", Map.of("ticker", ticker, "cache", "hit"), cached.get(), true);
                return cached.get();
            }
            var token = broker.mint(List.of(SCOPE));
            var out = stockApi.get()
                    .uri("/stocks/{ticker}/news", ticker)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(Object.class);
            cache.put(key, out, Duration.ofMinutes(2));
            trace.record("getLiveStockNews", Map.of("ticker", ticker), out, true);
            return out;
        } catch (Exception e) {
            trace.record("getLiveStockNews", Map.of("ticker", ticker), e.getMessage(), false);
            return Map.of("error", e.getMessage(), "ticker", ticker);
        }
    }

    @Tool(description = "Fetch the CURRENT live stock price for a ticker like AAPL from the secured API.")
    public Object getLiveStockPrice(String ticker) {
        trace.start("getLiveStockPrice");
        try {
            var key = cacheKey("price", ticker);
            var cached = cache.get(key);
            if (cached.isPresent()) {
                trace.record("getLiveStockPrice", Map.of("ticker", ticker, "cache", "hit"), cached.get(), true);
                return cached.get();
            }
            var token = broker.mint(List.of(SCOPE));
            var out = stockApi.get()
                    .uri("/stocks/{ticker}/price", ticker)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(Object.class);
            cache.put(key, out, Duration.ofSeconds(30));
            trace.record("getLiveStockPrice", Map.of("ticker", ticker), out, true);
            return out;
        } catch (Exception e) {
            trace.record("getLiveStockPrice", Map.of("ticker", ticker), e.getMessage(), false);
            return Map.of("error", e.getMessage(), "ticker", ticker);
        }
    }

    private String cacheKey(String type, String ticker) {
        return tenantContext.tenantId() + ":stock:" + type + ":" + ticker.toUpperCase();
    }
}
