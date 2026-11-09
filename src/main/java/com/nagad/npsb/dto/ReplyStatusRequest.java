package com.nagad.npsb.dto;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

/** Whether the partner replied to a sent email. */
@Schema(description = "The reply status of a sent email, with an optional note for its history.")
public record ReplyStatusRequest(
        @Schema(allowableValues = {"not_received", "received", "reconciled"}) String status,
        @Schema(defaultValue = "") String note) {

    public static ReplyStatusRequest from(Body b) {
        String status = b.literal("status", null, "not_received", "received", "reconciled");
        String note = b.str("note", "");
        b.done();
        return new ReplyStatusRequest(status, note);
    }
}
