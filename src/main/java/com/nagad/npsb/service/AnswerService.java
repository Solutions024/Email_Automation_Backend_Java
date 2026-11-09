package com.nagad.npsb.service;

import com.nagad.npsb.dto.AnswerRequest;
import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.EmailEvent;
import com.nagad.npsb.entity.EmailReply;
import com.nagad.npsb.entity.ReplyAnswer;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.EmailDispatchRepository;
import com.nagad.npsb.repository.EmailEventRepository;
import com.nagad.npsb.repository.EmailReplyAttachmentRepository;
import com.nagad.npsb.repository.EmailReplyRepository;
import com.nagad.npsb.repository.ReplyAnswerRepository;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.TransactionGroups;
import com.nagad.npsb.service.dispatch.UserNames;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
@Service
public class AnswerService {
    static final String PENDING = "PENDING", SENDING = "SENDING", SENT = "SENT", FAILED = "FAILED", REJECTED = "REJECTED",
            CANCELLED = "CANCELLED";
    static final List<String> EDITABLE = List.of(PENDING, REJECTED, FAILED);  // the writer can still change or cancel it
    static final long CLAIM_EXPIRES_MINUTES = 10;                             // "sending" for longer: the server stopped mid-way
    private static final Pattern PREFIX_RE = Pattern.compile("^\\s*re\\s*:", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private final UserNames userNames;
    private final TransactionGroups groups;
    private final EmailService emailService;
    private final Sql sql;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ReplyAnswerRepository answers;
    private final EmailReplyRepository replies;
    private final EmailDispatchRepository dispatches;
    private final EmailEventRepository events;
    private final EmailReplyAttachmentRepository attachments;

    public AnswerService(UserNames userNames, TransactionGroups groups, EmailService emailService, Sql sql, EntityManager em,
                         TransactionTemplate tx, ReplyAnswerRepository answers, EmailReplyRepository replies,
                         EmailDispatchRepository dispatches, EmailEventRepository events, EmailReplyAttachmentRepository attachments) {
        this.userNames = userNames;
        this.groups = groups;
        this.emailService = emailService;
        this.sql = sql;
        this.em = em;
        this.tx = tx;
        this.answers = answers;
        this.replies = replies;
        this.dispatches = dispatches;
        this.events = events;
        this.attachments = attachments;
    }

    /** r.dispatch(): the email a partner message answers (null: none). */
    EmailDispatch dispatchOf(EmailReply r) {
        return r.getDispatchId() == null ? null : dispatches.findById(r.getDispatchId()).orElse(null);
    }

    private EmailReply replyOf(ReplyAnswer a) {
        return a.getReplyId() == null ? null : replies.findById(a.getReplyId()).orElse(null);
    }

    private EmailDispatch dispatchOf(ReplyAnswer a) {
        return a.getDispatchId() == null ? null : dispatches.findById(a.getDispatchId()).orElse(null);
    }

    /** null when this user may answer the message, else why not. */
    public String canAnswer(User user, EmailReply r) {
        if (dispatchOf(r) == null) return "Only replies to one of our emails can be answered. Link it to an email first.";
        if (!"reply".equals(r.getKind())) return "Automatic replies and delivery failure notices can't be answered.";
        return null;  // shared dashboard: anyone can answer, e.g. while the person who prepared the email is away
    }

    /** Pre-filled answer: to the partner who wrote, our team in CC, "Re:" subject. */
    public Map<String, Object> defaults(EmailReply r) {
        EmailDispatch d = dispatchOf(r);
        String subject = PREFIX_RE.matcher(r.getSubject()).find() ? r.getSubject() : "Re: " + r.getSubject();
        return Py.map("to", List.of(r.getFromEmail()), "cc", Py.csv(d != null ? d.getCcEmails() : ""), "subject", Py.cut(subject, 300));
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
    String fullHtml(ReplyAnswer a) {
        EmailReply r = replyOf(a);
        String who = !Py.isEmpty(r.getFromName()) ? Py.escape(r.getFromName()) + " &lt;" + Py.escape(r.getFromEmail()) + "&gt;"
                : Py.escape(r.getFromEmail());
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222;line-height:1.45\">"
                + textHtml(a.getBodyText()) + EmailService.SIGNATURE
                + "<div style=\"margin-top:16px;color:#555\">On " + Py.strftime(r.getReceivedAt(), "%d %b %Y %H:%M") + ", " + who + " wrote:</div>"
                + "<blockquote style=\"margin:6px 0 0;border-left:2px solid #ccc;padding-left:10px;color:#555\">"
                + r.getBodyHtml() + "</blockquote></div>";
    }

    /** A Message-ID as a header value; null for ids we made up for messages without one. */
    static String bracketed(String messageId) {
        if (Py.isEmpty(messageId) || messageId.startsWith("imap-")) return null;
        return messageId.startsWith("<") ? messageId : "<" + messageId + ">";
    }

    Map<String, Object> answerToDict(ReplyAnswer a, User user, Map<String, String> names) {
        EmailReply r = replyOf(a);
        EmailDispatch d = dispatchOf(a);
        boolean mine = a.getCreatedBy().equals(user.getUsername());
        boolean admin = "admin".equals(user.getRole());
        return Py.map(
                "id", a.getId(), "email_id", a.getDispatchId(), "reply_id", a.getReplyId(), "partner_name", groups.partnerOf(d).getName(),
                "to", Py.csv(a.getToEmails()), "cc", Py.csv(a.getCcEmails()),
                "subject", a.getSubject(), "text", a.getBodyText(), "status", a.getStatus(),
                "created_by", a.getCreatedBy(), "created_by_name", names.getOrDefault(a.getCreatedBy(), a.getCreatedBy()),
                "created_at", Py.iso(a.getCreatedAt()), "submitted_at", Py.iso(a.getSubmittedAt()),
                "decided_by_name", a.getDecidedBy() != null ? names.getOrDefault(a.getDecidedBy(), a.getDecidedBy()) : null,
                "decided_at", Py.iso(a.getDecidedAt()), "note", a.getDecisionNote(),
                "sent_at", Py.iso(a.getSentAt()), "error", a.getError(),
                "reply", Py.map("from_email", r.getFromEmail(), "from_name", r.getFromName(), "subject", r.getSubject(),
                        "received_at", Py.iso(r.getReceivedAt()), "snippet", r.getSnippet()),
                "email", Py.map("subject", d.getSubject(), "sent_at", Py.iso(d.getSentAt()), "upload_id", d.getUploadId()),
                "can_edit", mine && EDITABLE.contains(a.getStatus()),
                "can_cancel", (mine || admin) && EDITABLE.contains(a.getStatus()),
                "can_approve", admin && (PENDING.equals(a.getStatus()) || FAILED.equals(a.getStatus())));
    }

    List<Map<String, Object>> toDicts(List<ReplyAnswer> list, User user) {
        List<String> who = new ArrayList<>();
        for (ReplyAnswer a : list) {
            who.add(a.getCreatedBy());
            who.add(a.getDecidedBy());
        }
        Map<String, String> names = userNames.namesOf(who);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReplyAnswer a : list) out.add(answerToDict(a, user, names));
        return out;
    }

    ReplyAnswer getAnswerOr404(int answerId) {
        ReplyAnswer a = answers.findById(answerId).orElse(null);
        if (a == null) throw new ApiException(404, "Answer not found.");
        return a;
    }

    /** A line in the email's history (written with the caller's transaction). */
    private void history(ReplyAnswer a, String kind, String by, String note, String to, String cc, String bodyHtml,
                         Boolean ok, String error, String messageId) {
        EmailEvent e = new EmailEvent();
        e.setDispatchId(a.getDispatchId());
        e.setKind(kind);
        e.setReplyId(a.getReplyId());
        e.setSubject(a.getSubject());
        e.setNote(note);
        e.setCreatedBy(by);
        e.setToEmails(to);
        e.setCcEmails(cc);
        e.setBodyHtml(bodyHtml);
        if (ok != null) e.setOk(ok);
        e.setError(error);
        e.setMessageId(messageId);
        events.save(e);
    }

    static String addressed(EmailReply r) {
        return Py.or(r.getFromName(), r.getFromEmail());
    }

    private static String editableIn() {
        return "('" + String.join("', '", EDITABLE) + "')";
    }

    // ---------------------------------------------------------------- endpoints

    /** Write an answer to a partner's reply: it waits for an admin's approval (an admin's own answer is sent now). */
    public Map<String, Object> createAnswer(User user, int replyId, AnswerRequest data) {
        EmailReply r = replies.findById(replyId).orElse(null);
        if (r == null) throw new ApiException(404, "Message not found.");
        String reason = canAnswer(user, r);
        if (reason != null) throw new ApiException(403, reason);
        boolean admin = "admin".equals(user.getRole());
        ReplyAnswer a = tx.execute(status -> {
            ReplyAnswer answer = new ReplyAnswer();
            answer.setDispatchId(r.getDispatchId());
            answer.setReplyId(r.getId());
            answer.setToEmails(String.join(",", data.to()));
            answer.setCcEmails(String.join(",", data.cc()));
            answer.setSubject(data.subject());
            answer.setBodyText(data.text());
            answer.setStatus(PENDING);
            answer.setCreatedBy(user.getUsername());
            answers.save(answer);
            em.flush();
            history(answer, "answer_submitted", user.getUsername(),
                    "Answer to " + addressed(r) + (admin ? " written by an admin" : " sent for approval"),
                    answer.getToEmails(), answer.getCcEmails(), textHtml(answer.getBodyText()), null, null, null);
            return answer;
        });
        if (admin) return approveAndSend(a, user);  // an admin's answer needs no approval: sent now
        return toDicts(List.of(a), user).get(0);
    }

    /** The writer changes their answer: it waits for approval again. */
    public Map<String, Object> updateAnswer(User user, int answerId, AnswerRequest data) {
        ReplyAnswer a = getAnswerOr404(answerId);
        if (!a.getCreatedBy().equals(user.getUsername())) throw new ApiException(403, "Only the person who wrote this answer can change it.");
        boolean admin = "admin".equals(user.getRole());
        tx.executeWithoutResult(status -> {
            int changed = sql.execute("UPDATE reply_answers SET to_emails = ?, cc_emails = ?, subject = ?, body_text = ?, status = ?, "
                            + "submitted_at = ?, decided_by = NULL, decided_at = NULL, decision_note = NULL, error = NULL "
                            + "WHERE id = ? AND status IN " + editableIn(),
                    String.join(",", data.to()), String.join(",", data.cc()), data.subject(), data.text(), PENDING, Py.now(), a.getId());
            if (changed == 0) throw new ApiException(409, "This answer can't be changed any more: it is being sent or was sent.");
            em.refresh(a);
            history(a, "answer_submitted", user.getUsername(), "Answer to " + addressed(replyOf(a))
                            + (admin ? " changed by an admin" : " changed and sent for approval again"),
                    a.getToEmails(), a.getCcEmails(), textHtml(a.getBodyText()), null, null, null);
        });
        if (admin) return approveAndSend(a, user);  // sent now, no approval
        return toDicts(List.of(a), user).get(0);
    }

    public Map<String, Object> cancelAnswer(User user, int answerId) {
        ReplyAnswer a = getAnswerOr404(answerId);
        if (!a.getCreatedBy().equals(user.getUsername()) && !"admin".equals(user.getRole())) {
            throw new ApiException(403, "Only the person who wrote this answer, or an admin, can cancel it.");
        }
        tx.executeWithoutResult(status -> {
            if (sql.execute("UPDATE reply_answers SET status = ? WHERE id = ? AND status IN " + editableIn(), CANCELLED, a.getId()) == 0) {
                throw new ApiException(409, "This answer can't be cancelled any more: it is being sent or was sent.");
            }
            em.refresh(a);
            history(a, "answer_cancelled", user.getUsername(), "Answer to " + addressed(replyOf(a)) + " cancelled",
                    null, null, null, null, null, null);
        });
        return toDicts(List.of(a), user).get(0);
    }

    /** Admin sends an answer back to its writer, with a reason. */
    public Map<String, Object> rejectAnswer(User admin, int answerId, String noteText) {
        ReplyAnswer a = getAnswerOr404(answerId);
        String cut = Py.cut(Py.strip(noteText), 500);
        String note = cut.isEmpty() ? null : cut;
        tx.executeWithoutResult(status -> {
            if (sql.execute("UPDATE reply_answers SET status = ?, decided_by = ?, decided_at = ?, decision_note = ? "
                    + "WHERE id = ? AND status IN ('PENDING', 'FAILED')", REJECTED, admin.getUsername(), Py.now(), note, a.getId()) == 0) {
                throw new ApiException(409, "This answer is no longer waiting for approval.");
            }
            em.refresh(a);
            history(a, "answer_rejected", admin.getUsername(), note != null ? note : "No reason given", null, null, null, null, null, null);
        });
        return toDicts(List.of(a), admin).get(0);
    }

    /** Admin approves an answer: it is sent to the partner in the same email thread. */
    public Map<String, Object> approveAnswer(User admin, int answerId) {
        return approveAndSend(getAnswerOr404(answerId), admin);
    }

    /** Send a waiting answer (an admin approved it, or an admin wrote it: no approval needed). */
    Map<String, Object> approveAndSend(ReplyAnswer a, User admin) {
        LocalDateTime now = Py.now();
        // claimed with a single UPDATE (committed at once): two admins never send the same answer
        boolean claimed = Boolean.TRUE.equals(tx.execute(status -> sql.execute(
                "UPDATE reply_answers SET status = ?, sending_since = ?, decided_by = ?, decided_at = ? "
                        + "WHERE id = ? AND (status IN ('PENDING', 'FAILED') OR (status = 'SENDING' AND sending_since < ?))",
                SENDING, now, admin.getUsername(), now, a.getId(), now.minusMinutes(CLAIM_EXPIRES_MINUTES)) != 0));
        tx.executeWithoutResult(status -> em.refresh(a));  // db.refresh(a) after the commit
        if (!claimed) {
            throw new ApiException(409, SENDING.equals(a.getStatus()) ? "Another admin is sending this answer right now."
                    : SENT.equals(a.getStatus()) ? "This answer was already sent." : "This answer is not waiting for approval.");
        }
        EmailReply r = replyOf(a);
        EmailDispatch d = dispatchOf(a);
        String html = fullHtml(a);
        List<String> to = Py.splitOn(a.getToEmails(), ","), cc = Py.csv(a.getCcEmails());
        String parent = bracketed(r.getMessageId()) != null ? bracketed(r.getMessageId()) : bracketed(d.getMessageId());
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (bracketed(d.getMessageId()) != null) refs.add(bracketed(d.getMessageId()));
        if (bracketed(r.getMessageId()) != null) refs.add(bracketed(r.getMessageId()));
        String references = refs.isEmpty() ? null : String.join(" ", refs);
        EmailService.SendResult result;
        try {
            result = emailService.sendEmail(to, cc, a.getSubject(), html, parent, null, references);
        } catch (Exception exc) {
            String message = exc.getMessage() == null ? exc.toString() : exc.getMessage();
            tx.executeWithoutResult(status -> {
                a.setStatus(FAILED);
                a.setError(Py.cut(message, 2000));
                a.setAttempts(a.getAttempts() + 1);
                a.setSendingSince(null);
                history(a, "answer_failed", admin.getUsername(), "Answer to " + addressed(r) + " could not be sent",
                        a.getToEmails(), a.getCcEmails(), null, false, Py.cut(message, 1000), null);
            });
            throw new ApiException(502, "The answer could not be sent: " + message);
        }
        tx.executeWithoutResult(status -> {
            a.setStatus(SENT);
            a.setSentAt(Py.now());
            a.setSendingSince(null);
            a.setError(null);
            a.setAttempts(a.getAttempts() + result.attempts());
            a.setMessageId(result.messageId());  // the partner's reply to it is matched to this email
            Map<String, String> names = userNames.namesOf(List.of(a.getCreatedBy()));
            history(a, "answer", admin.getUsername(),
                    "Answer by " + names.getOrDefault(a.getCreatedBy(), a.getCreatedBy()) + " to " + addressed(r)
                            + (a.getCreatedBy().equals(admin.getUsername()) ? ", sent" : ", approved and sent"),
                    a.getToEmails(), a.getCcEmails(), textHtml(a.getBodyText()), null,
                    result.refused().isEmpty() ? null : "Refused: " + EmailService.joinRefused(result.refused()), result.messageId());
        });
        return toDicts(List.of(a), admin).get(0);
    }

    /** Answers waiting for approval (or failed); plus your own rejected answers. */
    public List<Map<String, Object>> listAnswers(User user) {
        List<ReplyAnswer> rows;
        if ("admin".equals(user.getRole())) {
            rows = sql.all(ReplyAnswer.class, "WHERE t.status IN ('PENDING', 'SENDING', 'FAILED') OR (t.status = 'REJECTED' AND t.created_by = ?) "
                    + "ORDER BY t.submitted_at, t.id", user.getUsername());
        } else {
            rows = sql.all(ReplyAnswer.class, "WHERE t.created_by = ? AND t.status IN ('PENDING', 'SENDING', 'FAILED', 'REJECTED') "
                    + "ORDER BY t.submitted_at, t.id", user.getUsername());
        }
        return toDicts(rows, user);
    }

    private static String norm(String m) {
        return Py.strip(Py.strip(Py.strip(m == null ? "" : m), "<>")).toLowerCase(Locale.ROOT);
    }

    /** The back-and-forth of one email as a tree. */
    public Map<String, Object> conversation(User user, int emailId) {
        EmailDispatch d = dispatches.findById(emailId).orElse(null);
        if (d == null) throw new ApiException(404, "Email not found.");
        List<EmailEvent> followups = sql.all(EmailEvent.class, "WHERE t.dispatch_id = ? AND t.kind = 'followup' AND t.ok = ? ORDER BY t.created_at",
                d.getId(), true);
        List<EmailReply> found = sql.all(EmailReply.class, "WHERE t.dispatch_id = ? ORDER BY t.received_at, t.id", d.getId());
        List<ReplyAnswer> written = sql.all(ReplyAnswer.class, "WHERE t.dispatch_id = ? AND t.status != 'CANCELLED' ORDER BY t.created_at, t.id",
                d.getId());
        List<String> who = new ArrayList<>(Arrays.asList(Py.or(d.getSubmittedBy(), ""), Py.or(d.getSentBy(), "")));
        for (EmailEvent e : followups) who.add(e.getCreatedBy());
        for (ReplyAnswer a : written) who.add(a.getCreatedBy());
        for (ReplyAnswer a : written) who.add(a.getDecidedBy());
        Map<String, String> names = userNames.namesOf(who);

        String root = "email-" + d.getId();
        Map<String, String> byMessage = new HashMap<>();
        if (!Py.isEmpty(d.getMessageId())) byMessage.put(norm(d.getMessageId()), root);
        for (EmailEvent e : followups) if (!Py.isEmpty(e.getMessageId())) byMessage.put(norm(e.getMessageId()), "followup-" + e.getId());
        for (ReplyAnswer a : written) if (!Py.isEmpty(a.getMessageId())) byMessage.put(norm(a.getMessageId()), "answer-" + a.getId());
        for (EmailReply r : found) byMessage.put(norm(r.getMessageId()), "reply-" + r.getId());

        List<Object> nodes = new ArrayList<>();
        LocalDateTime rootAt = d.getSentAt() != null ? d.getSentAt() : d.getSubmittedAt() != null ? d.getSubmittedAt() : Py.now();
        nodes.add(Py.map("id", root, "type", "email", "parent", null, "at", Py.iso(rootAt),
                "subject", d.getSubject(), "by_name", names.getOrDefault(d.getSubmittedBy(), d.getSubmittedBy()),
                "sent_by_name", names.getOrDefault(d.getSentBy(), d.getSentBy()), "to", Py.csv(d.getToEmails())));
        for (EmailEvent e : followups) {
            nodes.add(Py.map("id", "followup-" + e.getId(), "type", "followup", "parent", root, "at", Py.iso(e.getCreatedAt()),
                    "subject", e.getSubject(), "by_name", names.getOrDefault(e.getCreatedBy(), e.getCreatedBy()), "to", Py.csv(e.getToEmails())));
        }
        for (EmailReply r : found) {
            String nodeId = "reply-" + r.getId();
            String parent = !Py.isEmpty(r.getInReplyTo()) ? byMessage.get(norm(r.getInReplyTo())) : null;
            nodes.add(Py.map("id", nodeId, "type", "reply", "reply_id", r.getId(), "kind", r.getKind(),
                    "parent", parent != null && !parent.equals(nodeId) ? parent : root, "at", Py.iso(r.getReceivedAt()),
                    "subject", r.getSubject(), "from_email", r.getFromEmail(), "from_name", r.getFromName(), "snippet", r.getSnippet(),
                    "attachments", attachments.findByReplyIdOrderByIdAsc(r.getId()).size(), "can_answer", canAnswer(user, r) == null,
                    "defaults", defaults(r)));
        }
        for (Map<String, Object> item : toDicts(written, user)) {
            Map<String, Object> node = new LinkedHashMap<>(item);
            node.put("id", "answer-" + item.get("id"));
            node.put("answer_id", item.get("id"));
            node.put("type", "answer");
            node.put("parent", "reply-" + item.get("reply_id"));
            node.put("at", item.get("sent_at") != null ? item.get("sent_at") : item.get("submitted_at"));
            nodes.add(node);
        }
        return Py.map("email_id", d.getId(), "partner_name", groups.partnerOf(d).getName(), "nodes", nodes);
    }

    /** Answers waiting for an admin (the menu count). */
    public long waitingCount() {
        return sql.count("SELECT COUNT(*) FROM reply_answers WHERE status IN ('PENDING', 'FAILED')");
    }

    /** The answers' part of the activity fingerprint. */
    public List<Object> activityParts() {
        List<Object> parts = new ArrayList<>();
        parts.add(Arrays.asList(sql.rows("SELECT COUNT(id), MAX(id), MAX(submitted_at), MAX(decided_at), MAX(sent_at) FROM reply_answers").get(0)));
        List<Object> statuses = new ArrayList<>();
        for (Object[] row : sql.rows("SELECT status, COUNT(id) FROM reply_answers GROUP BY status ORDER BY status")) statuses.add(Arrays.asList(row));
        parts.add(statuses);
        return parts;
    }
}
