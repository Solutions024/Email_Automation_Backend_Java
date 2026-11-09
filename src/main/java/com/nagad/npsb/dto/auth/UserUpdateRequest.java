package com.nagad.npsb.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

/** PATCH /api/users/{user_id}: unlock, role, activation, or a new password set by the admin. */
@Schema(description = "Changes to an account; fields left out stay as they are.")
public record UserUpdateRequest(
        @Schema(defaultValue = "false") boolean unlock,
        @Schema(allowableValues = {"admin", "user"}, nullable = true) String role,
        @JsonProperty("is_active") @Schema(nullable = true) Boolean isActive,
        @Schema(nullable = true, description = "A new password for someone else") String password) {

    public static UserUpdateRequest from(Body b) {
        boolean unlock = b.bool("unlock", false);
        String role = b.optLiteral("role", "admin", "user");
        Boolean isActive = b.optBool("is_active");
        String password = b.str("password", null);  // an admin sets a new password for someone else
        b.done();
        return new UserUpdateRequest(unlock, role, isActive, password);
    }
}
