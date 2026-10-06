package com.example.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the reuseable chat evaluation cases against a locally running stack.
 *
 * <p>Requires the token broker and the assistant to be up, plus a reachable chat/embedding model.
 * It authenticates as a real user first, because {@code /chat} now rejects anonymous callers.
 *
 * <pre>
 * mvn -pl token-broker spring-boot:run
 * mvn -pl mock-stock-api spring-boot:run
 * mvn -pl ai-assistant spring-boot:run
 * mvn -pl evaluation-harness spring-boot:run
 * </pre>
 */
@SpringBootApplication
public class EvalRunner {

    public static void main(String[] args) {
        SpringApplication.run(EvalRunner.class, args).close();
    }

    @Bean
    ApplicationRunner run(@Value("${eval.assistant-url:http://localhost:8080}") String assistantUrl,
                          @Value("${eval.broker-url:http://localhost:8082}") String brokerUrl,
                          @Value("${eval.username:alice}") String username,
                          @Value("${eval.password:password}") String password,
                          @Value("${eval.employee-id:1001}") String employeeId) {
        return args -> {
            var mapper = new ObjectMapper(new YAMLFactory());
            var cases = mapper.readTree(new ClassPathResource("cases.yml").getInputStream()).path("cases");

            var chat = RestClient.builder()
                    .baseUrl(assistantUrl)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + authenticate(brokerUrl, username, password, employeeId))
                    .build();

            var results = new ArrayList<String>();
            var pass = 0;
            var total = cases.size();

            for (JsonNode goldenCase : cases) {
                var question = goldenCase.get("q").asText();
                try {
                    var raw = chat.post()
                            .uri("/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            // Only the message is sent. Tenant and entitlements come from the token.
                            .body(Map.of("message", question))
                            .retrieve()
                            .body(String.class);
                    var response = mapper.readTree(raw);
                    var verdict = CaseAssertions.evaluate(goldenCase, response);
                    results.add((verdict.passed() ? "PASS" : "FAIL") + "  " + question
                            + (verdict.passed() ? "" : "\n      -> " + verdict.reason()));
                    if (verdict.passed()) {
                        pass++;
                    }
                } catch (Exception e) {
                    results.add("ERROR " + question + " -> " + e.getMessage());
                }
            }

            System.out.println("\n===== Evaluation Report =====");
            results.forEach(System.out::println);
            System.out.printf("Pass rate: %d/%d (%d%%)%n", pass, total, total == 0 ? 0 : (pass * 100) / total);

            if (pass < total) {
                throw new IllegalStateException("Evaluation failed: " + pass + "/" + total + " cases passed");
            }
        };
    }

    /**
     * Exchanges demo credentials for a broker-issued JWT. A failure here is reported as such rather
     * than surfacing later as a confusing 401 on every case.
     */
    private static String authenticate(String brokerUrl, String username, String password, String employeeId) {
        try {
            var response = RestClient.create(brokerUrl).post()
                    .uri("/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", username,
                            "password", password,
                            "employeeId", employeeId,
                            "scopes", List.of("assistant:chat")))
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new IllegalStateException("Token broker returned no access token");
            }
            return response.accessToken();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not authenticate against the token broker at " + brokerUrl
                            + ". Is token-broker running on 8082? Cause: " + e.getMessage(), e);
        }
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, List<String> scopes) {
    }
}
