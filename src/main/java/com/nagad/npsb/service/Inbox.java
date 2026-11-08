package com.nagad.npsb.service;

import com.nagad.npsb.Config;
import com.nagad.npsb.Py;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.EmailDispatch;
import com.nagad.npsb.models.EmailEvent;
import com.nagad.npsb.models.EmailReply;
import com.nagad.npsb.models.EmailReplyAttachment;
import com.nagad.npsb.models.InboxIssue;
import com.nagad.npsb.models.InboxState;
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
import com.sun.mail.imap.IMAPFolder;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
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
 */
public final class Inbox {
    private Inbox() {}

    private static final Logger LOG = Logger.getLogger("npsb");

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

    private static final ReentrantLock LOCK = new ReentrantLock();  // one check at a time ("Check now" while the timer runs)
    private static volatile boolean stopped = false;
    private static final Object WAKE = new Object();
    private static boolean woken = false;
    public static final Map<String, Object> STATUS = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    static {
        STATUS.put("last_run_at", null);
        STATUS.put("last_ok_at", null);
        STATUS.put("last_error", null);
        STATUS.put("last_found", 0);
        STATUS.put("next_run_at", null);
    }

    public static boolean configured() {
        return !Config.imapHost().isEmpty() && !Config.imapUser().isEmpty() && !Config.imapPassword().isEmpty();
    }

    public static boolean enabled() {
        return Config.INBOX_ENABLED() && configured();
    }

    public static String mailboxName() {
        return Config.imapUser() + "@" + Config.imapHost() + "/" + Config.IMAP_FOLDER();
    }

    public static Map<String, Object> statusDict() {
        Map<String, Object> out;
        synchronized (STATUS) {
            out = new LinkedHashMap<>(STATUS);
        }
        out.put("enabled", enabled());
        out.put("switched_on", Config.INBOX_ENABLED());
        out.put("configured", configured());
        out.put("provider", Config.MAIL_PROVIDER());
        out.put("mailbox", configured() ? Config.mailboxAddress() : null);
        out.put("host", Config.imapHost());
        out.put("folder", Config.IMAP_FOLDER());
        out.put("check_every_seconds", Config.INBOX_CHECK_SECONDS());
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

        Matcher_(Db db) {
            Map<Integer, Set<String>> partnerAddresses = new HashMap<>();
            for (Object[] row : db.rows("SELECT partner_id, email FROM partner_emails")) {
                partnerAddresses.computeIfAbsent(((Number) row[0]).intValue(), k -> new HashSet<>())
                        .add(((String) row[1]).toLowerCase(java.util.Locale.ROOT));
            }
            for (Object[] row : db.rows("SELECT id, name FROM partners")) partnerNames.put(((Number) row[0]).intValue(), (String) row[1]);
            LocalDateTime oldest = null;
            for (EmailDispatch d : db.all(EmailDispatch.class, "WHERE t.status = 'SENT'")) {
                Sent s = new Sent(d.id, d.partnerId, partnerNames.getOrDefault(d.partnerId, ""), d.subject, d.toEmails, d.ccEmails,
                        d.sentAt, partnerAddresses.getOrDefault(d.partnerId, Set.of()));
                if (!Py.isEmpty(d.messageId)) byId.put(normId(d.messageId), d.id);
                dispatches.put(d.id, s);
                bySubject.computeIfAbsent(normSubject(d.subject), k -> new ArrayList<>()).add(s);
                if (d.sentAt != null && (oldest == null || d.sentAt.isBefore(oldest))) oldest = d.sentAt;
            }
            for (Object[] row : db.rows("SELECT dispatch_id, message_id FROM email_events WHERE kind IN ('followup', 'answer') "
                    + "AND message_id IS NOT NULL ORDER BY id")) {
                byId.put(normId((String) row[1]), ((Number) row[0]).intValue());
            }
            for (String a : new String[]{Config.imapUser(), Config.MAIL_FROM(), Config.SMTP_USER(), Config.mailFrom(), Config.mailboxAddress()}) {
                if (a != null && !Py.strip(a).isEmpty()) ours.add(Py.strip(a).toLowerCase(java.util.Locale.ROOT));
            }
            oldestSent = oldest;
            Set<String> ownDomains = new HashSet<>();
            for (String a : ours) ownDomains.add(domain(a));
            for (Object[] row : db.rows("SELECT partner_id, email FROM partner_emails ORDER BY id")) {
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

    /** Sign in to the mailbox: the settings in effect, or the given ones (System Settings test). */
    static Store signIn(String host, Integer port, String user, String password) {
        host = host != null ? host : Config.imapHost();
        port = port != null ? port : Config.IMAP_PORT();
        user = user != null ? user : Config.imapUser();
        password = password != null ? password : Config.imapPassword();
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

        ImapSource(IMAPFolder folder, long uidvalidity) {
            this.folder = folder;
            this.uidvalidity = uidvalidity;
        }

        /** Message-ID for a message without one (made-up ids start with "imap-"). */
        public String localId(Object uid) {
            return "imap-" + uidvalidity + "-" + uid + "@" + Config.imapHost();
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
    private static final Set<String> RECOVER_TRIED = java.util.Collections.synchronizedSet(new HashSet<>());

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
    static int recoverMessageIds(Db db, Store store) throws MessagingException {
        List<Pending> records = new ArrayList<>();
        for (EmailDispatch d : db.all(EmailDispatch.class, "WHERE t.status = 'SENT' AND t.message_id IS NULL AND t.sent_at IS NOT NULL")) {
            records.add(new Pending("d", d.id, d.subject, d.toEmails, d.sentAt));
        }
        for (EmailEvent e : db.all(EmailEvent.class, "WHERE t.kind = 'followup' AND t.ok = ? AND t.message_id IS NULL", true)) {
            records.add(new Pending("e", e.id, e.subject, e.toEmails, e.createdAt));
        }
        records.removeIf(r -> RECOVER_TRIED.contains(r.kind() + ":" + r.id()) || Py.isEmpty(r.subject()));
        if (records.isEmpty()) return 0;
        String folderName = sentFolder(store);
        if (folderName == null) return 0;
        IMAPFolder folder = openFolder(store, folderName);
        try {
            LocalDateTime since = records.stream().map(Pending::at).min(Comparator.naturalOrder()).get().minusDays(1);
            Message[] messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GE,
                    Date.from(since.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant())));
            Set<String> used = new HashSet<>();
            for (Object id : db.column("SELECT message_id FROM email_dispatches WHERE message_id IS NOT NULL")) used.add(normId((String) id));
            for (Object id : db.column("SELECT message_id FROM email_events WHERE message_id IS NOT NULL")) used.add(normId((String) id));
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
            int foundCount = 0;
            Set<String> done = new HashSet<>();
            Set<Integer> taken = new HashSet<>();
            for (Object[] p : pairs) {
                Pending r = (Pending) p[1];
                int i = (Integer) p[2];
                String key = r.kind() + ":" + r.id();
                if (done.contains(key) || taken.contains(i)) continue;
                String messageId = Py.cut(copies.get(i).messageId(), 255);
                if (r.kind().equals("d")) db.get(EmailDispatch.class, r.id()).messageId = messageId;
                else db.get(EmailEvent.class, r.id()).messageId = messageId;
                done.add(key);
                taken.add(i);
                foundCount++;
            }
            db.commit();
            for (Pending r : records) RECOVER_TRIED.add(r.kind() + ":" + r.id());
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

    /** Look for new partner mail once. Throws when the mailbox can't be read. */
    public static Map<String, Object> scan() throws Exception {
        LOCK.lock();
        try (Db db = Db.open()) {
            STATUS.put("last_run_at", Py.isoSeconds(LocalDateTime.now()));
            Map<String, Object> result;
            try {
                result = scanWith(db);
            } catch (Exception e) {
                STATUS.put("last_error", Py.cut(String.valueOf(e.getMessage()), 500));
                throw e;
            }
            STATUS.put("last_ok_at", STATUS.get("last_run_at"));
            STATUS.put("last_error", null);
            STATUS.put("last_found", result.get("found"));
            if ((Integer) result.get("found") > 0) {
                LOG.info("Inbox: " + result.get("found") + " new partner messages (" + result.get("replies") + " replies)");
            }
            return result;
        } finally {
            LOCK.unlock();
        }
    }

    private static Map<String, Object> scanWith(Db db) throws Exception {
        Map<String, Object> result = newResult();
        if (!configured()) throw new RuntimeException("Reading partner mail is not set up: fill in the mailbox on the System Settings page.");
        if (!Config.INBOX_ENABLED()) throw new RuntimeException("Reading partner mail is switched off on the System Settings page.");
        boolean hasSent = db.scalar("SELECT id FROM email_dispatches WHERE status = 'SENT' LIMIT 1") != null;
        if (!hasSent && db.scalar("SELECT id FROM partner_emails LIMIT 1") == null) return result;  // nothing to match yet

        Store store = signIn(null, null, null, null);
        try {
            if (hasSent) {
                int recovered = recoverMessageIds(db, store);
                result.put("recovered", recovered);
                if (recovered > 0) {  // read the inbox again from the start: earlier replies can now be matched
                    db.execute("DELETE FROM inbox_state WHERE mailbox = ?", mailboxName());
                    db.commit();
                }
            }
            Matcher_ matcher = new Matcher_(db);
            IMAPFolder folder = openFolder(store, Config.IMAP_FOLDER());
            try {
                long uidvalidity = folder.getUIDValidity();
                InboxState state = db.get(InboxState.class, mailboxName());
                Message[] found;
                if (state == null || state.uidvalidity != uidvalidity) {  // first check, or the folder was rebuilt
                    if (state == null) {
                        state = new InboxState();
                        state.mailbox = mailboxName();
                    }
                    state.uidvalidity = uidvalidity;
                    state.lastUid = 0L;
                    db.add(state);
                    db.commit();
                    LocalDateTime since = LocalDateTime.now().minusDays(Config.INBOX_FIRST_SCAN_DAYS());
                    if (matcher.oldestSent != null && matcher.oldestSent.minusDays(1).isAfter(since)) since = matcher.oldestSent.minusDays(1);
                    found = folder.search(new ReceivedDateTerm(ComparisonTerm.GE,
                            Date.from(since.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant())));
                } else {
                    found = folder.getMessagesByUID(state.lastUid + 1, UIDFolder.LASTUID);
                }
                FetchProfile uidOnly = new FetchProfile();
                uidOnly.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(found, uidOnly);
                // "UID n:*" also returns the newest message when there is nothing newer than n
                Map<Long, Message> byUid = new java.util.TreeMap<>();
                long last = state.lastUid;
                for (Message m : found) {
                    if (m == null) continue;
                    long uid = folder.getUID(m);
                    if (uid > last) byUid.put(uid, m);
                }
                List<Long> uids = new ArrayList<>(byUid.keySet());
                result.put("checked", uids.size());
                ImapSource src = new ImapSource(folder, uidvalidity);
                for (int start = 0; start < uids.size(); start += FETCH_BATCH) {
                    List<Long> batch = uids.subList(start, Math.min(uids.size(), start + FETCH_BATCH));
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
                            outcome = processMessage(db, src, matcher, uid, header, Math.max(0, messages[i].getSize()));
                        } catch (Exception e) {  // one bad message must not stop the others
                            db.rollback();
                            recordIssue(db, src, uid, header, e);
                            bump(result, "issues");
                            state = reattach(db, state);
                            continue;
                        }
                        state = reattach(db, state);
                        if (outcome != null) {
                            bump(result, "found");
                            bump(result, outcome);
                        }
                    }
                    state.lastUid = Math.max(state.lastUid, batch.get(batch.size() - 1));
                    db.commit();
                }
                db.commit();
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

    /** The session's copy of the state row (a rollback may have reloaded it). */
    private static InboxState reattach(Db db, InboxState state) {
        if (db.contains(state)) return state;
        InboxState fresh = db.get(InboxState.class, state.mailbox);
        return fresh != null ? fresh : state;
    }

    /** Store one message if it is partner mail. Returns "replies", "partner_mail", "review", or null. */
    static String processMessage(Db db, Source src, Matcher_ matcher, Object key, byte[] headerBytes, int size) throws Exception {
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
        if (db.scalar("SELECT id FROM email_replies WHERE message_id = ? LIMIT 1", messageId) != null) return null;
        EmailDispatch d = dispatchId == null ? null : db.get(EmailDispatch.class, dispatchId);
        if (!saveMessage(db, src, key, size, messageId, d, matchedBy, kind, partnerId, review)) return null;
        return kind.equals(REVIEW) ? "review" : d != null ? "replies" : "partner_mail";
    }

    /** A message that could not be read: listed for an admin (once per message). */
    static void recordIssue(Db db, Source src, Object key, byte[] headerBytes, Exception exc) {
        String sender = "", subject = "";
        try {
            Headers headers = new Headers(headerBytes == null ? new byte[0] : headerBytes);
            sender = firstAddress(headers.raw("From"))[1];
            subject = headers.text("Subject");
        } catch (Exception ignored) {
            // unreadable headers: listed without them
        }
        String error = Py.cut(exc.getClass().getSimpleName() + ": " + exc.getMessage(), 2000);
        LOG.warning("Inbox: message " + key + " could not be read: " + error);
        Map<String, Object> ident = src.ident(key);
        InboxIssue issue = db.first(InboxIssue.class, "WHERE t.mailbox = ? AND t.resolved_at IS NULL AND t.uidvalidity = ? AND t.uid = ? "
                        + (ident.get("remote_id") == null ? "AND t.remote_id IS NULL" : "AND t.remote_id = ?"),
                ident.get("remote_id") == null ? new Object[]{mailboxName(), ident.get("uidvalidity"), ident.get("uid")}
                        : new Object[]{mailboxName(), ident.get("uidvalidity"), ident.get("uid"), ident.get("remote_id")});
        if (issue != null) {
            issue.attempts += 1;
            issue.error = error;
        } else {
            issue = new InboxIssue();
            issue.mailbox = mailboxName();
            issue.sender = Py.cut(sender, 254);
            issue.subject = Py.cut(subject, 500);
            issue.error = error;
            issue.uidvalidity = (Long) ident.get("uidvalidity");
            issue.uid = (Long) ident.get("uid");
            issue.remoteId = (String) ident.get("remote_id");
            db.add(issue);
        }
        db.commit();
    }

    /**
     * Read a message that failed before, again. Returns what it was (see processMessage); throws if it still
     * can't be read (the issue stays open with the new error).
     */
    public static String retryIssue(int issueId, String username) throws Exception {
        LOCK.lock();
        try (Db db = Db.open()) {
            InboxIssue issue = db.get(InboxIssue.class, issueId);
            if (issue == null || issue.resolvedAt != null) throw new RuntimeException("This issue is already resolved.");
            if (!Py.isEmpty(issue.remoteId)) {  // read through Microsoft 365 before the app moved to Gmail
                throw new RuntimeException("This message was in the old Microsoft 365 mailbox and can't be read from Gmail. Dismiss it.");
            }
            Store store = signIn(null, null, null, null);
            try {
                IMAPFolder folder = openFolder(store, Config.IMAP_FOLDER());
                try {
                    long uidvalidity = folder.getUIDValidity();
                    if (uidvalidity != issue.uidvalidity || !issue.mailbox.equals(mailboxName())) {
                        throw new RuntimeException("The mailbox has changed since; this message can't be found again. Dismiss it.");
                    }
                    Message m = folder.getMessageByUID(issue.uid);
                    if (m == null) throw new RuntimeException("The message is no longer in the mailbox. Dismiss it.");
                    FetchProfile fp = new FetchProfile();
                    fp.add(UIDFolder.FetchProfileItem.UID);
                    fp.add(FetchProfile.Item.SIZE);
                    for (String f : HEADER_FIELDS) fp.add(f);
                    folder.fetch(new Message[]{m}, fp);
                    String outcome;
                    try {
                        outcome = processMessage(db, new ImapSource(folder, uidvalidity), new Matcher_(db), issue.uid,
                                headerBytes(m, HEADER_FIELDS), Math.max(0, m.getSize()));
                    } catch (Exception e) {
                        db.rollback();
                        InboxIssue again = db.get(InboxIssue.class, issueId);
                        again.attempts += 1;
                        again.error = Py.cut(e.getClass().getSimpleName() + ": " + e.getMessage(), 2000);
                        db.commit();
                        throw new RuntimeException("Still can't be read: " + e.getMessage());
                    }
                    InboxIssue done = db.get(InboxIssue.class, issueId);
                    done.resolvedAt = Py.now();
                    done.resolvedBy = username;
                    db.commit();
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
            LOCK.unlock();
        }
    }

    public static final Map<String, String> EVENT_NOTES = Map.of(
            REPLY, "Reply from %s",
            AUTO_REPLY, "Automatic reply from %s",
            BOUNCE, "Delivery failure notice from %s");

    /**
     * Download one partner message and store it. A reply (not an automatic one) sets its email to "Reply
     * received"; a bounce flags the address it names as refused.
     */
    static boolean saveMessage(Db db, Source src, Object key, int size, String messageId, EmailDispatch d, String matchedBy,
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
        EmailReply reply = new EmailReply();
        reply.dispatchId = d != null ? d.id : null;
        reply.partnerId = partnerId;
        reply.kind = kind;
        reply.messageId = messageId;
        reply.fromEmail = Py.cut(sender.toLowerCase(java.util.Locale.ROOT), 254);
        reply.fromName = Py.cut(name, 200);
        reply.toEmails = addressList(msg.raw("To"));
        reply.ccEmails = addressList(msg.raw("Cc"));
        reply.subject = Py.cut(Py.or(msg.text("Subject"), "(no subject)"), 500);
        reply.bodyHtml = content.bodyHtml();
        reply.snippet = content.snippet();
        reply.matchedBy = matchedBy;
        reply.receivedAt = received;
        List<String> parents = referencedIds(msg.text("In-Reply-To"), "");
        String parent = parents.isEmpty() ? "" : Py.cut(parents.get(0), 255);
        reply.inReplyTo = parent.isEmpty() ? null : parent;
        reply.suggestedDispatchId = review != null ? (Integer) review[0] : null;
        reply.reviewReason = review != null ? Py.cut((String) review[1], 255) : null;
        db.add(reply);
        try {
            db.flush();
        } catch (Db.DbException e) {
            if (!e.integrity()) throw e;
            db.rollback();  // stored by another check at the same moment
            return false;
        }
        for (Object[] a : content.attachments()) {
            EmailReplyAttachment att = new EmailReplyAttachment();
            att.replyId = reply.id;
            att.filename = (String) a[0];
            att.contentType = (String) a[1];
            att.content = (byte[]) a[2];
            att.size = att.content.length;
            db.add(att);
        }
        if (d != null) {
            String who = !name.isEmpty() ? name + " <" + sender + ">" : sender;
            // in the history after the email it answers, even if the sender's clock is wrong
            LocalDateTime at = d.sentAt != null && d.sentAt.isAfter(received) ? d.sentAt : received;
            EmailEvent ev = new EmailEvent();
            ev.dispatchId = d.id;
            ev.kind = kind;
            ev.replyId = reply.id;
            ev.subject = Py.cut(reply.subject, 300);
            ev.note = String.format(EVENT_NOTES.get(kind), who);
            ev.createdBy = INBOX_USER;
            ev.createdAt = at;
            db.add(ev);
            if (kind.equals(REPLY) && "not_received".equals(d.replyStatus)) {  // never overrides Reconciled
                d.replyStatus = "received";
                d.replyStatusBy = INBOX_USER;
                d.replyStatusAt = Py.now();
                EmailEvent status = new EmailEvent();
                status.dispatchId = d.id;
                status.kind = "status";
                status.replyStatus = "received";
                status.note = "Set automatically: the partner's reply was found in the mailbox.";
                status.createdBy = INBOX_USER;
                db.add(status);
            }
            if (kind.equals(BOUNCE)) {
                for (String address : Py.csv(addressList(msg.raw("X-Failed-Recipients")))) {
                    Delivery.recordRefused(d, address, "Bounced: the mail server could not deliver it");
                }
            }
        }
        db.commit();
        return true;
    }

    // ---------------------------------------------------------------- background thread

    /** Background thread: check the mailbox every INBOX_CHECK_SECONDS (at once after a settings change). */
    public static void loop() {
        while (!stopped) {
            if (enabled()) {
                try {
                    scan();
                } catch (Exception e) {
                    LOG.warning("Inbox check failed: " + e.getMessage());
                }
                STATUS.put("next_run_at", Py.isoSeconds(LocalDateTime.now().plusSeconds(Config.INBOX_CHECK_SECONDS())));
            } else {
                STATUS.put("next_run_at", null);
            }
            synchronized (WAKE) {
                if (!woken && !stopped) {
                    try {
                        WAKE.wait(Config.INBOX_CHECK_SECONDS() * 1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                woken = false;
            }
        }
    }

    /** The mailbox settings changed: check again now, with the new settings. */
    public static void wake() {
        synchronized (WAKE) {
            woken = true;
            WAKE.notifyAll();
        }
    }

    public static void stop() {
        stopped = true;
        wake();
    }

    /** users.created_at is stored in UTC; replies use local time. */
    public static LocalDateTime utcToLocal(LocalDateTime value) {
        return value.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    static Address[] none() {
        return new Address[0];
    }
}
