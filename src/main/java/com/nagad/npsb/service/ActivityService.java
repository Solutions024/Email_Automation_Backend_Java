package com.nagad.npsb.service;

import com.nagad.npsb.entity.EmailEvent;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.util.Crypto;
import com.nagad.npsb.util.Py;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** What changed (open pages reload when it did) and what needs someone's attention (banner and menu counts). */
@Service
public class ActivityService {
    private final Sql sql;
    private final Inbox inbox;
    private final AnswerService answerService;

    public ActivityService(Sql sql, Inbox inbox, AnswerService answerService) {
        this.sql = sql;
        this.inbox = inbox;
        this.answerService = answerService;
    }

    /**
     * A fingerprint of the emails and partner mail: it changes when an email is sent for approval, sent, fails, gets
     * a reply, a follow-up, a note or a status change, or a change is approved. Open pages ask for it every few
     * seconds and reload only when it changed.
     */
    public Map<String, Object> activity() {
        List<Object> parts = new ArrayList<>();
        parts.add(Arrays.asList(sql.rows("SELECT COUNT(id), MAX(id), MAX(submitted_at), MAX(sent_at), MAX(sending_since), "
                + "MAX(reply_status_at), MAX(remarks_at), MAX(last_followup_at) FROM email_dispatches").get(0)));
        List<Object> statuses = new ArrayList<>();
        for (Object[] row : sql.rows("SELECT status, COUNT(id) FROM email_dispatches GROUP BY status ORDER BY status")) {
            statuses.add(Arrays.asList(row));
        }
        parts.add(statuses);
        parts.add(sql.scalar("SELECT MAX(id) FROM email_events"));
        parts.add(Arrays.asList(sql.rows("SELECT COUNT(id), MAX(id), MAX(handled_at) FROM email_replies").get(0)));
        parts.add(Arrays.asList(sql.rows("SELECT MAX(id), MAX(decided_at) FROM change_requests").get(0)));
        parts.add(answerService.activityParts());
        return Py.map("stamp", Crypto.sha1Hex(String.valueOf(parts)).substring(0, 16));
    }

    /** What needs someone's attention, for the banner and the menu counts. */
    public Map<String, Object> attention(User user) {
        boolean admin = "admin".equals(user.getRole());
        Map<String, Object> out = Py.map(
                "approvals", admin ? sql.count("SELECT COUNT(*) FROM change_requests WHERE status = 'pending' AND requested_by != ?",
                        user.getUsername()) : 0,
                "my_requests", sql.count("SELECT COUNT(*) FROM change_requests WHERE status = 'pending' AND requested_by = ?", user.getUsername()),
                "failed_emails", sql.count("SELECT COUNT(*) FROM email_dispatches WHERE status = 'FAILED'"),
                "inbox_error", null, "inbox_issues", 0, "review", 0, "mail_error", null,
                // answers to partners waiting for an admin (admins) / your answers sent back to you (users)
                "answers", admin ? answerService.waitingCount() : 0,
                "my_rejected_answers", sql.count("SELECT COUNT(*) FROM reply_answers WHERE created_by = ? AND status = 'REJECTED'",
                        user.getUsername()));
        if (admin) {
            Map<String, Object> status = inbox.statusDict();
            out.put("inbox_error", Boolean.TRUE.equals(status.get("enabled")) ? status.get("last_error") : null);
            out.put("inbox_issues", sql.count("SELECT COUNT(*) FROM inbox_issues WHERE resolved_at IS NULL"));
            out.put("review", sql.count("SELECT COUNT(*) FROM email_replies WHERE kind = 'review'"));
            EmailEvent lastSend = sql.first(EmailEvent.class, "WHERE t.kind IN ('sent', 'send_failed', 'followup') ORDER BY t.created_at DESC, t.id DESC");
            if (lastSend != null && !Boolean.TRUE.equals(lastSend.getOk())) out.put("mail_error", lastSend.getError());
        }
        return out;
    }
}
