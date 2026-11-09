package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

/** An admin's note on a sent email. */
@Schema(description = "A note for the history of a sent email (at most 1000 characters).")
public record EmailNoteRequest(String note) {

    public static EmailNoteRequest from(Body b) {
        String note = b.check("note", b.str("note"), v -> {
            v = Py.strip(v);
            if (v.isEmpty()) throw new ValueError("Write a note first");
            if (v.length() > 1000) throw new ValueError("A note can have at most 1000 characters");
            return v;
        });
        b.done();
        return new EmailNoteRequest(note);
    }
}
