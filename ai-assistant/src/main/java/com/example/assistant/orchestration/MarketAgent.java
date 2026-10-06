package com.example.assistant.orchestration;

public interface MarketAgent {
    String name();

    AgentCapability capability();

    String answer(String message);
}
