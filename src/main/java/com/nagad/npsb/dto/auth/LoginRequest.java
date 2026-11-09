package com.nagad.npsb.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** POST /api/auth/login: step 1 of sign-in. */
@Schema(description = "Step 1 of sign-in: username or email, and the password.")
public record LoginRequest(
        @Schema(requiredMode = REQUIRED, description = "Username or email address") String login,
        @Schema(requiredMode = REQUIRED) String password,
        @JsonProperty("remember_me") @Schema(defaultValue = "false") boolean rememberMe) {

    public static LoginRequest from(Body b) {
        String login = b.check("login", b.str("login"), AccountFields::normalizeLogin);
        String password = b.str("password");
        boolean rememberMe = b.bool("remember_me", false);
        b.done();
        return new LoginRequest(login, password, rememberMe);
    }
}
