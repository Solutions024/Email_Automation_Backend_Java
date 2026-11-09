package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The answer form: {to, cc, subject, text}. */
@Schema(description = "An answer to a partner's reply: recipients, CC, subject and the text (plain text, paragraphs).")
public record AnswerRequest(List<String> to, @Schema(defaultValue = "[]") List<String> cc, String subject, String text) {

    private static final Pattern ADDRESS_RE = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+", Pattern.UNICODE_CHARACTER_CLASS);

    private static List<String> checkAddresses(List<String> value) {
        List<String> cleaned = new ArrayList<>();
        for (String address : value) {
            address = Py.strip(address);
            if (address.isEmpty()) continue;
            if (!ADDRESS_RE.matcher(address).matches()) throw new ValueError("'" + address + "' is not a valid email address");
            boolean seen = false;
            for (String c : cleaned) if (c.equalsIgnoreCase(address)) seen = true;
            if (!seen) cleaned.add(address);
        }
        if (cleaned.size() > 20) throw new ValueError("At most 20 addresses");
        return cleaned;
    }

    public static AnswerRequest from(Body b) {
        List<String> to = b.check("to", b.strList("to", null), v -> {
            List<String> cleaned = checkAddresses(v);
            if (cleaned.isEmpty()) throw new ValueError("Add at least one recipient");
            return cleaned;
        });
        List<String> cc = b.check("cc", b.strList("cc", List.of()), AnswerRequest::checkAddresses);
        String subject = b.check("subject", b.str("subject"), v -> {
            v = Py.strip(v);
            if (v.isEmpty()) throw new ValueError("Subject cannot be empty");
            if (v.length() > 300) throw new ValueError("Subject is too long (max 300 characters)");
            return v;
        });
        String text = b.check("text", b.str("text"), v -> {
            if (Py.strip(v).isEmpty()) throw new ValueError("Write your answer first");
            if (v.length() > 20000) throw new ValueError("The answer is too long (max 20000 characters)");
            return Py.strip(v);
        });
        b.done();
        return new AnswerRequest(to, cc, subject, text);
    }
}
