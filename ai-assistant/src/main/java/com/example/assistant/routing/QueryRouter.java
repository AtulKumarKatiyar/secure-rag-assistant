package com.example.assistant.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class QueryRouter {

    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public QueryRouter(ChatClient.Builder builder) {
        this.chatClient = builder
            .defaultSystem("""
                You are a strict router for a stock-news assistant.
                Return ONLY valid JSON, no markdown fences, no prose, matching:
                {"route":"RAG"|"API"|"BOTH"|"NONE","ticker":"<UPPER TICKER or null>","reason":"<short>"}

                Rules:
                - RAG  = "what happened", "summarize recent news", "why did X move last week"
                - API  = "current price", "right now", "latest tick"
                - BOTH = needs live data AND background context
                - NONE = chit-chat / unclear / not stock-related
                Extract the ticker if mentioned. Uppercase it. Null if absent.
                Never invent data. Never include URLs or HTTP methods.
                """)
            .build();
    }

    public RouteDecision decide(String userMessage) {
        try {
            String json = chatClient.prompt().user(userMessage).call().content();
            if (json == null) {
                return new RouteDecision(RouteDecision.Route.RAG, null, "router-null");
            }
            json = json.replaceAll("(?s)```json|```", "").trim();
            return mapper.readValue(json, RouteDecision.class);
        } catch (Exception e) {
            return new RouteDecision(RouteDecision.Route.RAG, null, "router-fallback: " + e.getMessage());
        }
    }
}