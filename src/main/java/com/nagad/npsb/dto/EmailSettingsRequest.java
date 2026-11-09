package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The Email Configuration page. */
@Schema(description = "Follow-up days, automatic follow-ups and the CC addresses of new emails.")
public record EmailSettingsRequest(
        @Schema(minimum = "1", maximum = "90") Integer followupDueDays,
        Boolean autoFollowupEnabled,
        @Schema(minimum = "1", maximum = "20") Integer autoFollowupMax,
        List<String> ccEmails) {

    public static final Pattern EMAIL_RE = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+", Pattern.UNICODE_CHARACTER_CLASS);

    public static List<String> cleanList(List<String> value, String what) {
        List<String> cleaned = new ArrayList<>();
        for (String address : value) {
            address = Py.strip(address).toLowerCase(Locale.ROOT);
            if (address.isEmpty()) continue;
            if (!EMAIL_RE.matcher(address).matches()) throw new ValueError("'" + address + "' is not a valid email address");
            if (!cleaned.contains(address)) cleaned.add(address);
        }
        if (cleaned.size() > 20) throw new ValueError("At most 20 " + what + " addresses");
        return cleaned;
    }

    public static EmailSettingsRequest from(Body b) {
        Integer followupDueDays = b.check("followup_due_days", b.integer("followup_due_days"), v -> {
            if (v < 1 || v > 90) throw new ValueError("Follow-up days must be between 1 and 90");
            return v;
        });
        Boolean autoEnabled = b.bool("auto_followup_enabled");
        Integer autoMax = b.check("auto_followup_max", b.integer("auto_followup_max"), v -> {
            if (v < 1 || v > 20) throw new ValueError("Follow-ups per email must be between 1 and 20");
            return v;
        });
        List<String> cc = b.check("cc_emails", b.strList("cc_emails", null), v -> cleanList(v, "CC"));
        b.done();
        return new EmailSettingsRequest(followupDueDays, autoEnabled, autoMax, cc);
    }
}
