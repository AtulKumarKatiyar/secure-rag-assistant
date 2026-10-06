package com.example.tokenbroker;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;

import static com.example.tokenbroker.TokenController.TokenRequest;
import static com.example.tokenbroker.TokenController.TokenResponse;

class KeycloakTokenService implements AccessTokenService {
    private final TokenBrokerApplication.KeycloakSettings settings;
    private final RestClient keycloakClient;

    KeycloakTokenService(TokenBrokerApplication.KeycloakSettings settings, RestClient keycloakClient) {
        this.settings = settings;
        this.keycloakClient = keycloakClient;
    }

    @Override
    public TokenResponse mint(TokenRequest request) {
        var requestedScopes = request.scopes() == null ? List.<String>of() : request.scopes();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", settings.clientId());
        form.add("client_secret", settings.clientSecret());
        form.add("scope", String.join(" ", requestedScopes));

        var response = keycloakClient.post()
                .uri(settings.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KeycloakTokenResponse.class);

        if (response == null) {
            throw new IllegalStateException("Keycloak returned an empty token response");
        }

        return new TokenResponse(
                response.accessToken(),
                response.tokenType() == null ? "Bearer" : response.tokenType(),
                response.expiresIn(),
                splitScopes(response.scope()));
    }

    private static List<String> splitScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scope.split("\\s+"))
                .filter(value -> !value.isBlank())
                .toList();
    }

    record KeycloakTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            String scope) {
    }
}
