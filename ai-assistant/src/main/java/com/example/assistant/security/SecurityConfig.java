package com.example.assistant.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Secures the assistant's own endpoints.
 *
 * <p>Before this existed, {@code /chat} was anonymous and the caller supplied its own
 * {@code tenantId} and {@code entitlementGroups} in the request body, so tenant isolation was
 * advisory. The caller must now present a broker-issued JWT, and the tenant and entitlements are
 * read from its verified claims.
 *
 * <p>{@code /chat} and {@code /rag/ingest} require distinct scopes. The service tokens the tools
 * mint for downstream API calls request only market-data scopes, so they cannot be replayed
 * against {@code /chat}.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/chat").hasAuthority("SCOPE_assistant:chat")
                        .requestMatchers(HttpMethod.POST, "/rag/ingest").hasAuthority("SCOPE_rag:ingest")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                }))
                .build();
    }

    /**
     * Local/demo profile: the mock token broker signs with a shared HS256 secret.
     * Under {@code prod} the decoder is auto-configured from
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} instead.
     */
    @Bean
    @Profile("!prod")
    JwtDecoder jwtDecoder(@Value("${security.jwt.secret}") String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}
