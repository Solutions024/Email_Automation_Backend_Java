package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.dto.EmailSettingsRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.service.dispatch.DispatchQueries;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.SqlQuery;
import com.nagad.npsb.service.dispatch.UserNames;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Email Configuration (follow-up days, automatic follow-ups, CC addresses; admins change, everyone reads), the
 * settings shown in the UI, running the follow-up check now, and a test email.
 */
@Service
public class EmailSettingsService {
    private final Settings settings;
    private final LiveSettings live;
    private final ServerSettings serverSettings;
    private final UserNames userNames;
    private final DispatchQueries queries;
    private final FollowupService followupService;
    private final Inbox inbox;
    private final EmailService emailService;
    private final Sql sql;

    public EmailSettingsService(Settings settings, LiveSettings live, ServerSettings serverSettings, UserNames userNames,
                                DispatchQueries queries, FollowupService followupService, Inbox inbox, EmailService emailService, Sql sql) {
        this.settings = settings;
        this.live = live;
        this.serverSettings = serverSettings;
        this.userNames = userNames;
        this.queries = queries;
        this.followupService = followupService;
        this.inbox = inbox;
        this.emailService = emailService;
        this.sql = sql;
    }

    /** Settings shown in the UI. */
    public Map<String, Object> config() {
        return Py.map(
                "mail_provider", live.mailProvider(),
                "smtp_host", live.smtpHost(),
                "smtp_port", live.smtpPort(),
                "mail_from", live.mailFrom(),
                "cc_emails", settings.get("cc_emails"),
                "maker_checker", live.makerChecker());
    }

    public Map<String, Object> emailSettingsDict() {
        Map<String, Object> values = settings.current();
        Map<String, Object> change = settings.lastChange();
        Map<String, String> names = userNames.userNames(change != null ? Collections.singletonList((String) change.get("by")) : List.of());
        Map<String, Object> out = new LinkedHashMap<>(values);
        out.put("defaults", settings.defaults());
        out.put("updated_by_name", change != null ? UserNames.name(names, (String) change.get("by")) : null);
        out.put("updated_at", change != null ? change.get("at") : null);
        SqlQuery due = queries.dueFilter(new SqlQuery("WHERE t.status = 'SENT'", new ArrayList<>()));
        out.put("due_now", sql.count("SELECT COUNT(*) FROM email_dispatches t " + due.sql(), due.argArray()));
        out.put("auto_eligible_now", followupService.autoCandidates(((Number) values.get("auto_followup_max")).intValue()).size());
        out.put("check_every_seconds", live.autoFollowupCheckSeconds());
        out.put("auto_status", followupService.autoStatus());
        out.put("inbox", inbox.statusDict());
        out.put("smtp", Py.map("provider", live.mailProvider(),
                "configured", !live.gmail() || SystemSettingsService.gmailMissing(serverSettings.effective()).isEmpty(),
                "host", live.smtpHost(), "port", live.smtpPort(), "use_tls", live.smtpUseTls(),
                "user", live.smtpUser(), "mail_from", live.mailFrom(),
                "max_attempts", live.smtpMaxAttempts(), "retry_seconds", live.smtpRetrySeconds()));
        return out;
    }

    public Map<String, Object> updateEmailSettings(User admin, EmailSettingsRequest data) {
        settings.save(Py.map("followup_due_days", data.followupDueDays(), "auto_followup_enabled", data.autoFollowupEnabled(),
                "auto_followup_max", data.autoFollowupMax(), "cc_emails", data.ccEmails()), admin.getUsername());  // committed
        return emailSettingsDict();
    }

    /** Look for due emails now instead of waiting for the next automatic check. */
    public Map<String, Object> runFollowupsNow() {
        Map<String, Object> result = followupService.runAutoFollowups();
        return Py.map("result", result, "settings", emailSettingsDict());
    }

    /** Send a short email to check the mail server settings. */
    public Map<String, Object> sendTestEmail(String to) {
        String body = "<div style=\"font-family:Arial,sans-serif;font-size:14px\">"
                + "<p>This is a test email from NPSB Dispute Email Automation.</p>"
                + (live.gmail() ? "<p>Sent through Gmail from " + Py.escape(live.mailFrom()) + ".</p></div>"
                : "<p>Mail server: " + live.smtpHost() + ":" + live.smtpPort() + "</p></div>");
        EmailService.SendResult result;
        try {
            result = emailService.sendEmail(List.of(to), List.of(), "NPSB Dispute Email Automation - test email", body, null, null, null);
        } catch (Exception exc) {
            throw new ApiException(502, "The test email could not be sent: " + exc.getMessage());
        }
        if (!result.refused().isEmpty()) {
            throw new ApiException(502, "The mail server refused " + to + ": " + String.join("; ", result.refused().values()));
        }
        return Py.map("ok", true, "message", "Test email sent to " + to + ".");
    }
}
