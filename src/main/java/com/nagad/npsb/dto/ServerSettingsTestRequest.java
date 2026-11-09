package com.nagad.npsb.dto;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

/** Try the mail server, the mailbox or the Gmail account with unsaved settings. */
@Schema(description = "What to try (smtp, imap or gmail) with these unsaved values.")
public record ServerSettingsTestRequest(
        @Schema(allowableValues = {"smtp", "imap", "gmail"}) String target,
        @Schema(defaultValue = "{}") Map<String, Object> values) {

    public static ServerSettingsTestRequest from(Body b) {
        String target = b.literal("target", null, "smtp", "imap", "gmail");
        Map<String, Object> values = b.dict("values", new LinkedHashMap<>());
        b.done();
        return new ServerSettingsTestRequest(target, values);
    }
}
