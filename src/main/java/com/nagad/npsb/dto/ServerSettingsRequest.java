package com.nagad.npsb.dto;

import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Changes on the System Settings page. */
@Schema(description = "New values by setting name, settings to reset to their .env value, and force: save without testing the connections.")
public record ServerSettingsRequest(
        @Schema(defaultValue = "{}") Map<String, Object> values,
        @Schema(defaultValue = "[]") List<String> reset,
        @Schema(defaultValue = "false") boolean force) {

    public static ServerSettingsRequest from(Body b) {
        Map<String, Object> values = b.dict("values", new LinkedHashMap<>());
        List<String> reset = b.strList("reset", List.of());
        boolean force = b.bool("force", false);
        b.done();
        return new ServerSettingsRequest(values, reset, force);
    }
}
