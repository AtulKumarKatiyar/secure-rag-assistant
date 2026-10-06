package com.example.assistant.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentExecutionProperties {
    private final long timeoutMs;

    public AgentExecutionProperties(@Value("${assistant.agent-execution.timeout-ms:8000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long timeoutMs() {
        return timeoutMs;
    }
}
