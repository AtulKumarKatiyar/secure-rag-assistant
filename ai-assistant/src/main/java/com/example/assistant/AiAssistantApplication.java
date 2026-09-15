package com.example.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AiAssistantApplication.ServiceSettings.class)
public class AiAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }

    @Bean
    @Qualifier("tokenBrokerClient")
    RestClient tokenBrokerClient(ServiceSettings settings) {
        return RestClient.builder().baseUrl(settings.tokenBrokerUrl()).build();
    }

    @Bean
    @Qualifier("ecBackendClient")
    RestClient ecBackendClient(ServiceSettings settings) {
        return RestClient.builder().baseUrl(settings.ecBackendUrl()).build();
    }

    @ConfigurationProperties(prefix = "services")
    public record ServiceSettings(String tokenBrokerUrl, String ecBackendUrl) {
    }
}
