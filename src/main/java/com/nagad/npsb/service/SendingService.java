package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.EmailEvent;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.EmailDispatchRepository;
import com.nagad.npsb.repository.EmailEventRepository;
import com.nagad.npsb.service.dispatch.DispatchQueries;
import com.nagad.npsb.service.dispatch.DispatchViews;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Pending Mails: emails waiting for an admin (PENDING), and ones whose sending failed (FAILED). An admin sends the
 * ticked ones; each email is claimed first (SENDING), so two admins never send the same email twice, and every
 * result is saved right after its email went out.
 */
@Service
public class SendingService {
    /** An admin's claim on an email: if the server dies mid-send, another admin may claim it after this long */
    static final long CLAIM_EXPIRES_MINUTES = 10;

    private final DispatchQueries queries;
    private final DispatchViews views;
    private final TransactionGroups groups;
    private final UserNames userNames;
    private final EmailService emailService;
    private final Delivery delivery;
    private final LiveSettings live;
    private final Sql sql;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final EmailDispatchRepository dispatches;
    private final EmailEventRepository events;

    public SendingService(DispatchQueries queries, DispatchViews views, TransactionGroups groups, UserNames userNames,
                          EmailService emailService, Delivery delivery, LiveSettings live, Sql sql, EntityManager em,
                          TransactionTemplate tx, EmailDispatchRepository dispatches, EmailEventRepository events) {
        this.queries = queries;
        this.views = views;
        this.groups = groups;
        this.userNames = userNames;
        this.emailService = emailService;
        this.delivery = delivery;
        this.live = live;
        this.sql = sql;
        this.em = em;
        this.tx = tx;
        this.dispatches = dispatches;
        this.events = events;
    }

    public List<Map<String, Object>> listPendingEmails() {
        return views.dispatchesToDicts(queries.pendingQuery());
    }

    /**
     * Mark one email as SENDING by this admin, in a single UPDATE (committed at once), and read the email again.
     * When two admins send the same email at the same moment, the database lets only one UPDATE match, so only one
     * of them sends it.
     */
    boolean claim(EmailDispatch d, User admin) {
        LocalDateTime now = Py.now();
        boolean claimed = Boolean.TRUE.equals(tx.execute(status -> sql.execute(
                "UPDATE email_dispatches SET status = 'SENDING', sent_by = ?, sending_since = ? WHERE id = ? AND "
                        + "(status IN ('PENDING', 'FAILED') OR (status = 'SENDING' AND sending_since < ?))",
                admin.getUsername(), now, d.getId(), now.minusMinutes(CLAIM_EXPIRES_MINUTES)) == 1));
        tx.executeWithoutResult(status -> em.refresh(d));  // db.refresh(d) after the commit
        return claimed;
    }

    /** Send one email this admin has claimed, and record the result. Saved right away, so a crash never loses a "SENT". */
    void deliver(EmailDispatch d, User admin) {
        EmailService.SendResult result = null;
        EmailService.RecipientsRefused refusedAll = null;
        Exception failure = null;
        try {
            result = emailService.sendEmail(Py.splitOn(d.getToEmails(), ","), Py.csv(d.getCcEmails()), d.getSubject(),
                    CaseIds.withHidden(d.getBodyHtml(), d.getCaseId()), null, groups.attachmentFor(d), null);  // + the invisible case ID
        } catch (EmailService.RecipientsRefused exc) {
            refusedAll = exc;
        } catch (Exception exc) {
            failure = exc;
        }
        EmailService.SendResult sent = result;
        EmailService.RecipientsRefused refused = refusedAll;
        Exception error = failure;
        tx.executeWithoutResult(status -> {
            if (sent != null) {
                d.setStatus("SENT");
                d.setSentAt(Py.now());
                d.setSentBy(admin.getUsername());
                d.setError(null);
                d.setAttempts(d.getAttempts() + sent.attempts());
                d.setMessageId(sent.messageId());
                for (Map.Entry<String, String> r : sent.refused().entrySet()) {  // some recipients refused: record, don't hide
                    delivery.recordRefused(d, r.getKey(), r.getValue());
                }
                EmailEvent e = new EmailEvent();
                e.setDispatchId(d.getId());
                e.setKind("sent");
                e.setSubject(d.getSubject());
                e.setToEmails(d.getToEmails());
                e.setCcEmails(d.getCcEmails());
                e.setNote("Accepted by the mail server (attempt " + sent.attempts() + ").");
                e.setMessageId(sent.messageId());
                e.setCreatedBy(admin.getUsername());
                e.setCreatedAt(d.getSentAt());
                e.setError(sent.refused().isEmpty() ? null : "Refused: " + EmailService.joinRefused(sent.refused()));
                events.save(e);
            } else if (refused != null) {
                d.setStatus("FAILED");
                d.setError(refused.getMessage());
                d.setAttempts(d.getAttempts() + 1);
                for (Map.Entry<String, String> r : refused.refused.entrySet()) delivery.recordRefused(d, r.getKey(), r.getValue());
            } else {
                d.setStatus("FAILED");
                d.setError(error.getMessage() == null ? error.toString() : error.getMessage());
                d.setAttempts(d.getAttempts() + live.smtpMaxAttempts());
            }
            if ("FAILED".equals(d.getStatus())) {
                EmailEvent e = new EmailEvent();
                e.setDispatchId(d.getId());
                e.setKind("send_failed");
                e.setSubject(d.getSubject());
                e.setToEmails(d.getToEmails());
                e.setCcEmails(d.getCcEmails());
                e.setOk(false);
                e.setError(Py.cut(Py.or(d.getError(), ""), 1000));
                e.setCreatedBy(admin.getUsername());
                events.save(e);
            }
            d.setSendingSince(null);
        });
    }

    /** Admin sends the ticked PENDING / FAILED emails (never twice). */
    public Map<String, Object> sendSelected(User admin, List<Integer> ids) {
        if (ids.isEmpty()) throw new ApiException(400, "Select at least one pending email to send.");
        int sent = 0, failed = 0;
        List<String> skipped = new ArrayList<>();
        for (Integer emailId : new LinkedHashSet<>(ids)) {  // each id once, in the order given
            EmailDispatch d = dispatches.findById(emailId).orElse(null);
            if (d == null) continue;
            if (!claim(d, admin)) {
                String who = UserNames.name(userNames.userNames(Collections.singletonList(d.getSentBy())), d.getSentBy());
                skipped.add(groups.partnerOf(d).getName() + ": " + ("SENT".equals(d.getStatus()) ? "already sent by " + who
                        : "SENDING".equals(d.getStatus()) ? "being sent by " + who : "not waiting to be sent"));
                continue;
            }
            deliver(d, admin);
            if ("SENT".equals(d.getStatus())) sent++;
            else failed++;
        }
        if (sent == 0 && failed == 0 && !skipped.isEmpty()) {
            throw new ApiException(409, "Nothing was sent. " + String.join("; ", skipped) + ".");
        }
        return Py.map("sent", sent, "failed", failed, "skipped", skipped, "emails", listPendingEmails());
    }
}
