package com.nagad.npsb.dto.auth;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** POST /api/auth/resend-code: a new code for the sign-in (or sign-up) in progress. */
@Schema(description = "A new sign-in code for the sign-in (or sign-up) in progress.")
public record ResendCodeRequest(
        @Schema(requiredMode = REQUIRED, description = "Username or email address") String login,
        @Schema(defaultValue = "false", description = "true for the code of a new sign-up") boolean registration) {

    public static ResendCodeRequest from(Body b) {
        String login = b.check("login", b.str("login"), AccountFields::normalizeLogin);
        boolean registration = b.bool("registration", false);
        b.done();
        return new ResendCodeRequest(login, registration);
    }
}
