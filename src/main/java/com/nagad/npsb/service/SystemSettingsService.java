package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.service.dispatch.UserNames;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * System Settings (admins only): mail server, inbox, retries and security settings. They apply at once; a change of
 * the mail server or mailbox is tested first (unless forced). Every change is kept in the change history and the
 * security log.
 */
@Service
public class SystemSettingsService {
    private final ServerSettings serverSettings;
    private final UserNames userNames;
    private final AuthService authService;
    private final Approvals approvals;
    private final Inbox inbox;
    private final FollowupService followupService;
    private final TransactionTemplate tx;

    public SystemSettingsService(ServerSettings serverSettings, UserNames userNames, AuthService authService, Approvals approvals,
                                 Inbox inbox, FollowupService followupService, TransactionTemplate tx) {
        this.serverSettings = serverSettings;
        this.userNames = userNames;
        this.authService = authService;
        this.approvals = approvals;
        this.inbox = inbox;
        this.followupService = followupService;
        this.tx = tx;
    }

    public Map<String, Object> serverSettingsDict() {
        return serverSettings.toDict(userNames.userNames(serverSettings.usernames()));
    }

    /** What is still missing for the Gmail account (labels), empty when it can be tried. */
    public static List<String> gmailMissing(Map<String, Object> v) {
        List<String> out = new ArrayList<>();
        if (Py.isEmpty(Py.strip((String) v.get("GMAIL_ADDRESS")))) out.add("Gmail address");
        if (Py.isEmpty(LiveSettings.gmailPassword((String) v.get("GMAIL_APP_PASSWORD")))) out.add("App password");
        return out;
    }

    /** null when the mail server (smtp), mailbox (imap) or Gmail account (gmail: both) accepts these settings, else the reason. */
    static String connectionProblem(String target, Map<String, Object> v) {
        try {
            if (target.equals("gmail")) {
                List<String> missing = gmailMissing(v);
                if (!missing.isEmpty()) return "Fill in " + String.join(", ", missing) + " first.";
                String problem = connectionProblem("smtp", v);
                if (problem != null) return "Sending (smtp.gmail.com): " + problem;
                if (Boolean.TRUE.equals(v.get("INBOX_ENABLED"))) {
                    problem = connectionProblem("imap", v);
                    if (problem != null) return "Reading (imap.gmail.com): " + problem;
                }
            } else if (target.equals("smtp")) {
                Map<String, Object> m = ServerSettings.smtpValues(v);
                EmailService.testSmtp((String) m.get("host"), (Integer) m.get("port"), (Boolean) m.get("use_tls"),
                        (String) m.get("user"), (String) m.get("password"));
            } else {
                Map<String, Object> m = ServerSettings.imapValues(v);
                Inbox.testConnection((String) m.get("host"), (Integer) m.get("port"), (String) m.get("user"), (String) m.get("password"),
                        (String) m.get("folder"));
            }
        } catch (Exception exc) {
            return String.valueOf(exc.getMessage());
        }
        return null;
    }

    static Object[] parseOr400(Map<String, Object> values, List<String> reset) {
        try {
            return ServerSettings.parseChanges(values, reset);
        } catch (ServerSettings.SettingsError exc) {
            throw new ApiException(400, exc.getMessage());
        }
    }

    /** Save settings; they apply at once (connection changes are tested first). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateServerSettings(User admin, Map<String, Object> values, List<String> reset, boolean force) {
        Object[] parsed = parseOr400(values, reset);
        Map<String, Object> changes = (Map<String, Object>) parsed[0];
        Set<String> resets = (Set<String>) parsed[1];
        if (changes.isEmpty() && resets.isEmpty()) {
            Map<String, Object> out = serverSettingsDict();
            out.put("changed", new ArrayList<>());
            return out;
        }
        Map<String, Object> old = serverSettings.effective(), next = serverSettings.effective(changes, resets);
        if (!force) {
            List<String> problems = new ArrayList<>();
            boolean switched = !old.get("MAIL_PROVIDER").equals(next.get("MAIL_PROVIDER"));
            if ("gmail".equals(next.get("MAIL_PROVIDER"))) {
                boolean changed = switched || (Boolean.TRUE.equals(next.get("INBOX_ENABLED")) && !Boolean.TRUE.equals(old.get("INBOX_ENABLED")));
                for (String k : ServerSettings.GMAIL_FIELDS) changed |= !Objects.equals(old.get(k), next.get(k));
                if (changed) {
                    String problem = connectionProblem("gmail", next);
                    if (problem != null) problems.add("Gmail: " + problem);
                }
            } else {
                boolean changed = switched;
                for (String k : ServerSettings.SMTP_FIELDS) changed |= !Objects.equals(old.get(k), next.get(k));
                if (changed) {
                    String problem = connectionProblem("smtp", next);
                    if (problem != null) problems.add("Mail server: " + problem);
                }
            }
            boolean imapChanged = !ServerSettings.imapValues(old).equals(ServerSettings.imapValues(next)) || switched
                    || (Boolean.TRUE.equals(next.get("INBOX_ENABLED")) && !Boolean.TRUE.equals(old.get("INBOX_ENABLED")));
            if ("smtp".equals(next.get("MAIL_PROVIDER")) && Boolean.TRUE.equals(next.get("INBOX_ENABLED")) && imapChanged) {
                Map<String, Object> m = ServerSettings.imapValues(next);
                String problem = !Py.isEmpty((String) m.get("host")) && !Py.isEmpty((String) m.get("user")) && !Py.isEmpty((String) m.get("password"))
                        ? connectionProblem("imap", next) : "the mailbox needs a server, a user and a password.";
                if (problem != null) problems.add("Inbox: " + problem);
            }
            if (!problems.isEmpty()) {
                throw new ApiException(409, Py.map("code", "test_failed", "problems", problems, "message", "Not saved: " + String.join(" ", problems)));
            }
        }
        List<String> changed = serverSettings.save(changes, resets, admin.getUsername());  // committed
        if (!changed.isEmpty()) {
            tx.executeWithoutResult(status -> {
                authService.logEvent("settings_changed", admin, "System Settings: " + String.join(", ", changed));
                Set<String> secret = new HashSet<>();
                for (ServerSettings.Field f : ServerSettings.FIELDS) if (f.kind().equals("secret")) secret.add(f.name());
                List<String> labels = new ArrayList<>();
                Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
                Map<String, Object> now = serverSettings.effective();
                for (String n : changed) {
                    labels.add(ServerSettings.BY_NAME.get(n).label());
                    before.put(n, shown(secret, n, old.get(n)));
                    after.put(n, shown(secret, n, now.get(n)));
                }
                approvals.record("settings", "update", Py.cut(String.join(", ", labels), 255), before, after, admin.getUsername());
            });
            inbox.wake();               // check the mailbox with the new settings now
            followupService.wake();     // and restart the follow-up timer with its new interval
        }
        Map<String, Object> out = serverSettingsDict();
        out.put("changed", changed);
        return out;
    }

    private static Object shown(Set<String> secret, String name, Object v) {
        if (!secret.contains(name)) return v;
        boolean set = v != null && !(v instanceof String s && s.isEmpty());
        return set ? "(set)" : "(empty)";
    }

    /** Try the mail server or the mailbox with these (unsaved) settings. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> testServerSettings(String target, Map<String, Object> values) {
        Object[] parsed = parseOr400(values, List.of());
        Map<String, Object> v = serverSettings.effective((Map<String, Object>) parsed[0], null);
        String problem = connectionProblem(target, v);
        if (problem != null) throw new ApiException(502, problem);
        Map<String, Object> m = ServerSettings.imapValues(v);
        if (target.equals("gmail")) {
            String address = Py.strip((String) v.get("GMAIL_ADDRESS"));
            return Py.map("ok", true, "message", "Signed in to Gmail as " + address + " (smtp.gmail.com"
                    + (Boolean.TRUE.equals(v.get("INBOX_ENABLED")) ? ", and opened " + m.get("folder") + " on imap.gmail.com read-only" : "")
                    + "). Nothing was sent.");
        }
        if (target.equals("smtp")) {
            Map<String, Object> sm = ServerSettings.smtpValues(v);
            String user = (String) sm.get("user");
            return Py.map("ok", true, "message", "Signed in to " + sm.get("host") + ":" + sm.get("port")
                    + (Py.isEmpty(user) ? "" : " as " + user) + ". Nothing was sent.");
        }
        return Py.map("ok", true, "message", "Opened " + m.get("folder") + " of " + m.get("user") + " on " + m.get("host") + " (read-only).");
    }
}
