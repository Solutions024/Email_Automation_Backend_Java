package com.nagad.npsb.api;

import com.nagad.npsb.Py;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.EmailDispatch;
import com.nagad.npsb.models.EmailEvent;
import com.nagad.npsb.models.EmailReply;
import com.nagad.npsb.models.ReplyAnswer;
import com.nagad.npsb.models.User;
import com.nagad.npsb.service.EmailService;
import com.nagad.npsb.web.ApiError;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.Req;
import com.nagad.npsb.web.Server;
import com.nagad.npsb.web.ValueError;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Our answers to partners' replies, written in the app and approved by an admin before they are sent.
 *
 * Who can answer a partner's reply: anyone (Partner Replies is shared, so a colleague can take over while the
 * person who prepared the email is away). The answer waits on Pending Mails until an admin approves it (it is then sent to the
 * partner in the same email thread, with the partner's message quoted) or rejects it with a reason (the writer can
 * change it and send it for approval again). The partner's reply to an answer is matched to the email like any
 * other reply (Inbox knows the answers' Message-IDs) and can be answered again:
 * GET /api/emails/{id}/conversation returns the whole back-and-forth as a tree.
 */
public final class Answers {
    private Answers() {}

    static final String PENDING = "PENDING", SENDING = "SENDING", SENT = "SENT", FAILED = "FAILED", REJECTED = "REJECTED",
            CANCELLED = "CANCELLED";
    static final List<String> EDITABLE = List.of(PENDING, REJECTED, FAILED);  // the writer can still change or cancel it
    static final long CLAIM_EXPIRES_MINUTES = 10;                             // "sending" for longer: the server stopped mid-way
    private static final Pattern ADDRESS_RE = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PREFIX_RE = Pattern.compile("^\\s*re\\s*:", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    public static void routes(Server s) {
        s.post("/api/replies/{reply_id}/answers", 201, "Write an answer to a partner's reply: it waits for an admin's approval.",
                Answers::createAnswer);
        s.put("/api/answers/{answer_id}", "The writer changes their answer: it waits for approval again.", Answers::updateAnswer);
        s.post("/api/answers/{answer_id}/cancel", "Cancel Answer", Answers::cancelAnswer);
        s.post("/api/answers/{answer_id}/reject", "Admin sends an answer back to its writer, with a reason.", Answers::rejectAnswer);
        s.post("/api/answers/{answer_id}/approve", "Admin approves an answer: it is sent to the partner in the same email thread.",
                Answers::approveAnswer);
        s.get("/api/answers", "Answers waiting for approval (or failed); plus your own rejected answers.", Answers::listAnswers);
        s.get("/api/emails/{email_id}/conversation", "The back-and-forth of one email as a tree.", Answers::conversation);
    }

    static Map<String, String> namesOf(Db db, Collection<String> usernames) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String u : usernames) if (!Py.isEmpty(u)) wanted.add(u);
        Map<String, String> out = new HashMap<>();
        out.put("#auto", "Sent automatically");
        out.put("#inbox", "Found in the mailbox");
        if (!wanted.isEmpty()) {
            for (Object[] row : db.rows("SELECT username, full_name FROM users WHERE username IN " + Db.in(wanted), wanted.toArray())) {
                out.put((String) row[0], Py.or((String) row[1], (String) row[0]));
            }
        }
        return out;
    }

    /** null when this user may answer the message, else why not. */
    public static String canAnswer(Db db, User user, EmailReply r) {
        if (r.dispatch() == null) return "Only replies to one of our emails can be answered. Link it to an email first.";
        if (!"reply".equals(r.kind)) return "Automatic replies and delivery failure notices can't be answered.";
        return null;  // shared dashboard: anyone can answer, e.g. while the person who prepared the email is away
    }

    /** Pre-filled answer: to the partner who wrote, our team in CC, "Re:" subject. */
    public static Map<String, Object> defaults(EmailReply r) {
        EmailDispatch d = r.dispatch();
        String subject = PREFIX_RE.matcher(r.subject).find() ? r.subject : "Re: " + r.subject;
        return Py.map("to", List.of(r.fromEmail), "cc", Py.csv(d != null ? d.ccEmails : ""), "subject", Py.cut(subject, 300));
    }

    /** What the writer typed, as HTML paragraphs. */
    static String textHtml(String text) {
        StringBuilder b = new StringBuilder();
        for (String p : Py.strip(text).split("\\n\\s*\\n", -1)) {
            if (Py.strip(p).isEmpty()) continue;
            String paragraph = Py.strip(p, "\n");
            b.append("<p style=\"margin:0 0 12px\">").append(Py.escape(paragraph).replace("\n", "<br/>")).append("</p>");
        }
        return b.toString();
    }

    /** The email as sent: the answer, our signature, then the partner's message quoted. */
    static String fullHtml(ReplyAnswer a) {
        EmailReply r = a.reply();
        String who = !Py.isEmpty(r.fromName) ? Py.escape(r.fromName) + " &lt;" + Py.escape(r.fromEmail) + "&gt;" : Py.escape(r.fromEmail);
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222;line-height:1.45\">"
                + textHtml(a.bodyText) + EmailService.SIGNATURE
                + "<div style=\"margin-top:16px;color:#555\">On " + Py.strftime(r.receivedAt, "%d %b %Y %H:%M") + ", " + who + " wrote:</div>"
                + "<blockquote style=\"margin:6px 0 0;border-left:2px solid #ccc;padding-left:10px;color:#555\">"
                + r.bodyHtml + "</blockquote></div>";
    }

    /** A Message-ID as a header value; null for ids we made up for messages without one. */
    static String bracketed(String messageId) {
        if (Py.isEmpty(messageId) || messageId.startsWith("imap-")) return null;
        return messageId.startsWith("<") ? messageId : "<" + messageId + ">";
    }

    static Map<String, Object> answerToDict(ReplyAnswer a, User user, Map<String, String> names) {
        EmailReply r = a.reply();
        EmailDispatch d = a.dispatch();
        boolean mine = a.createdBy.equals(user.username);
        boolean admin = "admin".equals(user.role);
        return Py.map(
                "id", a.id, "email_id", a.dispatchId, "reply_id", a.replyId, "partner_name", d.partner().name,
                "to", Py.csv(a.toEmails), "cc", Py.csv(a.ccEmails),
                "subject", a.subject, "text", a.bodyText, "status", a.status,
                "created_by", a.createdBy, "created_by_name", names.getOrDefault(a.createdBy, a.createdBy),
                "created_at", Py.iso(a.createdAt), "submitted_at", Py.iso(a.submittedAt),
                "decided_by_name", a.decidedBy != null ? names.getOrDefault(a.decidedBy, a.decidedBy) : null,
                "decided_at", Py.iso(a.decidedAt), "note", a.decisionNote,
                "sent_at", Py.iso(a.sentAt), "error", a.error,
                "reply", Py.map("from_email", r.fromEmail, "from_name", r.fromName, "subject", r.subject,
                        "received_at", Py.iso(r.receivedAt), "snippet", r.snippet),
                "email", Py.map("subject", d.subject, "sent_at", Py.iso(d.sentAt), "upload_id", d.uploadId),
                "can_edit", mine && EDITABLE.contains(a.status),
                "can_cancel", (mine || admin) && EDITABLE.contains(a.status),
                "can_approve", admin && (PENDING.equals(a.status) || FAILED.equals(a.status)));
    }

    static List<Map<String, Object>> toDicts(Db db, List<ReplyAnswer> answers, User user) {
        List<String> who = new ArrayList<>();
        for (ReplyAnswer a : answers) { who.add(a.createdBy); who.add(a.decidedBy); }
        Map<String, String> names = namesOf(db, who);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReplyAnswer a : answers) out.add(answerToDict(a, user, names));
        return out;
    }

    static ReplyAnswer getAnswerOr404(Db db, int answerId) {
        ReplyAnswer a = db.get(ReplyAnswer.class, answerId);
        if (a == null) throw new ApiError(404, "Answer not found.");
        return a;
    }

    static void history(Db db, ReplyAnswer a, String kind, String by, String note, String to, String cc, String bodyHtml,
                        Boolean ok, String error, String messageId) {
        EmailEvent e = new EmailEvent();
        e.dispatchId = a.dispatchId;
        e.kind = kind;
        e.replyId = a.replyId;
        e.subject = a.subject;
        e.note = note;
        e.createdBy = by;
        e.toEmails = to;
        e.ccEmails = cc;
        e.bodyHtml = bodyHtml;
        if (ok != null) e.ok = ok;
        e.error = error;
        e.messageId = messageId;
        db.add(e);
    }

    static String addressed(EmailReply r) {
        return Py.or(r.fromName, r.fromEmail);
    }

    /** The answer form: {to, cc, subject, text}. */
    private record AnswerIn(List<String> to, List<String> cc, String subject, String text) {}

    private static List<String> checkAddresses(List<String> value) {
        List<String> cleaned = new ArrayList<>();
        for (String address : value) {
            address = Py.strip(address);
            if (address.isEmpty()) continue;
            if (!ADDRESS_RE.matcher(address).matches()) throw new ValueError("'" + address + "' is not a valid email address");
            boolean seen = false;
            for (String c : cleaned) if (c.equalsIgnoreCase(address)) seen = true;
            if (!seen) cleaned.add(address);
        }
        if (cleaned.size() > 20) throw new ValueError("At most 20 addresses");
        return cleaned;
    }

    private static AnswerIn readAnswer(Req req) {
        Body b = Body.of(req);
        List<String> to = b.check("to", b.strList("to", null), v -> {
            List<String> cleaned = checkAddresses(v);
            if (cleaned.isEmpty()) throw new ValueError("Add at least one recipient");
            return cleaned;
        });
        List<String> cc = b.check("cc", b.strList("cc", List.of()), Answers::checkAddresses);
        String subject = b.check("subject", b.str("subject"), v -> {
            v = Py.strip(v);
            if (v.isEmpty()) throw new ValueError("Subject cannot be empty");
            if (v.length() > 300) throw new ValueError("Subject is too long (max 300 characters)");
            return v;
        });
        String text = b.check("text", b.str("text"), v -> {
            if (Py.strip(v).isEmpty()) throw new ValueError("Write your answer first");
            if (v.length() > 20000) throw new ValueError("The answer is too long (max 20000 characters)");
            return Py.strip(v);
        });
        b.done();
        return new AnswerIn(to, cc, subject, text);
    }

    static Object createAnswer(Req req) {
        User user = Auth.currentUser(req);
        int replyId = req.pathInt("reply_id");
        AnswerIn data = readAnswer(req);
        Db db = req.db();
        EmailReply r = db.get(EmailReply.class, replyId);
        if (r == null) throw new ApiError(404, "Message not found.");
        String reason = canAnswer(db, user, r);
        if (reason != null) throw new ApiError(403, reason);
        ReplyAnswer a = new ReplyAnswer();
        a.dispatchId = r.dispatchId;
        a.replyId = r.id;
        a.toEmails = String.join(",", data.to());
        a.ccEmails = String.join(",", data.cc());
        a.subject = data.subject();
        a.bodyText = data.text();
        a.status = PENDING;
        a.createdBy = user.username;
        db.add(a);
        db.flush();
        boolean admin = "admin".equals(user.role);
        history(db, a, "answer_submitted", user.username, "Answer to " + addressed(r) + (admin ? " written by an admin" : " sent for approval"),
                a.toEmails, a.ccEmails, textHtml(a.bodyText), null, null, null);
        db.commit();
        if (admin) return approveAndSend(db, a, user);  // an admin's answer needs no approval: sent now
        return toDicts(db, List.of(a), user).get(0);
    }

    private static String editableIn() {
        return "('" + String.join("', '", EDITABLE) + "')";
    }

    static Object updateAnswer(Req req) {
        User user = Auth.currentUser(req);
        int answerId = req.pathInt("answer_id");
        AnswerIn data = readAnswer(req);
        Db db = req.db();
        ReplyAnswer a = getAnswerOr404(db, answerId);
        if (!a.createdBy.equals(user.username)) throw new ApiError(403, "Only the person who wrote this answer can change it.");
        int changed = db.execute("UPDATE reply_answers SET to_emails = ?, cc_emails = ?, subject = ?, body_text = ?, status = ?, "
                        + "submitted_at = ?, decided_by = NULL, decided_at = NULL, decision_note = NULL, error = NULL "
                        + "WHERE id = ? AND status IN " + editableIn(),
                String.join(",", data.to()), String.join(",", data.cc()), data.subject(), data.text(), PENDING, Py.now(), a.id);
        if (changed == 0) {
            db.rollback();
            throw new ApiError(409, "This answer can't be changed any more: it is being sent or was sent.");
        }
        db.refresh(a);
        boolean admin = "admin".equals(user.role);
        history(db, a, "answer_submitted", user.username, "Answer to " + addressed(a.reply())
                        + (admin ? " changed by an admin" : " changed and sent for approval again"),
                a.toEmails, a.ccEmails, textHtml(a.bodyText), null, null, null);
        db.commit();
        if (admin) return approveAndSend(db, a, user);  // sent now, no approval
        return toDicts(db, List.of(a), user).get(0);
    }

    static Object cancelAnswer(Req req) {
        User user = Auth.currentUser(req);
        int answerId = req.pathInt("answer_id");
        Db db = req.db();
        ReplyAnswer a = getAnswerOr404(db, answerId);
        if (!a.createdBy.equals(user.username) && !"admin".equals(user.role)) {
            throw new ApiError(403, "Only the person who wrote this answer, or an admin, can cancel it.");
        }
        if (db.execute("UPDATE reply_answers SET status = ? WHERE id = ? AND status IN " + editableIn(), CANCELLED, a.id) == 0) {
            db.rollback();
            throw new ApiError(409, "This answer can't be cancelled any more: it is being sent or was sent.");
        }
        db.refresh(a);
        history(db, a, "answer_cancelled", user.username, "Answer to " + addressed(a.reply()) + " cancelled", null, null, null, null, null, null);
        db.commit();
        return toDicts(db, List.of(a), user).get(0);
    }

    static Object rejectAnswer(Req req) {
        User admin = Auth.requireAdmin(req);
        int answerId = req.pathInt("answer_id");
        Body b = Body.of(req);
        String noteText = b.str("note", "");
        b.done();
        Db db = req.db();
        ReplyAnswer a = getAnswerOr404(db, answerId);
        String note = Py.cut(Py.strip(noteText), 500);
        if (note.isEmpty()) note = null;
        if (db.execute("UPDATE reply_answers SET status = ?, decided_by = ?, decided_at = ?, decision_note = ? "
                + "WHERE id = ? AND status IN ('PENDING', 'FAILED')", REJECTED, admin.username, Py.now(), note, a.id) == 0) {
            db.rollback();
            throw new ApiError(409, "This answer is no longer waiting for approval.");
        }
        db.refresh(a);
        history(db, a, "answer_rejected", admin.username, note != null ? note : "No reason given", null, null, null, null, null, null);
        db.commit();
        return toDicts(db, List.of(a), admin).get(0);
    }

    static Object approveAnswer(Req req) {
        User admin = Auth.requireAdmin(req);
        int answerId = req.pathInt("answer_id");
        Db db = req.db();
        return approveAndSend(db, getAnswerOr404(db, answerId), admin);
    }

    /** Send a waiting answer (an admin approved it, or an admin wrote it: no approval needed). */
    static Object approveAndSend(Db db, ReplyAnswer a, User admin) {
        LocalDateTime now = Py.now();
        int claimed = db.execute("UPDATE reply_answers SET status = ?, sending_since = ?, decided_by = ?, decided_at = ? "
                        + "WHERE id = ? AND (status IN ('PENDING', 'FAILED') OR (status = 'SENDING' AND sending_since < ?))",
                SENDING, now, admin.username, now, a.id, now.minusMinutes(CLAIM_EXPIRES_MINUTES));
        db.commit();
        if (claimed == 0) {
            db.refresh(a);
            throw new ApiError(409, SENDING.equals(a.status) ? "Another admin is sending this answer right now."
                    : SENT.equals(a.status) ? "This answer was already sent." : "This answer is not waiting for approval.");
        }
        db.refresh(a);
        EmailReply r = a.reply();
        EmailDispatch d = a.dispatch();
        String html = fullHtml(a);
        List<String> to = Py.splitOn(a.toEmails, ","), cc = Py.csv(a.ccEmails);
        String parent = bracketed(r.messageId) != null ? bracketed(r.messageId) : bracketed(d.messageId);
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (bracketed(d.messageId) != null) refs.add(bracketed(d.messageId));
        if (bracketed(r.messageId) != null) refs.add(bracketed(r.messageId));
        String references = refs.isEmpty() ? null : String.join(" ", refs);
        EmailService.SendResult result;
        try {
            result = EmailService.sendEmail(to, cc, a.subject, com.nagad.npsb.service.CaseIds.withHidden(html, d.caseId), parent, null, references);
        } catch (Exception exc) {
            String message = exc.getMessage() == null ? exc.toString() : exc.getMessage();
            a.status = FAILED;
            a.error = Py.cut(message, 2000);
            a.attempts += 1;
            a.sendingSince = null;
            history(db, a, "answer_failed", admin.username, "Answer to " + addressed(r) + " could not be sent",
                    a.toEmails, a.ccEmails, null, false, Py.cut(message, 1000), null);
            db.commit();
            throw new ApiError(502, "The answer could not be sent: " + message);
        }
        a.status = SENT;
        a.sentAt = Py.now();
        a.sendingSince = null;
        a.error = null;
        a.attempts += result.attempts();
        a.messageId = result.messageId();  // the partner's reply to it is matched to this email
        Map<String, String> names = namesOf(db, List.of(a.createdBy));
        history(db, a, "answer", admin.username,
                "Answer by " + names.getOrDefault(a.createdBy, a.createdBy) + " to " + addressed(r)
                        + (a.createdBy.equals(admin.username) ? ", sent" : ", approved and sent"),
                a.toEmails, a.ccEmails, textHtml(a.bodyText), null,
                result.refused().isEmpty() ? null : "Refused: " + EmailService.joinRefused(result.refused()), result.messageId());
        db.commit();
        return toDicts(db, List.of(a), admin).get(0);
    }

    static Object listAnswers(Req req) {
        User user = Auth.currentUser(req);
        Db db = req.db();
        List<ReplyAnswer> rows;
        if ("admin".equals(user.role)) {
            rows = db.all(ReplyAnswer.class, "WHERE t.status IN ('PENDING', 'SENDING', 'FAILED') OR (t.status = 'REJECTED' AND t.created_by = ?) "
                    + "ORDER BY t.submitted_at, t.id", user.username);
        } else {
            rows = db.all(ReplyAnswer.class, "WHERE t.created_by = ? AND t.status IN ('PENDING', 'SENDING', 'FAILED', 'REJECTED') "
                    + "ORDER BY t.submitted_at, t.id", user.username);
        }
        return toDicts(db, rows, user);
    }

    private static String norm(String m) {
        return Py.strip(Py.strip(Py.strip(m == null ? "" : m), "<>")).toLowerCase(java.util.Locale.ROOT);
    }

    static Object conversation(Req req) {
        User user = Auth.currentUser(req);
        int emailId = req.pathInt("email_id");
        Db db = req.db();
        EmailDispatch d = db.get(EmailDispatch.class, emailId);
        if (d == null) throw new ApiError(404, "Email not found.");
        List<EmailEvent> followups = db.all(EmailEvent.class, "WHERE t.dispatch_id = ? AND t.kind = 'followup' AND t.ok = ? ORDER BY t.created_at",
                d.id, true);
        List<EmailReply> replies = db.all(EmailReply.class, "WHERE t.dispatch_id = ? ORDER BY t.received_at, t.id", d.id);
        List<ReplyAnswer> answers = db.all(ReplyAnswer.class, "WHERE t.dispatch_id = ? AND t.status != 'CANCELLED' ORDER BY t.created_at, t.id", d.id);
        List<String> who = new ArrayList<>(List.of(Py.or(d.submittedBy, ""), Py.or(d.sentBy, "")));
        for (EmailEvent e : followups) who.add(e.createdBy);
        for (ReplyAnswer a : answers) who.add(a.createdBy);
        for (ReplyAnswer a : answers) who.add(a.decidedBy);
        Map<String, String> names = namesOf(db, who);

        String root = "email-" + d.id;
        Map<String, String> byMessage = new HashMap<>();
        if (!Py.isEmpty(d.messageId)) byMessage.put(norm(d.messageId), root);
        for (EmailEvent e : followups) if (!Py.isEmpty(e.messageId)) byMessage.put(norm(e.messageId), "followup-" + e.id);
        for (ReplyAnswer a : answers) if (!Py.isEmpty(a.messageId)) byMessage.put(norm(a.messageId), "answer-" + a.id);
        for (EmailReply r : replies) byMessage.put(norm(r.messageId), "reply-" + r.id);

        List<Object> nodes = new ArrayList<>();
        LocalDateTime rootAt = d.sentAt != null ? d.sentAt : d.submittedAt != null ? d.submittedAt : Py.now();
        nodes.add(Py.map("id", root, "type", "email", "parent", null, "at", Py.iso(rootAt),
                "subject", d.subject, "by_name", names.getOrDefault(d.submittedBy, d.submittedBy),
                "sent_by_name", names.getOrDefault(d.sentBy, d.sentBy), "to", Py.csv(d.toEmails)));
        for (EmailEvent e : followups) {
            nodes.add(Py.map("id", "followup-" + e.id, "type", "followup", "parent", root, "at", Py.iso(e.createdAt),
                    "subject", e.subject, "by_name", names.getOrDefault(e.createdBy, e.createdBy), "to", Py.csv(e.toEmails)));
        }
        for (EmailReply r : replies) {
            String nodeId = "reply-" + r.id;
            String parent = !Py.isEmpty(r.inReplyTo) ? byMessage.get(norm(r.inReplyTo)) : null;
            nodes.add(Py.map("id", nodeId, "type", "reply", "reply_id", r.id, "kind", r.kind,
                    "parent", parent != null && !parent.equals(nodeId) ? parent : root, "at", Py.iso(r.receivedAt),
                    "subject", r.subject, "from_email", r.fromEmail, "from_name", r.fromName, "snippet", r.snippet,
                    "attachments", r.attachments().size(), "can_answer", canAnswer(db, user, r) == null, "defaults", defaults(r)));
        }
        for (Map<String, Object> item : toDicts(db, answers, user)) {
            Map<String, Object> node = new java.util.LinkedHashMap<>(item);
            node.put("id", "answer-" + item.get("id"));
            node.put("answer_id", item.get("id"));
            node.put("type", "answer");
            node.put("parent", "reply-" + item.get("reply_id"));
            node.put("at", item.get("sent_at") != null ? item.get("sent_at") : item.get("submitted_at"));
            nodes.add(node);
        }
        return Py.map("email_id", d.id, "partner_name", d.partner().name, "nodes", nodes);
    }

    public static long waitingCount(Db db) {
        return db.count("SELECT COUNT(*) FROM reply_answers WHERE status IN ('PENDING', 'FAILED')");
    }

    public static List<Object> activityParts(Db db) {
        List<Object> parts = new ArrayList<>();
        parts.add(java.util.Arrays.asList(db.rows("SELECT COUNT(id), MAX(id), MAX(submitted_at), MAX(decided_at), MAX(sent_at) FROM reply_answers").get(0)));
        List<Object> statuses = new ArrayList<>();
        for (Object[] row : db.rows("SELECT status, COUNT(id) FROM reply_answers GROUP BY status ORDER BY status")) statuses.add(java.util.Arrays.asList(row));
        parts.add(statuses);
        return parts;
    }
}
