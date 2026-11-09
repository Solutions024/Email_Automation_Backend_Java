package com.nagad.npsb.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** POST /api/auth/register: a self sign-up (closed: the built-in admin creates every account). */
@Schema(description = "A self sign-up. The account is created when the emailed code is entered.")
public record RegisterRequest(
        @JsonProperty("full_name") @Schema(requiredMode = REQUIRED) String fullName,
        @Schema(requiredMode = REQUIRED) String username,
        @Schema(requiredMode = REQUIRED) String email,
        @Schema(requiredMode = REQUIRED) String password,
        @Schema(allowableValues = {"admin", "user"}, defaultValue = "user") String role,
        @JsonProperty("admin_code") @Schema(defaultValue = "") String adminCode) {

    public static RegisterRequest from(Body b) {
        String fullName = b.check("full_name", b.str("full_name"), AccountFields::cleanFullName);
        String username = b.check("username", b.str("username"), AccountFields::cleanUsername);
        String email = b.check("email", b.str("email"), AccountFields::cleanEmail);
        String password = b.str("password");
        String role = b.literal("role", "user", "admin", "user");
        String adminCode = b.str("admin_code", "");
        b.done();
        return new RegisterRequest(fullName, username, email, password, role, adminCode);
    }
}
