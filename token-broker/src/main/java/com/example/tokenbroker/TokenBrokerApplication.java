package com.example.tokenbroker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@SpringBootApplication
@EnableConfigurationProperties(TokenBrokerApplication.JwtSettings.class)
public class TokenBrokerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TokenBrokerApplication.class, args);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtSettings settings) {
        var key = new SecretKeySpec(settings.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
    }

    @Bean
    TokenService tokenService(JwtSettings settings, JwtEncoder encoder) {
        return new TokenService(settings, encoder, Instant::now);
    }

    @ConfigurationProperties(prefix = "security.jwt")
    public record JwtSettings(String secret, String issuer) {
    }
}
