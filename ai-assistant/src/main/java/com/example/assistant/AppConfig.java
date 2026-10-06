package com.example.assistant;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AppConfig {

    @Bean
    @Qualifier("tokenBrokerClient")
    RestClient tokenBrokerClient(@Value("${services.token-broker-url}") String baseUrl,
                                 @Value("${services.connect-timeout-ms:1000}") int connectTimeoutMs,
                                 @Value("${services.read-timeout-ms:3000}") int readTimeoutMs) {
        return restClient(baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    @Bean
    @Qualifier("stockApiClient")
    RestClient stockApiClient(@Value("${services.stock-api-url}") String baseUrl,
                              @Value("${services.connect-timeout-ms:1000}") int connectTimeoutMs,
                              @Value("${services.read-timeout-ms:3000}") int readTimeoutMs) {
        return restClient(baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
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
