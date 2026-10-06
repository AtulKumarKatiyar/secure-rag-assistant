package com.example.tokenbroker;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.example.tokenbroker.TokenController.InvalidCredentialsException;
import static com.example.tokenbroker.TokenController.TokenRequest;
import static com.example.tokenbroker.TokenController.TokenResponse;

/**
 * Local demo broker.
 *
 * <p>Tokens now carry {@code tenant_id} and {@code entitlement_groups} because the assistant reads
 * the caller's tenant and entitlements from the verified token. Previously those values travelled
 * in the {@code /chat} request body, which meant any caller could claim any tenant.
 *
 * <p>The granted scope set remains an intersection of what was requested and what the user is
 * allowed, so requesting a scope never grants it.
 */
class TokenService implements AccessTokenService {
    private static final long TTL_SECONDS = 120;

    private final TokenBrokerApplication.JwtSettings settings;
    private final JwtEncoder encoder;
    private final Supplier<Instant> now;
    private final Map<String, DemoUser> users = Map.of(
            "alice", new DemoUser("password", "1001", "clientA", Set.of("premium-research"),
                    Set.of("profile:read", "assistant:chat", "rag:ingest",
                            "stock-news:read", "index-data:read", "commodity-data:read")),
            "ben", new DemoUser("password", "1002", "clientB", Set.of(),
                    Set.of("profile:read", "assistant:chat",
                            "stock-news:read", "index-data:read", "commodity-data:read")));

    TokenService(TokenBrokerApplication.JwtSettings settings, JwtEncoder encoder, Supplier<Instant> now) {
        this.settings = settings;
        this.encoder = encoder;
        this.now = now;
    }

    @Override
    public TokenResponse mint(TokenRequest request) {
        var user = users.get(request.username());
        if (user == null || !user.password().equals(request.password()) || !user.employeeId().equals(request.employeeId())) {
            throw new InvalidCredentialsException();
        }

        var requestedScopes = request.scopes() == null ? List.<String>of() : request.scopes();
        var grantedScopes = requestedScopes.stream()
                .filter(user.allowedScopes()::contains)
                .distinct()
                .toList();

        var issuedAt = now.get();
        var claims = JwtClaimsSet.builder()
                .issuer(settings.issuer())
                .subject(request.username())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(TTL_SECONDS))
                .claim("employee_id", request.employeeId())
                .claim("tenant_id", user.tenantId())
                .claim("entitlement_groups", List.copyOf(user.entitlementGroups()))
                .claim("scope", String.join(" ", grantedScopes))
                .build();

        var token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims));

        return new TokenResponse(token.getTokenValue(), "Bearer", TTL_SECONDS, grantedScopes);
    }

    record DemoUser(String password,
                    String employeeId,
                    String tenantId,
                    Set<String> entitlementGroups,
                    Set<String> allowedScopes) {
    }
}
