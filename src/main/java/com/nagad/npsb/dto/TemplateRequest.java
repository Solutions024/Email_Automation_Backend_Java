package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.regex.Pattern;

/** An email template (Email Templates page). */
@Schema(description = "An email template: subject and HTML body with {{placeholders}}.")
public record TemplateRequest(
        String name,
        @Schema(allowableValues = {"inward", "outward"}) String mailType,
        @Schema(allowableValues = {"initial", "reminder"}, defaultValue = "initial") String purpose,
        @Schema(defaultValue = "") String description,
        String subject,
        String bodyHtml,
        @Schema(defaultValue = "false") boolean isDefault) {

    private static final Pattern BODY_EMPTY = Pattern.compile("<[^>]*>|&nbsp;|\\s", Pattern.UNICODE_CHARACTER_CLASS);

    public static TemplateRequest read(Body b) {
        String name = b.check("name", b.str("name"), v -> {
            v = Py.squash(v);
            if (v.isEmpty()) throw new ValueError("Enter a template name");
            if (v.length() > 100) throw new ValueError("Template name is too long (max 100 characters)");
            return v;
        });
        String mailType = b.literal("mail_type", null, "inward", "outward");
        String purpose = b.literal("purpose", "initial", "initial", "reminder");
        String description = b.check("description", b.str("description", ""), v -> {
            v = Py.squash(v);
            if (v.length() > 255) throw new ValueError("Description is too long (max 255 characters)");
            return v;
        });
        String subject = b.check("subject", b.str("subject"), v -> {
            v = Py.squash(v);
            if (v.isEmpty()) throw new ValueError("Subject cannot be empty");
            if (v.length() > 300) throw new ValueError("Subject is too long (max 300 characters)");
            return v;
        });
        String bodyHtml = b.check("body_html", b.str("body_html"), v -> {
            if (BODY_EMPTY.matcher(v).replaceAll("").isEmpty()) throw new ValueError("The email body cannot be empty");
            return Py.strip(v);
        });
        boolean isDefault = b.bool("is_default", false);
        return new TemplateRequest(name, mailType, purpose, description, subject, bodyHtml, isDefault);
    }

    public static TemplateRequest from(Body b) {
        TemplateRequest data = read(b);
        b.done();
        return data;
    }

    public static TemplateRequest fromStored(Map<String, Object> after) {
        Body b = Body.ofStored(after);
        TemplateRequest data = read(b);
        b.doneOr400();
        return data;
    }

    public Map<String, Object> dump() {
        return Py.map("name", name, "mail_type", mailType, "purpose", purpose, "description", description,
                "subject", subject, "body_html", bodyHtml, "is_default", isDefault);
    }
}
