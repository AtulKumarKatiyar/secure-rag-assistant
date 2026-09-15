package com.example.tokenbroker;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class TokenController {
    private final TokenService tokenService;

    TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/token")
    TokenResponse token(@RequestBody TokenRequest request) {
        return tokenService.mint(request);
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    static class InvalidCredentialsException extends RuntimeException {
        InvalidCredentialsException() {
            super("Invalid demo credentials");
        }
    }

    record TokenRequest(String username, String password, String employeeId, List<String> scopes) {
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, List<String> scopes) {
    }
}
