package com.example.assistant.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class EmployeeTools {
    private final RestClient tokenBrokerClient;
    private final RestClient ecBackendClient;
    private final String demoUser;
    private final String demoPassword;

    EmployeeTools(@Qualifier("tokenBrokerClient") RestClient tokenBrokerClient,
                  @Qualifier("ecBackendClient") RestClient ecBackendClient,
                  @Value("${assistant.demo-user}") String demoUser,
                  @Value("${assistant.demo-password}") String demoPassword) {
        this.tokenBrokerClient = tokenBrokerClient;
        this.ecBackendClient = ecBackendClient;
        this.demoUser = demoUser;
        this.demoPassword = demoPassword;
    }

    @Tool(description = "Read an employee's remaining leave balances from the secured EC backend.")
    public LeaveBalance getLeaveBalance(String employeeId) {
        var token = scopedToken(employeeId, List.of("leave:read"));
        return ecBackendClient.get()
                .uri("/employee/{id}/leave-balance", employeeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                .retrieve()
                .body(LeaveBalance.class);
    }

    @Tool(description = "Read an employee's public profile fields from the secured EC backend.")
    public EmployeeProfile getProfile(String employeeId) {
        var token = scopedToken(employeeId, List.of("profile:read"));
        return ecBackendClient.get()
                .uri("/employee/{id}/profile", employeeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                .retrieve()
                .body(EmployeeProfile.class);
    }

    private TokenResponse scopedToken(String employeeId, List<String> scopes) {
        return tokenBrokerClient.post()
                .uri("/token")
                .body(new TokenRequest(demoUser, demoPassword, employeeId, scopes))
                .retrieve()
                .body(TokenResponse.class);
    }

    record TokenRequest(String username, String password, String employeeId, List<String> scopes) {
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, List<String> scopes) {
    }

    public record LeaveBalance(String employeeId, int annualLeaveDaysLeft, int sickLeaveDaysLeft, int personalDaysLeft) {
    }

    public record EmployeeProfile(String employeeId, String name, String department, String title) {
    }
}
