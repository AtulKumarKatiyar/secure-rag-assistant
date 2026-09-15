package com.example.eval;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

public class EvaluationHarnessApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(EvaluationHarnessApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)
                .getBean(EvaluationRunner.class)
                .run();
    }

    @Bean
    RestClient assistantClient() {
        return RestClient.builder().baseUrl("http://localhost:8080").build();
    }

    @Bean
    EvaluationRunner evaluationRunner(RestClient assistantClient) {
        return new EvaluationRunner(assistantClient);
    }
}
