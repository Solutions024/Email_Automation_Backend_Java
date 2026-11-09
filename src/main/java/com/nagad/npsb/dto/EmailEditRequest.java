package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

/** A manual edit of a prepared email. */
@Schema(description = "A manual edit of an email: subject and HTML body.")
public record EmailEditRequest(String subject, String bodyHtml) {

    public static EmailEditRequest from(Body b) {
        String subject = b.check("subject", b.str("subject"), v -> {
            v = Py.strip(v);
            if (v.isEmpty()) throw new ValueError("Subject cannot be empty");
            if (v.length() > 300) throw new ValueError("Subject is too long (max 300 characters)");
            return v;
        });
        String bodyHtml = b.str("body_html");
        b.done();
        return new EmailEditRequest(subject, bodyHtml);
    }
}
