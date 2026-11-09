package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

/** Where to send the test email. */
@Schema(description = "The address the test email is sent to.")
public record TestEmailRequest(String to) {

    public static TestEmailRequest from(Body b) {
        String to = b.check("to", b.str("to"), v -> {
            v = Py.strip(v);
            if (!EmailSettingsRequest.EMAIL_RE.matcher(v).matches()) throw new ValueError("'" + v + "' is not a valid email address");
            return v;
        });
        b.done();
        return new TestEmailRequest(to);
    }
}
