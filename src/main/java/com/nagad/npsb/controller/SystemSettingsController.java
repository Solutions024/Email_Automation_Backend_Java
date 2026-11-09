package com.nagad.npsb.controller;

import com.nagad.npsb.dto.ServerSettingsRequest;
import com.nagad.npsb.dto.ServerSettingsTestRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.SystemSettingsService;
import com.nagad.npsb.web.Body;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** System Settings: mail server, inbox, retries and security settings (admins only). */
@RestController
@Tag(name = "system settings", description = "Mail server, inbox, retries and security settings (admins only)")
public class SystemSettingsController {
    private final CurrentUser currentUser;
    private final SystemSettingsService systemSettings;

    public SystemSettingsController(CurrentUser currentUser, SystemSettingsService systemSettings) {
        this.currentUser = currentUser;
        this.systemSettings = systemSettings;
    }

    @GetMapping("/api/server-settings")
    @Operation(summary = "Get Server Settings")
    public Map<String, Object> getServerSettings() {
        currentUser.requireAdmin();
        return systemSettings.serverSettingsDict();
    }

    @PutMapping("/api/server-settings")
    @Operation(summary = "Save settings; they apply at once (connection changes are tested first).")
    public Map<String, Object> updateServerSettings(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ServerSettingsRequest.class)))
            @RequestBody(required = false) String body) {
        User admin = currentUser.requireAdmin();
        ServerSettingsRequest data = ServerSettingsRequest.from(Body.parse(body));
        return systemSettings.updateServerSettings(admin, data.values(), data.reset(), data.force());
    }

    @PostMapping("/api/server-settings/test")
    @Operation(summary = "Try the mail server or the mailbox with these (unsaved) settings.")
    public Map<String, Object> testServerSettings(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ServerSettingsTestRequest.class)))
            @RequestBody(required = false) String body) {
        currentUser.requireAdmin();
        ServerSettingsTestRequest data = ServerSettingsTestRequest.from(Body.parse(body));
        return systemSettings.testServerSettings(data.target(), data.values());
    }
}
