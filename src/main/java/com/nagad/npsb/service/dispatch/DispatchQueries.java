package com.nagad.npsb.service.dispatch;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.repository.EmailDispatchRepository;
import com.nagad.npsb.service.Settings;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** The email lists: sent emails with the filters of the Sent Mails page, due follow-ups, pending emails. */
@Component
public class DispatchQueries {
    /** The reply filters of the Sent Mails page ("due": follow-up due). */
    public static final String[] REPLY_FILTERS = {"", "not_received", "received", "reconciled", "due"};

    private final Sql sql;
    private final Settings settings;
    private final EmailDispatchRepository dispatches;

    public DispatchQueries(Sql sql, Settings settings, EmailDispatchRepository dispatches) {
        this.sql = sql;
        this.settings = settings;
        this.dispatches = dispatches;
    }

    /** SENT emails matching the search / partner / date filters of the Sent Mails page. */
    public static SqlQuery sentQuery(String q, Integer partnerId, LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new ApiException(400, "The 'from' date must be on or before the 'to' date.");
        }
        SqlQuery query = new SqlQuery("JOIN partners p ON t.partner_id = p.id JOIN uploads u ON t.upload_id = u.id WHERE t.status = 'SENT'",
                new ArrayList<>());
        if (partnerId != null) query = query.and("t.partner_id = ?", partnerId);
        if (dateFrom != null) query = query.and("t.sent_at >= ?", dateFrom.atStartOfDay());
        if (dateTo != null) query = query.and("t.sent_at < ?", dateTo.plusDays(1).atStartOfDay());
        List<String> words = Py.split(q);
        for (String word : words.subList(0, Math.min(words.size(), 10))) {
            String like = "%" + word.replace("%", "").replace("_", "") + "%";
            String names = "(SELECT username FROM users WHERE LOWER(full_name) LIKE LOWER(?))";
            query = query.and("(t.case_id LIKE ? OR LOWER(p.name) LIKE LOWER(?) OR LOWER(t.subject) LIKE LOWER(?) OR LOWER(t.to_emails) LIKE LOWER(?) "
                            + "OR LOWER(t.cc_emails) LIKE LOWER(?) OR LOWER(u.filename) LIKE LOWER(?) OR LOWER(t.submitted_by) LIKE LOWER(?) "
                            + "OR LOWER(t.sent_by) LIKE LOWER(?) OR LOWER(t.remarks) LIKE LOWER(?) OR t.submitted_by IN " + names
                            + " OR t.sent_by IN " + names + ")",
                    like, like, like, like, like, like, like, like, like, like, like);
        }
        return query;
    }

    /** SQL condition for DispatchViews.followupDue(). */
    public SqlQuery dueFilter(SqlQuery query) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(settings.getInt("followup_due_days"));
        return query.and("t.reply_status = 'not_received' AND ((t.last_followup_at IS NULL AND t.sent_at < ?) OR t.last_followup_at < ?)",
                cutoff, cutoff);
    }

    public SqlQuery withReply(SqlQuery query, String reply) {
        if ("due".equals(reply)) return dueFilter(query);
        if (!reply.isEmpty()) return query.and("t.reply_status = ?", reply);
        return query;
    }

    /** PENDING and FAILED emails, plus SENDING ones (another admin is sending them right now). */
    public List<EmailDispatch> pendingQuery() {
        return sql.all(EmailDispatch.class, "WHERE t.status IN ('PENDING', 'FAILED', 'SENDING') ORDER BY t.submitted_at, t.id");
    }

    public EmailDispatch getSentOr404(int emailId) {
        EmailDispatch d = dispatches.findById(emailId).orElse(null);
        if (d == null || !"SENT".equals(d.getStatus())) throw new ApiException(404, "Sent email not found.");
        return d;
    }
}
