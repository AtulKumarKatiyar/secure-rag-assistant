package com.example.tokenbroker;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static com.example.tokenbroker.TokenController.InvalidCredentialsException;
import static com.example.tokenbroker.TokenController.TokenRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The assistant reads the caller's tenant and entitlements from the verified token, so the claims
 * this broker emits are the root of tenant isolation.
 */
class TokenServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-32";

    /**
     * Must be the real current time: the decoder validates {@code exp} against the system clock and
     * tokens only live for 120 seconds.
     */
    private static final Instant NOW = Instant.now();

    private final TokenService service = new TokenService(
            new TokenBrokerApplication.JwtSettings(SECRET, "mock-token-broker"),
            encoder(SECRET),
            () -> NOW);

    private static JwtEncoder encoder(String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Test
    void emitsTenantAndEntitlementClaims() {
        var token = service.mint(new TokenRequest("alice", "password", "1001", List.of("assistant:chat")));

        var claims = decode(token.accessToken());

        assertThat(claims.getClaimAsString("tenant_id")).isEqualTo("clientA");
        assertThat(claims.getClaimAsStringList("entitlement_groups")).containsExactly("premium-research");
    }

    @Test
    void differentUsersCarryDifferentTenants() {
        var token = service.mint(new TokenRequest("ben", "password", "1002", List.of("assistant:chat")));

        var claims = decode(token.accessToken());

        assertThat(claims.getClaimAsString("tenant_id")).isEqualTo("clientB");
        assertThat(claims.getClaimAsStringList("entitlement_groups")).isEmpty();
    }

    @Test
    void neverGrantsAScopeTheUserDoesNotHave() {
        // ben is not entitled to ingest.
        var token = service.mint(new TokenRequest("ben", "password", "1002", List.of("rag:ingest", "assistant:chat")));

        assertThat(token.scopes()).containsExactly("assistant:chat");
    }

    @Test
    void serviceTokensCannotBeReplayedAgainstChat() {
        // The assistant's tools mint market-data scopes only, never assistant:chat.
        var token = service.mint(new TokenRequest("alice", "password", "1001",
                List.of("stock-news:read", "index-data:read", "commodity-data:read")));

        assertThat(token.scopes())
                .containsExactlyInAnyOrder("stock-news:read", "index-data:read", "commodity-data:read")
                .doesNotContain("assistant:chat");
    }

    @Test
    void rejectsBadCredentials() {
        assertThatThrownBy(() -> service.mint(new TokenRequest("alice", "wrong", "1001", List.of())))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsAMismatchedEmployeeId() {
        assertThatThrownBy(() -> service.mint(new TokenRequest("alice", "password", "9999", List.of())))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    private static Jwt decode(String token) {
        var key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build().decode(token);
    }
}
