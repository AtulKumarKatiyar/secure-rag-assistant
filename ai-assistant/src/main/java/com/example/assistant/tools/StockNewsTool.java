package com.example.assistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class StockNewsTool {

    private static final String SCOPE = "stock-news:read";

    private final TokenBrokerClient broker;
    private final RestClient stockApi;

    public StockNewsTool(TokenBrokerClient broker,
                         @Qualifier("stockApiClient") RestClient stockApi) {
        this.broker = broker;
        this.stockApi = stockApi;
    }

    @Tool(description = "Fetch the LATEST live stock news for a ticker like AAPL from the secured stock-news API. Use for real-time / 'right now' news questions.")
    public Object getLiveStockNews(String ticker) {
        var token = broker.mint(List.of(SCOPE));
        return stockApi.get()
            .uri("/stocks/{ticker}/news", ticker)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
            .retrieve()
            .body(Object.class);
    }

    @Tool(description = "Fetch the CURRENT live stock price for a ticker like AAPL from the secured stock-news API.")
    public Object getLiveStockPrice(String ticker) {
        var token = broker.mint(List.of(SCOPE));
        return stockApi.get()
            .uri("/stocks/{ticker}/price", ticker)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
            .retrieve()
            .body(Object.class);
    }
}