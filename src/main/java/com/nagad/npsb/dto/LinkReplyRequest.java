package com.nagad.npsb.dto;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

/** The sent email a partner message answers. */
@Schema(description = "The sent email this message answers.")
public record LinkReplyRequest(Integer emailId) {

    public static LinkReplyRequest from(Body b) {
        Integer emailId = b.integer("email_id");
        b.done();
        return new LinkReplyRequest(emailId);
    }
}
