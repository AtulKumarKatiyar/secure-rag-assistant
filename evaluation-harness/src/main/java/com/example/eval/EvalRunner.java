package com.example.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class EvalRunner {

    public static void main(String[] args) {
        SpringApplication.run(EvalRunner.class, args).close();
    }

    @Bean
    ApplicationRunner run() {
        return args -> {
            var mapper = new ObjectMapper(new YAMLFactory());
            var cases = mapper.readTree(new ClassPathResource("cases.yml").getInputStream());
            var chat = RestClient.create("http://localhost:8080");
            var results = new ArrayList<String>();

            int pass = 0;
            int total = cases.get("cases").size();
            for (JsonNode c : cases.get("cases")) {
                var payload = Map.of("employeeId", "1001", "message", c.get("q").asText());
                try {
                    var raw = chat.post().uri("/chat")
                        .body(payload)
                        .retrieve()
                        .body(String.class);
                    var resp = mapper.readTree(raw);
                    boolean ok = assertCase(c, resp);
                    results.add((ok ? "PASS" : "FAIL") + "  " + c.get("q").asText());
                    if (ok) pass++;
                } catch (Exception e) {
                    results.add("ERROR " + c.get("q").asText() + " -> " + e.getMessage());
                }
            }

            System.out.println("\n===== Evaluation Report =====");
            results.forEach(System.out::println);
            System.out.printf("Pass rate: %d/%d (%d%%)%n", pass, total, total == 0 ? 0 : (pass * 100) / total);
        };
    }

    private boolean assertCase(JsonNode c, JsonNode resp) {
        if (c.has("expectRoute")) {
            var route = resp.path("route").asText("");
            if (!c.get("expectRoute").asText().equalsIgnoreCase(route)) return false;
        }
        if (c.has("expectTicker")) {
            if (!resp.path("toolResults").toString().contains(c.get("expectTicker").asText())) return false;
        }
        if (c.has("forbidEmployeeId")) {
            if (resp.toString().contains(c.get("forbidEmployeeId").asText())) return false;
        }
        return true;
    }
}