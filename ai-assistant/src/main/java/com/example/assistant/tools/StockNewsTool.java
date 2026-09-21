package com.example.assistant.tools;

import com.example.assistant.orchestration.ToolTraceRecorder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class StockNewsTool {

    private static final String SCOPE = "stock-news:read";

    private final TokenBrokerClient broker;
    private final RestClient stockApi;
    private final ToolTraceRecorder trace;

    public StockNewsTool(TokenBrokerClient broker,
                         @Qualifier("stockApiClient") RestClient stockApi,
                         ToolTraceRecorder trace) {
        this.broker = broker;
        this.stockApi = stockApi;
        this.trace = trace;
    }

    @Tool(description = "Fetch the LATEST live stock news for a ticker like AAPL from the secured API. Use for 'right now' / breaking news.")
    public Object getLiveStockNews(String ticker) {
        trace.start("getLiveStockNews");
        try {
            var token = broker.mint(List.of(SCOPE));
            var out = stockApi.get()
                    .uri("/stocks/{ticker}/news", ticker)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(Object.class);
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
            var token = broker.mint(List.of(SCOPE));
            var out = stockApi.get()
                    .uri("/stocks/{ticker}/price", ticker)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(Object.class);
            trace.record("getLiveStockPrice", Map.of("ticker", ticker), out, true);
            return out;
        } catch (Exception e) {
            trace.record("getLiveStockPrice", Map.of("ticker", ticker), e.getMessage(), false);
            return Map.of("error", e.getMessage(), "ticker", ticker);
        }
    }
}