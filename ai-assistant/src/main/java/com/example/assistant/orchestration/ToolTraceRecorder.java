package com.example.assistant.orchestration;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped recorder. Every tool writes a record here.
 * Thread-safe so parallel tool calls don't corrupt the trace.
 */
@Component
@RequestScope
public class ToolTraceRecorder {

    private final List<Map<String, Object>> records = new ArrayList<>();
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    public void start(String toolCallId) {
        startedAt.put(toolCallId, System.currentTimeMillis());
    }

    public void record(String tool, Object args, Object result, boolean ok) {
        long dur = startedAt.remove(tool) == null
                ? -1
                : System.currentTimeMillis() - startedAt.get(tool);
        records.add(Map.of(
                "tool", tool,
                "args", String.valueOf(args),
                "result", truncate(String.valueOf(result)),
                "ok", ok,
                "durationMs", dur
        ));
    }

    public List<Map<String, Object>> trace() {
        return List.copyOf(records);
    }

    private static String truncate(String s) {
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}