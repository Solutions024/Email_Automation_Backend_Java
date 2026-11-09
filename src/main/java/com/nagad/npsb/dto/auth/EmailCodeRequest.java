package com.nagad.npsb.dto.auth;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** POST /api/auth/me/confirm-email: the code emailed to the new address. */
@Schema(description = "The 6-digit code emailed to the new address.")
public record EmailCodeRequest(@Schema(requiredMode = REQUIRED) String code) {

    public static EmailCodeRequest from(Body b) {
        String code = b.str("code");
        b.done();
        return new EmailCodeRequest(code);
    }
}
