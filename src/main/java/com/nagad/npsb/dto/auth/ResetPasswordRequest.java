package com.nagad.npsb.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** POST /api/auth/reset-password: step 2 of a password reset. */
@Schema(description = "The email address, the emailed 6-digit code and the new password.")
public record ResetPasswordRequest(
        @Schema(requiredMode = REQUIRED) String email,
        @Schema(requiredMode = REQUIRED) String code,
        @JsonProperty("new_password") @Schema(requiredMode = REQUIRED) String newPassword) {

    public static ResetPasswordRequest from(Body b) {
        String email = b.check("email", b.str("email"), AccountFields::normalizeLogin);
        String code = b.str("code");
        String newPassword = b.str("new_password");
        b.done();
        return new ResetPasswordRequest(email, code, newPassword);
    }
}
