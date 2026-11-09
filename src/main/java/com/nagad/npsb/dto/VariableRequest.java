package com.nagad.npsb.dto;

import com.nagad.npsb.service.EmailService;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/** A template variable (a {{placeholder}} declared on the Email Templates page). */
@Schema(description = "A template variable: a fixed text, or a value from the email's transactions (column + aggregate).")
public record VariableRequest(
        @Schema(example = "rrn_list") String name,
        @Schema(defaultValue = "") String description,
        @Schema(allowableValues = {"text", "column"}) String source,
        @Schema(defaultValue = "") String value,
        String column,
        String aggregate) {

    public static VariableRequest read(Body b) {
        String name = b.check("name", b.str("name"), v -> {
            v = Py.strip(Py.strip(Py.strip(v), "{}"));
            if (!EmailService.VARIABLE_NAME_RE.matcher(v).find()) {
                throw new ValueError("Name: 2-40 lower-case letters, digits or _, starting with a letter (e.g. rrn_list)");
            }
            if (EmailService.PLACEHOLDERS.containsKey(v) || EmailService.REMINDER_PLACEHOLDERS.containsKey(v)) {
                throw new ValueError("{{" + v + "}} is already a built-in placeholder");
            }
            return v;
        });
        String description = b.check("description", b.str("description", ""), v -> {
            v = Py.squash(v);
            if (v.length() > 255) throw new ValueError("Description is too long (max 255 characters)");
            return v;
        });
        String source = b.literal("source", null, "text", "column");
        String value = b.check("value", b.str("value", ""), v -> {
            if (v.length() > 2000) throw new ValueError("The text is too long (max 2000 characters)");
            return v;
        });
        String column = b.optStr("column");
        String aggregate = b.optStr("aggregate");
        return new VariableRequest(name, description, source, value, column, aggregate);
    }

    public static VariableRequest from(Body b) {
        VariableRequest data = read(b);
        b.done();
        return data;
    }

    public static VariableRequest fromStored(Map<String, Object> after) {
        Body b = Body.ofStored(after);
        VariableRequest data = read(b);
        b.doneOr400();
        return data;
    }

    public Map<String, Object> dump() {
        return Py.map("name", name, "description", description, "source", source, "value", value, "column", column, "aggregate", aggregate);
    }
}
