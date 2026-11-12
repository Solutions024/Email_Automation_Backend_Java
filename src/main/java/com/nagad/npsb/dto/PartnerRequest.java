package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** A partner and its recipient addresses (Partner Configuration page). */
@Schema(description = "A partner: its NPSB_ORG_CODE (the key: 103 and 0103 are the same code), its name and its email addresses.")
public record PartnerRequest(
        @Schema(example = "103", description = "NPSB_ORG_CODE: digits; leading zeros don't matter. Required for a new partner; "
                + "left out on an update: the partner keeps its code.") String orgCode,
        @Schema(example = "bKash") String name,
        @Schema(defaultValue = "true") boolean isActive,
        List<PartnerEmailRequest> emails) {

    private static final Pattern PARTNER_EMAIL_RE = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** One address: switched on or off; clear_problem removes the "refused" flag of an unchanged address. */
    @Schema(description = "One recipient address of a partner.")
    public record PartnerEmailRequest(
            String email,
            @Schema(defaultValue = "true") boolean isActive,
            @Schema(defaultValue = "false") boolean clearProblem) {}

    /** The fields as read from a body (problems are collected in b). */
    public static PartnerRequest read(Body b) {
        // a number (103) or text ("0103"); checked by PartnerService (400 with a clear message)
        Object rawCode = b.data().get("org_code");
        String orgCode = rawCode instanceof Number n ? String.valueOf(n.longValue()) : b.optStr("org_code");
        String name = b.str("name");
        boolean isActive = b.bool("is_active", true);
        List<PartnerEmailRequest> emails = new ArrayList<>();
        for (Body e : b.objects("emails", false)) {
            String email = e.check("email", e.str("email"), v -> {
                v = Py.strip(v);
                if (!PARTNER_EMAIL_RE.matcher(v).matches()) throw new ValueError("'" + v + "' is not a valid email address");
                return v.toLowerCase(Locale.ROOT);
            });
            boolean active = e.bool("is_active", true);
            boolean clear = e.bool("clear_problem", false);
            emails.add(new PartnerEmailRequest(email, active, clear));
        }
        return new PartnerRequest(orgCode, name, isActive, emails);
    }

    /** The request body (422 with every problem). */
    public static PartnerRequest from(Body b) {
        PartnerRequest data = read(b);
        b.done();
        return data;
    }

    /** A change stored for approval, checked again (400). */
    public static PartnerRequest fromStored(Map<String, Object> after) {
        Body b = Body.ofStored(after);
        PartnerRequest data = read(b);
        b.doneOr400();
        return data;
    }

    /** As stored in the change request. */
    public Map<String, Object> dump() {
        List<Object> list = new ArrayList<>();
        for (PartnerEmailRequest e : emails) {
            list.add(Py.map("email", e.email(), "is_active", e.isActive(), "clear_problem", e.clearProblem()));
        }
        return Py.map("org_code", orgCode, "name", name, "is_active", isActive, "emails", list);
    }
}
