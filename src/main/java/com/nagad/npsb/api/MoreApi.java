package com.nagad.npsb.api;

import com.nagad.npsb.Config;
import com.nagad.npsb.Crypto;
import com.nagad.npsb.Py;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.ChangeRequest;
import com.nagad.npsb.models.EmailDispatch;
import com.nagad.npsb.models.EmailEvent;
import com.nagad.npsb.models.EmailReply;
import com.nagad.npsb.models.EmailReplyAttachment;
import com.nagad.npsb.models.EmailReplyRead;
import com.nagad.npsb.models.InboxIssue;
import com.nagad.npsb.models.User;
import com.nagad.npsb.service.Approvals;
import com.nagad.npsb.service.EmailService;
import com.nagad.npsb.service.Forwarding;
import com.nagad.npsb.service.Inbox;
import com.nagad.npsb.service.ServerSettings;
import com.nagad.npsb.service.Settings;
import com.nagad.npsb.service.Xlsx;
import com.nagad.npsb.web.ApiError;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.Req;
import com.nagad.npsb.web.Res;
import com.nagad.npsb.web.Server;
import com.nagad.npsb.web.ValueError;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Partner replies, inbox issues, approvals, activity, exports, System Settings and Email Configuration. */
public final class MoreApi {
    private MoreApi() {}

    static void routes(Server s) {
        s.get("/api/replies/summary", "New-reply count for the sidebar badge, and how the mailbox check is doing.", req ->
                repliesSummary(req.db(), Auth.currentUser(req)));
        s.get("/api/replies", "Partner messages, newest first.", MoreApi::listReplies);
        s.get("/api/replies/{reply_id}", "One reply with its text. Opening it marks it as read for this user.", MoreApi::getReply);
        s.put("/api/replies/{reply_id}/read", "Mark one reply as read or as new again (for this user only).", MoreApi::setReplyRead);
        s.post("/api/replies/read-all", "Mark every new message as read: of one list (category), or of all of them.", MoreApi::markAllRepliesRead);
        s.post("/api/replies/{reply_id}/link", "An admin decides which sent email a message answers.", MoreApi::linkReply);
        s.post("/api/replies/{reply_id}/forward", "Email a reply to the person who prepared the email it answers, or to yourself.",
                MoreApi::forwardReply);
        s.post("/api/replies/{reply_id}/dismiss", "Not about any of our emails: hidden from the lists.", MoreApi::dismissReply);
        s.get("/api/inbox-issues", "Messages in the mailbox that could not be read.", MoreApi::listInboxIssues);
        s.post("/api/inbox-issues/{issue_id}/retry", "Retry Inbox Issue", MoreApi::retryInboxIssue);
        s.post("/api/inbox-issues/{issue_id}/dismiss", "Dismiss Inbox Issue", MoreApi::dismissInboxIssue);
        s.get("/api/replies/{reply_id}/attachments/{attachment_id}", "Download Reply Attachment", MoreApi::downloadReplyAttachment);
        s.post("/api/replies/check-now", "Read the mailbox now instead of waiting for the next automatic check.", MoreApi::checkInboxNow);
        s.get("/api/change-requests", "Changes waiting for approval, or the change history, newest first.", MoreApi::listChangeRequests);
        s.post("/api/change-requests/{request_id}/approve", "Approve Change", MoreApi::approveChange);
        s.post("/api/change-requests/{request_id}/reject", "Reject Change", MoreApi::rejectChange);
        s.post("/api/change-requests/{request_id}/cancel", "Cancel Change", MoreApi::cancelChange);
        s.get("/api/activity", "A fingerprint of the emails and partner mail: open pages reload when it changes.", MoreApi::activity);
        s.get("/api/attention", "What needs someone's attention, for the banner and the menu counts.", MoreApi::attention);
        s.get("/api/pending-emails/export", "Pending Mails as an Excel file.", MoreApi::exportPending);
        s.get("/api/sent-emails/export", "Sent Mails, with the same filters as the page, as an Excel file.", MoreApi::exportSent);
        s.get("/api/server-settings", "Get Server Settings", req -> {
            Auth.requireAdmin(req);
            return serverSettingsDict(req.db());
        });
        s.put("/api/server-settings", "Save settings; they apply at once (connection changes are tested first).", MoreApi::updateServerSettings);
        s.post("/api/server-settings/test", "Try the mail server or the mailbox with these (unsaved) settings.", MoreApi::testServerSettings);
        s.get("/api/email-settings", "Get Email Settings", req -> {
            Auth.currentUser(req);
            return emailSettingsDict(req.db());
        });
        s.put("/api/email-settings", "Update Email Settings", MoreApi::updateEmailSettings);
        s.post("/api/email-settings/run-followups", "Look for due emails now instead of waiting for the next automatic check.",
                MoreApi::runFollowupsNow);
        s.post("/api/email-settings/test-email", "Send a short email to check the mail server settings.", MoreApi::sendTestEmail);
    }

    // ---------------------------------------------------------------- partners' replies (read from the mailbox)

    /** Replies this user has not opened. Replies found before the account existed don't count. (SQL + args) */
    static MainApi.Query unread(MainApi.Query q, User user) {
        return q.and("NOT EXISTS (SELECT 1 FROM email_reply_reads rr WHERE rr.reply_id = t.id AND rr.user_id = ?) AND t.found_at >= ?",
                user.id, Inbox.utcToLocal(user.createdAt));
    }

    static Map<Integer, String> partnerNames(Db db) {
        Map<Integer, String> out = new HashMap<>();
        for (Object[] row : db.rows("SELECT id, name FROM partners")) out.put(((Number) row[0]).intValue(), (String) row[1]);
        return out;
    }

    /** A partner message. category "reply": it answers one of our emails (original); "partner_mail": other mail. */
    static Map<String, Object> replyToDict(EmailReply r, boolean isRead, Map<Integer, String> partners, boolean full) {
        EmailDispatch d = r.dispatch();
        Integer partnerId = d != null ? d.partnerId : r.partnerId;
        List<Object> attachments = new ArrayList<>();
        for (EmailReplyAttachment a : r.attachments()) attachments.add(Py.map("id", a.id, "filename", a.filename, "size", a.size));
        Map<String, Object> data = Py.map(
                "id", r.id, "category", Inbox.REVIEW.equals(r.kind) ? "review" : d != null ? "reply" : "partner_mail", "kind", r.kind,
                "review_reason", r.reviewReason, "suggested_email_id", r.suggestedDispatchId,
                "handled_by", r.handledBy, "handled_at", Py.iso(r.handledAt),
                "email_id", d != null ? d.id : null, "partner_id", partnerId,
                "partner_name", partners.containsKey(partnerId) ? partners.get(partnerId) : "Unknown partner",
                "mail_type", d != null ? d.mailType : null, "from_email", r.fromEmail, "from_name", r.fromName,
                "subject", r.subject, "snippet", r.snippet, "matched_by", r.matchedBy,
                "received_at", Py.iso(r.receivedAt), "found_at", Py.iso(r.foundAt), "is_read", isRead,
                "attachments", attachments,
                "forward", Py.map("status", r.forwardStatus, "to", r.forwardTo, "note", r.forwardNote, "at", Py.iso(r.forwardedAt)),
                "original", d == null ? null : Py.map("subject", d.subject, "sent_at", Py.iso(d.sentAt),
                        "upload_id", d.uploadId, "txn_count", d.txnCount, "total_amount", Py.toDouble(d.totalAmount),
                        "reply_status", d.replyStatus, "to", Py.csv(d.toEmails)));
        if (full) {
            data.put("body_html", r.bodyHtml);
            data.put("to", Py.csv(r.toEmails));
            data.put("cc", Py.csv(r.ccEmails));
        }
        return data;
    }

    static final String[] CATEGORIES = {"replies", "partner_mail", "review"};

    static String categoryFilter(String category) {
        if (category.equals("replies")) return "t.dispatch_id IS NOT NULL";
        if (category.equals("review")) return "t.kind = 'review'";
        return "t.dispatch_id IS NULL AND t.kind NOT IN ('review', 'dismissed')";
    }

    /** New (unopened) and total messages: replies to our emails, other partner mail, and messages to review. */
    static Map<String, Object> repliesSummary(Db db, User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        long unreadSum = 0, totalSum = 0;
        for (String name : CATEGORIES) {
            MainApi.Query q = unread(new MainApi.Query("WHERE " + categoryFilter(name), new ArrayList<>()), user);
            long unreadCount = db.count("SELECT COUNT(*) FROM email_replies t " + q.sql(), q.args().toArray());
            long total = db.count("SELECT COUNT(*) FROM email_replies t WHERE " + categoryFilter(name));
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
        ordered.put("inbox_issues", db.count("SELECT COUNT(*) FROM inbox_issues WHERE resolved_at IS NULL"));
        ordered.put("inbox", Inbox.statusDict());
        ordered.put("reply_to", Config.mailFrom());
        return ordered;
    }

    static Object listReplies(Req req) {
        User user = Auth.currentUser(req);
        String category = req.queryLiteral("category", "replies", CATEGORIES);
        String show = req.queryLiteral("show", "all", "all", "unread");
        String q = req.query("q", "");
        Integer partnerId = req.queryInt("partner_id", null);
        Db db = req.db();
        MainApi.Query query = new MainApi.Query("LEFT JOIN partners p ON t.partner_id = p.id WHERE " + categoryFilter(category), new ArrayList<>());
        if ("unread".equals(show)) query = unread(query, user);
        if (partnerId != null && partnerId != 0) query = query.and("t.partner_id = ?", partnerId);
        if (!Py.strip(q).isEmpty()) {
            String like = "%" + Py.strip(q) + "%";
            query = query.and("(LOWER(t.from_email) LIKE LOWER(?) OR LOWER(t.from_name) LIKE LOWER(?) OR LOWER(t.subject) LIKE LOWER(?) "
                    + "OR LOWER(t.snippet) LIKE LOWER(?) OR LOWER(p.name) LIKE LOWER(?))", like, like, like, like, like);
        }
        List<EmailReply> replies = db.all(EmailReply.class, query.sql() + " ORDER BY t.received_at DESC, t.id DESC LIMIT 500",
                query.args().toArray());
        Set<Long> unreadIds = new HashSet<>();
        if (!replies.isEmpty()) {
            List<Integer> ids = new ArrayList<>();
            for (EmailReply r : replies) ids.add(r.id);
            MainApi.Query u = unread(new MainApi.Query("WHERE t.id IN " + Db.in(ids), new ArrayList<>(ids)), user);
            unreadIds.addAll(db.ids("SELECT t.id FROM email_replies t " + u.sql(), u.args().toArray()));
        }
        Map<Integer, String> partners = partnerNames(db);
        List<Object> out = new ArrayList<>();
        for (EmailReply r : replies) out.add(replyToDict(r, !unreadIds.contains((long) r.id), partners, false));
        return out;
    }

    static EmailReply getReplyOr404(Db db, int replyId) {
        EmailReply r = db.get(EmailReply.class, replyId);
        if (r == null) throw new ApiError(404, "Message not found.");
        return r;
    }

    /** Opened by this user (in a transaction of its own: two tabs opening it at once is fine). */
    static void markRead(Db db, User user, List<Integer> replyIds) {
        if (replyIds.isEmpty()) return;
        db.commit();
        Set<Long> done = new HashSet<>(db.ids("SELECT reply_id FROM email_reply_reads WHERE user_id = ? AND reply_id IN " + Db.in(replyIds),
                Db.args(user.id, replyIds)));
        db.commit();
        try (Db own = Db.open()) {
            for (Integer id : new java.util.LinkedHashSet<>(replyIds)) {
                if (done.contains((long) id)) continue;
                EmailReplyRead read = new EmailReplyRead();
                read.replyId = id;
                read.userId = user.id;
                own.add(read);
            }
            own.commit();
        } catch (Db.DbException e) {
            if (!e.integrity()) throw e;  // opened in two tabs at once: already marked
        }
    }

    static Object getReply(Req req) {
        User user = Auth.currentUser(req);
        int replyId = req.pathInt("reply_id");
        Db db = req.db();
        EmailReply r = getReplyOr404(db, replyId);
        markRead(db, user, List.of(r.id));
        Map<String, Object> data = replyToDict(r, true, partnerNames(db), true);
        EmailDispatch d = r.dispatch();
        if (d != null) {
            User preparedBy = Forwarding.composer(db, d);
            data.put("prepared_by", Py.map(
                    "username", d.submittedBy,
                    "name", preparedBy != null ? Py.or(preparedBy.fullName, preparedBy.username) : d.submittedBy,
                    "email", preparedBy != null ? preparedBy.email : null,
                    "problem", Forwarding.problem(preparedBy, d),
                    "is_me", user.username.equals(d.submittedBy)));
            data.put("my_email_problem", Forwarding.problem(user));
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("problem", Answers.canAnswer(db, user, r));
            answer.putAll(Answers.defaults(r));
            data.put("answer", answer);
        }
        if (r.suggestedDispatchId != null && r.suggestedDispatchId != 0) {
            EmailDispatch s = db.get(EmailDispatch.class, r.suggestedDispatchId);
            data.put("suggested", s == null ? null : Py.map("email_id", s.id, "partner_name", s.partner().name, "subject", s.subject,
                    "sent_at", Py.iso(s.sentAt), "upload_filename", s.upload().filename));
        }
        return data;
    }

    static Object setReplyRead(Req req) {
        User user = Auth.currentUser(req);
        int replyId = req.pathInt("reply_id");
        Body b = Body.of(req);
        boolean read = b.bool("read");
        b.done();
        Db db = req.db();
        EmailReply r = getReplyOr404(db, replyId);
        if (read) {
            markRead(db, user, List.of(r.id));
        } else {
            db.execute("DELETE FROM email_reply_reads WHERE reply_id = ? AND user_id = ?", r.id, user.id);
            db.commit();
        }
        return repliesSummary(db, user);
    }

    static Object markAllRepliesRead(Req req) {
        User user = Auth.currentUser(req);
        String category = req.query("category") == null ? null : req.queryLiteral("category", null, CATEGORIES);
        Db db = req.db();
        MainApi.Query q = unread(new MainApi.Query("WHERE 1 = 1", new ArrayList<>()), user);
        if (category != null) q = q.and(categoryFilter(category));
        List<Integer> ids = new ArrayList<>();
        for (Long id : db.ids("SELECT t.id FROM email_replies t " + q.sql(), q.args().toArray())) ids.add(id.intValue());
        markRead(db, user, ids);
        return repliesSummary(db, user);
    }

    static Object linkReply(Req req) {
        User admin = Auth.requireAdmin(req);
        int replyId = req.pathInt("reply_id");
        Body b = Body.of(req);
        Integer emailId = b.integer("email_id");
        b.done();
        Db db = req.db();
        EmailReply r = getReplyOr404(db, replyId);
        if (r.dispatchId != null) throw new ApiError(409, "This message already belongs to an email.");
        if (Inbox.DISMISSED.equals(r.kind)) throw new ApiError(409, "This message was dismissed.");
        EmailDispatch d = db.get(EmailDispatch.class, emailId);
        if (d == null || !"SENT".equals(d.status)) throw new ApiError(400, "Choose a sent email.");
        r.dispatchId = d.id;
        r.partnerId = d.partnerId;
        r.matchedBy = "manual";
        if (Inbox.REVIEW.equals(r.kind) || Inbox.PARTNER_MAIL.equals(r.kind)) r.kind = Inbox.REPLY;
        r.handledBy = admin.username;
        r.handledAt = Py.now();
        String who = !Py.isEmpty(r.fromName) ? r.fromName + " <" + r.fromEmail + ">" : r.fromEmail;
        LocalDateTime at = d.sentAt != null && d.sentAt.isAfter(r.receivedAt) ? d.sentAt : r.receivedAt;
        EmailEvent e = new EmailEvent();
        e.dispatchId = d.id;
        e.kind = r.kind;
        e.replyId = r.id;
        e.subject = Py.cut(r.subject, 300);
        e.note = String.format(Inbox.EVENT_NOTES.get(r.kind), who);
        e.createdBy = admin.username;
        e.createdAt = at;
        db.add(e);
        if (Inbox.REPLY.equals(r.kind) && "not_received".equals(d.replyStatus)) {
            d.replyStatus = "received";
            d.replyStatusBy = admin.username;
            d.replyStatusAt = Py.now();
            MainApi.addEvent(db, d.id, "status", "received", "The partner's reply was linked to this email by hand.", admin.username);
        }
        boolean queued = Forwarding.queue(r);
        db.commit();
        if (queued) Forwarding.kick();
        return replyToDict(r, true, partnerNames(db), false);
    }

    static Object forwardReply(Req req) {
        User user = Auth.currentUser(req);
        int replyId = req.pathInt("reply_id");
        Body b = Body.of(req);
        String to = b.literal("to", "prepared_by", "prepared_by", "me");
        b.done();
        Db db = req.db();
        EmailReply r = getReplyOr404(db, replyId);
        if (r.dispatch() == null) {
            throw new ApiError(400, "Only messages that belong to one of our emails can be forwarded. Link it to an email first.");
        }
        Object[] result;
        if (to.equals("me") && !user.username.equals(r.dispatch().submittedBy)) {
            String reason = Forwarding.problem(user);
            if (reason != null) throw new ApiError(400, reason);
            result = Forwarding.sendCopy(db, r, user, user.username);
            db.commit();
        } else {
            if (Forwarding.QUEUED.equals(r.forwardStatus) || Forwarding.SENDING.equals(r.forwardStatus)) {
                throw new ApiError(409, "It is being forwarded right now. Wait a moment and open it again.");
            }
            result = Forwarding.forwardToComposer(db, r, user.username);
        }
        boolean ok = (Boolean) result[0];
        String text = (String) result[1];
        if (!ok) throw new ApiError(text.contains("Could not forward") ? 502 : 400, text);
        return Py.map("message", text, "forward", replyToDict(r, true, new HashMap<>(), false).get("forward"));
    }

    static Object dismissReply(Req req) {
        User admin = Auth.requireAdmin(req);
        int replyId = req.pathInt("reply_id");
        Db db = req.db();
        EmailReply r = getReplyOr404(db, replyId);
        if (r.dispatchId != null) throw new ApiError(409, "This message belongs to an email and can't be dismissed.");
        r.kind = Inbox.DISMISSED;
        r.handledBy = admin.username;
        r.handledAt = Py.now();
        db.commit();
        return repliesSummary(db, admin);
    }

    static Map<String, Object> issueToDict(InboxIssue i) {
        return Py.map("id", i.id, "uid", i.uid, "sender", i.sender, "subject", i.subject, "error", i.error,
                "attempts", i.attempts, "created_at", Py.iso(i.createdAt));
    }

    static Object listInboxIssues(Req req) {
        Auth.requireAdmin(req);
        List<Object> out = new ArrayList<>();
        for (InboxIssue i : req.db().all(InboxIssue.class, "WHERE t.resolved_at IS NULL ORDER BY t.id DESC")) out.add(issueToDict(i));
        return out;
    }

    static Object retryInboxIssue(Req req) {
        User admin = Auth.requireAdmin(req);
        int issueId = req.pathInt("issue_id");
        String outcome;
        try {
            outcome = Inbox.retryIssue(issueId, admin.username);
        } catch (Exception exc) {
            throw new ApiError(502, String.valueOf(exc.getMessage()));
        }
        return Py.map("outcome", outcome, "summary", repliesSummary(req.db(), admin));
    }

    static Object dismissInboxIssue(Req req) {
        User admin = Auth.requireAdmin(req);
        int issueId = req.pathInt("issue_id");
        Db db = req.db();
        InboxIssue issue = db.get(InboxIssue.class, issueId);
        if (issue == null) throw new ApiError(404, "Issue not found.");
        issue.resolvedAt = Py.now();
        issue.resolvedBy = admin.username;
        db.commit();
        return Py.map("ok", true);
    }

    private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9._ -]");

    static Object downloadReplyAttachment(Req req) {
        Auth.currentUser(req);
        int replyId = req.pathInt("reply_id"), attachmentId = req.pathInt("attachment_id");
        EmailReplyAttachment a = req.db().get(EmailReplyAttachment.class, attachmentId);
        if (a == null || !a.replyId.equals(replyId)) throw new ApiError(404, "Attachment not found.");
        // always downloaded as a file (never opened in the app's own page), whatever the partner sent
        String fallback = UNSAFE_NAME.matcher(a.filename).replaceAll("_");
        if (fallback.isEmpty()) fallback = "attachment";
        String quoted = Py.quote(a.filename, "/");
        return Res.file(a.data(), "application/octet-stream", "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + quoted);
    }

    static Object checkInboxNow(Req req) {
        User admin = Auth.requireAdmin(req);
        Map<String, Object> result;
        try {
            result = Inbox.scan();
        } catch (Exception exc) {
            throw new ApiError(502, "The mailbox could not be checked: " + exc.getMessage());
        }
        return Py.map("result", result, "summary", repliesSummary(req.db(), admin));
    }

    // ---------------------------------------------------------------- approvals (maker-checker) and change history

    static Object listChangeRequests(Req req) {
        User user = Auth.currentUser(req);
        String status = req.queryLiteral("status", "pending", "pending", "history", "all");
        String entity = req.query("entity", "");
        Integer entityId = req.queryInt("entity_id", null);
        Db db = req.db();
        MainApi.Query q = new MainApi.Query("WHERE 1 = 1", new ArrayList<>());
        if (status.equals("pending")) q = q.and("t.status = 'pending'");
        else if (status.equals("history")) q = q.and("t.status != 'pending'");
        if (!entity.isEmpty()) q = q.and("t.entity = ?", entity);
        if (entityId != null) q = q.and("t.entity_id = ?", entityId);
        List<ChangeRequest> rows = db.all(ChangeRequest.class, q.sql() + " ORDER BY t.id DESC LIMIT 300", q.args().toArray());
        List<String> who = new ArrayList<>();
        for (ChangeRequest r : rows) { who.add(r.requestedBy); who.add(r.decidedBy); }
        Map<String, String> names = MainApi.userNames(db, who);
        List<Object> out = new ArrayList<>();
        for (ChangeRequest r : rows) out.add(Approvals.toDict(r, names, user));
        return out;
    }

    static Map<String, Object> changeRequestDict(Db db, ChangeRequest r, User user) {
        return Approvals.toDict(r, MainApi.userNames(db, Arrays.asList(r.requestedBy, r.decidedBy)), user);
    }

    static Object approveChange(Req req) {
        User admin = Auth.requireAdmin(req);
        int requestId = req.pathInt("request_id");
        Db db = req.db();
        return changeRequestDict(db, Approvals.approve(db, requestId, admin), admin);
    }

    static Object rejectChange(Req req) {
        User admin = Auth.requireAdmin(req);
        int requestId = req.pathInt("request_id");
        Body b = Body.of(req);
        String note = b.str("note", "");
        b.done();
        Db db = req.db();
        return changeRequestDict(db, Approvals.reject(db, requestId, admin, note), admin);
    }

    static Object cancelChange(Req req) {
        User user = Auth.currentUser(req);
        int requestId = req.pathInt("request_id");
        Db db = req.db();
        return changeRequestDict(db, Approvals.cancel(db, requestId, user), user);
    }

    /**
     * A fingerprint of the emails and partner mail: it changes when an email is sent for approval, sent, fails, gets
     * a reply, a follow-up, a note or a status change, or a change is approved. Open pages ask for it every few
     * seconds and reload only when it changed.
     */
    static Object activity(Req req) {
        Auth.currentUser(req);
        Db db = req.db();
        List<Object> parts = new ArrayList<>();
        parts.add(Arrays.asList(db.rows("SELECT COUNT(id), MAX(id), MAX(submitted_at), MAX(sent_at), MAX(sending_since), "
                + "MAX(reply_status_at), MAX(remarks_at), MAX(last_followup_at) FROM email_dispatches").get(0)));
        List<Object> statuses = new ArrayList<>();
        for (Object[] row : db.rows("SELECT status, COUNT(id) FROM email_dispatches GROUP BY status ORDER BY status")) statuses.add(Arrays.asList(row));
        parts.add(statuses);
        parts.add(db.scalar("SELECT MAX(id) FROM email_events"));
        parts.add(Arrays.asList(db.rows("SELECT COUNT(id), MAX(id), MAX(handled_at), MAX(forwarded_at) FROM email_replies").get(0)));
        List<Object> forwards = new ArrayList<>();
        for (Object[] row : db.rows("SELECT forward_status, COUNT(id) FROM email_replies GROUP BY forward_status ORDER BY forward_status")) {
            forwards.add(Arrays.asList(row));
        }
        parts.add(forwards);
        parts.add(Arrays.asList(db.rows("SELECT MAX(id), MAX(decided_at) FROM change_requests").get(0)));
        parts.add(Answers.activityParts(db));
        return Py.map("stamp", Crypto.sha1Hex(String.valueOf(parts)).substring(0, 16));
    }

    static Object attention(Req req) {
        User user = Auth.currentUser(req);
        Db db = req.db();
        boolean admin = "admin".equals(user.role);
        Map<String, Object> out = Py.map(
                "approvals", admin ? db.count("SELECT COUNT(*) FROM change_requests WHERE status = 'pending' AND requested_by != ?", user.username) : 0,
                "my_requests", db.count("SELECT COUNT(*) FROM change_requests WHERE status = 'pending' AND requested_by = ?", user.username),
                "failed_emails", db.count("SELECT COUNT(*) FROM email_dispatches WHERE status = 'FAILED'"),
                "inbox_error", null, "inbox_issues", 0, "review", 0, "mail_error", null,
                // answers to partners waiting for an admin (admins) / your answers sent back to you (users)
                "answers", admin ? Answers.waitingCount(db) : 0,
                "my_rejected_answers", db.count("SELECT COUNT(*) FROM reply_answers WHERE created_by = ? AND status = 'REJECTED'", user.username));
        if (admin) {
            Map<String, Object> status = Inbox.statusDict();
            out.put("inbox_error", Boolean.TRUE.equals(status.get("enabled")) ? status.get("last_error") : null);
            out.put("inbox_issues", db.count("SELECT COUNT(*) FROM inbox_issues WHERE resolved_at IS NULL"));
            out.put("review", db.count("SELECT COUNT(*) FROM email_replies WHERE kind = 'review'"));
            EmailEvent lastSend = db.first(EmailEvent.class, "WHERE t.kind IN ('sent', 'send_failed', 'followup') ORDER BY t.created_at DESC, t.id DESC");
            if (lastSend != null && !Boolean.TRUE.equals(lastSend.ok)) out.put("mail_error", lastSend.error);
        }
        return out;
    }

    static Res.File xlsxResponse(List<Map<String, Object>> rows, String name) {
        List<String> headers;
        List<List<Object>> table = new ArrayList<>();
        if (rows.isEmpty()) {
            headers = List.of("");
            table.add(List.of("No emails"));
        } else {
            headers = new ArrayList<>(rows.get(0).keySet());
            for (Map<String, Object> r : rows) table.add(new ArrayList<>(r.values()));
        }
        String filename = name + "_" + Py.strftime(LocalDateTime.now(), "%Y-%m-%d_%H%M") + ".xlsx";
        return Res.file(Xlsx.write(headers, table, Py.cut(name, 31)), MainApi.XLSX_TYPE, "attachment; filename=\"" + filename + "\"");
    }

    static Object exportPending(Req req) {
        Auth.currentUser(req);
        Db db = req.db();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> e : MainApi.dispatchesToDicts(db, MainApi.pendingQuery(db))) {
            rows.add(Py.map(
                    "Email #", e.get("id"), "Partner", e.get("partner_name"), "Mail type", e.get("mail_type_label"),
                    "To", String.join(", ", strings(e.get("to"))), "CC", String.join(", ", strings(e.get("cc"))),
                    "Subject", e.get("subject"), "File", e.get("upload_filename"), "Transactions", e.get("txn_count"),
                    "Amount (BDT)", e.get("total_amount"), "Status", e.get("status"), "Error", or(e.get("error")),
                    "Sent for approval by", or(e.get("submitted_by_name")), "Sent for approval at", or(e.get("submitted_at"))));
        }
        return xlsxResponse(rows, "Pending_Mails");
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object v) {
        return (List<String>) v;
    }

    static Object or(Object v) {
        return v == null || "".equals(v) ? "" : v;
    }

    static Object exportSent(Req req) {
        Auth.currentUser(req);
        String q = req.query("q", "");
        Integer partnerId = req.queryInt("partner_id", null);
        LocalDate dateFrom = req.queryDate("date_from"), dateTo = req.queryDate("date_to");
        String reply = req.queryLiteral("reply", "", MainApi.REPLY_FILTERS);
        Db db = req.db();
        MainApi.Query query = MainApi.withReply(MainApi.sentQuery(q, partnerId, dateFrom, dateTo), reply);
        List<EmailDispatch> emails = db.all(EmailDispatch.class, query.sql() + " ORDER BY t.sent_at DESC, t.id DESC LIMIT 5000",
                query.args().toArray());
        List<Integer> ids = new ArrayList<>();
        for (EmailDispatch d : emails) ids.add(d.id);
        if (ids.isEmpty()) ids.add(0);
        Map<Integer, Long> replyCounts = new HashMap<>();
        for (Object[] row : db.rows("SELECT dispatch_id, COUNT(id) FROM email_replies WHERE dispatch_id IN " + Db.in(ids)
                + " AND kind = 'reply' GROUP BY dispatch_id", ids.toArray())) {
            replyCounts.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> e : MainApi.dispatchesToDicts(db, emails)) {
            rows.add(Py.map(
                    "Email #", e.get("id"), "Partner", e.get("partner_name"), "Mail type", e.get("mail_type_label"),
                    "To", String.join(", ", strings(e.get("to"))),
                    "Subject", e.get("subject"), "File", e.get("upload_filename"), "Transactions", e.get("txn_count"),
                    "Amount (BDT)", e.get("total_amount"), "Sent at", or(e.get("sent_at")), "Sent by", or(e.get("sent_by_name")),
                    "Reply status", MainApi.REPLY_STATUS_LABELS.getOrDefault((String) e.get("reply_status"), (String) e.get("reply_status")),
                    "Replies", replyCounts.getOrDefault((Integer) e.get("id"), 0L), "Follow-ups sent", e.get("followup_count"),
                    "Last follow-up", or(e.get("last_followup_at")), "Follow-up due", Boolean.TRUE.equals(e.get("followup_due")) ? "Yes" : "",
                    "Remarks", e.get("remarks")));
        }
        return xlsxResponse(rows, "Sent_Mails");
    }

    // ---------------------------------------------------------------- System Settings (admins only)

    static Map<String, Object> serverSettingsDict(Db db) {
        return ServerSettings.toDict(db, MainApi.userNames(db, ServerSettings.usernames(db)));
    }

    /** What is still missing for the Gmail account (labels), empty when it can be tried. */
    static List<String> gmailMissing(Map<String, Object> v) {
        List<String> out = new ArrayList<>();
        if (Py.isEmpty(Py.strip((String) v.get("GMAIL_ADDRESS")))) out.add("Gmail address");
        if (Py.isEmpty(Config.gmailPassword((String) v.get("GMAIL_APP_PASSWORD")))) out.add("App password");
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

    @SuppressWarnings("unchecked")
    static Object[] parseOr400(Map<String, Object> values, List<String> reset) {
        try {
            return ServerSettings.parseChanges(values, reset);
        } catch (ServerSettings.SettingsError exc) {
            throw new ApiError(400, exc.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static Object updateServerSettings(Req req) {
        User admin = Auth.requireAdmin(req);
        Body b = Body.of(req);
        Map<String, Object> values = b.dict("values", new LinkedHashMap<>());
        List<String> reset = b.strList("reset", List.of());
        boolean force = b.bool("force", false);
        b.done();
        Db db = req.db();
        Object[] parsed = parseOr400(values, reset);
        Map<String, Object> changes = (Map<String, Object>) parsed[0];
        Set<String> resets = (Set<String>) parsed[1];
        if (changes.isEmpty() && resets.isEmpty()) {
            Map<String, Object> out = serverSettingsDict(db);
            out.put("changed", new ArrayList<>());
            return out;
        }
        Map<String, Object> old = ServerSettings.effective(), next = ServerSettings.effective(changes, resets);
        if (!force) {
            List<String> problems = new ArrayList<>();
            boolean switched = !old.get("MAIL_PROVIDER").equals(next.get("MAIL_PROVIDER"));
            if ("gmail".equals(next.get("MAIL_PROVIDER"))) {
                boolean changed = switched || (Boolean.TRUE.equals(next.get("INBOX_ENABLED")) && !Boolean.TRUE.equals(old.get("INBOX_ENABLED")));
                for (String k : ServerSettings.GMAIL_FIELDS) changed |= !java.util.Objects.equals(old.get(k), next.get(k));
                if (changed) {
                    String problem = connectionProblem("gmail", next);
                    if (problem != null) problems.add("Gmail: " + problem);
                }
            } else {
                boolean changed = switched;
                for (String k : ServerSettings.SMTP_FIELDS) changed |= !java.util.Objects.equals(old.get(k), next.get(k));
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
                throw new ApiError(409, Py.map("code", "test_failed", "problems", problems, "message", "Not saved: " + String.join(" ", problems)));
            }
        }
        List<String> changed = ServerSettings.save(db, changes, resets, admin.username);
        if (!changed.isEmpty()) {
            Auth.logEvent(db, req, "settings_changed", admin, "System Settings: " + String.join(", ", changed));
            Set<String> secret = new HashSet<>();
            for (ServerSettings.Field f : ServerSettings.FIELDS) if (f.kind().equals("secret")) secret.add(f.name());
            List<String> labels = new ArrayList<>();
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            Map<String, Object> now = ServerSettings.effective();
            for (String n : changed) {
                labels.add(ServerSettings.BY_NAME.get(n).label());
                before.put(n, shown(secret, n, old.get(n)));
                after.put(n, shown(secret, n, now.get(n)));
            }
            Approvals.record(db, "settings", "update", Py.cut(String.join(", ", labels), 255), before, after, admin.username);
            db.commit();
            Inbox.wake();               // check the mailbox with the new settings now
            MainApi.wakeFollowups();    // and restart the follow-up timer with its new interval
        }
        Map<String, Object> out = serverSettingsDict(db);
        out.put("changed", changed);
        return out;
    }

    private static Object shown(Set<String> secret, String name, Object v) {
        if (!secret.contains(name)) return v;
        boolean set = v != null && !(v instanceof String s && s.isEmpty());
        return set ? "(set)" : "(empty)";
    }

    @SuppressWarnings("unchecked")
    static Object testServerSettings(Req req) {
        Auth.requireAdmin(req);
        Body b = Body.of(req);
        String target = b.literal("target", null, "smtp", "imap", "gmail");
        Map<String, Object> values = b.dict("values", new LinkedHashMap<>());
        b.done();
        Object[] parsed = parseOr400(values, List.of());
        Map<String, Object> v = ServerSettings.effective((Map<String, Object>) parsed[0], null);
        String problem = connectionProblem(target, v);
        if (problem != null) throw new ApiError(502, problem);
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

    // ---------------------------------------------------------------- email configuration

    private static final Pattern EMAIL_RE = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+", Pattern.UNICODE_CHARACTER_CLASS);

    static List<String> cleanList(List<String> value, String what) {
        List<String> cleaned = new ArrayList<>();
        for (String address : value) {
            address = Py.strip(address).toLowerCase(java.util.Locale.ROOT);
            if (address.isEmpty()) continue;
            if (!EMAIL_RE.matcher(address).matches()) throw new ValueError("'" + address + "' is not a valid email address");
            if (!cleaned.contains(address)) cleaned.add(address);
        }
        if (cleaned.size() > 20) throw new ValueError("At most 20 " + what + " addresses");
        return cleaned;
    }

    static Map<String, Object> emailSettingsDict(Db db) {
        Map<String, Object> values = Settings.current(db);
        Map<String, Object> change = Settings.lastChange(db);
        Map<String, String> names = MainApi.userNames(db, change != null ? Arrays.asList((String) change.get("by")) : List.of());
        Map<String, Object> out = new LinkedHashMap<>(values);
        out.put("defaults", Settings.DEFAULTS);
        out.put("updated_by_name", change != null ? MainApi.name(names, (String) change.get("by")) : null);
        out.put("updated_at", change != null ? change.get("at") : null);
        MainApi.Query due = MainApi.dueFilter(new MainApi.Query("WHERE t.status = 'SENT'", new ArrayList<>()));
        out.put("due_now", db.count("SELECT COUNT(*) FROM email_dispatches t " + due.sql(), due.args().toArray()));
        out.put("auto_eligible_now", MainApi.autoCandidates(db, ((Number) values.get("auto_followup_max")).intValue()).size());
        out.put("check_every_seconds", Config.AUTO_FOLLOWUP_CHECK_SECONDS());
        synchronized (MainApi.AUTO_STATUS) {
            out.put("auto_status", new LinkedHashMap<>(MainApi.AUTO_STATUS));
        }
        out.put("inbox", Inbox.statusDict());
        out.put("smtp", Py.map("provider", Config.MAIL_PROVIDER(),
                "configured", !Config.gmail() || gmailMissing(ServerSettings.effective()).isEmpty(),
                "host", Config.SMTP_HOST(), "port", Config.SMTP_PORT(), "use_tls", Config.SMTP_USE_TLS(),
                "user", Config.SMTP_USER(), "mail_from", Config.mailFrom(),
                "max_attempts", Config.SMTP_MAX_ATTEMPTS(), "retry_seconds", Config.SMTP_RETRY_SECONDS()));
        return out;
    }

    static Object updateEmailSettings(Req req) {
        User admin = Auth.requireAdmin(req);
        Body b = Body.of(req);
        Integer followupDueDays = b.check("followup_due_days", b.integer("followup_due_days"), v -> {
            if (v < 1 || v > 90) throw new ValueError("Follow-up days must be between 1 and 90");
            return v;
        });
        Boolean autoEnabled = b.bool("auto_followup_enabled");
        Integer autoMax = b.check("auto_followup_max", b.integer("auto_followup_max"), v -> {
            if (v < 1 || v > 20) throw new ValueError("Follow-ups per email must be between 1 and 20");
            return v;
        });
        List<String> cc = b.check("cc_emails", b.strList("cc_emails", null), v -> cleanList(v, "CC"));
        Integer window = b.check("duplicate_window_days", b.integer("duplicate_window_days", 30), v -> {
            if (v < 0 || v > 365) throw new ValueError("Days for repeated transactions must be between 0 (off) and 365");
            return v;
        });
        List<String> ops = b.check("ops_alert_emails", b.strList("ops_alert_emails", List.of()), v -> cleanList(v, "alert"));
        Boolean forward = b.bool("forward_replies", true);
        b.done();
        Db db = req.db();
        Settings.save(db, Py.map("followup_due_days", followupDueDays, "auto_followup_enabled", autoEnabled, "auto_followup_max", autoMax,
                "cc_emails", cc, "duplicate_window_days", window, "ops_alert_emails", ops, "forward_replies", forward), admin.username);
        return emailSettingsDict(db);
    }

    static Object runFollowupsNow(Req req) {
        Auth.requireAdmin(req);
        Map<String, Object> result = MainApi.runAutoFollowups();
        return Py.map("result", result, "settings", emailSettingsDict(req.db()));
    }

    static Object sendTestEmail(Req req) {
        Auth.requireAdmin(req);
        Body b = Body.of(req);
        String to = b.check("to", b.str("to"), v -> {
            v = Py.strip(v);
            if (!EMAIL_RE.matcher(v).matches()) throw new ValueError("'" + v + "' is not a valid email address");
            return v;
        });
        b.done();
        String body = "<div style=\"font-family:Arial,sans-serif;font-size:14px\">"
                + "<p>This is a test email from NPSB Dispute Email Automation.</p>"
                + (Config.gmail() ? "<p>Sent through Gmail from " + Py.escape(Config.mailFrom()) + ".</p></div>"
                : "<p>Mail server: " + Config.SMTP_HOST() + ":" + Config.SMTP_PORT() + "</p></div>");
        EmailService.SendResult result;
        try {
            result = EmailService.sendEmail(List.of(to), List.of(), "NPSB Dispute Email Automation - test email", body, null, null, null);
        } catch (Exception exc) {
            throw new ApiError(502, "The test email could not be sent: " + exc.getMessage());
        }
        if (!result.refused().isEmpty()) {
            throw new ApiError(502, "The mail server refused " + to + ": " + String.join("; ", result.refused().values()));
        }
        return Py.map("ok", true, "message", "Test email sent to " + to + ".");
    }
}
