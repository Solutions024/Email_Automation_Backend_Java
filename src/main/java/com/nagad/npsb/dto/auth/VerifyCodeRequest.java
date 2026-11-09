package com.nagad.npsb.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** POST /api/auth/verify-code: step 2 of sign-in (or of a sign-up). */
@Schema(description = "Step 2 of sign-in: the 6-digit code that was emailed.")
public record VerifyCodeRequest(
        @Schema(requiredMode = REQUIRED, description = "Username or email address") String login,
        @Schema(requiredMode = REQUIRED) String code,
        @JsonProperty("remember_me") @Schema(defaultValue = "false") boolean rememberMe,
        @Schema(defaultValue = "false", description = "true for the code of a new sign-up") boolean registration) {

    public static VerifyCodeRequest from(Body b) {
        String login = b.check("login", b.str("login"), AccountFields::normalizeLogin);
        String code = b.str("code");
        boolean rememberMe = b.bool("remember_me", false);
        boolean registration = b.bool("registration", false);
        b.done();
        return new VerifyCodeRequest(login, code, rememberMe, registration);
    }
}
