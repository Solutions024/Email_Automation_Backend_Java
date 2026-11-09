package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.RequestValidationException;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional body of "prepare emails": the template to use per mail type (else each email's own / the default). */
@Schema(description = "The template to build the emails from, per mail type: {\"inward\": 3, \"outward\": 5}. Optional.")
public record PrepareEmailsRequest(Map<String, Integer> templates) {

    /** b: the optional body (null: none sent). */
    public static PrepareEmailsRequest from(Body b) {
        Map<String, Integer> templates = new LinkedHashMap<>();
        if (b != null) {
            Map<String, Object> raw = b.dict("templates", new LinkedHashMap<>());
            if (raw != null) {
                List<Map<String, Object>> errors = new ArrayList<>();
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    if (!e.getKey().equals("inward") && !e.getKey().equals("outward")) {
                        errors.add(Py.map("type", "literal_error", "loc", List.of("body", "templates", e.getKey(), "[key]"),
                                "msg", "Input should be 'inward' or 'outward'", "input", e.getKey()));
                        continue;
                    }
                    Integer id = intOf(e.getValue());
                    if (id == null) {
                        errors.add(Py.map("type", "int_parsing", "loc", List.of("body", "templates", e.getKey()),
                                "msg", "Input should be a valid integer, unable to parse string as an integer", "input", e.getValue()));
                        continue;
                    }
                    templates.put(e.getKey(), id);
                }
                if (!errors.isEmpty()) throw new RequestValidationException(errors);
            }
            b.done();
        }
        return new PrepareEmailsRequest(templates);
    }

    static Integer intOf(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        if (v instanceof Double d && d == Math.rint(d)) return d.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(Py.strip(s));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
