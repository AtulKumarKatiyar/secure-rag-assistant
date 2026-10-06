package com.example.assistant.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Retrieval tuning. Extracted from the tool methods so the values are configurable and the
 * tools stay unit-testable without a Spring context.
 */
@Component
public class RagProperties {

    private final int topK;
    private final double minScore;
    private final long maxAgeHours;

    public RagProperties(@Value("${assistant.rag.top-k:5}") int topK,
                         @Value("${assistant.rag.min-score:0.75}") double minScore,
                         @Value("${assistant.rag.max-age-hours:720}") long maxAgeHours) {
        this.topK = topK;
        this.minScore = minScore;
        this.maxAgeHours = maxAgeHours;
    }

    public int topK() {
        return topK;
    }

    public double minScore() {
        return minScore;
    }

    public long maxAgeHours() {
        return maxAgeHours;
    }
}
