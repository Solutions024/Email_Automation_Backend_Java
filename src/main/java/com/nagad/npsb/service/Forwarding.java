package com.nagad.npsb.service;

import com.nagad.npsb.Config;
import com.nagad.npsb.Py;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.EmailDispatch;
import com.nagad.npsb.models.EmailEvent;
import com.nagad.npsb.models.EmailReply;
import com.nagad.npsb.models.EmailReplyAttachment;
import com.nagad.npsb.models.User;
import jakarta.mail.internet.InternetAddress;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Partner replies emailed to the person who prepared the email they answer.
 *
 * When the mailbox check finds a reply to one of our emails, or an admin links a message to an email, the
 * reply is queued (email_replies.forward_status) and emailed, with its attachments, to the user who sent
 * that email for approval (EmailDispatch.submitted_by), if "Forward replies" is on (Email Configuration).
 * Sending happens in a background thread, so a slow mail server never holds up the mailbox check. Each
 * reply is forwarded automatically at most once; from the reply anyone can forward it again, to that
 * person or to themselves. Every forward, sent or failed, is in the email's history.
 *
 * Not forwarded (forward_status "skipped", with the reason): nobody is recorded as having prepared the
 * email, their account is gone or deactivated, they have no confirmed email address, or their address
 * is the mailbox the replies are read from (the reply is already there).
 */
public final class Forwarding {
    private Forwarding() {}

    private static final Logger LOG = Logger.getLogger("npsb");

    public static final String AUTO_USER = "#auto";   // created_by of automatic forwards (shown as "Sent automatically")
    private static final long STALE_MINUTES = 10;    // "sending" for longer: the server stopped mid-way, try again
    private static final int MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;  // bigger: forwarded without the files
    // Gmail takes at most 25 MB per email, attachments base64-encoded (4/3 bigger): keep room for the message itself
    private static final int MAX_ATTACHMENT_BYTES_GMAIL = 17 * 1024 * 1024;
    public static final String QUEUED = "queued", SENDING = "sending", SENT = "sent", FAILED = "failed", SKIPPED = "skipped";

    private static final ReentrantLock RUNNING = new ReentrantLock();

    /** The mailbox replies are read from, and the sender address. */
    static Set<String> mailboxAddresses() {
        Set<String> out = new LinkedHashSet<>();
        String mailbox = Config.mailboxAddress();
        if (mailbox != null && !Py.strip(mailbox).isEmpty()) out.add(Py.strip(mailbox).toLowerCase(java.util.Locale.ROOT));
        String from = parseAddress(Config.mailFrom());
        if (!Py.strip(from).isEmpty()) out.add(Py.strip(from).toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    /** email.utils.parseaddr(value)[1] */
    static String parseAddress(String value) {
        if (value == null) return "";
        try {
            InternetAddress[] parsed = InternetAddress.parseHeader(value, false);
            return parsed.length > 0 && parsed[0].getAddress() != null ? parsed[0].getAddress() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** The user who sent the email for approval. */
    public static User composer(Db db, EmailDispatch d) {
        return Py.isEmpty(d.submittedBy) ? null : db.first(User.class, "WHERE t.username = ?", d.submittedBy);
    }

    /** Why this user can't be sent a copy, or null. */
    public static String problem(User user, EmailDispatch d) {
        if (user == null) {
            return d != null && Py.isEmpty(d.submittedBy) ? "Nobody is recorded as having prepared this email."
                    : "The person who prepared this email no longer has an account.";
        }
        String name = Py.or(user.fullName, user.username);
        if (!Boolean.TRUE.equals(user.isActive)) return name + "'s account is deactivated.";
        if (Py.isEmpty(user.email) || !Boolean.TRUE.equals(user.emailVerified)) return name + " has no confirmed email address.";
        if (mailboxAddresses().contains(user.email.toLowerCase(java.util.Locale.ROOT))) {
            return user.email + " is the mailbox partner replies are read from: the reply is already there.";
        }
        return null;
    }

    public static String problem(User user) {
        return problem(user, null);
    }

    /**
     * A reply to one of our emails was stored (not committed yet): forward it, if forwarding is on.
     * Call kick() after committing.
     */
    public static boolean queue(EmailReply reply) {
        if (!"reply".equals(reply.kind) || reply.dispatchId == null || reply.forwardStatus != null) return false;
        if (!Settings.getBool("forward_replies")) return false;
        reply.forwardStatus = QUEUED;
        return true;
    }

    /** Send the queued forwards in the background (does nothing if that is already running). */
    public static void kick() {
        Thread t = new Thread(Forwarding::runQueued, "forward-replies");
        t.setDaemon(true);
        t.start();
    }

    /** Send every queued forward. Returns how many were sent. */
    public static int runQueued() {
        if (!RUNNING.tryLock()) return 0;
        int sent = 0;
        try (Db db = Db.open()) {
            db.execute("UPDATE email_replies SET forward_status = ? WHERE forward_status = ? AND forwarded_at < ?",
                    QUEUED, SENDING, Py.now().minusMinutes(STALE_MINUTES));
            db.commit();
            for (Long replyId : db.ids("SELECT id FROM email_replies WHERE forward_status = ? ORDER BY id LIMIT 200", QUEUED)) {
                int claimed = db.execute("UPDATE email_replies SET forward_status = ?, forwarded_at = ? WHERE id = ? AND forward_status = ?",
                        SENDING, Py.now(), replyId, QUEUED);
                db.commit();
                if (claimed == 0) continue;  // forwarded by hand at the same moment
                try {
                    Object[] result = forwardToComposer(db, db.get(EmailReply.class, replyId), AUTO_USER);
                    if ((Boolean) result[0]) sent++;
                } catch (Exception e) {  // one bad reply must not stop the others
                    db.rollback();
                    LOG.log(Level.SEVERE, "Reply " + replyId + " could not be forwarded", e);
                    db.execute("UPDATE email_replies SET forward_status = ?, forward_note = ? WHERE id = ?",
                            FAILED, "Unexpected error while forwarding.", replyId);
                    db.commit();
                }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Forwarding replies failed", e);
        } finally {
            RUNNING.unlock();
        }
        return sent;
    }

    /** Email the reply to the person who prepared its email; record it on the reply and in the history. {ok, message} */
    public static Object[] forwardToComposer(Db db, EmailReply reply, String by) {
        EmailDispatch d = reply.dispatch();
        User user = composer(db, d);
        String reason = problem(user, d);
        if (reason == null && AUTO_USER.equals(by) && !Settings.getBool("forward_replies")) reason = "Forwarding replies was switched off.";
        if (reason != null) {
            reply.forwardStatus = SKIPPED;
            reply.forwardNote = Py.cut(reason, 500);
            reply.forwardTo = null;
            db.commit();
            return new Object[]{false, reason};
        }
        Object[] result = sendCopy(db, reply, user, by);
        boolean ok = (Boolean) result[0];
        reply.forwardStatus = ok ? SENT : FAILED;
        reply.forwardTo = user.email;
        reply.forwardNote = ok ? null : Py.cut((String) result[1], 500);
        reply.forwardedAt = Py.now();
        db.commit();
        return result;
    }

    /** Email the reply to this user and add it to the email's history (not committed). {sent, message} */
    public static Object[] sendCopy(Db db, EmailReply reply, User user, String by) {
        EmailDispatch d = reply.dispatch();
        String name = Py.or(user.fullName, user.username);
        List<EmailService.Attachment> files = new ArrayList<>();
        long total = 0;
        for (EmailReplyAttachment a : reply.attachments()) {
            byte[] data = a.data() == null ? new byte[0] : a.data();
            files.add(new EmailService.Attachment(a.filename, data, a.contentType));
            total += data.length;
        }
        int limit = Config.gmail() ? MAX_ATTACHMENT_BYTES_GMAIL : MAX_ATTACHMENT_BYTES;
        boolean tooBig = total > limit;
        boolean ok;
        String error = null;
        try {
            EmailService.sendEmail(List.of(user.email), List.of(), Py.cut("Fwd: " + reply.subject, 300), body(reply, user, tooBig),
                    null, tooBig ? List.of() : files, null);
            ok = true;
        } catch (Exception e) {
            ok = false;
            error = Py.cut(e.getMessage() == null ? e.toString() : e.getMessage(), 2000);
            LOG.warning("Reply " + reply.id + " could not be forwarded to " + user.email + ": " + e.getMessage());
        }
        if (d != null) {
            String who = !Py.isEmpty(reply.fromName) ? reply.fromName + " <" + reply.fromEmail + ">" : reply.fromEmail;
            EmailEvent ev = new EmailEvent();
            ev.dispatchId = d.id;
            ev.kind = "forwarded";
            ev.replyId = reply.id;
            ev.subject = Py.cut(reply.subject, 300);
            ev.note = "Reply from " + who + " forwarded to " + name;
            ev.toEmails = user.email;
            ev.ccEmails = "";
            ev.ok = ok;
            ev.error = error;
            ev.createdBy = by;
            db.add(ev);
        }
        return ok ? new Object[]{true, "Forwarded to " + name + " (" + user.email + ")."}
                : new Object[]{false, "Could not forward it to " + user.email + ": " + error};
    }

    /** The forwarded message: what it answers, then the partner's message as received. */
    static String body(EmailReply reply, User user, boolean tooBig) {
        EmailDispatch d = reply.dispatch();
        String partner = d != null ? d.partner().name : "A partner";
        String whose = d != null && user.username.equals(d.submittedBy) ? "you prepared" : "prepared in the app";
        String who = !Py.isEmpty(reply.fromName) ? Py.escape(reply.fromName) + " &lt;" + Py.escape(reply.fromEmail) + "&gt;"
                : Py.escape(reply.fromEmail);
        StringBuilder lines = new StringBuilder("<p><b>" + Py.escape(partner) + "</b> replied to an email " + whose + ".</p>");
        if (d != null) {
            String sent = d.sentAt != null ? Py.strftime(d.sentAt, "%d %b %Y %H:%M") : "not sent";
            String[][] rows = {{"Our email", Py.escape(d.subject)}, {"Sent", sent},
                    {"Transactions", d.txnCount + " · BDT " + Py.money(Py.toDouble(d.totalAmount)) + " · file #" + d.uploadId}};
            lines.append("<table style=\"border-collapse:collapse;font-size:13px;margin:0 0 12px\">");
            for (String[] r : rows) {
                lines.append("<tr><td style=\"padding:2px 12px 2px 0;color:#666\">").append(r[0])
                        .append("</td><td style=\"padding:2px 0\">").append(r[1]).append("</td></tr>");
            }
            lines.append("</table>");
        }
        if (tooBig) {
            lines.append("<p style=\"color:#9a3412\">The attachments are too large to forward: open the reply on the "
                    + "Partner Replies page to download them.</p>");
        }
        lines.append("<p style=\"color:#666;font-size:12px\">To answer the partner, open this reply on the Partner Replies "
                + "page of the app and write your answer there: an admin approves it, then it is sent in the same email "
                + "thread and recorded in the email's history. Please don't reply to this email.</p>");
        StringBuilder header = new StringBuilder();
        header.append("<b>From:</b> ").append(who).append("<br/>");
        header.append("<b>Date:</b> ").append(Py.strftime(reply.receivedAt, "%d %b %Y %H:%M")).append("<br/>");
        header.append("<b>Subject:</b> ").append(Py.escape(reply.subject)).append("<br/>");
        header.append("<b>To:</b> ").append(Py.escape(reply.toEmails.replace(",", ", "))).append("<br/>");
        if (!Py.isEmpty(reply.ccEmails)) header.append("<b>Cc:</b> ").append(Py.escape(reply.ccEmails.replace(",", ", "))).append("<br/>");
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222;line-height:1.45\">"
                + lines
                + "<div style=\"border-top:1px solid #ccc;margin-top:12px;padding-top:8px;font-size:13px\">"
                + "---------- Forwarded message ----------<br/>" + header + "</div><br/>"
                + reply.bodyHtml + "</div>";
    }
}
