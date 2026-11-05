package com.nagad.npsb.service;

import com.nagad.npsb.Py;
import com.nagad.npsb.PyJson;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.AppSetting;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Alert emails to NPSB operations (Email Configuration > "Alert NPSB operations").
 *
 * Sent when an email or follow-up can't be sent, a partner's mail server bounces an email, or the inbox
 * can't be read. Each problem (key) is sent at most once every COOLDOWN, remembered in app_settings so a
 * restart doesn't repeat it. Sending happens in the background, so a slow or broken mail server never
 * holds up the page that caused the alert. If the mail server itself is broken the alert can't go out:
 * the banner in the app (/api/attention) still shows the problem.
 */
public final class Alerts {
    private Alerts() {}

    private static final Logger LOG = Logger.getLogger("npsb");
    private static final Duration COOLDOWN = Duration.ofHours(6);
    private static final String PREFIX = "alert:";

    /** Queue an alert email. Returns false when there is nobody to tell or it was sent recently. */
    public static boolean alert(String key, String subject, List<String> lines) {
        List<String> recipients = Settings.getList("ops_alert_emails");
        if (recipients.isEmpty()) return false;
        String rowKey = Py.cut(PREFIX + key, 50);
        LocalDateTime now = Py.now();
        try (Db db = Db.open()) {
            AppSetting row = db.get(AppSetting.class, rowKey);
            if (row != null) {
                try {
                    LocalDateTime last = LocalDateTime.parse((String) PyJson.loads(row.value));
                    if (Duration.between(last, now).compareTo(COOLDOWN) < 0) return false;
                } catch (RuntimeException ignored) {
                    // unreadable: send again
                }
            } else {
                row = new AppSetting();
                row.key = rowKey;
                db.add(row);
            }
            row.value = PyJson.dumps(Py.isoSeconds(now));
            row.updatedBy = "#alert";
            row.updatedAt = now;
            db.commit();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Alert " + key + " could not be recorded", e);
            return false;
        }
        List<String> to = new ArrayList<>(recipients);
        Thread t = new Thread(() -> send(to, subject, lines), "ops-alert");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void send(List<String> recipients, String subject, List<String> lines) {
        StringBuilder body = new StringBuilder("<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222\">"
                + "<p><b>NPSB Dispute Email Automation needs attention.</b></p>");
        for (String line : lines) body.append("<p>").append(Py.escape(line)).append("</p>");
        body.append("<p>Open the app for details.</p></div>");
        try {
            EmailService.sendEmail(recipients, List.of(), "[NPSB alert] " + subject, body.toString(), null, null, null);
        } catch (Exception e) {
            LOG.warning("Alert email could not be sent (" + subject + "): " + e.getMessage());
        }
    }
}
