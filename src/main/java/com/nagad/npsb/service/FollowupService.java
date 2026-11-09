package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.EmailEvent;
import com.nagad.npsb.entity.Partner;
import com.nagad.npsb.entity.PartnerEmail;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.EmailEventRepository;
import com.nagad.npsb.service.dispatch.DispatchQueries;
import com.nagad.npsb.service.dispatch.DispatchViews;
import com.nagad.npsb.service.dispatch.SeparateSession;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.SqlQuery;
import com.nagad.npsb.service.dispatch.TransactionGroups;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Follow-ups of sent emails: a ready-made draft to edit, sending one by hand (users and admins, in the original
 * thread), and the automatic follow-ups.
 *
 * Automatic follow-ups: when switched on (Email Configuration), a background job sends the follow-up of every email
 * that is due (runAutoFollowups), as the "system" user, every AUTO_FOLLOWUP_CHECK_SECONDS (System Settings; wake()
 * starts the next run at once, e.g. after the interval changed). Never two runs at once ("Run now" waits for a
 * running check).
 */
@Service
public class FollowupService {
    private static final Logger LOG = LoggerFactory.getLogger("npsb");

    /** created_by of automatic follow-ups. "#" is not allowed in usernames, so no account can have it. */
    public static final String SYSTEM_USER = "#auto";
    /** One follow-up per email per minute: no double clicks / two admins at once. */
    static final long FOLLOWUP_GAP_SECONDS = 60;
    /** An automatic follow-up that failed is tried again after this long. */
    static final long AUTO_RETRY_AFTER_HOURS = 1;

    private final DispatchQueries queries;
    private final DispatchViews views;
    private final TransactionGroups groups;
    private final TemplateService templateService;
    private final PartnerService partnerService;
    private final EmailService emailService;
    private final Delivery delivery;
    private final Settings settings;
    private final LiveSettings live;
    private final Sql sql;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final SeparateSession separateSession;
    private final TaskScheduler scheduler;
    private final EmailEventRepository events;

    /** One run at a time ("Run now" while the timer runs). */
    private final ReentrantLock runLock = new ReentrantLock();
    private final Map<String, Object> autoStatus = Collections.synchronizedMap(
            Py.map("last_run_at", null, "last_result", null, "next_run_at", null));

    // the timer (guarded by timerLock)
    private final Object timerLock = new Object();
    private boolean started = false;
    private boolean stopped = false;
    private boolean running = false;
    private boolean woken = false;  // System Settings changed the check interval: start again with it
    private long generation = 0;    // the plan the next check belongs to (an older planned check does nothing)
    private ScheduledFuture<?> next;

    public FollowupService(DispatchQueries queries, DispatchViews views, TransactionGroups groups, TemplateService templateService,
                           PartnerService partnerService, EmailService emailService, Delivery delivery, Settings settings,
                           LiveSettings live, Sql sql, EntityManager em, TransactionTemplate tx, SeparateSession separateSession,
                           TaskScheduler scheduler, EmailEventRepository events) {
        this.queries = queries;
        this.views = views;
        this.groups = groups;
        this.templateService = templateService;
        this.partnerService = partnerService;
        this.emailService = emailService;
        this.delivery = delivery;
        this.settings = settings;
        this.live = live;
        this.sql = sql;
        this.em = em;
        this.tx = tx;
        this.separateSession = separateSession;
        this.scheduler = scheduler;
        this.events = events;
    }

    // ---------------------------------------------------------------- follow-ups of one email

    /** The partner's addresses now: active and not refused (they may have changed since the email was sent). */
    List<String> currentRecipients(EmailDispatch d) {
        List<String> out = new ArrayList<>();
        Partner partner = groups.partnerOf(d);
        if (!Boolean.TRUE.equals(partner.getIsActive())) return out;
        for (PartnerEmail e : partnerService.emailsOf(partner)) {
            if (Boolean.TRUE.equals(e.getIsActive()) && Py.isEmpty(e.getProblem())) out.add(e.getEmail());
        }
        return out;
    }

    String[] followupContent(EmailDispatch d, int number) {
        return EmailService.buildFollowup(d.getMailType(), d.getSubject(), d.getSentAt(), d.getTxnCount(), Py.toDouble(d.getTotalAmount()),
                d.getReplyStatus(), number, templateService.reminderTemplateFor(d.getMailType()), groups.partnerOf(d).getName(),
                groups.dispatchTransactions(d), templateService.templateVariables());
    }

    static void ensureFollowupAllowed(EmailDispatch d) {
        if ("reconciled".equals(d.getReplyStatus())) {
            throw new ApiException(409, "This email is reconciled, so follow-ups are closed. "
                    + "Change its reply status first if it needs another follow-up.");
        }
    }

    /** A ready-made follow-up to edit before sending. Anyone signed in can follow up, whenever they want. */
    public Map<String, Object> followupDraft(int emailId) {
        EmailDispatch d = queries.getSentOr404(emailId);
        ensureFollowupAllowed(d);
        String[] content = followupContent(d, d.getFollowupCount() + 1);
        List<String> to = currentRecipients(d);
        Set<String> toLower = new HashSet<>();
        for (String t : to) toLower.add(t.toLowerCase(Locale.ROOT));
        List<String> leftOut = new ArrayList<>();
        for (String a : Py.csv(d.getToEmails())) if (!toLower.contains(a.toLowerCase(Locale.ROOT))) leftOut.add(a);
        String partnerName = groups.partnerOf(d).getName();
        List<String> note = new ArrayList<>();
        if (!leftOut.isEmpty()) note.add("Not sent to " + String.join(", ", leftOut) + " (no longer an active address of " + partnerName + ").");
        if (to.isEmpty()) note.add(partnerName + " has no active address: add one on the Partners page first.");
        return Py.map("to", to, "cc", Py.csv(d.getCcEmails()), "subject", content[0], "body_html", content[1],
                "number", d.getFollowupCount() + 1, "left_out", leftOut, "note", String.join(" ", note));
    }

    /** Send a follow-up for a sent email (users and admins), in the original thread. */
    public Map<String, Object> sendFollowup(User user, int emailId, List<String> to, List<String> cc, String subject, String bodyHtml) {
        if (to.isEmpty()) throw new ApiException(400, "Add at least one recipient.");
        if (Py.strip(bodyHtml).isEmpty()) throw new ApiException(400, "The email body is empty.");
        EmailDispatch d = queries.getSentOr404(emailId);
        ensureFollowupAllowed(d);

        LocalDateTime previous = d.getLastFollowupAt();
        if (!claimFollowup(d, null)) {
            ensureFollowupAllowed(d);  // reconciled in the meantime
            throw new ApiException(409, "A follow-up for this email was just sent. Check its history before sending another.");
        }
        List<String> refused;
        try {
            refused = deliverFollowup(d, previous, to, cc, subject, bodyHtml, user.getUsername());
        } catch (Exception exc) {
            throw new ApiException(502, "The follow-up could not be sent: " + exc.getMessage());
        }
        return Py.map("email", views.dispatchToDict(d, null), "refused", refused);
    }

    /**
     * Claim the email for one follow-up with a single UPDATE (sets last_followup_at to now; committed at once, then
     * the email is read again). Fails when it is reconciled, a follow-up went out less than a minute ago (a double
     * click, two admins, or the automatic sender at the same moment), or (dueCutoff given) it is no longer due.
     */
    boolean claimFollowup(EmailDispatch d, LocalDateTime dueCutoff) {
        LocalDateTime now = Py.now();
        StringBuilder text = new StringBuilder("UPDATE email_dispatches SET last_followup_at = ? WHERE id = ? AND status = 'SENT' "
                + "AND reply_status != 'reconciled' AND (last_followup_at IS NULL OR last_followup_at < ?)");
        List<Object> args = new ArrayList<>(List.of(now, d.getId(), now.minusSeconds(FOLLOWUP_GAP_SECONDS)));
        if (dueCutoff != null) {
            text.append(" AND reply_status = 'not_received' AND ((last_followup_at IS NULL AND sent_at < ?) OR last_followup_at < ?)");
            args.add(dueCutoff);
            args.add(dueCutoff);
        }
        boolean claimed = Boolean.TRUE.equals(tx.execute(status -> sql.execute(text.toString(), args.toArray()) == 1));
        tx.executeWithoutResult(status -> em.refresh(d));  // db.refresh(d) after the commit
        return claimed;
    }

    /**
     * Send the follow-up of an email claimed with claimFollowup, in the original email's thread, and record it in
     * the history. previous: last_followup_at before the claim (restored when nothing went out). Returns the refused
     * recipients; throws when the email could not be sent (the failure is recorded first).
     */
    List<String> deliverFollowup(EmailDispatch d, LocalDateTime previous, List<String> to, List<String> cc,
                                 String subject, String bodyHtml, String by) {
        EmailEvent event = new EmailEvent();
        event.setDispatchId(d.getId());
        event.setKind("followup");
        event.setSubject(subject);
        event.setBodyHtml(bodyHtml);
        event.setToEmails(String.join(",", to));
        event.setCcEmails(String.join(",", cc));
        event.setCreatedBy(by);
        EmailService.SendResult result;
        try {
            result = emailService.sendEmail(to, cc, subject, bodyHtml, d.getMessageId(), groups.attachmentFor(d), null);
        } catch (RuntimeException exc) {
            tx.executeWithoutResult(status -> {
                d.setLastFollowupAt(previous);  // nothing went out
                event.setOk(false);
                event.setError(Py.cut(exc.getMessage() == null ? exc.toString() : exc.getMessage(), 1000));
                events.save(event);
            });
            throw exc;
        }
        tx.executeWithoutResult(status -> {
            d.setFollowupCount(d.getFollowupCount() + 1);
            event.setMessageId(result.messageId());  // a reply to the follow-up is matched to this email
            for (Map.Entry<String, String> r : result.refused().entrySet()) delivery.recordRefused(d, r.getKey(), r.getValue());
            if (!result.refused().isEmpty()) event.setError("Refused: " + EmailService.joinRefused(result.refused()));
            events.save(event);
        });
        return new ArrayList<>(result.refused().keySet());
    }

    // ---------------------------------------------------------------- automatic follow-ups

    /** Due emails that may still get an automatic follow-up (below the follow-up limit). */
    public List<EmailDispatch> autoCandidates(int maxFollowups) {
        SqlQuery q = queries.dueFilter(new SqlQuery("WHERE t.status = 'SENT'", new ArrayList<>())).and("t.followup_count < ?", maxFollowups);
        return sql.all(EmailDispatch.class, q.sql() + " ORDER BY t.sent_at, t.id", q.argArray());
    }

    /** last_run_at, last_result, next_run_at of the automatic follow-ups (a copy). */
    public Map<String, Object> autoStatus() {
        synchronized (autoStatus) {
            return new LinkedHashMap<>(autoStatus);
        }
    }

    /**
     * Send the follow-up of every due email, when automatic follow-ups are switched on. Runs in a database session
     * of its own (also when an admin starts it from a request), one commit per email.
     */
    public Map<String, Object> runAutoFollowups() {
        runLock.lock();
        try {
            return separateSession.run(this::runInOwnSession);
        } finally {
            runLock.unlock();
        }
    }

    private Map<String, Object> runInOwnSession() {
        Map<String, Object> values = settings.current();
        boolean enabled = Boolean.TRUE.equals(values.get("auto_followup_enabled"));
        List<String> errors = new ArrayList<>();
        int sent = 0, failed = 0;
        if (enabled) {
            LocalDateTime now = LocalDateTime.now();
            Set<Long> recentlyFailed = new HashSet<>(sql.ids(  // kept in the database: survives a restart
                    "SELECT dispatch_id FROM email_events WHERE kind = 'followup' AND ok = ? AND created_by = ? AND created_at > ?",
                    false, SYSTEM_USER, now.minusHours(AUTO_RETRY_AFTER_HOURS)));
            for (EmailDispatch d : autoCandidates(((Number) values.get("auto_followup_max")).intValue())) {
                if (recentlyFailed.contains(d.getId().longValue())) continue;
                List<String> to = currentRecipients(d);
                if (to.isEmpty()) {  // every address was refused, removed or switched off since the email was sent
                    String partnerName = groups.partnerOf(d).getName();
                    tx.executeWithoutResult(status -> {
                        EmailEvent e = new EmailEvent();
                        e.setDispatchId(d.getId());
                        e.setKind("followup");
                        e.setOk(false);
                        e.setCreatedBy(SYSTEM_USER);
                        e.setError("Not sent: " + partnerName + " has no active address.");
                        events.save(e);
                    });
                    failed++;
                    errors.add(partnerName + " (#" + d.getId() + "): no active address");
                    continue;
                }
                LocalDateTime previous = d.getLastFollowupAt();
                // claimed only while still due: an admin may have just followed up or changed the reply status
                if (!claimFollowup(d, LocalDateTime.now().minusDays(settings.getInt("followup_due_days")))) continue;
                String[] content = followupContent(d, d.getFollowupCount() + 1);
                try {
                    deliverFollowup(d, previous, to, Py.csv(d.getCcEmails()), content[0], content[1], SYSTEM_USER);
                    sent++;
                } catch (Exception exc) {
                    failed++;
                    errors.add(Py.cut(groups.partnerOf(d).getName() + " (#" + d.getId() + "): " + exc.getMessage(), 300));
                }
            }
        }
        Map<String, Object> result = Py.map("enabled", enabled, "sent", sent, "failed", failed, "errors", errors);
        autoStatus.put("last_run_at", Py.isoSeconds(LocalDateTime.now()));
        autoStatus.put("last_result", result);
        if (sent > 0 || failed > 0) LOG.info("Automatic follow-ups: " + sent + " sent, " + failed + " failed");
        return result;
    }

    // ---------------------------------------------------------------- the timer

    /** Start the background job: look for due emails now, then every AUTO_FOLLOWUP_CHECK_SECONDS. */
    public void start() {
        synchronized (timerLock) {
            if (started || stopped) return;
            started = true;
            schedule(Instant.now());
        }
    }

    /** Plan the next check (replaces the one planned before). Called with timerLock held. */
    private void schedule(Instant at) {
        long plan = ++generation;
        next = scheduler.schedule(() -> tick(plan), at);
    }

    /** One check, then the next one is planned (at once when woken meanwhile). */
    private void tick(long plan) {
        synchronized (timerLock) {
            if (stopped || plan != generation) return;  // stopped, or replaced by a newer plan
            next = null;
            running = true;
        }
        try {
            runAutoFollowups();
        } catch (Exception e) {
            LOG.error("Automatic follow-ups failed", e);
        }
        int seconds = live.autoFollowupCheckSeconds();
        autoStatus.put("next_run_at", Py.isoSeconds(LocalDateTime.now().plusSeconds(seconds)));
        synchronized (timerLock) {
            running = false;
            boolean now = woken;
            woken = false;
            if (!stopped) schedule(now ? Instant.now() : Instant.now().plusSeconds(seconds));
        }
    }

    /** System Settings changed (e.g. the check interval): check again now, and go on with the new interval. */
    public void wake() {
        synchronized (timerLock) {
            if (stopped) return;
            if (!started || running) {  // not started yet / a check is running: the next one starts right after it
                woken = true;
                return;
            }
            if (next != null) next.cancel(false);
            schedule(Instant.now());
        }
    }

    @PreDestroy
    public void stop() {
        synchronized (timerLock) {
            stopped = true;
            if (next != null) next.cancel(false);
            next = null;
        }
    }
}
