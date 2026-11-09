package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.entity.EmailTemplate;
import com.nagad.npsb.entity.TemplateVariable;
import com.nagad.npsb.entity.Transaction;
import com.nagad.npsb.util.Crypto;
import com.nagad.npsb.util.Py;
import jakarta.activation.DataHandler;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.eclipse.angus.mail.util.MailConnectException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email templates, building the emails, and sending them (Gmail or another SMTP server).
 * The mail server settings are read from LiveSettings at every send, so a change on the System Settings page
 * applies at once.
 */
@Service
public class EmailService {

    private final LiveSettings live;

    public EmailService(LiveSettings live) {
        this.live = live;
    }

    static {
        System.setProperty("mail.mime.decodefilename", "true");
        System.setProperty("mail.mime.encodefilename", "false");
    }

    // Mail types come from the TXN_DIRECTION column. One email per partner per mail type.
    public static final Map<String, String> MAIL_TYPES = new LinkedHashMap<>();
    /** Older emails (before TXN_DIRECTION) could be an "Outward Reminder"; kept so they still show a name */
    public static final Map<String, String> MAIL_TYPE_NAMES = new LinkedHashMap<>();
    static {
        MAIL_TYPES.put("inward", "Inward Transaction");
        MAIL_TYPES.put("outward", "Outward Transaction");
        MAIL_TYPE_NAMES.putAll(MAIL_TYPES);
        MAIL_TYPE_NAMES.put("outward_reminder", "Outward Reminder");
    }

    /** TXN_DIRECTION 'Inward' / 'OUTWARD' / 'in' / 'Outward Transaction' -> 'inward' / 'outward', or null. */
    public static String parseDirection(String value) {
        String cleaned = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z ]", " ");
        List<String> words = new ArrayList<>();
        for (String w : Py.split(cleaned)) if (!Set.of("transaction", "transactions", "txn").contains(w)) words.add(w);
        String text = String.join(" ", words);
        return switch (text) {
            case "inward", "in" -> "inward";
            case "outward", "out" -> "outward";
            default -> null;
        };
    }

    public static final String SIGNATURE = "<p>Regards<br/>Reconciliation<br/>Nagad Limited</p>";
    static final String P = "<p style=\"margin:0 0 12px\">";
    private static final String WRAP_START = "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222;line-height:1.45\">";
    private static String wrap(String body) {
        return WRAP_START + body + "\n  " + SIGNATURE + "\n</div>\n";
    }

    private static LocalDate dateOf(Transaction t) {
        return t.getApprovalDate() != null ? t.getApprovalDate() : t.getTxnDatetime().toLocalDate();
    }

    /** '02 Sep 2026', or '02 Sep 2026 to 04 Sep 2026' when the transactions span several days. */
    static String dateText(List<Transaction> transactions) {
        List<LocalDate> dates = new ArrayList<>();
        for (Transaction t : transactions) dates.add(dateOf(t));
        dates.sort(Comparator.naturalOrder());
        String first = Py.strftime(dates.get(0), "%d %b %Y"), last = Py.strftime(dates.get(dates.size() - 1), "%d %b %Y");
        return first.equals(last) ? first : first + " to " + last;
    }

    // ---------------------------------------------------------------- email templates

    /** Placeholders of every template, filled in for each partner when the email is prepared. */
    public static final Map<String, String> PLACEHOLDERS = new LinkedHashMap<>();
    /** Only in reminder (follow-up) templates */
    public static final Map<String, String> REMINDER_PLACEHOLDERS = new LinkedHashMap<>();
    static {
        PLACEHOLDERS.put("partner_name", "Partner name (KEY_ORG_NM)");
        PLACEHOLDERS.put("date", "Transaction date, or date range");
        PLACEHOLDERS.put("txn_count", "Number of transactions");
        PLACEHOLDERS.put("total_amount", "Total amount (BDT)");
        PLACEHOLDERS.put("mail_type", "Inward Transaction / Outward Transaction");
        REMINDER_PLACEHOLDERS.put("sent_date", "Date the first email was sent");
        REMINDER_PLACEHOLDERS.put("followup_number", "Number of this follow-up (1, 2, ...)");
    }
    public static final Pattern PLACEHOLDER_RE = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}", Pattern.UNICODE_CHARACTER_CLASS);

    /** Columns of the uploaded file a template variable can take its value from (the email's transactions). */
    public static final Map<String, Function<Transaction, Object>> VARIABLE_COLUMNS = new LinkedHashMap<>();
    public static final Map<String, String> VARIABLE_AGGREGATES = new LinkedHashMap<>();
    static {
        VARIABLE_COLUMNS.put("NPSB_TXN_RRN", t -> t.getRrn());
        VARIABLE_COLUMNS.put("TXN_ID", t -> t.getTxnId());
        VARIABLE_COLUMNS.put("ORG_TXN_STAN", t -> t.getStan());
        VARIABLE_COLUMNS.put("TXN_TYPE", t -> t.getTxnType());
        VARIABLE_COLUMNS.put("FIRST_TXN_STATUS_NAME", t -> t.getStatus());
        VARIABLE_COLUMNS.put("TXN_AMOUNT", t -> t.getAmount());
        VARIABLE_COLUMNS.put("FROMAC_NAME", t -> t.getFromAccountName());
        VARIABLE_COLUMNS.put("TOAC_TYPE", t -> t.getToAccountType());
        VARIABLE_COLUMNS.put("NPSB_ORG_CODE", t -> t.getOrgCode());
        VARIABLE_COLUMNS.put("APPROVAL_DATE", t -> t.getApprovalDate());
        VARIABLE_AGGREGATES.put("list", "All different values, comma separated");
        VARIABLE_AGGREGATES.put("first", "The first value");
        VARIABLE_AGGREGATES.put("count", "How many different values");
        VARIABLE_AGGREGATES.put("sum", "Total (numbers only)");
    }
    public static final Pattern VARIABLE_NAME_RE = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");

    /** str(value) as Python prints it. */
    private static String pyStr(Object v) {
        if (v instanceof BigDecimal d) return Py.decimalStr(d);
        if (v instanceof Double d) return Py.floatRepr(d);
        return String.valueOf(v);
    }

    /** The text a declared template variable stands for in one email. */
    public static String variableValue(TemplateVariable variable, List<Transaction> transactions) {
        if ("text".equals(variable.getSource())) return variable.getValue() == null ? "" : variable.getValue();
        Function<Transaction, Object> attribute = VARIABLE_COLUMNS.get(variable.getColumn() == null ? "" : variable.getColumn());
        if (attribute == null) return "";
        List<Object> values = new ArrayList<>();
        for (Transaction t : transactions) {
            Object v = attribute.apply(t);
            if (v != null && !Py.strip(pyStr(v)).isEmpty()) values.add(v);
        }
        if ("sum".equals(variable.getAggregate())) {
            double total = 0.0;
            for (Object v : values) {
                try {
                    total += v instanceof BigDecimal d ? d.doubleValue() : Double.parseDouble(Py.strip(pyStr(v)).replace("_", ""));
                } catch (NumberFormatException e) {
                    // not a number: left out
                }
            }
            return Py.money(total);
        }
        List<String> texts = new ArrayList<>();
        for (Object v : values) texts.add(v instanceof LocalDate d ? Py.strftime(d, "%d-%m-%Y") : pyStr(v));
        texts = Py.unique(texts);
        if ("first".equals(variable.getAggregate())) return texts.isEmpty() ? "" : texts.get(0);
        if ("count".equals(variable.getAggregate())) return String.valueOf(texts.size());
        return String.join(", ", texts);
    }

    /** The body of a template is wrapped in this (font, size, colour) when the email is built */
    private static String bodyWrap(String body) {
        return WRAP_START + body + "\n</div>\n";
    }

    public record DefaultTemplate(String name, String subject, String bodyHtml) {}

    /** The standard wording: the first templates of a new database, and the fallback when a mail type has no template. */
    public static final Map<String, DefaultTemplate> DEFAULT_TEMPLATES = new LinkedHashMap<>();
    static {
        DEFAULT_TEMPLATES.put("inward", new DefaultTemplate("Inward - Not found in OIC Settlement File",
            "Successful at NAGAD but not found in OIC Settlement File - {{partner_name}} - Dated {{date}}",
            "\n"
            + "  " + P + "Dear Concern</p>\n"
            + "  " + P + "Assalamualaikum.</p>\n"
            + "  " + P + "This is to bring to your kind attention that the attached NPSB Inward and Bangla QR Acquirer transactions\n"
            + "     dated {{date}} were successfully processed at NAGAD’s end; however, the transactions are not reflected\n"
            + "     in the Bangladesh Bank OIC Settlement File.</p>\n"
            + "  " + P + "As these transactions are not included in the settlement file, the corresponding settlement funds have not\n"
            + "     yet been received by NAGAD. This may result in a financial exposure if the matter remains unresolved.</p>\n"
            + "  " + P + "We would highly appreciate your support in investigating the matter on priority and identifying the reason\n"
            + "     for the transactions not being reflected in the OIC Settlement File. Kindly take the necessary corrective\n"
            + "     measures and arrange the corresponding settlement at the earliest possible time to minimize any potential\n"
            + "     financial impact.</p>\n"
            + "  " + P + "For your review and necessary action, please find attached the required transaction details.</p>\n"
            + "  " + SIGNATURE));
        DEFAULT_TEMPLATES.put("outward", new DefaultTemplate("Outward - Claim confirmation",
            "NPSB Outward Transactions - Claim Confirmation Required - {{partner_name}} - Dated {{date}}",
            "\n"
            + "  " + P + "Dear Concern,</p>\n"
            + "  " + P + "Assalamualaikum.</p>\n"
            + "  " + P + "Please find attached a list of transactions. We would appreciate your review and feedback on these\n"
            + "     transactions. Kindly confirm whether there are any claims at your end.<br/>\n"
            + "     In the absence of any claim or observation, we will proceed with the necessary action from our end\n"
            + "     accordingly.</p>\n"
            + "  " + P + "<b>Kindly note that we are expecting your feedback within 5 working days. If we do not receive any feedback\n"
            + "     within this timeframe, we will consider that there is no claim from your end regarding these\n"
            + "     transactions.</b></p>\n"
            + "  " + P + "Your prompt feedback will help us complete the reconciliation process smoothly.</p>\n"
            + "  " + P + "Thank you for your continued cooperation and support.</p>\n"
            + "  " + SIGNATURE));
    }

    /** Placeholders used in the texts that are not known (typos like {{partner}}), sorted. */
    public static List<String> unknownPlaceholders(List<String> known, String... texts) {
        TreeSet<String> found = new TreeSet<>();
        for (String t : texts) {
            Matcher m = PLACEHOLDER_RE.matcher(t);
            while (m.find()) found.add(m.group(1));
        }
        found.removeAll(PLACEHOLDERS.keySet());
        found.removeAll(known);
        return new ArrayList<>(found);
    }

    private static final Pattern P_TAG = Pattern.compile("<p>", Pattern.CASE_INSENSITIVE);

    /**
     * (subject, html body) of one email: the template with its placeholders filled in.
     * extra: values of declared template variables (and of reminder placeholders).
     */
    public static String[] renderTemplate(String subject, String bodyHtml, String partnerName, String mailType,
                                          List<Transaction> transactions, Map<String, String> extra) {
        Map<String, String> values = new LinkedHashMap<>();
        if (extra != null) values.putAll(extra);
        double total = 0;
        for (Transaction t : transactions) total += Py.toDouble(t.getAmount());
        values.put("partner_name", partnerName);
        values.put("date", dateText(transactions));
        values.put("txn_count", String.valueOf(transactions.size()));
        values.put("total_amount", Py.money(total));
        values.put("mail_type", MAIL_TYPES.getOrDefault(mailType, mailType));

        // Email clients ignore stylesheets: plain paragraphs (written on the Email Templates page) get their spacing inline
        String body = P_TAG.matcher(fill(bodyHtml, values, true)).replaceAll(Matcher.quoteReplacement(P));
        return new String[]{Py.cut(Py.squash(fill(subject, values, false)), 300), bodyWrap(body)};
    }

    private static String fill(String text, Map<String, String> values, boolean html) {
        Matcher m = PLACEHOLDER_RE.matcher(text);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            String replacement = value == null ? m.group(0) : html ? Py.escape(value) : value;  // unknown: left as written
            m.appendReplacement(b, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static Map<String, String> variableValues(List<TemplateVariable> variables, List<Transaction> transactions) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (variables != null) for (TemplateVariable v : variables) extra.put(v.getName(), variableValue(v, transactions));
        return extra;
    }

    /**
     * (subject, html body) for one partner and mail type from `template`, or from the standard wording
     * when there is none. The transactions go in an attached Excel file (transactionsXlsx), as the templates say.
     */
    public static String[] buildEmail(String partnerName, String mailType, List<Transaction> transactions,
                                      EmailTemplate template, List<TemplateVariable> variables) {
        String subject, bodyHtml;
        if (template != null) {
            subject = template.getSubject();
            bodyHtml = template.getBodyHtml();
        } else {
            DefaultTemplate t = DEFAULT_TEMPLATES.get("inward".equals(mailType) ? "inward" : "outward");
            subject = t.subject();
            bodyHtml = t.bodyHtml();
        }
        return renderTemplate(subject, bodyHtml, partnerName, mailType, transactions, variableValues(variables, transactions));
    }

    /** The attachment has the same columns as the uploaded file */
    public static final List<String> ATTACHMENT_COLUMNS = List.of("NPSB_TXN_RRN", "APPROVAL_DATE", "APPROVAL_DATETIME", "TXN_TYPE",
            "TXN_ID", "FIRST_TXN_STATUS_NAME", "TXN_AMOUNT", "FROMAC_NAME", "TOAC_TYPE", "ORG_TXN_STAN", "KEY_ORG_NM",
            "NPSB_ORG_CODE", "TXN_DIRECTION");

    /** The transactions of one email as an Excel file (the attachment), in the upload file's format. */
    public static byte[] transactionsXlsx(List<Transaction> transactions) {
        List<Transaction> sorted = new ArrayList<>(transactions);
        sorted.sort(Comparator.comparing(t -> t.getTxnDatetime()));
        List<List<Object>> rows = new ArrayList<>();
        for (Transaction t : sorted) {
            rows.add(java.util.Arrays.asList(
                    t.getRrn(),
                    t.getApprovalDate(),  // a real date cell, as in the uploaded file
                    t.getApprovalDatetimeText() != null && !t.getApprovalDatetimeText().isEmpty() ? t.getApprovalDatetimeText()
                            : Py.strftime(t.getTxnDatetime(), "%d-%b-%y %I.%M.%S.%f %p").toUpperCase(java.util.Locale.ROOT),
                    t.getTxnType(), Py.or(t.getTxnId(), ""), t.getStatus(), Py.toDouble(t.getAmount()), Py.or(t.getFromAccountName(), ""),
                    Py.or(t.getToAccountType(), ""), t.getStan(), t.getPartnerName(), t.getOrgCode(), Py.capitalize(Py.or(t.getMailType(), ""))));
        }
        return Xlsx.write(ATTACHMENT_COLUMNS, rows, ExcelParser.SHEET_NAME);
    }

    /**
     * 'Successful_at_Nagad_not_found_in_OIC_bKash_Dated_02-09-2026.xlsx' (inward) or
     * 'NPSB_Outward_Transactions_Rocket_Dated_02-09-2026.xlsx' (outward).
     */
    public static String attachmentName(String partnerName, String mailType, List<Transaction> transactions) {
        LocalDate first = null;
        for (Transaction t : transactions) if (first == null || dateOf(t).isBefore(first)) first = dateOf(t);
        String kind = "inward".equals(mailType) ? "Successful_at_Nagad_not_found_in_OIC" : "NPSB_Outward_Transactions";
        String partner = Py.strip(partnerName.replaceAll("[^A-Za-z0-9]+", "_"), "_");
        return kind + "_" + partner + "_Dated_" + Py.strftime(first, "%d-%m-%Y") + ".xlsx";
    }

    /**
     * Default follow-up for a sent email: (subject, html body). The admin edits it before sending. The
     * transactions are attached again.
     *
     * A reminder template (Email Templates page) is used when one is the default for the mail type and no reply
     * has come yet. Otherwise the built-in wording: no reply yet -> the outward reminder, or a settlement reminder
     * (inward); reply received -> thanks, and a request for what is still missing.
     */
    public static String[] buildFollowup(String mailType, String originalSubject, LocalDateTime sentAt, int txnCount, double total,
                                         String replyStatus, int number, EmailTemplate template, String partnerName,
                                         List<Transaction> transactions, List<TemplateVariable> variables) {
        String subject = originalSubject.toLowerCase(java.util.Locale.ROOT).startsWith("re:") ? originalSubject : "Re: " + originalSubject;
        String sentOn = sentAt != null ? Py.strftime(sentAt, "%d %b %Y") : "earlier";
        if (template != null && !"received".equals(replyStatus) && transactions != null && !transactions.isEmpty()) {
            Map<String, String> extra = variableValues(variables, transactions);
            extra.put("sent_date", sentOn);
            extra.put("followup_number", String.valueOf(number));
            return renderTemplate(template.getSubject(), template.getBodyHtml(), partnerName, mailType, transactions, extra);
        }
        String summary = "<b>" + txnCount + "</b> transaction(s) totalling <b>BDT " + Py.money(total) + "</b>";
        String text;
        if ("received".equals(replyStatus)) {
            text = "\n"
                + "  " + P + "Dear Concern,</p>\n"
                + "  " + P + "Assalamualaikum.</p>\n"
                + "  " + P + "Thank you for your reply to our email of " + sentOn + " regarding " + summary + ".</p>\n"
                + "  " + P + "To complete the reconciliation, we still need the following from your end:</p>\n"
                + "  <p style=\"margin:0 0 12px;background:#fff7d6;padding:6px 8px\">[Describe what is still needed, e.g. the status of RRN ...]</p>\n"
                + "  " + P + "The transaction details are attached again for your reference.</p>";
        } else if ("inward".equals(mailType)) {
            text = "\n"
                + "  " + P + "Dear Concern,</p>\n"
                + "  " + P + "Assalamualaikum.</p>\n"
                + "  " + P + "This is a soft reminder" + (number > 1 ? " (follow-up " + number + ")" : "") + " regarding our email of " + sentOn + "\n"
                + "     about " + summary + " that were successfully processed at NAGAD’s end but are not reflected in the\n"
                + "     Bangladesh Bank OIC Settlement File. We have not yet received the corresponding settlement or your feedback.</p>\n"
                + "  " + P + "Kindly arrange the settlement at the earliest possible time to minimize any potential financial impact.\n"
                + "     The transaction details are attached again for your reference.</p>";
        } else {  // outward: the "Outward Reminder" template
            text = "\n"
                + "  " + P + "Dear Concern.</p>\n"
                + "  " + P + "Assalamualaikum.</p>\n"
                + "  " + P + "This is a soft reminder, if we didn’t get any feedback from your end within COB today, we will assuming\n"
                + "     that there are no claim from your end. So, we will be proceeding with necessary action from our end.</p>";
        }
        return new String[]{subject, wrap(text)};
    }

    // ---------------------------------------------------------------- sending

    /** attempts, refused recipients (address -> reason) and the Message-ID (so replies can join the thread). */
    public record SendResult(int attempts, Map<String, String> refused, String messageId) {}

    /** The server refused every recipient (permanent: not retried). */
    public static final class RecipientsRefused extends RuntimeException {
        public final Map<String, String> refused;

        public RecipientsRefused(Map<String, String> refused) {
            super("Recipient refused: " + joinRefused(refused));
            this.refused = refused;
        }
    }

    public static String joinRefused(Map<String, String> refused) {
        List<String> parts = new ArrayList<>();
        refused.forEach((a, r) -> parts.add(a + " (" + r + ")"));
        return String.join("; ", parts);
    }

    /** An attachment: file name, bytes and content type (null: an .xlsx file). */
    public record Attachment(String filename, byte[] data, String contentType) {}

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** A MimeMessage that keeps the Message-ID we gave it. */
    private static final class Built extends MimeMessage {
        private final String id;

        Built(Session session, String id) {
            super(session);
            this.id = id;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", id);
        }
    }

    /** email.utils.make_msgid(domain) */
    static String makeMsgid(String domain) {
        long stamp = System.currentTimeMillis() / 10;
        long pid = ProcessHandle.current().pid();
        String random = Long.toUnsignedString(Crypto.RANDOM.nextLong());
        if (domain == null || domain.isEmpty()) {
            try {
                domain = java.net.InetAddress.getLocalHost().getCanonicalHostName();
            } catch (Exception e) {
                domain = "localhost";
            }
        }
        return "<" + stamp + "." + pid + "." + random + "@" + domain + ">";
    }

    MimeMessage buildMessage(Session session, List<String> to, List<String> cc, String subject, String htmlBody,
                             String inReplyTo, List<Attachment> attachments, String references) throws MessagingException {
        String sender = live.mailFrom();
        String domain = sender.contains("@") ? sender.substring(sender.lastIndexOf('@') + 1) : sender;
        MimeMessage msg = new Built(session, makeMsgid(domain));
        msg.setFrom(new InternetAddress(sender, false));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(String.join(", ", to), false));
        if (cc != null && !cc.isEmpty()) msg.setRecipients(Message.RecipientType.CC, InternetAddress.parse(String.join(", ", cc), false));
        msg.setReplyTo(InternetAddress.parse(sender, false));  // replies come back to the mailbox they are read from (Inbox)
        msg.setSubject(subject, "UTF-8");
        if (inReplyTo != null && !inReplyTo.isEmpty()) {
            msg.setHeader("In-Reply-To", inReplyTo);
            msg.setHeader("References", references != null && !references.isEmpty() ? references : inReplyTo);
        }
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Please view this email in an HTML capable mail client.\n", "UTF-8", "plain");
        MimeBodyPart html = new MimeBodyPart();
        html.setText(htmlBody, "UTF-8", "html");
        alternative.addBodyPart(plain);
        alternative.addBodyPart(html);
        if (attachments == null || attachments.isEmpty()) {
            msg.setContent(alternative);
        } else {
            MimeMultipart mixed = new MimeMultipart("mixed");
            MimeBodyPart body = new MimeBodyPart();
            body.setContent(alternative);
            mixed.addBodyPart(body);
            for (Attachment a : attachments) {
                String kind = a.contentType() == null ? XLSX : a.contentType();
                String[] parts = kind.split("/", 2);
                if (parts.length < 2 || parts[1].isEmpty() || parts[0].equals("multipart")) kind = "application/octet-stream";
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new DataHandler(new ByteArrayDataSource(a.data(), kind)));
                part.setFileName(a.filename());
                part.setDisposition(MimeBodyPart.ATTACHMENT);
                part.setHeader("Content-Transfer-Encoding", "base64");
                mixed.addBodyPart(part);
            }
            msg.setContent(mixed);
        }
        msg.saveChanges();
        return msg;
    }

    private static Session smtpSession(String host, int port, boolean tls, String user) {
        Properties p = new Properties();
        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.connectiontimeout", "15000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        p.put("mail.smtp.sendpartial", "true");
        p.put("mail.smtp.auth", String.valueOf(user != null && !user.isEmpty()));
        if (tls) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        return Session.getInstance(p);
    }

    /** One line, like str(exc) in Python. */
    static String text(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return Py.squash(m.replace(";\n  nested exception is:\n\t", ": "));
    }

    private static String reason(int code, String message) {
        String m = message == null ? "" : Py.strip(message);
        String prefix = String.valueOf(code);
        String text = m.startsWith(prefix) ? Py.strip(m.substring(prefix.length())) : m;
        return Py.cut(Py.strip(code + " " + text), 250);
    }

    /** The refused recipients of a failed send, with the server's answer for each. */
    private static Map<String, String> refusedOf(SendFailedException e) {
        Map<String, String> refused = new LinkedHashMap<>();
        Exception next = e;
        while (next instanceof MessagingException me) {
            if (me instanceof SMTPAddressFailedException af) {
                refused.put(af.getAddress().getAddress(), reason(af.getReturnCode(), af.getMessage()));
            }
            next = me.getNextException();
        }
        if (e.getInvalidAddresses() != null) {
            for (jakarta.mail.Address a : e.getInvalidAddresses()) {
                String address = a instanceof InternetAddress ia ? ia.getAddress() : a.toString();
                refused.putIfAbsent(address, "");
            }
        }
        return refused;
    }

    /**
     * Send one email. The server may accept some recipients and refuse others: those are in SendResult.refused
     * instead of being dropped silently. inReplyTo: Message-ID of an earlier email, so this one shows in the same
     * conversation (follow-ups). attachments: files to attach.
     *
     * Retries on temporary errors (e.g. server down); throws RecipientsRefused when every recipient is refused, and
     * RuntimeException on any other final failure.
     */
    public SendResult sendEmail(List<String> to, List<String> cc, String subject, String htmlBody, String inReplyTo,
                                List<Attachment> attachments, String references) {
        if (live.gmail() && (live.smtpUser().isEmpty() || live.smtpPassword().isEmpty())) {
            throw new RuntimeException("Gmail is not set up yet: fill in the Gmail address and app password on the System Settings page.");
        }

        int maxAttempts = live.smtpMaxAttempts();
        String host = live.smtpHost(), user = live.smtpUser();
        Session session = smtpSession(host, live.smtpPort(), live.smtpUseTls(), user);
        MimeMessage msg;
        try {
            msg = buildMessage(session, to, cc, subject, htmlBody, inReplyTo, attachments, references);
        } catch (MessagingException e) {
            throw new RuntimeException(text(e));
        }
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Transport transport = session.getTransport("smtp")) {
                transport.connect(host, live.smtpPort(), user.isEmpty() ? null : user, user.isEmpty() ? null : live.smtpPassword());
                if (!user.isEmpty() && transport instanceof SMTPTransport smtp && !smtp.supportsExtension("AUTH")) {
                    throw new MessagingException("SMTP AUTH extension not supported by server.");
                }
                String messageId = msg.getHeader("Message-ID", null);
                try {
                    transport.sendMessage(msg, msg.getAllRecipients());
                } catch (SendFailedException e) {
                    Map<String, String> refused = refusedOf(e);
                    boolean someSent = e.getValidSentAddresses() != null && e.getValidSentAddresses().length > 0;
                    if (!someSent) {
                        if (refused.isEmpty()) throw e;
                        throw new RecipientsRefused(refused);  // permanent, no retry
                    }
                    return new SendResult(attempt, refused, messageId);
                }
                return new SendResult(attempt, new LinkedHashMap<>(), messageId);
            } catch (RecipientsRefused e) {
                throw e;
            } catch (Exception e) {  // connection problems etc. -> try again
                lastError = e;
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(live.smtpRetrySeconds() * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        throw new RuntimeException("Failed after " + maxAttempts + " attempt(s): " + text(lastError) + signInHint(lastError));
    }

    /** Gmail closes the connection (or answers 535) when the app password is wrong or was revoked. */
    static String signInHint(Exception e) {
        if (e instanceof AuthenticationFailedException) {
            return ". The mail server refused the sign-in: check the user and password on the System Settings "
                    + "page (for Gmail, create a new app password).";
        }
        return "";
    }

    /** Connect and sign in to a mail server without sending anything. Throws with a readable message. */
    public static void testSmtp(String host, int port, boolean useTls, String user, String password) {
        Session session = smtpSession(host, port, useTls, user);
        try (Transport transport = session.getTransport("smtp")) {
            try {
                transport.connect(host, port, user.isEmpty() ? null : user, user.isEmpty() ? null : password);
            } catch (AuthenticationFailedException e) {
                String m = e.getMessage() == null ? "" : Py.strip(e.getMessage());
                if (m.isEmpty() || m.equalsIgnoreCase("failed to connect")) {
                    throw new RuntimeException("The mail server closed the connection while signing in " + user + ": the password "
                            + "was most likely refused (for Gmail, create a new app password).");
                }
                throw new RuntimeException("The mail server refused the sign-in for " + user + ": " + Py.cut(m, 250));
            }
            if (!user.isEmpty() && transport instanceof SMTPTransport smtp && !smtp.supportsExtension("AUTH")) {
                throw new RuntimeException("The mail server doesn't accept a sign-in on this connection: "
                        + "turn on STARTTLS or check the port.");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (MailConnectException e) {
            throw new RuntimeException("Could not reach the mail server " + host + ":" + port + ": "
                    + (e.getCause() != null ? text(e.getCause()) : text(e)));
        } catch (MessagingException e) {
            if (e.getNextException() instanceof java.io.IOException io) {
                throw new RuntimeException("Could not reach the mail server " + host + ":" + port + ": " + text(io));
            }
            throw new RuntimeException("The mail server " + host + ":" + port + " answered with an error: " + text(e));
        }
    }
}
