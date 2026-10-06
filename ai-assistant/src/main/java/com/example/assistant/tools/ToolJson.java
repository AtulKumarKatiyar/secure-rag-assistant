package com.example.assistant.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared JSON helpers for tool responses.
 *
 * <p>Tool methods must return a JSON {@code String} rather than a domain object, both because the
 * model consumes text and because Spring AI 1.0.0 drops {@code @Tool} methods whose return type is
 * {@code Object} (see {@link StockNewsTool}).
 */
final class ToolJson {

    private static final Logger log = LoggerFactory.getLogger(ToolJson.class);

    private ToolJson() {
    }

    static String error(ObjectMapper mapper, String tool, Map<String, Object> context, Exception cause) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("source", "ERROR");
        payload.put("tool", tool);
        payload.putAll(context);
        payload.put("message", String.valueOf(cause.getMessage()));
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception serializationFailure) {
            log.warn("Could not serialize error response for tool {}", tool, serializationFailure);
            return "{\"source\":\"ERROR\",\"tool\":\"" + tool + "\"}";
        }
    }
}
