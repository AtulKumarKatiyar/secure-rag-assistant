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
public class IndexManagementTool {
    private static final String SCOPE = "index-data:read";

    private final TokenBrokerClient broker;
    private final RestClient stockApi;
    private final ToolTraceRecorder trace;
    private final ProductionCache cache;
    private final TenantContext tenantContext;

    public IndexManagementTool(TokenBrokerClient broker,
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

    @Tool(description = "Fetch secured index data such as level, performance, constituents and sector weights for indexes like NIFTY50, SENSEX, NASDAQ100 or SP500.")
    public Object getIndexData(String indexSymbol) {
        trace.start("getIndexData");
        try {
            var key = tenantContext.tenantId() + ":index:" + indexSymbol.toUpperCase();
            var cached = cache.get(key);
            if (cached.isPresent()) {
                trace.record("getIndexData", Map.of("indexSymbol", indexSymbol, "cache", "hit"), cached.get(), true);
                return cached.get();
            }
            var token = broker.mint(List.of(SCOPE));
            var out = stockApi.get()
                    .uri("/indexes/{indexSymbol}/data", indexSymbol)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(Object.class);
            cache.put(key, out, Duration.ofSeconds(45));
            trace.record("getIndexData", Map.of("indexSymbol", indexSymbol), out, true);
            return out;
        } catch (Exception e) {
            trace.record("getIndexData", Map.of("indexSymbol", indexSymbol), e.getMessage(), false);
            return Map.of("error", e.getMessage(), "indexSymbol", indexSymbol);
        }
    }
}
