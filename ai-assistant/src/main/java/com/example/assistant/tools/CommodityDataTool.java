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
public class CommodityDataTool {
    private static final String SCOPE = "commodity-data:read";

    private final TokenBrokerClient broker;
    private final RestClient stockApi;
    private final ToolTraceRecorder trace;
    private final ProductionCache cache;
    private final TenantContext tenantContext;

    public CommodityDataTool(TokenBrokerClient broker,
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

    @Tool(description = "Fetch secured commodity data for oil, Brent, WTI, gold, silver or natural gas.")
    public Object getCommodityData(String commoditySymbol) {
        trace.start("getCommodityData");
        try {
            var key = tenantContext.tenantId() + ":commodity:" + commoditySymbol.toUpperCase();
            var cached = cache.get(key);
            if (cached.isPresent()) {
                trace.record("getCommodityData", Map.of("commoditySymbol", commoditySymbol, "cache", "hit"), cached.get(), true);
                return cached.get();
            }
            var token = broker.mint(List.of(SCOPE));
            var out = stockApi.get()
                    .uri("/commodities/{commoditySymbol}/data", commoditySymbol)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(Object.class);
            cache.put(key, out, Duration.ofSeconds(45));
            trace.record("getCommodityData", Map.of("commoditySymbol", commoditySymbol), out, true);
            return out;
        } catch (Exception e) {
            trace.record("getCommodityData", Map.of("commoditySymbol", commoditySymbol), e.getMessage(), false);
            return Map.of("error", e.getMessage(), "commoditySymbol", commoditySymbol);
        }
    }
}
