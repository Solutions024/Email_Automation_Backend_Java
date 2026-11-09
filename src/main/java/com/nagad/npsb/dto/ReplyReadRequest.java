package com.nagad.npsb.dto;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

/** Mark a partner message as read or as new again. */
@Schema(description = "read: true marks the message as read, false as new again (for this user only).")
public record ReplyReadRequest(Boolean read) {

    public static ReplyReadRequest from(Body b) {
        Boolean read = b.bool("read");
        b.done();
        return new ReplyReadRequest(read);
    }
}
