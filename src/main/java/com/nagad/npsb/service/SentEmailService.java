package com.nagad.npsb.service;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.EmailEvent;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.EmailDispatchRepository;
import com.nagad.npsb.repository.EmailEventRepository;
import com.nagad.npsb.service.dispatch.DispatchQueries;
import com.nagad.npsb.service.dispatch.DispatchViews;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.SqlQuery;
import com.nagad.npsb.service.dispatch.UserNames;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.nagad.npsb.service.dispatch.UserNames.name;

/**
 * Sent Mails: sent emails from every upload (search, partner and date filters, sort), and their follow-up by the
 * admins: reply status, notes, remarks and the history of each email. Everyone reads; admins change.
 */
@Service
public class SentEmailService {
    public static final Map<String, String> REPLY_STATUS_LABELS = Map.of("not_received", "Awaiting reply", "received", "Reply received",
            "reconciled", "Reconciled");

    private final DispatchQueries queries;
    private final DispatchViews views;
    private final UserNames userNames;
    private final Settings settings;
    private final Sql sql;
    private final TransactionTemplate tx;
    private final EmailDispatchRepository dispatches;
    private final EmailEventRepository events;

    public SentEmailService(DispatchQueries queries, DispatchViews views, UserNames userNames, Settings settings, Sql sql,
                            TransactionTemplate tx, EmailDispatchRepository dispatches, EmailEventRepository events) {
        this.queries = queries;
        this.views = views;
        this.userNames = userNames;
        this.settings = settings;
        this.sql = sql;
        this.tx = tx;
        this.dispatches = dispatches;
        this.events = events;
    }

    /** Sent emails from every upload. */
    public List<Map<String, Object>> listSentEmails(String q, Integer partnerId, String sort, LocalDate dateFrom, LocalDate dateTo,
                                                    String reply, int limit) {
        SqlQuery query = queries.withReply(DispatchQueries.sentQuery(q, partnerId, dateFrom, dateTo), reply);
        String order = "oldest".equals(sort) ? " ORDER BY t.sent_at ASC, t.id ASC" : " ORDER BY t.sent_at DESC, t.id DESC";
        return views.dispatchesToDicts(sql.all(EmailDispatch.class, query.sql() + order + " LIMIT " + limit, query.argArray()));
    }

    /** How many sent emails are in each reply status. */
    public Map<String, Object> sentSummary(String q, Integer partnerId, LocalDate dateFrom, LocalDate dateTo) {
        SqlQuery base = DispatchQueries.sentQuery(q, partnerId, dateFrom, dateTo);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String status : new String[]{"not_received", "received", "reconciled"}) counts.put(status, 0L);
        for (Object[] row : sql.rows("SELECT t.reply_status, COUNT(t.id) FROM email_dispatches t " + base.sql() + " GROUP BY t.reply_status",
                base.argArray())) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        SqlQuery due = queries.dueFilter(base);
        long total = 0;
        for (Object v : counts.values()) total += ((Number) v).longValue();
        Map<String, Object> out = new LinkedHashMap<>(counts);
        out.put("due", sql.count("SELECT COUNT(t.id) FROM email_dispatches t " + due.sql(), due.argArray()));
        out.put("total", total);
        out.put("due_after_days", settings.get("followup_due_days"));
        return out;
    }

    /** History of an email, oldest first. */
    public List<Map<String, Object>> listEmailEvents(int emailId) {
        EmailDispatch d = dispatches.findById(emailId).orElse(null);
        if (d == null) throw new ApiException(404, "Email not found.");
        List<EmailEvent> found = sql.all(EmailEvent.class, "WHERE t.dispatch_id = ? AND t.kind != 'forwarded' "
                + "ORDER BY t.created_at, t.id", emailId);  // forwarding replies no longer exists: old entries are hidden
        List<String> who = new ArrayList<>();
        for (EmailEvent e : found) who.add(e.getCreatedBy());
        who.add(d.getSubmittedBy());
        who.add(d.getSentBy());
        Map<String, String> names = userNames.userNames(who);
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> kinds = new HashSet<>();
        for (EmailEvent e : found) {
            items.add(DispatchViews.eventToDict(e, names));
            kinds.add(e.getKind());
        }
        if (!kinds.contains("submitted") && d.getSubmittedAt() != null) {
            Map<String, Object> item = baseEvent(d);
            item.put("id", "submitted-" + d.getId());
            item.put("kind", "submitted");
            item.put("created_by", d.getSubmittedBy());
            item.put("created_by_name", name(names, d.getSubmittedBy()));
            item.put("created_at", Py.iso(d.getSubmittedAt()));
            items.add(item);
        }
        if (!kinds.contains("sent") && d.getSentAt() != null) {
            Map<String, Object> item = baseEvent(d);
            item.put("id", "sent-" + d.getId());
            item.put("kind", "sent");
            item.put("created_by", d.getSentBy());
            item.put("created_by_name", name(names, d.getSentBy()));
            item.put("created_at", Py.iso(d.getSentAt()));
            item.put("to", Py.csv(d.getToEmails()));
            item.put("cc", Py.csv(d.getCcEmails()));
            items.add(item);
        }
        Map<String, Integer> order = Map.of("submitted", 0, "sent", 1, "send_failed", 1);  // same second: approval before sending
        items.sort(Comparator.<Map<String, Object>, String>comparing(i -> (String) i.get("created_at"))
                .thenComparing(i -> order.getOrDefault((String) i.get("kind"), 2)));
        return items;
    }

    private static Map<String, Object> baseEvent(EmailDispatch d) {
        return Py.map("reply_status", null, "note", null, "reply_id", null, "body_html", null, "ok", true, "error", null,
                "subject", d.getSubject(), "to", new ArrayList<>(), "cc", new ArrayList<>());
    }

    /** A line in an email's history (written with the caller's transaction). */
    public void addEvent(int dispatchId, String kind, String replyStatus, String note, String by) {
        EmailEvent e = new EmailEvent();
        e.setDispatchId(dispatchId);
        e.setKind(kind);
        e.setReplyStatus(replyStatus);
        e.setNote(note);
        e.setCreatedBy(by);
        events.save(e);
    }

    /** Admin records whether the partner replied. */
    public Map<String, Object> setReplyStatus(User admin, int emailId, String status, String note) {
        EmailDispatch d = queries.getSentOr404(emailId);
        if (!status.equals(d.getReplyStatus())) {
            tx.executeWithoutResult(s -> {
                d.setReplyStatus(status);
                d.setReplyStatusBy(admin.getUsername());
                d.setReplyStatusAt(Py.now());
                String n = Py.cut(Py.strip(note), 1000);
                addEvent(d.getId(), "status", status, n.isEmpty() ? null : n, admin.getUsername());
            });
        }
        return views.dispatchToDict(d, null);
    }

    /** Admin note on a sent email. */
    public Map<String, Object> addEmailNote(User admin, int emailId, String note) {
        tx.executeWithoutResult(s -> {
            EmailDispatch d = queries.getSentOr404(emailId);
            addEvent(d.getId(), "note", null, note, admin.getUsername());
        });
        return Py.map("ok", true);
    }

    /** Admin's free-text remarks on a sent email. */
    public Map<String, Object> setRemarks(User admin, int emailId, String remarks) {
        EmailDispatch d = queries.getSentOr404(emailId);
        if (!Py.or(d.getRemarks(), "").equals(remarks)) {
            tx.executeWithoutResult(s -> {
                d.setRemarks(remarks.isEmpty() ? null : remarks);
                d.setRemarksBy(admin.getUsername());
                d.setRemarksAt(Py.now());
                addEvent(d.getId(), "remarks", null, remarks.isEmpty() ? null : remarks, admin.getUsername());
            });
        }
        return views.dispatchToDict(d, null);
    }
}
