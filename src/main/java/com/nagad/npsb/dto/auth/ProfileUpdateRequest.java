package com.nagad.npsb.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** PUT /api/auth/me: My Profile. */
@Schema(description = "My Profile: a new email address is confirmed with an emailed code first.")
public record ProfileUpdateRequest(
        @JsonProperty("full_name") @Schema(requiredMode = REQUIRED) String fullName,
        @Schema(requiredMode = REQUIRED) String email) {

    public static ProfileUpdateRequest from(Body b) {
        String fullName = b.check("full_name", b.str("full_name"), AccountFields::cleanFullName);
        String email = b.check("email", b.str("email"), AccountFields::cleanEmail);
        b.done();
        return new ProfileUpdateRequest(fullName, email);
    }
}
