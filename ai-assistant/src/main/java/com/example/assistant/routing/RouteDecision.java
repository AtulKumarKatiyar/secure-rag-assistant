package com.example.assistant.routing;

public record RouteDecision(Route route, String ticker, String reason) {
    public enum Route { RAG, API, BOTH, NONE }
}