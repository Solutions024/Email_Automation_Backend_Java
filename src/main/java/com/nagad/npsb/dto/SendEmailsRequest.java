package com.nagad.npsb.dto;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The pending emails an admin ticked to send. */
@Schema(description = "Ids of the PENDING / FAILED emails to send, in this order.")
public record SendEmailsRequest(List<Integer> ids) {

    public static SendEmailsRequest from(Body b) {
        List<Integer> ids = b.intList("ids");
        b.done();
        return new SendEmailsRequest(ids);
    }
}
