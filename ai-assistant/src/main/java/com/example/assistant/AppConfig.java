package com.example.assistant;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Duration;

@Configuration
public class AppConfig {

    /**
     * Named {@code tokenBrokerRestClient} rather than {@code tokenBrokerClient}: the latter is the
     * bean name Spring derives for the {@link com.example.assistant.tools.TokenBrokerClient}
     * component, and two definitions of one bean name abort startup with
     * {@code BeanDefinitionOverrideException}.
     */
    @Bean
    @Qualifier("tokenBrokerRestClient")
    RestClient tokenBrokerRestClient(@Value("${services.token-broker-url}") String baseUrl,
                                     @Value("${services.connect-timeout-ms:1000}") int connectTimeoutMs,
                                     @Value("${services.read-timeout-ms:3000}") int readTimeoutMs) {
        return restClient(baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    @Bean
    @Qualifier("stockApiRestClient")
    RestClient stockApiRestClient(@Value("${services.stock-api-url}") String baseUrl,
                                  @Value("${services.connect-timeout-ms:1000}") int connectTimeoutMs,
                                  @Value("${services.read-timeout-ms:3000}") int readTimeoutMs) {
        return restClient(baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean(name = "supervisorClient")
    ChatClient supervisorClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                You are the supervisor router for a financial multi-agent assistant.

                Decide which specialist agents must be invoked for the user query.
                Use only the supplied agent catalogue. Never invent agent names.

                Routing rules:
                - Choose the smallest set of agents that can fully answer the query.
                - Select multiple agents for cross-domain questions, comparisons, "vs",
                  "and", impact analysis, correlation, hedging, or portfolio questions.
                - Route company names, ticker symbols, listed businesses, equity prices,
                  earnings, filings, analyst views and buy/sell-style questions to equity-agent.
                - Route index levels, constituents, rebalancing, benchmark and sector-weight
                  questions to index-agent.
                - Route oil, gold, silver, natural gas, copper and macro commodity-driver
                  questions to commodity-agent.
                - If the query is truly unclear, return an empty agents list and
                  needsClarification=true.

                Return ONLY valid JSON, no markdown, no prose:
                {
                  "agents": ["equity-agent"],
                  "needsClarification": false,
                  "confidence": 0.92,
                  "reason": "short routing reason"
                }
                """)
                .build();
    }

    @Bean(name = "agentExecutor")
    ThreadPoolTaskExecutor agentExecutor(@Value("${assistant.agent-execution.core-pool-size:4}") int corePoolSize,
                                         @Value("${assistant.agent-execution.max-pool-size:8}") int maxPoolSize,
                                         @Value("${assistant.agent-execution.queue-capacity:50}") int queueCapacity) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-worker-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setTaskDecorator(requestContextTaskDecorator());
        executor.initialize();
        return executor;
    }

    private TaskDecorator requestContextTaskDecorator() {
        return runnable -> {
            var requestAttributes = RequestContextHolder.getRequestAttributes();
            return () -> {
                try {
                    RequestContextHolder.setRequestAttributes(requestAttributes);
                    runnable.run();
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            };
        };
    }

    private RestClient restClient(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
