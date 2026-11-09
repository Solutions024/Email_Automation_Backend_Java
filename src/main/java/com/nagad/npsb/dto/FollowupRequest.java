package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** A follow-up of a sent email, as edited before sending. */
@Schema(description = "A follow-up email: recipients, CC, subject and HTML body.")
public record FollowupRequest(List<String> to, @Schema(defaultValue = "[]") List<String> cc, String subject, String bodyHtml) {

    private static final Pattern FOLLOWUP_ADDRESS = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+", Pattern.UNICODE_CHARACTER_CLASS);

    static List<String> cleanAddresses(List<String> value) {
        List<String> cleaned = new ArrayList<>();
        for (String address : value) {
            address = Py.strip(address);
            if (address.isEmpty()) continue;
            if (!FOLLOWUP_ADDRESS.matcher(address).matches()) throw new ValueError("'" + address + "' is not a valid email address");
            boolean seen = false;
            for (String c : cleaned) if (c.toLowerCase(Locale.ROOT).equals(address.toLowerCase(Locale.ROOT))) seen = true;
            if (!seen) cleaned.add(address);
        }
        return cleaned;
    }

    public static FollowupRequest from(Body b) {
        List<String> to = b.check("to", b.strList("to", null), FollowupRequest::cleanAddresses);
        List<String> cc = b.check("cc", b.strList("cc", List.of()), FollowupRequest::cleanAddresses);
        String subject = b.check("subject", b.str("subject"), v -> {
            v = Py.strip(v);
            if (v.isEmpty()) throw new ValueError("Subject cannot be empty");
            if (v.length() > 300) throw new ValueError("Subject is too long (max 300 characters)");
            return v;
        });
        String bodyHtml = b.str("body_html");
        b.done();
        return new FollowupRequest(to, cc, subject, bodyHtml);
    }
}
