package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.EmailEvent;
import com.nagad.npsb.entity.EmailReply;
import com.nagad.npsb.entity.EmailReplyAttachment;
import com.nagad.npsb.entity.InboxIssue;
import com.nagad.npsb.entity.InboxState;
import com.nagad.npsb.repository.EmailDispatchRepository;
import com.nagad.npsb.repository.EmailEventRepository;
import com.nagad.npsb.repository.EmailReplyAttachmentRepository;
import com.nagad.npsb.repository.EmailReplyRepository;
import com.nagad.npsb.repository.InboxIssueRepository;
import com.nagad.npsb.repository.InboxStateRepository;
import com.nagad.npsb.service.support.SqlRows;
import com.nagad.npsb.util.Py;
import jakarta.annotation.PreDestroy;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.Message;
import jakarta.mail.MessageRemovedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads partners' mail from the mailbox the emails are sent from, over IMAP (Gmail: imap.gmail.com).
 *
 * Kinds of incoming partner mail, kept apart:
 *   * Replies to our emails: the message's In-Reply-To / References header holds the Message-ID of one of our
 *     emails, follow-ups or answers, or (without those headers) its subject is our email's subject and it
 *     comes from one of that partner's addresses (or their domain). A reply sets the email to "Reply received".
 *     Automatic replies (out of office) and bounces are stored with the email but don't change its status.
 *   * Other mail from partners: from a partner's address (or domain), not answering any of our emails.
 *   * Messages that need a person to check them ("review"): an answer in our email's thread from an address that
 *     isn't the partner's, a subject that matches more than one of our emails, or an address that belongs to
 *     several partners. They change nothing until an admin links them to an email or dismisses them.
 * A message that can't be read is skipped (the others are still read) and listed as an inbox issue. Everything
 * else in the mailbox is left alone: only the headers are looked at, and the folder is opened read-only, so
 * nothing is marked as read in the mailbox either.
 *
 * Each check only looks at messages that arrived since the last one (inbox_state). The first check looks back
 * to the oldest sent email, at most INBOX_FIRST_SCAN_DAYS days. The mailbox settings are read at every check, so
 * a change on the System Settings page applies at once.
 *
 * The background check runs on the TaskScheduler (start / wake / stop); each message (and each other change to the
 * database) is stored in a transaction of its own.
 */
@Service
public class Inbox {
    private static final Logger LOG = LoggerFactory.getLogger("npsb");

    /** created_by of the history entries added when a reply is found ("#" is not allowed in usernames) */
    public static final String INBOX_USER = "#inbox";

    private static final String[] HEADER_FIELDS = {"Message-ID", "In-Reply-To", "References", "Subject", "From", "Auto-Submitted",
            "X-Autoreply", "X-Autorespond", "Precedence", "Content-Type"};
    private static final int FETCH_BATCH = 200;                       // headers of this many messages per request
    private static final int MAX_MESSAGE_BYTES = 25 * 1024 * 1024;   // bigger messages are stored without their attachments
    private static final int MAX_BODY_CHARS = 2_000_000;

    // What an incoming message is (email_replies.kind)
    public static final String REPLY = "reply", AUTO_REPLY = "auto_reply", BOUNCE = "bounce", PARTNER_MAIL = "partner_mail";
    public static final String REVIEW = "review", DISMISSED = "dismissed";

    private final ReentrantLock lock = new ReentrantLock();  // one check at a time ("Check now" while the timer runs)
    /** What the last checks did (shown on the Email Configuration page). */
    private final Map<String, Object> status = java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    // the background check (guarded by `schedule`)
    private final Object schedule = new Object();
    private ScheduledFuture<?> next;
    private boolean started, running, woken;
    private volatile boolean stopped = false;

    private final LiveSettings live;
    private final JdbcClient jdbc;
    private final EntityManager entityManager;
    private final EntityManagerFactory entityManagerFactory;
    private final TransactionTemplate transactionTemplate;
    private final TaskScheduler taskScheduler;
    private final Delivery delivery;
    private final EmailDispatchRepository dispatches;
    private final EmailEventRepository events;
    private final EmailReplyRepository replies;
    private final EmailReplyAttachmentRepository attachments;
    private final InboxIssueRepository issues;
    private final InboxStateRepository states;

    public Inbox(LiveSettings live, JdbcClient jdbc, EntityManager entityManager, EntityManagerFactory entityManagerFactory,
                 TransactionTemplate transactionTemplate, TaskScheduler taskScheduler, Delivery delivery,
                 EmailDispatchRepository dispatches, EmailEventRepository events, EmailReplyRepository replies,
                 EmailReplyAttachmentRepository attachments, InboxIssueRepository issues, InboxStateRepository states) {
        this.live = live;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.entityManagerFactory = entityManagerFactory;
        this.transactionTemplate = transactionTemplate;
        this.taskScheduler = taskScheduler;
        this.delivery = delivery;
        this.dispatches = dispatches;
        this.events = events;
        this.replies = replies;
        this.attachments = attachments;
        this.issues = issues;
        this.states = states;
        status.put("last_run_at", null);
        status.put("last_ok_at", null);
        status.put("last_error", null);
        status.put("last_found", 0);
        status.put("next_run_at", null);
    }

    public boolean configured() {
        return !live.imapHost().isEmpty() && !live.imapUser().isEmpty() && !live.imapPassword().isEmpty();
    }

    public boolean enabled() {
        return live.inboxEnabled() && configured();
    }

    public String mailboxName() {
        return live.imapUser() + "@" + live.imapHost() + "/" + live.imapFolder();
    }

    public Map<String, Object> statusDict() {
        Map<String, Object> out;
        synchronized (status) {
            out = new LinkedHashMap<>(status);
        }
        out.put("enabled", enabled());
        out.put("switched_on", live.inboxEnabled());
        out.put("configured", configured());
        out.put("provider", live.mailProvider());
        out.put("mailbox", configured() ? live.mailboxAddress() : null);
        out.put("host", live.imapHost());
        out.put("folder", live.imapFolder());
        out.put("check_every_seconds", live.inboxCheckSeconds());
        return out;
    }

    // ---------------------------------------------------------------- matching a message to our emails

    public static String normId(String value) {
        return Py.strip(Py.strip(Py.strip(value), "<>")).toLowerCase(java.util.Locale.ROOT);
    }

    private static final Pattern BRACKETED = Pattern.compile("<([^<>\\s]+)>", Pattern.UNICODE_CHARACTER_CLASS);

    /** Message-IDs a message answers, the direct parent (In-Reply-To) first. */
    static List<String> referencedIds(String inReplyTo, String references) {
        List<String> ids = new ArrayList<>();
        Matcher m = BRACKETED.matcher(inReplyTo == null ? "" : inReplyTo);
        while (m.find()) ids.add(m.group(1));
        List<String> refs = new ArrayList<>();
        m = BRACKETED.matcher(references == null ? "" : references);
        while (m.find()) refs.add(m.group(1));
        java.util.Collections.reverse(refs);
        ids.addAll(refs);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String i : ids) out.add(normId(i));
        return new ArrayList<>(out);
    }

    private static final Pattern SUBJECT_PREFIX = Pattern.compile("^\\s*((re|fw|fwd|aw|sv|wg|antw)\\s*(\\[\\d+\\])?\\s*:\\s*)+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    static String normSubject(String subject) {
        String s = SUBJECT_PREFIX.matcher(subject == null ? "" : subject).replaceFirst("");
        return Py.strip(SPACES.matcher(s).replaceAll(" ")).toLowerCase(java.util.Locale.ROOT);
    }

    static String domain(String address) {
        return address.substring(address.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Free mail providers: a partner using one of these is matched by its exact addresses only */
    private static final Set<String> PUBLIC_DOMAINS = Set.of("gmail.com", "googlemail.com", "yahoo.com", "outlook.com", "hotmail.com",
            "live.com", "icloud.com", "aol.com", "proton.me", "protonmail.com", "ymail.com");

    /** A sent email as the matcher needs it (plain values: safe after a rollback). */
    record Sent(int id, int partnerId, String partnerName, String subject, String toEmails, String ccEmails,
                LocalDateTime sentAt, Set<String> partnerAddresses) {}

    record Hit(Sent sent, String matchedBy, String doubt) {}

    /**
     * Our sent emails and the partners' addresses, loaded once per check: which email (if any) a message
     * answers, and which partner (if any) sent it.
     */
    static final class Matcher_ {
        final Map<String, Integer> byId = new HashMap<>();
        final Map<Integer, Sent> dispatches = new HashMap<>();
        final Map<String, List<Sent>> bySubject = new HashMap<>();
        final Set<String> ours = new LinkedHashSet<>();
        final LocalDateTime oldestSent;
        final Map<Integer, String> partnerNames = new HashMap<>();
        final Map<String, Set<Integer>> partnersByAddress = new HashMap<>();
        final Map<String, Set<Integer>> partnersByDomain = new HashMap<>();

        /** Read with plain SQL (the values are copied: nothing stays attached to a persistence context). */
        Matcher_(JdbcClient jdbc, LiveSettings live) {
            Map<Integer, Set<String>> partnerAddresses = new HashMap<>();
            for (Object[] row : SqlRows.rows(jdbc, "SELECT partner_id, email FROM partner_emails")) {
                partnerAddresses.computeIfAbsent(((Number) row[0]).intValue(), k -> new HashSet<>())
                        .add(((String) row[1]).toLowerCase(java.util.Locale.ROOT));
            }
            for (Object[] row : SqlRows.rows(jdbc, "SELECT id, name FROM partners")) partnerNames.put(((Number) row[0]).intValue(), (String) row[1]);
            LocalDateTime oldest = null;
            // the SENT email_dispatches rows (only the columns the matcher needs)
            for (Object[] row : SqlRows.rows(jdbc, "SELECT t.id, t.partner_id, t.subject, t.to_emails, t.cc_emails, t.sent_at, t.message_id "
                    + "FROM email_dispatches t WHERE t.status = 'SENT'")) {
                int id = ((Number) row[0]).intValue(), partnerId = ((Number) row[1]).intValue();
                String subject = (String) row[2], messageId = (String) row[6];
                LocalDateTime sentAt = (LocalDateTime) row[5];
                Sent s = new Sent(id, partnerId, partnerNames.getOrDefault(partnerId, ""), subject, (String) row[3], (String) row[4],
                        sentAt, partnerAddresses.getOrDefault(partnerId, Set.of()));
                if (!Py.isEmpty(messageId)) byId.put(normId(messageId), id);
                dispatches.put(id, s);
                bySubject.computeIfAbsent(normSubject(subject), k -> new ArrayList<>()).add(s);
                if (sentAt != null && (oldest == null || sentAt.isBefore(oldest))) oldest = sentAt;
            }
            for (Object[] row : SqlRows.rows(jdbc, "SELECT dispatch_id, message_id FROM email_events WHERE kind IN ('followup', 'answer') "
                    + "AND message_id IS NOT NULL ORDER BY id")) {
                byId.put(normId((String) row[1]), ((Number) row[0]).intValue());
            }
            for (String a : new String[]{live.imapUser(), live.mailFrom(), live.smtpUser(), live.mailFrom(), live.mailboxAddress()}) {
                if (a != null && !Py.strip(a).isEmpty()) ours.add(Py.strip(a).toLowerCase(java.util.Locale.ROOT));
            }
            oldestSent = oldest;
            Set<String> ownDomains = new HashSet<>();
            for (String a : ours) ownDomains.add(domain(a));
            for (Object[] row : SqlRows.rows(jdbc, "SELECT partner_id, email FROM partner_emails ORDER BY id")) {
                String address = Py.strip((String) row[1]).toLowerCase(java.util.Locale.ROOT);
                int partnerId = ((Number) row[0]).intValue();
                partnersByAddress.computeIfAbsent(address, k -> new LinkedHashSet<>()).add(partnerId);
                String dom = domain(address);
                if (!PUBLIC_DOMAINS.contains(dom) && !ownDomains.contains(dom)) {
                    partnersByDomain.computeIfAbsent(dom, k -> new LinkedHashSet<>()).add(partnerId);
                }
            }
        }

        /** (email, matched by, doubt) for a message that answers one of our emails, else null. */
        Hit match(String sender, String subject, String inReplyTo, String references) {
            sender = sender.toLowerCase(java.util.Locale.ROOT);
            if (sender.isEmpty() || ours.contains(sender)) return null;  // our own emails (copies, follow-ups)
            for (String ref : referencedIds(inReplyTo, references)) {
                Integer id = byId.get(ref);
                Sent d = id == null ? null : dispatches.get(id);
                if (d != null) {
                    // Nagad colleagues on CC answering in the thread are not the partner's reply
                    for (String c : Py.csv(d.ccEmails())) if (c.toLowerCase(java.util.Locale.ROOT).equals(sender)) return null;
                    String doubt = fromPartner(d, sender) ? null
                            : "Sent by " + sender + ", which is not one of " + d.partnerName() + "'s addresses.";
                    return new Hit(d, "thread", doubt);
                }
            }
            List<Sent> candidates = new ArrayList<>();
            for (Sent d : bySubject.getOrDefault(normSubject(subject), List.of())) if (fromPartner(d, sender)) candidates.add(d);
            if (!candidates.isEmpty()) {
                Sent newest = candidates.get(0);
                for (Sent d : candidates) {
                    LocalDateTime a = d.sentAt() == null ? LocalDateTime.MIN : d.sentAt();
                    LocalDateTime b = newest.sentAt() == null ? LocalDateTime.MIN : newest.sentAt();
                    if (a.isAfter(b)) newest = d;
                }
                String doubt = candidates.size() == 1 ? null
                        : "Its subject matches " + candidates.size() + " sent emails; the newest one is suggested.";
                return new Hit(newest, "subject", doubt);
            }
            return null;
        }

        /** The partners this address belongs to: one of their addresses, or their own (company) domain. */
        Set<Integer> partnersFor(String sender) {
            sender = sender.toLowerCase(java.util.Locale.ROOT);
            if (sender.isEmpty() || ours.contains(sender)) return Set.of();
            Set<Integer> byAddress = partnersByAddress.get(sender);
            if (byAddress != null && !byAddress.isEmpty()) return byAddress;
            Set<Integer> byDomain = partnersByDomain.get(domain(sender));
            return byDomain == null ? Set.of() : byDomain;
        }

        private boolean fromPartner(Sent d, String sender) {
            Set<String> addresses = new HashSet<>();
            for (String a : Py.splitOn(d.toEmails(), ",")) if (!Py.strip(a).isEmpty()) addresses.add(Py.strip(a).toLowerCase(java.util.Locale.ROOT));
            addresses.addAll(d.partnerAddresses());
            if (addresses.contains(sender)) return true;
            // a partner on a free mail provider, or on our own domain: its exact addresses only
            Set<String> domains = new HashSet<>();
            for (String a : addresses) domains.add(domain(a));
            domains.removeAll(PUBLIC_DOMAINS);
            for (String a : ours) domains.remove(domain(a));
            return domains.contains(domain(sender));
        }
    }

    private static final Pattern AUTO_SUBJECT = Pattern.compile(
            "^\\s*(automatic reply|auto(matic)?[- ]?reply|out of (the )?office|autoreply|auto:)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> BOUNCE_SENDERS = Set.of("mailer-daemon", "postmaster");

    // ---------------------------------------------------------------- headers

    /** The headers of a message, read like Python's email package (values decoded and unfolded). */
    static final class Headers {
        private final InternetHeaders raw;

        Headers(byte[] bytes) {
            InternetHeaders h;
            try {
                h = new InternetHeaders(new ByteArrayInputStream(bytes == null ? new byte[0] : bytes));
            } catch (MessagingException e) {
                h = new InternetHeaders();
            }
            raw = h;
        }

        /** The first value, unfolded but not decoded, or null. */
        String raw(String name) {
            String[] values = raw.getHeader(name);
            if (values == null || values.length == 0) return null;
            return MimeUtility.unfold(values[0]);
        }

        /** str(headers[name] or ""): decoded text. */
        String text(String name) {
            String v = raw(name);
            if (v == null) return "";
            try {
                return Py.strip(MimeUtility.decodeText(v));
            } catch (Exception e) {
                return Py.strip(v);
            }
        }

        boolean present(String name) {
            return !text(name).isEmpty();
        }
    }

    /** reply, auto_reply (out of office etc.) or bounce (delivery failure notice). */
    static String classify(Headers headers, String sender) {
        String local = sender.contains("@") ? sender.substring(0, sender.indexOf('@')) : sender;
        if (BOUNCE_SENDERS.contains(local.toLowerCase(java.util.Locale.ROOT))) return BOUNCE;
        String contentType = headers.text("Content-Type").toLowerCase(java.util.Locale.ROOT);
        if (contentType.contains("multipart/report") && contentType.contains("delivery-status")) return BOUNCE;
        String autoSubmitted = Py.strip(headers.text("Auto-Submitted")).toLowerCase(java.util.Locale.ROOT);
        if (!autoSubmitted.isEmpty() && !autoSubmitted.equals("no")) return AUTO_REPLY;
        if (headers.present("X-Autoreply") || headers.present("X-Autorespond")) return AUTO_REPLY;
        if (Py.strip(headers.text("Precedence")).toLowerCase(java.util.Locale.ROOT).equals("auto_reply")) return AUTO_REPLY;
        if (AUTO_SUBJECT.matcher(headers.text("Subject")).find()) return AUTO_REPLY;
        return REPLY;
    }

    /** (name, address) of the first address in a header value. */
    static String[] firstAddress(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return new String[]{"", ""};
        try {
            InternetAddress[] parsed = InternetAddress.parseHeader(rawValue, false);
            if (parsed.length == 0) return new String[]{"", ""};
            String personal = parsed[0].getPersonal();
            return new String[]{personal == null ? "" : personal, parsed[0].getAddress() == null ? "" : parsed[0].getAddress()};
        } catch (Exception e) {
            return new String[]{"", ""};
        }
    }

    /** The addresses of header values, comma separated. */
    static String addressList(String... rawValues) {
        List<String> out = new ArrayList<>();
        for (String v : rawValues) {
            if (v == null || v.isBlank()) continue;
            try {
                for (InternetAddress a : InternetAddress.parseHeader(v, false)) {
                    if (a.getAddress() != null && !a.getAddress().isEmpty()) out.add(a.getAddress());
                }
            } catch (Exception ignored) {
                // unreadable address list: left out
            }
        }
        return String.join(",", out);
    }

    /** parsedate_to_datetime(value) as local time, or null. */
    static LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Date d = new MailDateFormat().parse(Py.strip(value));
            return d == null ? null : LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()).withNano(0);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- reading the message

    private static final Pattern STYLE = Pattern.compile("(?is)<(style|script|head)\\b.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    static String textOf(String html) {
        String s = STYLE.matcher(html).replaceAll(" ");
        s = TAG.matcher(s).replaceAll(" ");
        return Py.strip(SPACES.matcher(Parser.unescapeEntities(s, false)).replaceAll(" "));
    }

    record Content(String bodyHtml, String snippet, List<Object[]> attachments) {}

    private static Session plainSession() {
        Properties p = new Properties();
        return Session.getInstance(p);
    }

    private static boolean isAttachment(Part part) throws MessagingException {
        String disposition = part.getDisposition();
        return disposition != null && disposition.equalsIgnoreCase("attachment");
    }

    private static String[] mainSub(Part part) throws MessagingException {
        String ct = part.getContentType();
        try {
            ContentType type = new ContentType(ct == null ? "text/plain" : ct);
            return new String[]{type.getPrimaryType().toLowerCase(java.util.Locale.ROOT), type.getSubType().toLowerCase(java.util.Locale.ROOT)};
        } catch (Exception e) {
            return new String[]{"text", "plain"};
        }
    }

    private static String param(Part part, String name) throws MessagingException {
        try {
            return new ContentType(part.getContentType()).getParameter(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String contentId(Part part) throws MessagingException {
        String[] ids = part.getHeader("Content-ID");
        return ids == null || ids.length == 0 ? null : ids[0];
    }

    /** email.message.MIMEPart._find_body */
    private static void findBody(Part part, List<String> preference, List<Object[]> out) throws MessagingException, IOException {
        if (isAttachment(part)) return;
        String[] t = mainSub(part);
        if (t[0].equals("text")) {
            if (preference.contains(t[1])) out.add(new Object[]{preference.indexOf(t[1]), part});
            return;
        }
        if (!t[0].equals("multipart") || !(part.getContent() instanceof Multipart mp)) return;
        if (!t[1].equals("related")) {
            for (int i = 0; i < mp.getCount(); i++) findBody(mp.getBodyPart(i), preference, out);
            return;
        }
        Part candidate = null;
        String start = param(part, "start");
        if (start != null && !start.isEmpty()) {
            for (int i = 0; i < mp.getCount(); i++) {
                if (start.equals(contentId(mp.getBodyPart(i)))) {
                    candidate = mp.getBodyPart(i);
                    break;
                }
            }
        }
        if (candidate == null && mp.getCount() > 0) candidate = mp.getBodyPart(0);
        if (candidate != null) findBody(candidate, preference, out);
    }

    /** email.message.MIMEPart.iter_attachments (top level only, like Python). */
    private static List<Part> iterAttachments(Part msg) throws MessagingException, IOException {
        List<Part> out = new ArrayList<>();
        String[] t = mainSub(msg);
        if (!t[0].equals("multipart") || t[1].equals("alternative")) return out;
        if (!(msg.getContent() instanceof Multipart mp)) return out;
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < mp.getCount(); i++) parts.add(mp.getBodyPart(i));
        if (t[1].equals("related")) {
            String start = param(msg, "start");
            if (start != null && !start.isEmpty()) {
                boolean found = false;
                List<Part> attachments = new ArrayList<>();
                for (Part p : parts) {
                    if (start.equals(contentId(p))) found = true;
                    else attachments.add(p);
                }
                if (found) return attachments;
            }
            if (!parts.isEmpty()) parts.remove(0);
            return parts;
        }
        List<String> seen = new ArrayList<>();
        for (Part p : parts) {
            String[] pt = mainSub(p);
            boolean bodyType = (pt[0].equals("text") && (pt[1].equals("plain") || pt[1].equals("html")))
                    || (pt[0].equals("multipart") && (pt[1].equals("related") || pt[1].equals("alternative")));
            if (bodyType && !isAttachment(p) && !seen.contains(pt[1])) {
                seen.add(pt[1]);
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private static byte[] bytesOf(Part part) {
        try (InputStream in = part.getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String textContent(Part part) {
        try {
            Object c = part.getContent();
            if (c instanceof String s) return s;
        } catch (Exception ignored) {
            // unknown charset: read the bytes as UTF-8 below
        }
        return new String(bytesOf(part), StandardCharsets.UTF_8);
    }

    /** Body (HTML), snippet and attachments {filename, content type, bytes} of a downloaded message. */
    static Content readMessage(byte[] raw) throws MessagingException, IOException {
        MimeMessage msg = new MimeMessage(plainSession(), new ByteArrayInputStream(raw));
        List<Object[]> found = new ArrayList<>();
        findBody(msg, List.of("html", "plain"), found);
        Part bodyPart = null;
        int best = 2;
        for (Object[] f : found) {
            if ((Integer) f[0] < best) {
                best = (Integer) f[0];
                bodyPart = (Part) f[1];
                if (best == 0) break;
            }
        }
        String bodyHtml = "", plain = "";
        if (bodyPart != null) {
            String content = textContent(bodyPart);
            if (mainSub(bodyPart)[1].equals("html")) {
                bodyHtml = content;
            } else {
                plain = content;
                bodyHtml = "<div style=\"white-space:pre-wrap\">" + Py.escape(content) + "</div>";
            }
        }
        List<Object[]> attachments = new ArrayList<>();
        for (Part part : iterAttachments(msg)) {
            String filename;
            try {
                filename = part.getFileName();
            } catch (Exception e) {
                filename = null;
            }
            String[] t = mainSub(part);
            String disposition = part.getDisposition();
            if (filename == null || filename.isEmpty() || (t[0].equals("image") && "inline".equalsIgnoreCase(disposition))) {
                continue;  // pictures inside the text (logos in signatures)
            }
            attachments.add(new Object[]{Py.cut(filename, 255), Py.cut(t[0] + "/" + t[1], 100), bytesOf(part)});
        }
        String snippet = !plain.isEmpty() ? Py.strip(SPACES.matcher(plain).replaceAll(" ")) : textOf(bodyHtml);
        return new Content(Py.cut(bodyHtml, MAX_BODY_CHARS), Py.cut(snippet, 300), attachments);
    }

    // ---------------------------------------------------------------- where messages come from

    /** One message to look at: its key (IMAP UID), headers, size. */
    record Listed(Object key, byte[] headerBytes, int size) {}

    /** What was downloaded: raw message, whether it is whole (or headers only), when it arrived. */
    record Fetched(byte[] raw, boolean whole, LocalDateTime arrived) {}

    interface Source {
        String localId(Object key);

        Map<String, Object> ident(Object key);

        Fetched fetch(Object key, int size) throws Exception;
    }

    private static Session imapSession() {
        Properties p = new Properties();
        p.put("mail.imaps.connectiontimeout", "30000");
        p.put("mail.imaps.timeout", "30000");
        p.put("mail.imaps.peek", "true");
        p.put("mail.imaps.partialfetch", "false");
        return Session.getInstance(p);
    }

    /** Sign in to the mailbox with the settings in effect. */
    Store signIn() {
        return signIn(live.imapHost(), live.imapPort(), live.imapUser(), live.imapPassword());
    }

    /** Sign in to the mailbox with the given settings (System Settings test). */
    static Store signIn(String host, int port, String user, String password) {
        Store store;
        try {
            store = imapSession().getStore("imaps");
        } catch (MessagingException e) {
            throw new RuntimeException("Could not reach the mail server " + host + ":" + port + ": " + EmailService.text(e));
        }
        try {
            store.connect(host, port, user, password);
        } catch (AuthenticationFailedException e) {
            String hint = host.contains("gmail") ? " For Gmail use the same 16-character app password as for sending." : "";
            throw new RuntimeException("The mailbox refused the sign-in for " + user + ": " + EmailService.text(e) + "." + hint);
        } catch (MessagingException e) {
            Throwable cause = e.getNextException() != null ? e.getNextException() : e;
            throw new RuntimeException("Could not reach the mail server " + host + ":" + port + ": " + EmailService.text(cause));
        }
        return store;
    }

    /** Open a folder read-only: nothing is marked as read. */
    static IMAPFolder openFolder(Store store, String name) throws MessagingException {
        IMAPFolder folder = (IMAPFolder) store.getFolder(Py.strip(name, "\""));
        try {
            folder.open(Folder.READ_ONLY);
        } catch (FolderNotFoundException e) {
            throw new RuntimeException("The folder " + name + " was not found in the mailbox.");
        } catch (MessagingException e) {
            if (!folder.exists()) throw new RuntimeException("The folder " + name + " was not found in the mailbox.");
            throw e;
        }
        return folder;
    }

    /** Sign in and open the folder, read-only, then sign out. Throws with a readable message. */
    public static void testConnection(String host, int port, String user, String password, String folder) {
        if (host.isEmpty() || user.isEmpty() || password.isEmpty()) {
            throw new RuntimeException("The mailbox needs a server, a user and a password.");
        }
        Store store = signIn(host, port, user, password);
        try {
            openFolder(store, folder).close(false);
        } catch (MessagingException e) {
            throw new RuntimeException(EmailService.text(e));
        } finally {
            try {
                store.close();
            } catch (Exception ignored) {
                // signed out anyway
            }
        }
    }

    /** The headers of a fetched IMAP message as raw header lines. */
    private static byte[] headerBytes(Message m, String[] names) throws MessagingException {
        StringBuilder b = new StringBuilder();
        for (String name : names) {
            String[] values = m.getHeader(name);
            if (values == null) continue;
            for (String v : values) b.append(name).append(": ").append(v).append("\r\n");
        }
        b.append("\r\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static final class ImapSource implements Source {
        final IMAPFolder folder;
        final long uidvalidity;
        final String host;

        /** host: the IMAP server in effect (LiveSettings.imapHost()). */
        ImapSource(IMAPFolder folder, long uidvalidity, String host) {
            this.folder = folder;
            this.uidvalidity = uidvalidity;
            this.host = host;
        }

        /** Message-ID for a message without one (made-up ids start with "imap-"). */
        public String localId(Object uid) {
            return "imap-" + uidvalidity + "-" + uid + "@" + host;
        }

        public Map<String, Object> ident(Object uid) {
            return Py.map("uidvalidity", uidvalidity, "uid", ((Number) uid).longValue(), "remote_id", null);
        }

        /** (raw message, whole, time it arrived); only the headers when it is too large. null: gone. */
        public Fetched fetch(Object uid, int size) throws Exception {
            Message m = folder.getMessageByUID(((Number) uid).longValue());
            if (m == null) return null;
            try {
                boolean whole = size <= MAX_MESSAGE_BYTES;
                byte[] raw;
                if (whole) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    m.writeTo(out);
                    raw = out.toByteArray();
                } else {
                    StringBuilder b = new StringBuilder();
                    java.util.Enumeration<String> lines = ((MimeMessage) m).getAllHeaderLines();
                    while (lines.hasMoreElements()) b.append(lines.nextElement()).append("\r\n");
                    raw = (b + "\r\n").getBytes(StandardCharsets.UTF_8);
                }
                Date received = m.getReceivedDate();
                LocalDateTime arrived = received == null ? null : LocalDateTime.ofInstant(received.toInstant(), ZoneId.systemDefault()).withNano(0);
                return new Fetched(raw, whole, arrived);
            } catch (MessageRemovedException e) {
                return null;
            }
        }
    }

    /** The header block of a raw message. */
    static byte[] headOnly(byte[] raw) {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("\\r?\\n\\r?\\n").matcher(text);
        String head = m.find() ? text.substring(0, m.start()) : text;
        return (head + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    // ---------------------------------------------------------------- Message-IDs of older emails

    private static final Duration RECOVER_WINDOW = Duration.ofMinutes(15);  // the Sent copy's Date vs. when the app recorded the send
    /** Emails / follow-ups already looked for in the Sent folder ("d:id" / "e:id"). */
    private final Set<String> recoverTried = java.util.Collections.synchronizedSet(new HashSet<>());

    /** The mailbox's Sent folder (the one flagged \Sent; Gmail: "[Gmail]/Sent Mail"). */
    static String sentFolder(Store store) throws MessagingException {
        List<String> names = new ArrayList<>();
        for (Folder f : store.getDefaultFolder().list("*")) {
            if (f instanceof IMAPFolder imap) {
                for (String attr : imap.getAttributes()) if (attr.equalsIgnoreCase("\\Sent")) return f.getFullName();
            }
            names.add(f.getFullName());
        }
        for (String n : new String[]{"[Gmail]/Sent Mail", "Sent", "Sent Items", "Sent Messages"}) if (names.contains(n)) return n;
        return null;
    }

    static String sameText(String value) {
        return Py.strip(SPACES.matcher(value).replaceAll(" ")).toLowerCase(java.util.Locale.ROOT);
    }

    private record Copy(String subject, Set<String> recipients, LocalDateTime sentAt, String messageId) {}

    private record Pending(String kind, int id, String subject, String to, LocalDateTime at) {}

    /**
     * Emails and follow-ups sent before their Message-ID was saved: find their copies in the Sent folder (same
     * subject, same recipient, sent within a few minutes) and save the Message-ID, so partners' replies to them
     * are matched by their reply headers. Returns how many were found.
     */
    int recoverMessageIds(Store store) throws MessagingException {
        List<Pending> records = new ArrayList<>();
        for (Object[] d : SqlRows.rows(jdbc, "SELECT t.id, t.subject, t.to_emails, t.sent_at FROM email_dispatches t "
                + "WHERE t.status = 'SENT' AND t.message_id IS NULL AND t.sent_at IS NOT NULL")) {
            records.add(new Pending("d", ((Number) d[0]).intValue(), (String) d[1], (String) d[2], (LocalDateTime) d[3]));
        }
        for (Object[] e : SqlRows.rows(jdbc, "SELECT t.id, t.subject, t.to_emails, t.created_at FROM email_events t "
                + "WHERE t.kind = 'followup' AND t.ok = ? AND t.message_id IS NULL", true)) {
            records.add(new Pending("e", ((Number) e[0]).intValue(), (String) e[1], (String) e[2], (LocalDateTime) e[3]));
        }
        records.removeIf(r -> recoverTried.contains(r.kind() + ":" + r.id()) || Py.isEmpty(r.subject()));
        if (records.isEmpty()) return 0;
        String folderName = sentFolder(store);
        if (folderName == null) return 0;
        IMAPFolder folder = openFolder(store, folderName);
        try {
            LocalDateTime since = records.stream().map(Pending::at).min(Comparator.naturalOrder()).get().minusDays(1);
            Message[] messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GE,
                    Date.from(since.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant())));
            Set<String> used = new HashSet<>();
            for (Object id : SqlRows.column(jdbc, "SELECT message_id FROM email_dispatches WHERE message_id IS NOT NULL")) used.add(normId((String) id));
            for (Object id : SqlRows.column(jdbc, "SELECT message_id FROM email_events WHERE message_id IS NOT NULL")) used.add(normId((String) id));
            List<Copy> copies = new ArrayList<>();
            String[] fields = {"Message-ID", "Subject", "To", "Cc", "Date"};
            for (int start = 0; start < messages.length; start += FETCH_BATCH) {
                Message[] batch = java.util.Arrays.copyOfRange(messages, start, Math.min(messages.length, start + FETCH_BATCH));
                FetchProfile fp = new FetchProfile();
                fp.add(UIDFolder.FetchProfileItem.UID);
                for (String f : fields) fp.add(f);
                folder.fetch(batch, fp);
                for (Message m : batch) {
                    Headers h = new Headers(headerBytes(m, fields));
                    String messageId = Py.strip(h.text("Message-ID"));
                    LocalDateTime sentAt = parseDate(h.raw("Date"));
                    if (sentAt == null) continue;
                    if (messageId.isEmpty() || used.contains(normId(messageId))) continue;
                    Set<String> recipients = new HashSet<>();
                    for (String a : Py.csv(addressList(h.raw("To"), h.raw("Cc")))) recipients.add(a.toLowerCase(java.util.Locale.ROOT));
                    copies.add(new Copy(sameText(h.text("Subject")), recipients, sentAt, messageId));
                }
            }
            // closest in time first, so two sends of the same email (minutes apart) each get their own copy
            List<Object[]> pairs = new ArrayList<>();
            for (Pending r : records) {
                Set<String> to = new HashSet<>();
                for (String a : Py.splitOn(r.to() == null ? "" : r.to(), ",")) if (!Py.strip(a).isEmpty()) to.add(Py.strip(a).toLowerCase(java.util.Locale.ROOT));
                for (int i = 0; i < copies.size(); i++) {
                    Copy c = copies.get(i);
                    Duration gap = Duration.between(c.sentAt(), r.at()).abs();
                    boolean sharesRecipient = to.stream().anyMatch(c.recipients()::contains);
                    if (c.subject().equals(sameText(r.subject())) && sharesRecipient && gap.compareTo(RECOVER_WINDOW) <= 0) {
                        pairs.add(new Object[]{gap, r, i});
                    }
                }
            }
            pairs.sort(Comparator.comparing(p -> (Duration) p[0]));
            Map<Pending, String> found = new LinkedHashMap<>();
            Set<String> done = new HashSet<>();
            Set<Integer> taken = new HashSet<>();
            for (Object[] p : pairs) {
                Pending r = (Pending) p[1];
                int i = (Integer) p[2];
                String key = r.kind() + ":" + r.id();
                if (done.contains(key) || taken.contains(i)) continue;
                found.put(r, Py.cut(copies.get(i).messageId(), 255));
                done.add(key);
                taken.add(i);
            }
            transactionTemplate.executeWithoutResult(s -> {
                for (Map.Entry<Pending, String> f : found.entrySet()) {
                    Pending r = f.getKey();
                    if (r.kind().equals("d")) dispatches.findById(r.id()).orElseThrow().setMessageId(f.getValue());
                    else events.findById(r.id()).orElseThrow().setMessageId(f.getValue());
                }
            });
            int foundCount = found.size();
            for (Pending r : records) recoverTried.add(r.kind() + ":" + r.id());
            if (foundCount > 0) LOG.info("Inbox: recovered the Message-ID of " + foundCount + " earlier emails from " + folderName);
            return foundCount;
        } finally {
            if (folder.isOpen()) folder.close(false);
        }
    }

    // ---------------------------------------------------------------- the check

    private static Map<String, Object> newResult() {
        return Py.map("found", 0, "replies", 0, "partner_mail", 0, "review", 0, "issues", 0, "checked", 0, "recovered", 0);
    }

    private static void bump(Map<String, Object> result, String key) {
        result.put(key, (Integer) result.get(key) + 1);
    }

    /**
     * The check works apart from the request that started it ("Check now"), like the background check (the old
     * code opened a Db session of its own): the request's persistence context is set aside meanwhile, so every
     * transaction below gets a new EntityManager and reads the rows as they are now. Returns what to put back.
     * Inside a transaction (don't do that) nothing is set aside.
     */
    private Object setRequestContextAside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return null;
        return TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory);
    }

    private void putRequestContextBack(Object requestContext) {
        if (requestContext != null) TransactionSynchronizationManager.bindResource(entityManagerFactory, requestContext);
    }

    /**
     * Look for new partner mail once. Throws when the mailbox can't be read.
     * Runs in transactions of its own: don't call it inside a transaction.
     */
    public Map<String, Object> scan() throws Exception {
        lock.lock();
        Object requestContext = setRequestContextAside();
        try {
            status.put("last_run_at", Py.isoSeconds(LocalDateTime.now()));
            Map<String, Object> result;
            try {
                result = scanWith();
            } catch (Exception e) {
                status.put("last_error", Py.cut(String.valueOf(e.getMessage()), 500));
                throw e;
            }
            status.put("last_ok_at", status.get("last_run_at"));
            status.put("last_error", null);
            status.put("last_found", result.get("found"));
            if ((Integer) result.get("found") > 0) {
                LOG.info("Inbox: " + result.get("found") + " new partner messages (" + result.get("replies") + " replies)");
            }
            return result;
        } finally {
            putRequestContextBack(requestContext);
            lock.unlock();
        }
    }

    private Map<String, Object> scanWith() throws Exception {
        Map<String, Object> result = newResult();
        if (!configured()) throw new RuntimeException("Reading partner mail is not set up: fill in the mailbox on the System Settings page.");
        if (!live.inboxEnabled()) throw new RuntimeException("Reading partner mail is switched off on the System Settings page.");
        boolean hasSent = SqlRows.scalar(jdbc, "SELECT id FROM email_dispatches WHERE status = 'SENT' LIMIT 1") != null;
        if (!hasSent && SqlRows.scalar(jdbc, "SELECT id FROM partner_emails LIMIT 1") == null) return result;  // nothing to match yet

        Store store = signIn();
        try {
            if (hasSent) {
                int recovered = recoverMessageIds(store);
                result.put("recovered", recovered);
                if (recovered > 0) {  // read the inbox again from the start: earlier replies can now be matched
                    String mailbox = mailboxName();
                    transactionTemplate.executeWithoutResult(s ->
                            jdbc.sql("DELETE FROM inbox_state WHERE mailbox = ?").param(mailbox).update());
                }
            }
            Matcher_ matcher = new Matcher_(jdbc, live);
            IMAPFolder folder = openFolder(store, live.imapFolder());
            try {
                long uidvalidity = folder.getUIDValidity();
                // {last UID read, 1 when this is the first check of the folder (or it was rebuilt)}
                long[] start = transactionTemplate.execute(s -> {
                    InboxState state = states.findById(mailboxName()).orElse(null);
                    if (state == null || !Objects.equals(state.getUidvalidity(), uidvalidity)) {  // first check, or the folder was rebuilt
                        if (state == null) {
                            state = new InboxState();
                            state.setMailbox(mailboxName());
                        }
                        state.setUidvalidity(uidvalidity);
                        state.setLastUid(0L);
                        states.save(state);
                        return new long[]{0L, 1L};
                    }
                    return new long[]{state.getLastUid() == null ? 0L : state.getLastUid(), 0L};
                });
                long last = start[0];
                Message[] found;
                if (start[1] == 1L) {
                    LocalDateTime since = LocalDateTime.now().minusDays(live.inboxFirstScanDays());
                    if (matcher.oldestSent != null && matcher.oldestSent.minusDays(1).isAfter(since)) since = matcher.oldestSent.minusDays(1);
                    found = folder.search(new ReceivedDateTerm(ComparisonTerm.GE,
                            Date.from(since.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant())));
                } else {
                    found = folder.getMessagesByUID(last + 1, UIDFolder.LASTUID);
                }
                FetchProfile uidOnly = new FetchProfile();
                uidOnly.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(found, uidOnly);
                // "UID n:*" also returns the newest message when there is nothing newer than n
                Map<Long, Message> byUid = new java.util.TreeMap<>();
                for (Message m : found) {
                    if (m == null) continue;
                    long uid = folder.getUID(m);
                    if (uid > last) byUid.put(uid, m);
                }
                List<Long> uids = new ArrayList<>(byUid.keySet());
                result.put("checked", uids.size());
                ImapSource src = new ImapSource(folder, uidvalidity, live.imapHost());
                for (int from = 0; from < uids.size(); from += FETCH_BATCH) {
                    List<Long> batch = uids.subList(from, Math.min(uids.size(), from + FETCH_BATCH));
                    Message[] messages = new Message[batch.size()];
                    for (int i = 0; i < batch.size(); i++) messages[i] = byUid.get(batch.get(i));
                    FetchProfile fp = new FetchProfile();
                    fp.add(UIDFolder.FetchProfileItem.UID);
                    fp.add(FetchProfile.Item.SIZE);
                    for (String f : HEADER_FIELDS) fp.add(f);
                    folder.fetch(messages, fp);
                    for (int i = 0; i < messages.length; i++) {
                        long uid = batch.get(i);
                        byte[] header = null;
                        String outcome;
                        try {
                            header = headerBytes(messages[i], HEADER_FIELDS);
                            outcome = processInTransaction(src, matcher, uid, header, Math.max(0, messages[i].getSize()));
                        } catch (Exception e) {  // one bad message must not stop the others (its changes were rolled back)
                            recordIssue(src, uid, header, e);
                            bump(result, "issues");
                            continue;
                        }
                        if (outcome != null) {
                            bump(result, "found");
                            bump(result, outcome);
                        }
                    }
                    long batchLast = batch.get(batch.size() - 1);
                    transactionTemplate.executeWithoutResult(s -> states.findById(mailboxName()).ifPresent(state ->
                            state.setLastUid(state.getLastUid() == null ? batchLast : Math.max(state.getLastUid(), batchLast))));
                }
                return result;
            } finally {
                if (folder.isOpen()) folder.close(false);
            }
        } finally {
            try {
                store.close();
            } catch (Exception ignored) {
                // signed out anyway
            }
        }
    }

    /** The message was stored by another check at the same moment (unique Message-ID): nothing more to do. */
    private static final class AlreadyStored extends RuntimeException {
        AlreadyStored() {
            super(null, null, false, false);
        }
    }

    /** A checked exception thrown inside a transaction callback (unwrapped again outside it). */
    private static final class Checked extends RuntimeException {
        Checked(Exception cause) {
            super(cause);
        }
    }

    /**
     * processMessage in a transaction of its own: committed when the message was stored, rolled back (and the
     * exception passed on) when it fails.
     */
    private String processInTransaction(Source src, Matcher_ matcher, Object key, byte[] headerBytes, int size) throws Exception {
        try {
            return transactionTemplate.execute(s -> {
                try {
                    return processMessage(src, matcher, key, headerBytes, size);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new Checked(e);
                }
            });
        } catch (AlreadyStored e) {
            return null;  // rolled back
        } catch (Checked e) {
            throw (Exception) e.getCause();
        }
    }

    /**
     * Store one message if it is partner mail. Returns "replies", "partner_mail", "review", or null.
     * Runs inside the caller's transaction (processInTransaction).
     */
    String processMessage(Source src, Matcher_ matcher, Object key, byte[] headerBytes, int size) throws Exception {
        Headers headers = new Headers(headerBytes);
        String sender = firstAddress(headers.raw("From"))[1];
        String kind = classify(headers, sender);
        Hit hit = matcher.match(sender, headers.text("Subject"), headers.text("In-Reply-To"), headers.text("References"));
        Object[] review = null;  // (suggested email id, reason)
        Integer dispatchId, partnerId;
        String matchedBy;
        if (hit != null) {
            dispatchId = hit.sent().id();
            matchedBy = hit.matchedBy();
            partnerId = hit.sent().partnerId();
            if (hit.doubt() != null && kind.equals(REPLY)) {
                review = new Object[]{dispatchId, hit.doubt()};
                dispatchId = null;
                kind = REVIEW;
            }
        } else {
            Set<Integer> partnerIds = matcher.partnersFor(sender);
            if (partnerIds.isEmpty() || kind.equals(BOUNCE)) return null;  // not partner mail: never downloaded
            dispatchId = null;
            matchedBy = "sender";
            if (partnerIds.size() > 1) {
                List<String> names = new ArrayList<>();
                for (Integer i : partnerIds) names.add(matcher.partnerNames.getOrDefault(i, "?"));
                java.util.Collections.sort(names);
                partnerId = null;
                kind = REVIEW;
                review = new Object[]{null, "This address belongs to several partners (" + String.join(", ", names) + ")."};
            } else {
                partnerId = partnerIds.iterator().next();
                if (kind.equals(REPLY)) kind = PARTNER_MAIL;
            }
        }
        String messageId = normId(headers.text("Message-ID"));
        if (messageId.isEmpty()) messageId = src.localId(key);
        messageId = Py.cut(messageId, 255);
        if (SqlRows.scalar(jdbc, "SELECT id FROM email_replies WHERE message_id = ? LIMIT 1", messageId) != null) return null;
        EmailDispatch d = dispatchId == null ? null : dispatches.findById(dispatchId).orElse(null);
        if (!saveMessage(src, key, size, messageId, d, matchedBy, kind, partnerId, review)) return null;
        return kind.equals(REVIEW) ? "review" : d != null ? "replies" : "partner_mail";
    }

    /** A message that could not be read: listed for an admin (once per message). Committed at once. */
    void recordIssue(Source src, Object key, byte[] headerBytes, Exception exc) {
        String sender = "", subject = "";
        try {
            Headers headers = new Headers(headerBytes == null ? new byte[0] : headerBytes);
            sender = firstAddress(headers.raw("From"))[1];
            subject = headers.text("Subject");
        } catch (Exception ignored) {
            // unreadable headers: listed without them
        }
        String error = Py.cut(exc.getClass().getSimpleName() + ": " + exc.getMessage(), 2000);
        LOG.warn("Inbox: message " + key + " could not be read: " + error);
        Map<String, Object> ident = src.ident(key);
        String mailbox = mailboxName(), from = sender, about = subject;
        transactionTemplate.executeWithoutResult(s -> {
            InboxIssue issue = openIssue(mailbox, ident);
            if (issue != null) {
                issue.setAttempts(issue.getAttempts() + 1);
                issue.setError(error);
            } else {
                issue = new InboxIssue();
                issue.setMailbox(mailbox);
                issue.setSender(Py.cut(from, 254));
                issue.setSubject(Py.cut(about, 500));
                issue.setError(error);
                issue.setUidvalidity((Long) ident.get("uidvalidity"));
                issue.setUid((Long) ident.get("uid"));
                issue.setRemoteId((String) ident.get("remote_id"));
                issues.save(issue);
            }
        });
    }

    /** The unresolved issue of this message, or null. */
    private InboxIssue openIssue(String mailbox, Map<String, Object> ident) {
        boolean noRemoteId = ident.get("remote_id") == null;
        var query = entityManager.createQuery("SELECT i FROM InboxIssue i WHERE i.mailbox = :mailbox AND i.resolvedAt IS NULL "
                        + "AND i.uidvalidity = :uidvalidity AND i.uid = :uid "
                        + (noRemoteId ? "AND i.remoteId IS NULL" : "AND i.remoteId = :remoteId"), InboxIssue.class)
                .setParameter("mailbox", mailbox)
                .setParameter("uidvalidity", ident.get("uidvalidity"))
                .setParameter("uid", ident.get("uid"))
                .setMaxResults(1);
        if (!noRemoteId) query.setParameter("remoteId", ident.get("remote_id"));
        List<InboxIssue> found = query.getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Read a message that failed before, again. Returns what it was (see processMessage); throws if it still
     * can't be read (the issue stays open with the new error). Runs in transactions of its own: don't call it
     * inside a transaction.
     */
    public String retryIssue(int issueId, String username) throws Exception {
        lock.lock();
        Object requestContext = setRequestContextAside();
        try {
            InboxIssue issue = issues.findById(issueId).orElse(null);
            if (issue == null || issue.getResolvedAt() != null) throw new RuntimeException("This issue is already resolved.");
            if (!Py.isEmpty(issue.getRemoteId())) {  // read through Microsoft 365 before the app moved to Gmail
                throw new RuntimeException("This message was in the old Microsoft 365 mailbox and can't be read from Gmail. Dismiss it.");
            }
            Store store = signIn();
            try {
                IMAPFolder folder = openFolder(store, live.imapFolder());
                try {
                    long uidvalidity = folder.getUIDValidity();
                    if (!Objects.equals(issue.getUidvalidity(), uidvalidity) || !issue.getMailbox().equals(mailboxName())) {
                        throw new RuntimeException("The mailbox has changed since; this message can't be found again. Dismiss it.");
                    }
                    long uid = issue.getUid();
                    Message m = folder.getMessageByUID(uid);
                    if (m == null) throw new RuntimeException("The message is no longer in the mailbox. Dismiss it.");
                    FetchProfile fp = new FetchProfile();
                    fp.add(UIDFolder.FetchProfileItem.UID);
                    fp.add(FetchProfile.Item.SIZE);
                    for (String f : HEADER_FIELDS) fp.add(f);
                    folder.fetch(new Message[]{m}, fp);
                    String outcome;
                    try {
                        outcome = processInTransaction(new ImapSource(folder, uidvalidity, live.imapHost()), new Matcher_(jdbc, live), uid,
                                headerBytes(m, HEADER_FIELDS), Math.max(0, m.getSize()));
                    } catch (Exception e) {  // its changes were rolled back
                        String error = Py.cut(e.getClass().getSimpleName() + ": " + e.getMessage(), 2000);
                        transactionTemplate.executeWithoutResult(s -> {
                            InboxIssue again = issues.findById(issueId).orElseThrow();
                            again.setAttempts(again.getAttempts() + 1);
                            again.setError(error);
                        });
                        throw new RuntimeException("Still can't be read: " + e.getMessage());
                    }
                    transactionTemplate.executeWithoutResult(s -> {
                        InboxIssue done = issues.findById(issueId).orElseThrow();
                        done.setResolvedAt(Py.now());
                        done.setResolvedBy(username);
                    });
                    return outcome;
                } finally {
                    if (folder.isOpen()) folder.close(false);
                }
            } finally {
                try {
                    store.close();
                } catch (Exception ignored) {
                    // signed out anyway
                }
            }
        } finally {
            putRequestContextBack(requestContext);
            lock.unlock();
        }
    }

    public static final Map<String, String> EVENT_NOTES = Map.of(
            REPLY, "Reply from %s",
            AUTO_REPLY, "Automatic reply from %s",
            BOUNCE, "Delivery failure notice from %s");

    /**
     * Download one partner message and store it. A reply (not an automatic one) sets its email to "Reply
     * received"; a bounce flags the address it names as refused. Runs inside the caller's transaction
     * (committed by processInTransaction); a message stored by another check at the same moment throws
     * AlreadyStored, which rolls it back.
     */
    boolean saveMessage(Source src, Object key, int size, String messageId, EmailDispatch d, String matchedBy,
                        String kind, Integer partnerId, Object[] review) throws Exception {
        Fetched fetched = src.fetch(key, size);
        if (fetched == null) return false;
        Headers msg = new Headers(headOnly(fetched.raw()));
        Content content = fetched.whole() ? readMessage(fetched.raw())
                : new Content("<p><i>This message is too large to show here. Open it in the mailbox.</i></p>", "(too large to show)", List.of());

        LocalDateTime received = parseDate(msg.raw("Date"));
        if (received == null) received = fetched.arrived() != null ? fetched.arrived() : Py.now();

        String[] from = firstAddress(msg.raw("From"));
        String name = from[0], sender = from[1];
        // not linked by its headers: a reply that quotes our email carries its invisible case ID (CaseIds)
        if (d == null && !kind.equals(BOUNCE)) {
            String caseId = CaseIds.find(content.bodyHtml());
            EmailDispatch byCase = caseId == null ? null : dispatches.findFirstByCaseIdAndStatus(caseId, "SENT").orElse(null);
            if (byCase != null && (partnerId == null || partnerId.equals(byCase.getPartnerId()))) {
                d = byCase;
                partnerId = byCase.getPartnerId();
                matchedBy = "case_id";
                review = null;
                if (kind.equals(PARTNER_MAIL) || kind.equals(REVIEW)) kind = REPLY;
            }
        }
        EmailReply reply = new EmailReply();
        reply.setDispatchId(d != null ? d.getId() : null);
        reply.setPartnerId(partnerId);
        reply.setKind(kind);
        reply.setMessageId(messageId);
        reply.setFromEmail(Py.cut(sender.toLowerCase(java.util.Locale.ROOT), 254));
        reply.setFromName(Py.cut(name, 200));
        reply.setToEmails(addressList(msg.raw("To")));
        reply.setCcEmails(addressList(msg.raw("Cc")));
        reply.setSubject(Py.cut(Py.or(msg.text("Subject"), "(no subject)"), 500));
        reply.setBodyHtml(content.bodyHtml());
        reply.setSnippet(content.snippet());
        reply.setMatchedBy(matchedBy);
        reply.setReceivedAt(received);
        List<String> parents = referencedIds(msg.text("In-Reply-To"), "");
        String parent = parents.isEmpty() ? "" : Py.cut(parents.get(0), 255);
        reply.setInReplyTo(parent.isEmpty() ? null : parent);
        reply.setSuggestedDispatchId(review != null ? (Integer) review[0] : null);
        reply.setReviewReason(review != null ? Py.cut((String) review[1], 255) : null);
        try {
            replies.save(reply);  // inserted now (its id is needed below)
        } catch (DataIntegrityViolationException e) {
            throw new AlreadyStored();  // stored by another check at the same moment
        }
        for (Object[] a : content.attachments()) {
            EmailReplyAttachment att = new EmailReplyAttachment();
            att.setReplyId(reply.getId());
            att.setFilename((String) a[0]);
            att.setContentType((String) a[1]);
            att.setContent((byte[]) a[2]);
            att.setSize(att.getContent().length);
            attachments.save(att);
        }
        if (d != null) {
            String who = !name.isEmpty() ? name + " <" + sender + ">" : sender;
            // in the history after the email it answers, even if the sender's clock is wrong
            LocalDateTime at = d.getSentAt() != null && d.getSentAt().isAfter(received) ? d.getSentAt() : received;
            EmailEvent ev = new EmailEvent();
            ev.setDispatchId(d.getId());
            ev.setKind(kind);
            ev.setReplyId(reply.getId());
            ev.setSubject(Py.cut(reply.getSubject(), 300));
            ev.setNote(String.format(EVENT_NOTES.get(kind), who));
            ev.setCreatedBy(INBOX_USER);
            ev.setCreatedAt(at);
            events.save(ev);
            if (kind.equals(REPLY) && "not_received".equals(d.getReplyStatus())) {  // never overrides Reconciled
                d.setReplyStatus("received");
                d.setReplyStatusBy(INBOX_USER);
                d.setReplyStatusAt(Py.now());
                EmailEvent statusEvent = new EmailEvent();
                statusEvent.setDispatchId(d.getId());
                statusEvent.setKind("status");
                statusEvent.setReplyStatus("received");
                statusEvent.setNote("Set automatically: the partner's reply was found in the mailbox.");
                statusEvent.setCreatedBy(INBOX_USER);
                events.save(statusEvent);
            }
            if (kind.equals(BOUNCE)) {
                for (String address : Py.csv(addressList(msg.raw("X-Failed-Recipients")))) {
                    delivery.recordRefused(d, address, "Bounced: the mail server could not deliver it");
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- background check

    /** At startup: check the mailbox now, then every INBOX_CHECK_SECONDS (at once after a settings change). */
    public void start() {
        synchronized (schedule) {
            if (started || stopped) return;
            started = true;
            next = taskScheduler.schedule(this::tick, Instant.now());
        }
    }

    /** One background check, then the next one is scheduled (the interval is read again each time). */
    private void tick() {
        synchronized (schedule) {
            if (stopped || running) return;
            running = true;
            woken = false;
        }
        try {
            if (enabled()) {
                try {
                    scan();
                } catch (Exception e) {
                    LOG.warn("Inbox check failed: " + e.getMessage());
                }
                status.put("next_run_at", Py.isoSeconds(LocalDateTime.now().plusSeconds(live.inboxCheckSeconds())));
            } else {
                status.put("next_run_at", null);
            }
        } catch (RuntimeException e) {
            LOG.warn("Inbox check failed: " + e.getMessage());
        } finally {
            synchronized (schedule) {
                running = false;
                if (!stopped) {
                    // woken while checking: check again at once, with the new settings
                    Instant at = woken ? Instant.now() : Instant.now().plusSeconds(live.inboxCheckSeconds());
                    woken = false;
                    next = taskScheduler.schedule(this::tick, at);
                }
            }
        }
    }

    /** The mailbox settings changed: check again now, with the new settings. */
    public void wake() {
        synchronized (schedule) {
            if (!started || stopped) return;
            if (running) {  // the check that is running now is followed by another one at once
                woken = true;
                return;
            }
            if (next != null) next.cancel(false);
            next = taskScheduler.schedule(this::tick, Instant.now());
        }
    }

    /** At shutdown: no more checks (one that is running finishes). */
    @PreDestroy
    public void stop() {
        synchronized (schedule) {
            stopped = true;
            if (next != null) next.cancel(false);
            next = null;
        }
    }

    /** users.created_at is stored in UTC; replies use local time. */
    public static LocalDateTime utcToLocal(LocalDateTime value) {
        return value.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    static Address[] none() {
        return new Address[0];
    }
}
