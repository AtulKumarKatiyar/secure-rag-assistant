package com.example.assistant.orchestration;

import java.util.List;

public record AgentCapability(String name,
                              String description,
                              List<String> exampleQueries) {
}
