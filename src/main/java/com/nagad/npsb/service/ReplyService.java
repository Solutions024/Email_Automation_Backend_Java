package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.EmailEvent;
import com.nagad.npsb.entity.EmailReply;
import com.nagad.npsb.entity.EmailReplyAttachment;
import com.nagad.npsb.entity.EmailReplyRead;
import com.nagad.npsb.entity.InboxIssue;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.EmailDispatchRepository;
import com.nagad.npsb.repository.EmailEventRepository;
import com.nagad.npsb.repository.EmailReplyAttachmentRepository;
import com.nagad.npsb.repository.EmailReplyReadRepository;
import com.nagad.npsb.repository.EmailReplyRepository;
import com.nagad.npsb.repository.InboxIssueRepository;
import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.service.dispatch.FileDownload;
import com.nagad.npsb.service.dispatch.SeparateSession;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.SqlQuery;
import com.nagad.npsb.service.dispatch.TransactionGroups;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Partners' mail, read from the sending mailbox (Inbox): replies to our emails, other partner mail, and messages to
 * review; which ones each user has opened; and the messages in the mailbox that could not be read (inbox issues).
 */
@Service
public class ReplyService {
    public static final String[] CATEGORIES = {"replies", "partner_mail", "review"};
    private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9._ -]");

    private final Inbox inbox;
    private final AnswerService answerService;
    private final SentEmailService sentEmails;
    private final TransactionGroups groups;
    private final LiveSettings live;
    private final Sql sql;
    private final TransactionTemplate tx;
    private final SeparateSession separateSession;
    private final EmailReplyRepository replies;
    private final EmailReplyReadRepository replyReads;
    private final EmailReplyAttachmentRepository attachments;
    private final EmailDispatchRepository dispatches;
    private final EmailEventRepository events;
    private final InboxIssueRepository issues;
    private final UserRepository users;

    public ReplyService(Inbox inbox, AnswerService answerService, SentEmailService sentEmails, TransactionGroups groups,
                        LiveSettings live, Sql sql, TransactionTemplate tx, SeparateSession separateSession,
                        EmailReplyRepository replies, EmailReplyReadRepository replyReads, EmailReplyAttachmentRepository attachments,
                        EmailDispatchRepository dispatches, EmailEventRepository events, InboxIssueRepository issues,
                        UserRepository users) {
        this.inbox = inbox;
        this.answerService = answerService;
        this.sentEmails = sentEmails;
        this.groups = groups;
        this.live = live;
        this.sql = sql;
        this.tx = tx;
        this.separateSession = separateSession;
        this.replies = replies;
        this.replyReads = replyReads;
        this.attachments = attachments;
        this.dispatches = dispatches;
        this.events = events;
        this.issues = issues;
        this.users = users;
    }

    // ---------------------------------------------------------------- partners' replies (read from the mailbox)

    /** Replies this user has not opened. Replies found before the account existed don't count. */
    static SqlQuery unread(SqlQuery q, User user) {
        return q.and("NOT EXISTS (SELECT 1 FROM email_reply_reads rr WHERE rr.reply_id = t.id AND rr.user_id = ?) AND t.found_at >= ?",
                user.getId(), Inbox.utcToLocal(user.getCreatedAt()));
    }

    Map<Integer, String> partnerNames() {
        Map<Integer, String> out = new HashMap<>();
        for (Object[] row : sql.rows("SELECT id, name FROM partners")) out.put(((Number) row[0]).intValue(), (String) row[1]);
        return out;
    }

    private EmailDispatch dispatchOf(EmailReply r) {
        return r.getDispatchId() == null ? null : dispatches.findById(r.getDispatchId()).orElse(null);
    }

    /** A partner message. category "reply": it answers one of our emails (original); "partner_mail": other mail. */
    Map<String, Object> replyToDict(EmailReply r, boolean isRead, Map<Integer, String> partners, boolean full) {
        EmailDispatch d = dispatchOf(r);
        Integer partnerId = d != null ? d.getPartnerId() : r.getPartnerId();
        List<Object> files = new ArrayList<>();
        for (EmailReplyAttachmentRepository.AttachmentInfo a : attachments.findByReplyIdOrderByIdAsc(r.getId())) {
            files.add(Py.map("id", a.getId(), "filename", a.getFilename(), "size", a.getSize()));
        }
        Map<String, Object> data = Py.map(
                "id", r.getId(), "category", Inbox.REVIEW.equals(r.getKind()) ? "review" : d != null ? "reply" : "partner_mail",
                "kind", r.getKind(),
                "review_reason", r.getReviewReason(), "suggested_email_id", r.getSuggestedDispatchId(),
                "handled_by", r.getHandledBy(), "handled_at", Py.iso(r.getHandledAt()),
                "email_id", d != null ? d.getId() : null, "case_id", d != null ? d.getCaseId() : null, "partner_id", partnerId,
                "partner_name", partners.containsKey(partnerId) ? partners.get(partnerId) : "Unknown partner",
                "mail_type", d != null ? d.getMailType() : null, "from_email", r.getFromEmail(), "from_name", r.getFromName(),
                "subject", r.getSubject(), "snippet", r.getSnippet(), "matched_by", r.getMatchedBy(),
                "received_at", Py.iso(r.getReceivedAt()), "found_at", Py.iso(r.getFoundAt()), "is_read", isRead,
                "attachments", files,
                "original", d == null ? null : Py.map("subject", d.getSubject(), "sent_at", Py.iso(d.getSentAt()),
                        "upload_id", d.getUploadId(), "txn_count", d.getTxnCount(), "total_amount", Py.toDouble(d.getTotalAmount()),
                        "reply_status", d.getReplyStatus(), "to", Py.csv(d.getToEmails())));
        if (full) {
            data.put("body_html", r.getBodyHtml());
            data.put("to", Py.csv(r.getToEmails()));
            data.put("cc", Py.csv(r.getCcEmails()));
        }
        return data;
    }

    static String categoryFilter(String category) {
        if (category.equals("replies")) return "t.dispatch_id IS NOT NULL";
        if (category.equals("review")) return "t.kind = 'review'";
        return "t.dispatch_id IS NULL AND t.kind NOT IN ('review', 'dismissed')";
    }

    /** New (unopened) and total messages: replies to our emails, other partner mail, and messages to review. */
    public Map<String, Object> repliesSummary(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        long unreadSum = 0, totalSum = 0;
        for (String name : CATEGORIES) {
            SqlQuery q = unread(new SqlQuery("WHERE " + categoryFilter(name), new ArrayList<>()), user);
            long unreadCount = sql.count("SELECT COUNT(*) FROM email_replies t " + q.sql(), q.argArray());
            long total = sql.count("SELECT COUNT(*) FROM email_replies t WHERE " + categoryFilter(name));
            out.put("unread_" + name, unreadCount);
            out.put("total_" + name, total);
            unreadSum += unreadCount;
            totalSum += total;
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String name : CATEGORIES) {
            ordered.put("unread_" + name, out.get("unread_" + name));
            ordered.put("total_" + name, out.get("total_" + name));
        }
        ordered.put("unread", unreadSum);
        ordered.put("total", totalSum);
        ordered.put("inbox_issues", sql.count("SELECT COUNT(*) FROM inbox_issues WHERE resolved_at IS NULL"));
        ordered.put("inbox", inbox.statusDict());
        ordered.put("reply_to", live.mailFrom());
        return ordered;
    }

    /** Partner messages, newest first. */
    public List<Object> listReplies(User user, String category, String show, String q, Integer partnerId) {
        SqlQuery query = new SqlQuery("LEFT JOIN partners p ON t.partner_id = p.id WHERE " + categoryFilter(category), new ArrayList<>());
        if ("unread".equals(show)) query = unread(query, user);
        if (partnerId != null && partnerId != 0) query = query.and("t.partner_id = ?", partnerId);
        if (!Py.strip(q).isEmpty()) {
            String like = "%" + Py.strip(q) + "%";
            query = query.and("(LOWER(t.from_email) LIKE LOWER(?) OR LOWER(t.from_name) LIKE LOWER(?) OR LOWER(t.subject) LIKE LOWER(?) "
                    + "OR LOWER(t.snippet) LIKE LOWER(?) OR LOWER(p.name) LIKE LOWER(?))", like, like, like, like, like);
        }
        List<EmailReply> found = sql.all(EmailReply.class, query.sql() + " ORDER BY t.received_at DESC, t.id DESC LIMIT 500", query.argArray());
        Set<Long> unreadIds = new HashSet<>();
        if (!found.isEmpty()) {
            List<Integer> ids = new ArrayList<>();
            for (EmailReply r : found) ids.add(r.getId());
            SqlQuery u = unread(new SqlQuery("WHERE t.id IN " + Sql.in(ids), new ArrayList<>(ids)), user);
            unreadIds.addAll(sql.ids("SELECT t.id FROM email_replies t " + u.sql(), u.argArray()));
        }
        Map<Integer, String> partners = partnerNames();
        List<Object> out = new ArrayList<>();
        for (EmailReply r : found) out.add(replyToDict(r, !unreadIds.contains(r.getId().longValue()), partners, false));
        return out;
    }

    EmailReply getReplyOr404(int replyId) {
        EmailReply r = replies.findById(replyId).orElse(null);
        if (r == null) throw new ApiException(404, "Message not found.");
        return r;
    }

    /** Opened by this user (in a session and transaction of its own: two tabs opening it at once is fine). */
    void markRead(User user, List<Integer> replyIds) {
        if (replyIds.isEmpty()) return;
        Integer userId = user.getId();
        Set<Long> done = new HashSet<>(sql.ids("SELECT reply_id FROM email_reply_reads WHERE user_id = ? AND reply_id IN " + Sql.in(replyIds),
                Sql.args(userId, replyIds)));
        try {
            separateSession.run(() -> tx.execute(status -> {
                for (Integer id : new LinkedHashSet<>(replyIds)) {
                    if (done.contains(id.longValue())) continue;
                    EmailReplyRead read = new EmailReplyRead();
                    read.setReplyId(id);
                    read.setUserId(userId);
                    replyReads.save(read);
                }
                replyReads.flush();
                return null;
            }));
        } catch (DataIntegrityViolationException e) {
            // opened in two tabs at once: already marked
        }
    }

    /** One reply with its text. Opening it marks it as read for this user. */
    public Map<String, Object> getReply(User user, int replyId) {
        EmailReply r = getReplyOr404(replyId);
        markRead(user, List.of(r.getId()));
        Map<String, Object> data = replyToDict(r, true, partnerNames(), true);
        EmailDispatch d = dispatchOf(r);
        if (d != null) {
            User preparedBy = Py.isEmpty(d.getSubmittedBy()) ? null : users.findByUsername(d.getSubmittedBy()).orElse(null);
            data.put("prepared_by", Py.map(
                    "username", d.getSubmittedBy(),
                    "name", preparedBy != null ? Py.or(preparedBy.getFullName(), preparedBy.getUsername()) : d.getSubmittedBy(),
                    "is_me", user.getUsername().equals(d.getSubmittedBy())));
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("problem", answerService.canAnswer(user, r));
            answer.putAll(answerService.defaults(r));
            data.put("answer", answer);
        }
        if (r.getSuggestedDispatchId() != null && r.getSuggestedDispatchId() != 0) {
            EmailDispatch s = dispatches.findById(r.getSuggestedDispatchId()).orElse(null);
            data.put("suggested", s == null ? null : Py.map("email_id", s.getId(), "partner_name", groups.partnerOf(s).getName(),
                    "subject", s.getSubject(), "sent_at", Py.iso(s.getSentAt()), "upload_filename", groups.uploadOf(s).getFilename()));
        }
        return data;
    }

    /** Mark one reply as read or as new again (for this user only). */
    public Map<String, Object> setReplyRead(User user, int replyId, boolean read) {
        EmailReply r = getReplyOr404(replyId);
        if (read) {
            markRead(user, List.of(r.getId()));
        } else {
            tx.executeWithoutResult(status -> sql.execute("DELETE FROM email_reply_reads WHERE reply_id = ? AND user_id = ?", r.getId(), user.getId()));
        }
        return repliesSummary(user);
    }

    /** Mark every new message as read: of one list (category), or of all of them. */
    public Map<String, Object> markAllRepliesRead(User user, String category) {
        SqlQuery q = unread(new SqlQuery("WHERE 1 = 1", new ArrayList<>()), user);
        if (category != null) q = q.and(categoryFilter(category));
        List<Integer> ids = new ArrayList<>();
        for (Long id : sql.ids("SELECT t.id FROM email_replies t " + q.sql(), q.argArray())) ids.add(id.intValue());
        markRead(user, ids);
        return repliesSummary(user);
    }

    /** An admin decides which sent email a message answers. */
    public Map<String, Object> linkReply(User admin, int replyId, Integer emailId) {
        EmailReply r = tx.execute(status -> {
            EmailReply reply = getReplyOr404(replyId);
            if (reply.getDispatchId() != null) throw new ApiException(409, "This message already belongs to an email.");
            if (Inbox.DISMISSED.equals(reply.getKind())) throw new ApiException(409, "This message was dismissed.");
            EmailDispatch d = emailId == null ? null : dispatches.findById(emailId).orElse(null);
            if (d == null || !"SENT".equals(d.getStatus())) throw new ApiException(400, "Choose a sent email.");
            reply.setDispatchId(d.getId());
            reply.setPartnerId(d.getPartnerId());
            reply.setMatchedBy("manual");
            if (Inbox.REVIEW.equals(reply.getKind()) || Inbox.PARTNER_MAIL.equals(reply.getKind())) reply.setKind(Inbox.REPLY);
            reply.setHandledBy(admin.getUsername());
            reply.setHandledAt(Py.now());
            String who = !Py.isEmpty(reply.getFromName()) ? reply.getFromName() + " <" + reply.getFromEmail() + ">" : reply.getFromEmail();
            LocalDateTime at = d.getSentAt() != null && d.getSentAt().isAfter(reply.getReceivedAt()) ? d.getSentAt() : reply.getReceivedAt();
            EmailEvent e = new EmailEvent();
            e.setDispatchId(d.getId());
            e.setKind(reply.getKind());
            e.setReplyId(reply.getId());
            e.setSubject(Py.cut(reply.getSubject(), 300));
            e.setNote(String.format(Inbox.EVENT_NOTES.get(reply.getKind()), who));
            e.setCreatedBy(admin.getUsername());
            e.setCreatedAt(at);
            events.save(e);
            if (Inbox.REPLY.equals(reply.getKind()) && "not_received".equals(d.getReplyStatus())) {
                d.setReplyStatus("received");
                d.setReplyStatusBy(admin.getUsername());
                d.setReplyStatusAt(Py.now());
                sentEmails.addEvent(d.getId(), "status", "received", "The partner's reply was linked to this email by hand.", admin.getUsername());
            }
            return reply;
        });
        return replyToDict(r, true, partnerNames(), false);
    }

    /** Not about any of our emails: hidden from the lists. */
    public Map<String, Object> dismissReply(User admin, int replyId) {
        tx.executeWithoutResult(status -> {
            EmailReply r = getReplyOr404(replyId);
            if (r.getDispatchId() != null) throw new ApiException(409, "This message belongs to an email and can't be dismissed.");
            r.setKind(Inbox.DISMISSED);
            r.setHandledBy(admin.getUsername());
            r.setHandledAt(Py.now());
        });
        return repliesSummary(admin);
    }

    // ---------------------------------------------------------------- inbox issues

    static Map<String, Object> issueToDict(InboxIssue i) {
        return Py.map("id", i.getId(), "uid", i.getUid(), "sender", i.getSender(), "subject", i.getSubject(), "error", i.getError(),
                "attempts", i.getAttempts(), "created_at", Py.iso(i.getCreatedAt()));
    }

    /** Messages in the mailbox that could not be read. */
    public List<Object> listInboxIssues() {
        List<Object> out = new ArrayList<>();
        for (InboxIssue i : sql.all(InboxIssue.class, "WHERE t.resolved_at IS NULL ORDER BY t.id DESC")) out.add(issueToDict(i));
        return out;
    }

    public Map<String, Object> retryInboxIssue(User admin, int issueId) {
        String outcome;
        try {
            outcome = inbox.retryIssue(issueId, admin.getUsername());
        } catch (Exception exc) {
            throw new ApiException(502, String.valueOf(exc.getMessage()));
        }
        return Py.map("outcome", outcome, "summary", repliesSummary(admin));
    }

    public Map<String, Object> dismissInboxIssue(User admin, int issueId) {
        tx.executeWithoutResult(status -> {
            InboxIssue issue = issues.findById(issueId).orElse(null);
            if (issue == null) throw new ApiException(404, "Issue not found.");
            issue.setResolvedAt(Py.now());
            issue.setResolvedBy(admin.getUsername());
        });
        return Py.map("ok", true);
    }

    /** A file attached to a partner's message: always downloaded as a file (never opened in the app's own page). */
    public FileDownload downloadReplyAttachment(int replyId, int attachmentId) {
        EmailReplyAttachment a = attachments.findById(attachmentId).orElse(null);
        if (a == null || !a.getReplyId().equals(replyId)) throw new ApiException(404, "Attachment not found.");
        // always downloaded as a file (never opened in the app's own page), whatever the partner sent
        String fallback = UNSAFE_NAME.matcher(a.getFilename()).replaceAll("_");
        if (fallback.isEmpty()) fallback = "attachment";
        String quoted = Py.quote(a.getFilename(), "/");
        return new FileDownload(a.getContent(), "application/octet-stream",
                "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + quoted);
    }

    /** Read the mailbox now instead of waiting for the next automatic check. */
    public Map<String, Object> checkInboxNow(User admin) {
        Map<String, Object> result;
        try {
            result = inbox.scan();
        } catch (Exception exc) {
            throw new ApiException(502, "The mailbox could not be checked: " + exc.getMessage());
        }
        return Py.map("result", result, "summary", repliesSummary(admin));
    }
}
