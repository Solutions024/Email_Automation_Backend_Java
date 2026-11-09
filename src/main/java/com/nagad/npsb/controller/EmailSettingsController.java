package com.nagad.npsb.controller;

import com.nagad.npsb.dto.EmailSettingsRequest;
import com.nagad.npsb.dto.TestEmailRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.EmailSettingsService;
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

/**
 * Email Configuration: follow-up days, automatic follow-ups and CC addresses (admins change, everyone reads), plus
 * the settings shown in the UI.
 */
@RestController
@Tag(name = "email settings", description = "Email Configuration and the settings shown in the UI")
public class EmailSettingsController {
    private final CurrentUser currentUser;
    private final EmailSettingsService emailSettings;

    public EmailSettingsController(CurrentUser currentUser, EmailSettingsService emailSettings) {
        this.currentUser = currentUser;
        this.emailSettings = emailSettings;
    }

    @GetMapping("/api/config")
    @Operation(summary = "Settings shown in the UI.")
    public Map<String, Object> getConfig() {
        currentUser.require();
        return emailSettings.config();
    }

    @GetMapping("/api/email-settings")
    @Operation(summary = "Get Email Settings")
    public Map<String, Object> getEmailSettings() {
        currentUser.require();
        return emailSettings.emailSettingsDict();
    }

    @PutMapping("/api/email-settings")
    @Operation(summary = "Update Email Settings")
    public Map<String, Object> updateEmailSettings(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = EmailSettingsRequest.class)))
            @RequestBody(required = false) String body) {
        User admin = currentUser.requireAdmin();
        EmailSettingsRequest data = EmailSettingsRequest.from(Body.parse(body));
        return emailSettings.updateEmailSettings(admin, data);
    }

    @PostMapping("/api/email-settings/run-followups")
    @Operation(summary = "Look for due emails now instead of waiting for the next automatic check.")
    public Map<String, Object> runFollowupsNow() {
        currentUser.requireAdmin();
        return emailSettings.runFollowupsNow();
    }

    @PostMapping("/api/email-settings/test-email")
    @Operation(summary = "Send a short email to check the mail server settings.")
    public Map<String, Object> sendTestEmail(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = TestEmailRequest.class)))
            @RequestBody(required = false) String body) {
        currentUser.requireAdmin();
        TestEmailRequest data = TestEmailRequest.from(Body.parse(body));
        return emailSettings.sendTestEmail(data.to());
    }
}
