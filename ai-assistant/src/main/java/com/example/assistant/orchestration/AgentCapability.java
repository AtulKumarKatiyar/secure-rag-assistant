package com.example.assistant.orchestration;

import java.util.List;

public record AgentCapability(String agentName,
                              String description,
                              List<String> domains,
                              List<String> keywords,
                              List<String> examples) {
}
