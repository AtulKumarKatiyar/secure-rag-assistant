package com.example.assistant.tools;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class TokenBrokerClient {

    private final RestClient broker;
    private final String demoUser;
    private final String demoPassword;
    private final String demoEmployeeId;

    public TokenBrokerClient(@Qualifier("tokenBrokerRestClient") RestClient broker,
                             @Value("${assistant.demo-user}") String demoUser,
                             @Value("${assistant.demo-password}") String demoPassword,
                             @Value("${assistant.demo-employee-id:1001}") String demoEmployeeId) {
        this.broker = broker;
        this.demoUser = demoUser;
        this.demoPassword = demoPassword;
        this.demoEmployeeId = demoEmployeeId;
    }

    public TokenResponse mint(List<String> scopes) {
        return broker.post()
            .uri("/token")
            .body(new TokenRequest(demoUser, demoPassword, demoEmployeeId, scopes))
            .retrieve()
            .body(TokenResponse.class);
    }

    public record TokenRequest(String username, String password, String employeeId, List<String> scopes) {}
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, List<String> scopes) {}
}
