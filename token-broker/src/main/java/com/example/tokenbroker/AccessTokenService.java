package com.example.tokenbroker;

import static com.example.tokenbroker.TokenController.TokenRequest;
import static com.example.tokenbroker.TokenController.TokenResponse;

interface AccessTokenService {
    TokenResponse mint(TokenRequest request);
}
