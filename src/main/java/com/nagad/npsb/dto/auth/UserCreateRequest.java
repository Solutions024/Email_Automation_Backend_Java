package com.nagad.npsb.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** POST /api/users: an account the built-in admin creates for someone. */
@Schema(description = "A new account. The person signs in with this password plus a code emailed to them.")
public record UserCreateRequest(
        @JsonProperty("full_name") @Schema(requiredMode = REQUIRED) String fullName,
        @Schema(requiredMode = REQUIRED) String username,
        @Schema(requiredMode = REQUIRED) String email,
        @Schema(requiredMode = REQUIRED) String password,
        @Schema(allowableValues = {"admin", "user"}, defaultValue = "user") String role,
        @JsonProperty("admin_code") @Schema(defaultValue = "", description = "Not needed (kept for older pages)") String adminCode) {

    public static UserCreateRequest from(Body b) {
        String fullName = b.check("full_name", b.str("full_name"), AccountFields::cleanFullName);
        String username = b.check("username", b.str("username"), AccountFields::cleanUsername);
        String email = b.check("email", b.str("email"), AccountFields::cleanEmail);
        String password = b.str("password");
        String role = b.literal("role", "user", "admin", "user");
        String adminCode = b.str("admin_code", "");  // not needed: an admin creates the account (kept for older pages)
        b.done();
        return new UserCreateRequest(fullName, username, email, password, role, adminCode);
    }
}
