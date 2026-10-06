package com.example.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loads the full application context.
 *
 * <p>This is the only check that the security wiring actually resolves: the JWT decoder, the
 * filter chain, the request-scoped tenant/trace beans, the vector store and the three agents all
 * have to be constructible together. Ollama is not required to start; only ingestion needs it, and
 * that failure is handled.
 */
@SpringBootTest
class AiAssistantApplicationTests {

    @Test
    void contextLoads() {
    }
}
