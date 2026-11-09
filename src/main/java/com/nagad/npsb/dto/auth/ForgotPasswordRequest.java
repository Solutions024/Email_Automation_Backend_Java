package com.nagad.npsb.dto.auth;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** POST /api/auth/forgot-password: step 1 of a password reset. */
@Schema(description = "The account's email address; a 6-digit reset code is emailed to it.")
public record ForgotPasswordRequest(@Schema(requiredMode = REQUIRED) String email) {

    public static ForgotPasswordRequest from(Body b) {
        String email = b.check("email", b.str("email"), AccountFields::normalizeLogin);
        b.done();
        return new ForgotPasswordRequest(email);
    }
}
