package com.nagad.npsb.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** POST /api/auth/change-password */
@Schema(description = "The current password and the new one.")
public record ChangePasswordRequest(
        @JsonProperty("current_password") @Schema(requiredMode = REQUIRED) String currentPassword,
        @JsonProperty("new_password") @Schema(requiredMode = REQUIRED) String newPassword) {

    public static ChangePasswordRequest from(Body b) {
        String current = b.str("current_password");
        String newPassword = b.str("new_password");
        b.done();
        return new ChangePasswordRequest(current, newPassword);
    }
}
