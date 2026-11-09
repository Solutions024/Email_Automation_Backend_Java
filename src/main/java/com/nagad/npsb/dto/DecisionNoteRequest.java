package com.nagad.npsb.dto;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

/** The reason given when an admin rejects a change or an answer. */
@Schema(description = "Why it was rejected (optional).")
public record DecisionNoteRequest(@Schema(defaultValue = "") String note) {

    public static DecisionNoteRequest from(Body b) {
        String note = b.str("note", "");
        b.done();
        return new DecisionNoteRequest(note);
    }
}
