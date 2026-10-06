package com.example.assistant.tools;

import com.example.assistant.cache.ProductionCache;
import com.example.assistant.orchestration.ToolTraceRecorder;
import com.example.assistant.web.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Live market-data tool.
 *
 * <p>Tool methods return {@code String} (JSON), never {@code Object}. Spring AI 1.0.0's
 * {@code MethodToolCallbackProvider} treats any return type that {@code Function}/{@code Supplier}/
 * {@code Consumer} is assignable <em>to</em> as a "functional type" and silently drops the method.
 * Because every class is assignable to {@code Object}, an {@code Object} return type made all four
 * market-data tools invisible, which prevented the agents — and therefore the application — from
 * starting at all.
 */
@Component
public class StockNewsTool {

    private static final String SCOPE = "stock-news:read";

    private final TokenBrokerClient broker;
    private final RestClient stockApi;
    private final ToolTraceRecorder trace;
    private final ProductionCache cache;
    private final TenantContext tenantContext;
    private final ObjectMapper mapper;

    public StockNewsTool(TokenBrokerClient broker,
                         @Qualifier("stockApiRestClient") RestClient stockApi,
                         ToolTraceRecorder trace,
                         ProductionCache cache,
                         TenantContext tenantContext,
                         ObjectMapper mapper) {
        this.broker = broker;
        this.stockApi = stockApi;
        this.trace = trace;
        this.cache = cache;
        this.tenantContext = tenantContext;
        this.mapper = mapper;
    }

    @Tool(description = "Fetch the LATEST live stock news for a ticker like AAPL from the secured API. Use for 'right now' / breaking news.")
    public String getLiveStockNews(String ticker) {
        trace.start("getLiveStockNews");
        try {
            var key = cacheKey("news", ticker);
            var cached = cache.get(key);
            if (cached.isPresent()) {
                trace.record("getLiveStockNews", Map.of("ticker", ticker, "cache", "hit"), cached.get(), true);
                return String.valueOf(cached.get());
            }
            var token = broker.mint(List.of(SCOPE));
            var out = stockApi.get()
                    .uri("/stocks/{ticker}/news", ticker)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(Object.class);
            var json = mapper.writeValueAsString(out);
            cache.put(key, json, Duration.ofMinutes(2));
            trace.record("getLiveStockNews", Map.of("ticker", ticker), json, true);
            return json;
        } catch (Exception e) {
            trace.record("getLiveStockNews", Map.of("ticker", ticker), e.getMessage(), false);
            return error("getLiveStockNews", ticker, e);
        }
    }

    @Tool(description = "Fetch the CURRENT live stock price for a ticker like AAPL from the secured API.")
    public String getLiveStockPrice(String ticker) {
        trace.start("getLiveStockPrice");
        try {
            var key = cacheKey("price", ticker);
            var cached = cache.get(key);
            if (cached.isPresent()) {
                trace.record("getLiveStockPrice", Map.of("ticker", ticker, "cache", "hit"), cached.get(), true);
                return String.valueOf(cached.get());
            }
            var token = broker.mint(List.of(SCOPE));
            var out = stockApi.get()
                    .uri("/stocks/{ticker}/price", ticker)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(Object.class);
            var json = mapper.writeValueAsString(out);
            cache.put(key, json, Duration.ofSeconds(30));
            trace.record("getLiveStockPrice", Map.of("ticker", ticker), json, true);
            return json;
        } catch (Exception e) {
            trace.record("getLiveStockPrice", Map.of("ticker", ticker), e.getMessage(), false);
            return error("getLiveStockPrice", ticker, e);
        }
    }

    private String error(String tool, String ticker, Exception e) {
        return ToolJson.error(mapper, tool, Map.of("ticker", ticker), e);
    }

    private String cacheKey(String type, String ticker) {
        return tenantContext.tenantId() + ":stock:" + type + ":" + ticker.toUpperCase();
    }
}
