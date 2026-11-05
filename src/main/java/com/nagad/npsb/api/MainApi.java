package com.nagad.npsb.api;

import com.nagad.npsb.Config;
import com.nagad.npsb.Py;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.ChangeRequest;
import com.nagad.npsb.models.EmailDispatch;
import com.nagad.npsb.models.EmailEvent;
import com.nagad.npsb.models.EmailTemplate;
import com.nagad.npsb.models.Partner;
import com.nagad.npsb.models.PartnerEmail;
import com.nagad.npsb.models.TemplateVariable;
import com.nagad.npsb.models.Transaction;
import com.nagad.npsb.models.Upload;
import com.nagad.npsb.models.User;
import com.nagad.npsb.service.Alerts;
import com.nagad.npsb.service.Approvals;
import com.nagad.npsb.service.Delivery;
import com.nagad.npsb.service.EmailCheck;
import com.nagad.npsb.service.EmailService;
import com.nagad.npsb.service.ExcelParser;
import com.nagad.npsb.service.Inbox;
import com.nagad.npsb.service.Settings;
import com.nagad.npsb.web.ApiError;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.Req;
import com.nagad.npsb.web.Res;
import com.nagad.npsb.web.Server;
import com.nagad.npsb.web.ValueError;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The app's API endpoints (everything except sign-in, users and answers).
 *
 * Flow:
 *   1. POST /api/uploads                     -> upload & validate file                      (users)
 *   2. GET  /api/uploads/{id}                -> transactions grouped by partner             (users)
 *   3. POST /api/uploads/{id}/prepare-emails -> build one email per partner (preview/edit)  (users)
 *   4. POST /api/uploads/{id}/submit         -> PREPARED -> PENDING: waits for an admin     (users)
 *   5. GET  /api/pending-emails              -> every PENDING / FAILED email                (everyone)
 *   6. POST /api/pending-emails/send         -> send the ticked pending emails              (admins)
 *   7. GET  /api/sent-emails                 -> sent emails: search, partner filter, sort   (everyone)
 *   8. Follow-up of sent emails: reply status, follow-up emails, notes, history (admins change, everyone reads)
 *   9. GET/PUT /api/email-settings           -> follow-up days, automatic follow-ups, CC   (admins change)
 *  10. /api/email-templates                  -> email templates per mail type (users change, everyone reads)
 *  11. /api/replies                          -> partners' mail, read from the sending mailbox
 *  12. /api/server-settings                  -> mail server, inbox, retries and security settings (admins only)
 *
 * Automatic follow-ups: when switched on, a background thread sends the follow-up of every email that is due
 * (runAutoFollowups), as the "system" user. Every endpoint here needs a signed-in user (JWT).
 */
public final class MainApi {
    private MainApi() {}

    static final Logger LOG = Logger.getLogger("npsb");

    // ---------------------------------------------------------------- helpers

    static Map<String, Object> partnerToDict(Partner p) {
        List<Object> emails = new ArrayList<>();
        for (PartnerEmail e : p.emails()) {
            emails.add(Py.map("id", e.id, "email", e.email, "is_active", e.isActive, "problem", e.problem,
                    "problem_at", Py.iso(e.problemAt)));
        }
        return Py.map("id", p.id, "name", p.name, "is_active", p.isActive, "emails", emails);
    }

    /** created_by of automatic follow-ups. "#" is not allowed in usernames, so no account can have it. */
    static final String SYSTEM_USER = "#auto";

    /** username -> full name (the username itself when no name is set). */
    static Map<String, String> userNames(Db db, Collection<String> usernames) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String u : usernames) if (!Py.isEmpty(u)) wanted.add(u);
        Map<String, String> out = new HashMap<>();
        if (wanted.isEmpty()) return out;
        out.put(SYSTEM_USER, "Sent automatically");
        out.put(Inbox.INBOX_USER, "Found in the mailbox");
        for (Object[] row : db.rows("SELECT username, full_name FROM users WHERE username IN " + Db.in(wanted), wanted.toArray())) {
            out.put((String) row[0], Py.or((String) row[1], (String) row[0]));
        }
        return out;
    }

    static String name(Map<String, String> names, String username) {
        return names.containsKey(username) ? names.get(username) : username;
    }

    static List<Map<String, Object>> dispatchesToDicts(Db db, List<EmailDispatch> dispatches) {
        List<String> who = new ArrayList<>();
        for (EmailDispatch d : dispatches) {
            who.add(d.submittedBy);
            who.add(d.sentBy);
            who.add(d.replyStatusBy);
            who.add(d.remarksBy);
        }
        Map<String, String> names = userNames(db, who);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmailDispatch d : dispatches) out.add(dispatchToDict(d, names));
        return out;
    }

    static Map<String, Object> dispatchToDict(EmailDispatch d, Map<String, String> names) {
        if (names == null) {
            names = userNames(d.session(), java.util.Arrays.asList(d.submittedBy, d.sentBy, d.replyStatusBy, d.remarksBy));
        }
        return Py.map(
                "id", d.id,
                "partner_name", d.partner().name,
                "to", Py.splitOn(d.toEmails, ","),
                "cc", Py.csv(d.ccEmails),
                "subject", d.subject,
                "body_html", d.bodyHtml,
                "is_edited", d.isEdited,
                "template_id", d.templateId,
                "txn_count", d.txnCount,
                "total_amount", Py.toDouble(d.totalAmount),
                "status", d.status,
                "attempts", d.attempts,
                "error", d.error,
                "sent_at", Py.iso(d.sentAt),
                "delivery_issues", Delivery.issuesOf(d),
                "upload_id", d.uploadId,
                "upload_filename", d.upload().filename,
                "mail_type", d.mailType,
                "mail_type_label", EmailService.MAIL_TYPE_NAMES.getOrDefault(d.mailType, d.mailType),
                "submitted_by", d.submittedBy,
                "submitted_by_name", name(names, d.submittedBy),
                "submitted_at", Py.iso(d.submittedAt),
                "sent_by", d.sentBy,                 // while SENDING: the admin sending it right now
                "sent_by_name", name(names, d.sentBy),
                // follow-up
                "reply_status", d.replyStatus,
                "reply_status_by_name", name(names, d.replyStatusBy),
                "reply_status_at", Py.iso(d.replyStatusAt),
                "followup_count", d.followupCount,
                "last_followup_at", Py.iso(d.lastFollowupAt),
                "followup_due", followupDue(d),
                "remarks", Py.or(d.remarks, ""),
                "remarks_by_name", name(names, d.remarksBy),
                "remarks_at", Py.iso(d.remarksAt));
    }

    /** Sent, no reply, and nothing sent to the partner for "followup_due_days" (Email Configuration). */
    static boolean followupDue(EmailDispatch d) {
        LocalDateTime lastContact = d.lastFollowupAt != null ? d.lastFollowupAt : d.sentAt;
        return "SENT".equals(d.status) && "not_received".equals(d.replyStatus) && lastContact != null
                && lastContact.isBefore(LocalDateTime.now().minusDays(Settings.getInt("followup_due_days")));
    }

    static final List<String> MAIL_TYPE_ORDER = new ArrayList<>(EmailService.MAIL_TYPES.keySet());

    /** Partners and KEY_ORG_NM are matched by name, ignoring case and extra spaces: ' UPAY ' == 'Upay'. */
    static String nameKey(String name) {
        return Py.casefold(Py.squash(String.valueOf(name)));
    }

    /** {(partner name key, mail type): [transactions]} for one upload: one email each. */
    static LinkedHashMap<List<String>, List<Transaction>> groupByPartner(Upload upload) {
        Map<List<String>, List<Transaction>> grouped = new HashMap<>();
        for (Transaction t : upload.transactions()) {
            if (EmailService.MAIL_TYPES.containsKey(t.mailType) && t.duplicateOf == null) {  // already reported elsewhere: left out
                grouped.computeIfAbsent(List.of(nameKey(t.partnerName), t.mailType), k -> new ArrayList<>()).add(t);
            }
        }
        List<List<String>> keys = new ArrayList<>(grouped.keySet());
        keys.sort(Comparator.<List<String>, String>comparing(k -> k.get(0)).thenComparing(k -> MAIL_TYPE_ORDER.indexOf(k.get(1))));
        LinkedHashMap<List<String>, List<Transaction>> out = new LinkedHashMap<>();
        for (List<String> k : keys) out.put(k, grouped.get(k));
        return out;
    }

    static List<Transaction> dispatchTransactions(EmailDispatch d) {
        String mailType = "outward_reminder".equals(d.mailType) ? "outward" : d.mailType;
        List<Transaction> found = groupByPartner(d.upload()).get(List.of(nameKey(d.partner().name), mailType));
        return found == null ? new ArrayList<>() : found;
    }

    static Map<String, Partner> partnersByName(Db db) {
        Map<String, Partner> out = new HashMap<>();
        for (Partner p : db.all(Partner.class, "ORDER BY t.id")) out.put(nameKey(p.name), p);
        return out;
    }

    /** The email's transactions as an attached Excel file ("please find attached" in the templates). */
    static List<EmailService.Attachment> attachmentFor(EmailDispatch d) {
        List<Transaction> txns = dispatchTransactions(d);
        if (txns.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(List.of(new EmailService.Attachment(EmailService.attachmentName(d.partner().name, d.mailType, txns),
                EmailService.transactionsXlsx(txns), null)));
    }

    static Upload getUploadOr404(Db db, int uploadId) {
        Upload upload = db.get(Upload.class, uploadId);
        if (upload == null) throw new ApiError(404, "Upload not found");
        return upload;
    }

    public static void routes(Server s) {
        s.get("/api/config", "Settings shown in the UI.", MainApi::getConfig);
        s.get("/api/partners", "List Partners", MainApi::listPartners);
        s.post("/api/partners", "Create Partner", MainApi::createPartner);
        s.put("/api/partners/{partner_id}", "Update Partner", MainApi::updatePartner);
        s.get("/api/email-templates", "List Templates", MainApi::listTemplates);
        s.post("/api/email-templates", 201, "Create Template", MainApi::createTemplate);
        s.put("/api/email-templates/{template_id}", "Update Template", MainApi::updateTemplate);
        s.post("/api/email-templates/{template_id}/default", "Set Default Template", MainApi::setDefaultTemplate);
        s.delete("/api/email-templates/{template_id}", "Delete Template", MainApi::deleteTemplate);
        s.post("/api/template-variables", 201, "Create Variable", MainApi::createVariable);
        s.put("/api/template-variables/{variable_id}", "Update Variable", MainApi::updateVariable);
        s.delete("/api/template-variables/{variable_id}", "Delete Variable", MainApi::deleteVariable);
        s.get("/api/upload-template", "Sample upload file with the required columns.", MainApi::downloadUploadTemplate);
        s.post("/api/uploads", "Upload File", MainApi::uploadFile);
        s.get("/api/uploads", "Earlier uploads, newest first, with how far their emails have got.", MainApi::listUploads);
        s.get("/api/uploads/{upload_id}/summary", "An earlier upload, as shown right after uploading it.", MainApi::getUploadSummary);
        s.get("/api/uploads/{upload_id}/rows/{kind}", "The upload's valid or faulty rows as a new .xlsx.", MainApi::downloadUploadRows);
        s.get("/api/uploads/{upload_id}", "Transactions grouped by partner (KEY_ORG_NM) and mail type (TXN_DIRECTION).", MainApi::getUploadGroups);
        s.post("/api/uploads/{upload_id}/prepare-emails", "Build (or rebuild) one email per partner and mail type.", MainApi::prepareEmails);
        s.get("/api/uploads/{upload_id}/emails", "List Emails", req -> {
            Auth.currentUser(req);
            return listEmails(req.db(), req.pathInt("upload_id"));
        });
        s.get("/api/emails/{email_id}/attachment", "The Excel file attached to this email: its transactions.", MainApi::downloadAttachment);
        s.put("/api/emails/{email_id}", "Save a manual edit. Edited emails are kept when previews are refreshed.", MainApi::editEmail);
        s.delete("/api/emails/{email_id}/edits", "Throw away manual edits.", MainApi::discardEmailEdits);
        s.put("/api/emails/{email_id}/template", "Rebuild this email from another template. Manual edits are replaced.", MainApi::applyTemplate);
        s.post("/api/uploads/{upload_id}/submit", "A user's \"Send\": every PREPARED email of this upload waits for an admin.", MainApi::submitEmails);
        s.get("/api/pending-emails", "Emails waiting for an admin (PENDING), and ones whose sending failed (FAILED).", req -> {
            Auth.currentUser(req);
            return listPendingEmails(req.db());
        });
        s.post("/api/pending-emails/send", "Admin sends the ticked PENDING / FAILED emails (never twice).", MainApi::sendSelected);
        s.get("/api/sent-emails", "Sent emails from every upload.", MainApi::listSentEmails);
        s.get("/api/sent-emails/summary", "How many sent emails are in each reply status.", MainApi::sentSummary);
        s.get("/api/emails/{email_id}/events", "History of an email, oldest first.", MainApi::listEmailEvents);
        s.put("/api/emails/{email_id}/reply-status", "Admin records whether the partner replied.", MainApi::setReplyStatus);
        s.post("/api/emails/{email_id}/notes", "Admin note on a sent email.", MainApi::addEmailNote);
        s.put("/api/emails/{email_id}/remarks", "Admin's free-text remarks on a sent email.", MainApi::setRemarks);
        s.get("/api/emails/{email_id}/followup-draft", "A ready-made follow-up for the admin to edit.", MainApi::followupDraft);
        s.post("/api/emails/{email_id}/followups", "Admin sends a follow-up for a sent email, in the original thread.", MainApi::sendFollowup);
        MoreApi.routes(s);
    }

    // ---------------------------------------------------------------- config

    static Object getConfig(Req req) {
        Auth.currentUser(req);
        return Py.map(
                "mail_provider", Config.MAIL_PROVIDER(),
                "smtp_host", Config.SMTP_HOST(),
                "smtp_port", Config.SMTP_PORT(),
                "mail_from", Config.mailFrom(),
                "cc_emails", Settings.get("cc_emails"),
                "maker_checker", Config.MAKER_CHECKER());
    }

    // ---------------------------------------------------------------- partners

    private static final Pattern PARTNER_EMAIL_RE = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", Pattern.UNICODE_CHARACTER_CLASS);

    record EmailIn(String email, boolean isActive, boolean clearProblem) {}

    record PartnerIn(String name, boolean isActive, List<EmailIn> emails) {
        Map<String, Object> dump() {
            List<Object> list = new ArrayList<>();
            for (EmailIn e : emails) list.add(Py.map("email", e.email(), "is_active", e.isActive(), "clear_problem", e.clearProblem()));
            return Py.map("name", name, "is_active", isActive, "emails", list);
        }
    }

    static PartnerIn readPartner(Body b) {
        String name = b.str("name");
        boolean isActive = b.bool("is_active", true);
        List<EmailIn> emails = new ArrayList<>();
        for (Body e : b.objects("emails", false)) {
            String email = e.check("email", e.str("email"), v -> {
                v = Py.strip(v);
                if (!PARTNER_EMAIL_RE.matcher(v).matches()) throw new ValueError("'" + v + "' is not a valid email address");
                return v.toLowerCase(java.util.Locale.ROOT);
            });
            boolean active = e.bool("is_active", true);
            boolean clear = e.bool("clear_problem", false);
            emails.add(new EmailIn(email, active, clear));
        }
        return new PartnerIn(name, isActive, emails);
    }

    /**
     * New PartnerEmail rows from the form. New addresses must be able to receive mail; a "refused" flag stays on
     * an unchanged address unless the user cleared it.
     */
    static List<PartnerEmail> buildPartnerEmails(List<EmailIn> emails, List<PartnerEmail> existing) {
        Map<String, PartnerEmail> old = new HashMap<>();
        for (PartnerEmail e : existing) old.put(e.email.toLowerCase(java.util.Locale.ROOT), e);
        Set<String> seen = new HashSet<>();
        List<PartnerEmail> result = new ArrayList<>();
        for (EmailIn e : emails) {
            if (seen.contains(e.email())) throw new ApiError(400, e.email() + " is listed twice.");
            seen.add(e.email());
            PartnerEmail previous = old.get(e.email());
            if (previous == null) {
                try {
                    EmailCheck.validateRealEmail(e.email());
                } catch (ApiError exc) {
                    throw new ApiError(exc.status, e.email() + ": " + exc.text());
                }
            }
            PartnerEmail row = new PartnerEmail();
            row.email = e.email();
            row.isActive = e.isActive();
            if (previous != null && !Py.isEmpty(previous.problem) && !e.clearProblem()) {
                row.problem = previous.problem;
                row.problemAt = previous.problemAt;
            }
            result.add(row);
        }
        return result;
    }

    /** The cleaned name, or 400: it must match KEY_ORG_NM in the files and be unique (ignoring case). */
    static String ensureUniquePartnerName(Db db, String name, Integer partnerId) {
        name = Py.squash(name);
        if (name.isEmpty()) throw new ApiError(400, "Enter the partner name exactly as in the KEY_ORG_NM column.");
        for (Partner other : db.all(Partner.class, "WHERE t.id != ?", partnerId == null ? 0 : partnerId)) {
            if (nameKey(other.name).equals(nameKey(name))) throw new ApiError(400, "A partner named \"" + other.name + "\" already exists.");
        }
        return name;
    }

    static Object listPartners(Req req) {
        Auth.currentUser(req);
        Db db = req.db();
        Map<Integer, Integer> pending = new HashMap<>();
        for (ChangeRequest r : db.all(ChangeRequest.class, "WHERE t.entity = 'partner' AND t.status = 'pending'")) {
            if (r.entityId != null) pending.put(r.entityId, r.id);
        }
        List<Object> out = new ArrayList<>();
        for (Partner p : db.all(Partner.class, "ORDER BY t.name")) {
            Map<String, Object> m = partnerToDict(p);
            m.put("pending_request_id", pending.get(p.id));
            out.add(m);
        }
        return out;
    }

    /** With maker-checker on, anyone may ask for a partner change (an admin approves it); else admins only. */
    static User requirePartnerMaker(Req req) {
        User user = Auth.currentUser(req);
        if (!Config.MAKER_CHECKER() && !"admin".equals(user.role)) throw new ApiError(403, "Only admins can do this.");
        return user;
    }

    /** What a partner change compares against (not the refused flags: those change by themselves). */
    static Map<String, Object> partnerSnapshot(Db db, int partnerId) {
        Partner p = db.get(Partner.class, partnerId);
        if (p == null) return null;
        List<Object> emails = new ArrayList<>();
        for (PartnerEmail e : p.emails()) emails.add(Py.map("email", e.email, "is_active", e.isActive));
        return Py.map("name", p.name, "is_active", p.isActive, "emails", emails);
    }

    /** Valid name and addresses (new addresses must accept mail). Nothing is changed. */
    static void checkPartner(Db db, PartnerIn data, Partner partner) {
        ensureUniquePartnerName(db, data.name(), partner != null ? partner.id : null);
        buildPartnerEmails(data.emails(), partner != null ? partner.emails() : List.of());
    }

    static PartnerIn partnerFrom(Map<String, Object> after) {
        Body b = Body.of(after);
        PartnerIn data = readPartner(b);
        b.doneOr400();
        return data;
    }

    static Integer applyPartnerCreate(Db db, Map<String, Object> after, Integer id, String by) {
        PartnerIn data = partnerFrom(after);
        String name = ensureUniquePartnerName(db, data.name(), null);
        List<PartnerEmail> emails = buildPartnerEmails(data.emails(), List.of());
        Partner partner = new Partner();
        partner.name = name;
        partner.isActive = data.isActive();
        db.add(partner);
        db.flush();
        partner.replaceEmails(emails);
        db.flush();
        return partner.id;
    }

    static Integer applyPartnerUpdate(Db db, Map<String, Object> after, Integer partnerId, String by) {
        Partner partner = db.get(Partner.class, partnerId);
        if (partner == null) throw new ApiError(404, "Partner not found");
        PartnerIn data = partnerFrom(after);
        partner.name = ensureUniquePartnerName(db, data.name(), partnerId);
        partner.isActive = data.isActive();
        // replace the email list with what the UI sent (known problems stay with unchanged addresses)
        partner.replaceEmails(buildPartnerEmails(data.emails(), partner.emails()));
        // emails waiting to be sent go to the new addresses
        List<String> toList = new ArrayList<>();
        for (PartnerEmail e : partner.emails()) if (Boolean.TRUE.equals(e.isActive) && Py.isEmpty(e.problem)) toList.add(e.email);
        if (!toList.isEmpty()) {
            for (EmailDispatch d : db.all(EmailDispatch.class, "WHERE t.partner_id = ? AND t.status IN ('PREPARED', 'PENDING', 'FAILED')", partner.id)) {
                d.toEmails = String.join(",", toList);
            }
        }
        db.flush();
        return partner.id;
    }

    static {
        Approvals.register("partner", MainApi::partnerSnapshot,
                Map.of("create", MainApi::applyPartnerCreate, "update", MainApi::applyPartnerUpdate));
    }

    static Object createPartner(Req req) {
        User user = requirePartnerMaker(req);
        Body b = Body.of(req);
        PartnerIn data = readPartner(b);
        b.done();
        Db db = req.db();
        checkPartner(db, data, null);
        Map<String, Object> result = Approvals.submit(db, user, "partner", "create", null, Py.squash(data.name()), data.dump());
        Partner partner = null;
        if (Boolean.TRUE.equals(result.get("applied"))) {  // maker-checker off: created now
            partner = db.get(Partner.class, db.get(ChangeRequest.class, result.get("request_id")).entityId);
        }
        return Py.map("result", result, "partner", partner != null ? partnerToDict(partner) : null);
    }

    static Object updatePartner(Req req) {
        User user = requirePartnerMaker(req);
        int partnerId = req.pathInt("partner_id");
        Body b = Body.of(req);
        PartnerIn data = readPartner(b);
        b.done();
        Db db = req.db();
        Partner partner = db.get(Partner.class, partnerId);
        if (partner == null) throw new ApiError(404, "Partner not found");
        checkPartner(db, data, partner);
        Map<String, Object> result = Approvals.submit(db, user, "partner", "update", partnerId, Py.squash(data.name()), data.dump());
        db.refresh(partner);
        return Py.map("result", result, "partner", partnerToDict(partner));
    }

    // ---------------------------------------------------------------- email templates

    /** Templates belong to the users who prepare the emails; admins only read (and approve) them. */
    static User requireUser(Req req) {
        User user = Auth.currentUser(req);
        if ("admin".equals(user.role)) throw new ApiError(403, "Email templates are managed by users. Admins approve the changes.");
        return user;
    }

    /** The email's own template if it still exists, else the default of its mail type (null: no template). */
    static EmailTemplate templateFor(Db db, String mailType, Integer templateId) {
        EmailTemplate template = templateId != null && templateId != 0 ? db.get(EmailTemplate.class, templateId) : null;
        if (template == null || !template.mailType.equals(mailType) || !"initial".equals(template.purpose)) {
            template = db.first(EmailTemplate.class, "WHERE t.mail_type = ? AND t.purpose = 'initial' AND t.is_default = ?", mailType, true);
        }
        return template;
    }

    /** The default reminder (follow-up) template of a mail type, if there is one. */
    static EmailTemplate reminderTemplateFor(Db db, String mailType) {
        String type = "outward_reminder".equals(mailType) ? "outward" : mailType;
        return db.first(EmailTemplate.class, "WHERE t.mail_type = ? AND t.purpose = 'reminder' AND t.is_default = ?", type, true);
    }

    static List<TemplateVariable> templateVariables(Db db) {
        return db.all(TemplateVariable.class, "ORDER BY t.name");
    }

    static Map<String, Object> templateToDict(EmailTemplate t, Map<String, String> names) {
        return Py.map(
                "id", t.id,
                "name", t.name,
                "mail_type", t.mailType,
                "mail_type_label", EmailService.MAIL_TYPE_NAMES.getOrDefault(t.mailType, t.mailType),
                "purpose", t.purpose,
                "description", t.description,
                "subject", t.subject,
                "body_html", t.bodyHtml,
                "is_default", t.isDefault,
                "created_by_name", name(names, t.createdBy),
                "created_at", Py.iso(t.createdAt),
                "updated_by_name", name(names, t.updatedBy),
                "updated_at", Py.iso(t.updatedAt));
    }

    private static final Pattern BODY_EMPTY = Pattern.compile("<[^>]*>|&nbsp;|\\s", Pattern.UNICODE_CHARACTER_CLASS);

    record TemplateIn(String name, String mailType, String purpose, String description, String subject, String bodyHtml,
                      boolean isDefault) {
        Map<String, Object> dump() {
            return Py.map("name", name, "mail_type", mailType, "purpose", purpose, "description", description,
                    "subject", subject, "body_html", bodyHtml, "is_default", isDefault);
        }
    }

    static TemplateIn readTemplate(Body b) {
        String name = b.check("name", b.str("name"), v -> {
            v = Py.squash(v);
            if (v.isEmpty()) throw new ValueError("Enter a template name");
            if (v.length() > 100) throw new ValueError("Template name is too long (max 100 characters)");
            return v;
        });
        String mailType = b.literal("mail_type", null, "inward", "outward");
        String purpose = b.literal("purpose", "initial", "initial", "reminder");
        String description = b.check("description", b.str("description", ""), v -> {
            v = Py.squash(v);
            if (v.length() > 255) throw new ValueError("Description is too long (max 255 characters)");
            return v;
        });
        String subject = b.check("subject", b.str("subject"), v -> {
            v = Py.squash(v);
            if (v.isEmpty()) throw new ValueError("Subject cannot be empty");
            if (v.length() > 300) throw new ValueError("Subject is too long (max 300 characters)");
            return v;
        });
        String bodyHtml = b.check("body_html", b.str("body_html"), v -> {
            if (BODY_EMPTY.matcher(v).replaceAll("").isEmpty()) throw new ValueError("The email body cannot be empty");
            return Py.strip(v);
        });
        boolean isDefault = b.bool("is_default", false);
        return new TemplateIn(name, mailType, purpose, description, subject, bodyHtml, isDefault);
    }

    static TemplateIn templateFrom(Map<String, Object> after) {
        Body b = Body.of(after);
        TemplateIn data = readTemplate(b);
        b.doneOr400();
        return data;
    }

    static void checkTemplate(Db db, TemplateIn data, Integer templateId) {
        List<String> known = new ArrayList<>();
        for (TemplateVariable v : templateVariables(db)) known.add(v.name);
        if ("reminder".equals(data.purpose())) known.addAll(EmailService.REMINDER_PLACEHOLDERS.keySet());
        List<String> unknown = EmailService.unknownPlaceholders(known, data.subject(), data.bodyHtml());
        if (!unknown.isEmpty()) {
            List<String> shown = new ArrayList<>(), use = new ArrayList<>();
            for (String u : unknown) shown.add("{{" + u + "}}");
            for (String p : EmailService.PLACEHOLDERS.keySet()) use.add("{{" + p + "}}");
            for (String k : known) use.add("{{" + k + "}}");
            throw new ApiError(400, "Unknown placeholder(s): " + String.join(", ", shown) + ". Use: " + String.join(", ", use) + ".");
        }
        for (EmailTemplate other : db.all(EmailTemplate.class, "WHERE t.id != ?", templateId == null ? 0 : templateId)) {
            if (Py.casefold(other.name).equals(Py.casefold(data.name()))) {
                throw new ApiError(400, "A template named \"" + other.name + "\" already exists.");
            }
        }
    }

    /** The only default of its mail type and purpose. */
    static void makeDefault(Db db, EmailTemplate template) {
        for (EmailTemplate other : db.all(EmailTemplate.class, "WHERE t.mail_type = ? AND t.purpose = ? AND t.id != ?",
                template.mailType, template.purpose, template.id)) {
            other.isDefault = false;
        }
        template.isDefault = true;
    }

    static List<Map<String, Object>> listTemplatesDicts(Db db) {
        List<EmailTemplate> templates = db.all(EmailTemplate.class, "ORDER BY t.mail_type, t.purpose, t.is_default DESC, t.name");
        List<String> who = new ArrayList<>();
        for (EmailTemplate t : templates) { who.add(t.createdBy); who.add(t.updatedBy); }
        Map<String, String> names = userNames(db, who);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmailTemplate t : templates) out.add(templateToDict(t, names));
        return out;
    }

    static Map<String, Object> variableToDict(TemplateVariable v) {
        return Py.map("id", v.id, "name", v.name, "description", v.description, "source", v.source, "value", v.value,
                "column", v.column, "aggregate", v.aggregate);
    }

    /** Everything the Email Templates page shows, with the changes still waiting for approval. */
    static Map<String, Object> templatesPayload(Db db, User user, Map<String, Object> result) {
        List<ChangeRequest> pending = db.all(ChangeRequest.class, "WHERE t.entity IN ('template', 'variable') AND t.status = 'pending' ORDER BY t.id");
        List<String> who = new ArrayList<>();
        for (ChangeRequest r : pending) who.add(r.requestedBy);
        Map<String, String> names = userNames(db, who);
        List<Object> placeholders = new ArrayList<>(), reminders = new ArrayList<>(), variables = new ArrayList<>(), pendingDicts = new ArrayList<>();
        EmailService.PLACEHOLDERS.forEach((k, v) -> placeholders.add(Py.map("name", k, "description", v)));
        EmailService.REMINDER_PLACEHOLDERS.forEach((k, v) -> reminders.add(Py.map("name", k, "description", v)));
        for (TemplateVariable v : templateVariables(db)) variables.add(variableToDict(v));
        for (ChangeRequest r : pending) pendingDicts.add(Approvals.toDict(r, names, user));
        return Py.map(
                "templates", listTemplatesDicts(db),
                "placeholders", placeholders,
                "reminder_placeholders", reminders,
                "variables", variables,
                "variable_columns", new ArrayList<>(EmailService.VARIABLE_COLUMNS.keySet()),
                "variable_aggregates", EmailService.VARIABLE_AGGREGATES,
                "pending", pendingDicts,
                "maker_checker", Config.MAKER_CHECKER(),
                "result", result);
    }

    static Map<String, Object> templateSnapshot(Db db, int templateId) {
        EmailTemplate t = db.get(EmailTemplate.class, templateId);
        if (t == null) return null;
        return Py.map("name", t.name, "mail_type", t.mailType, "purpose", t.purpose, "description", t.description,
                "subject", t.subject, "body_html", t.bodyHtml, "is_default", t.isDefault);
    }

    static void checkTemplateChange(Db db, EmailTemplate template, TemplateIn data) {
        checkTemplate(db, data, template.id);
        if (Boolean.TRUE.equals(template.isDefault) && (!data.mailType().equals(template.mailType) || !data.purpose().equals(template.purpose))) {
            throw new ApiError(400, "This is the default template of its mail type. "
                    + "Make another template the default first, then change its mail type.");
        }
        if (Boolean.TRUE.equals(template.isDefault) && !data.isDefault()) {
            throw new ApiError(400, "Every mail type needs a default template. Make another template the default instead.");
        }
    }

    static Integer applyTemplateCreate(Db db, Map<String, Object> after, Integer id, String by) {
        TemplateIn data = templateFrom(after);
        checkTemplate(db, data, null);
        EmailTemplate template = new EmailTemplate();
        template.name = data.name();
        template.mailType = data.mailType();
        template.purpose = data.purpose();
        template.description = data.description();
        template.subject = data.subject();
        template.bodyHtml = data.bodyHtml();
        template.createdBy = by;
        template.updatedBy = by;
        db.add(template);
        db.flush();
        long hasDefault = db.count("SELECT COUNT(*) FROM email_templates WHERE mail_type = ? AND purpose = ? AND is_default = ? AND id != ?",
                data.mailType(), data.purpose(), true, template.id);
        if (data.isDefault() || hasDefault == 0) makeDefault(db, template);  // the first template of a mail type is its default
        return template.id;
    }

    static EmailTemplate getTemplateOr404(Db db, int templateId) {
        EmailTemplate template = db.get(EmailTemplate.class, templateId);
        if (template == null) throw new ApiError(404, "Template not found");
        return template;
    }

    static Integer applyTemplateUpdate(Db db, Map<String, Object> after, Integer templateId, String by) {
        EmailTemplate template = getTemplateOr404(db, templateId);
        TemplateIn data = templateFrom(after);
        checkTemplateChange(db, template, data);
        template.name = data.name();
        template.mailType = data.mailType();
        template.purpose = data.purpose();
        template.description = data.description();
        template.subject = data.subject();
        template.bodyHtml = data.bodyHtml();
        template.updatedBy = by;
        if (data.isDefault()) makeDefault(db, template);
        return template.id;
    }

    static Integer applyTemplateDefault(Db db, Map<String, Object> after, Integer templateId, String by) {
        EmailTemplate template = getTemplateOr404(db, templateId);
        makeDefault(db, template);
        template.updatedBy = by;
        return template.id;
    }

    static void checkTemplateDelete(EmailTemplate template) {
        if (Boolean.TRUE.equals(template.isDefault)) {
            throw new ApiError(400, "The default template cannot be deleted. Make another template the default first.");
        }
    }

    /** Drafts that used it go back to the default template the next time previews are refreshed. */
    static Integer applyTemplateDelete(Db db, Map<String, Object> after, Integer templateId, String by) {
        EmailTemplate template = getTemplateOr404(db, templateId);
        checkTemplateDelete(template);
        db.execute("UPDATE email_dispatches SET template_id = NULL WHERE template_id = ?", template.id);
        db.delete(template);
        return templateId;
    }

    static {
        Approvals.register("template", MainApi::templateSnapshot, Map.of(
                "create", MainApi::applyTemplateCreate, "update", MainApi::applyTemplateUpdate,
                "default", MainApi::applyTemplateDefault, "delete", MainApi::applyTemplateDelete));
    }

    static Object listTemplates(Req req) {
        User user = Auth.currentUser(req);
        return templatesPayload(req.db(), user, null);
    }

    static Object createTemplate(Req req) {
        User user = requireUser(req);
        Body b = Body.of(req);
        TemplateIn data = readTemplate(b);
        b.done();
        Db db = req.db();
        checkTemplate(db, data, null);
        Map<String, Object> result = Approvals.submit(db, user, "template", "create", null, data.name(), data.dump());
        return templatesPayload(db, user, result);
    }

    static Object updateTemplate(Req req) {
        User user = requireUser(req);
        int templateId = req.pathInt("template_id");
        Body b = Body.of(req);
        TemplateIn data = readTemplate(b);
        b.done();
        Db db = req.db();
        EmailTemplate template = getTemplateOr404(db, templateId);
        checkTemplateChange(db, template, data);
        Map<String, Object> result = Approvals.submit(db, user, "template", "update", templateId, data.name(), data.dump());
        return templatesPayload(db, user, result);
    }

    static Object setDefaultTemplate(Req req) {
        User user = requireUser(req);
        int templateId = req.pathInt("template_id");
        Db db = req.db();
        EmailTemplate template = getTemplateOr404(db, templateId);
        Map<String, Object> result = Approvals.submit(db, user, "template", "default", templateId, template.name, new LinkedHashMap<>());
        return templatesPayload(db, user, result);
    }

    static Object deleteTemplate(Req req) {
        User user = requireUser(req);
        int templateId = req.pathInt("template_id");
        Db db = req.db();
        EmailTemplate template = getTemplateOr404(db, templateId);
        checkTemplateDelete(template);
        Map<String, Object> result = Approvals.submit(db, user, "template", "delete", templateId, template.name, new LinkedHashMap<>());
        return templatesPayload(db, user, result);
    }

    // ---------------------------------------------------------------- template variables

    record VariableIn(String name, String description, String source, String value, String column, String aggregate) {
        Map<String, Object> dump() {
            return Py.map("name", name, "description", description, "source", source, "value", value, "column", column, "aggregate", aggregate);
        }
    }

    static VariableIn readVariable(Body b) {
        String name = b.check("name", b.str("name"), v -> {
            v = Py.strip(Py.strip(Py.strip(v), "{}"));
            if (!EmailService.VARIABLE_NAME_RE.matcher(v).find()) {
                throw new ValueError("Name: 2-40 lower-case letters, digits or _, starting with a letter (e.g. rrn_list)");
            }
            if (EmailService.PLACEHOLDERS.containsKey(v) || EmailService.REMINDER_PLACEHOLDERS.containsKey(v)) {
                throw new ValueError("{{" + v + "}} is already a built-in placeholder");
            }
            return v;
        });
        String description = b.check("description", b.str("description", ""), v -> {
            v = Py.squash(v);
            if (v.length() > 255) throw new ValueError("Description is too long (max 255 characters)");
            return v;
        });
        String source = b.literal("source", null, "text", "column");
        String value = b.check("value", b.str("value", ""), v -> {
            if (v.length() > 2000) throw new ValueError("The text is too long (max 2000 characters)");
            return v;
        });
        String column = b.optStr("column");
        String aggregate = b.optStr("aggregate");
        return new VariableIn(name, description, source, value, column, aggregate);
    }

    static VariableIn variableFrom(Map<String, Object> after) {
        Body b = Body.of(after);
        VariableIn data = readVariable(b);
        b.doneOr400();
        return data;
    }

    static void checkVariable(Db db, VariableIn data, Integer variableId) {
        if ("column".equals(data.source())) {
            if (data.column() == null || !EmailService.VARIABLE_COLUMNS.containsKey(data.column())) {
                throw new ApiError(400, "Choose a column of the uploaded file.");
            }
            if (data.aggregate() == null || !EmailService.VARIABLE_AGGREGATES.containsKey(data.aggregate())) {
                throw new ApiError(400, "Choose how the values are combined.");
            }
            if ("sum".equals(data.aggregate()) && !"TXN_AMOUNT".equals(data.column())) throw new ApiError(400, "Only TXN_AMOUNT can be added up.");
        }
        TemplateVariable other = db.first(TemplateVariable.class, "WHERE t.name = ? AND t.id != ?", data.name(), variableId == null ? 0 : variableId);
        if (other != null) throw new ApiError(400, "A variable named {{" + data.name() + "}} already exists.");
    }

    static List<String> templatesUsing(Db db, String name) {
        Pattern pattern = Pattern.compile("\\{\\{\\s*" + Pattern.quote(name) + "\\s*\\}\\}", Pattern.UNICODE_CHARACTER_CLASS);
        List<String> out = new ArrayList<>();
        for (EmailTemplate t : db.all(EmailTemplate.class, "ORDER BY t.id")) {
            if (pattern.matcher(t.subject).find() || pattern.matcher(t.bodyHtml).find()) out.add(t.name);
        }
        return out;
    }

    static Map<String, Object> variableSnapshot(Db db, int variableId) {
        TemplateVariable v = db.get(TemplateVariable.class, variableId);
        if (v == null) return null;
        Map<String, Object> m = variableToDict(v);
        m.remove("id");
        return m;
    }

    static TemplateVariable getVariableOr404(Db db, int variableId) {
        TemplateVariable v = db.get(TemplateVariable.class, variableId);
        if (v == null) throw new ApiError(404, "Variable not found");
        return v;
    }

    static void checkVariableChange(Db db, TemplateVariable v, VariableIn data) {
        checkVariable(db, data, v.id);
        if (!data.name().equals(v.name)) {
            List<String> used = templatesUsing(db, v.name);
            if (!used.isEmpty()) {
                throw new ApiError(400, "{{" + v.name + "}} is used in: " + String.join(", ", used) + ". "
                        + "It can't be renamed; create a new variable instead.");
            }
        }
    }

    static Integer applyVariableCreate(Db db, Map<String, Object> after, Integer id, String by) {
        VariableIn data = variableFrom(after);
        checkVariable(db, data, null);
        TemplateVariable v = new TemplateVariable();
        v.name = data.name();
        v.description = data.description();
        v.source = data.source();
        v.value = "text".equals(data.source()) ? data.value() : "";
        v.column = "column".equals(data.source()) ? data.column() : null;
        v.aggregate = "column".equals(data.source()) ? data.aggregate() : null;
        v.createdBy = by;
        db.add(v);
        db.flush();
        return v.id;
    }

    static Integer applyVariableUpdate(Db db, Map<String, Object> after, Integer variableId, String by) {
        TemplateVariable v = getVariableOr404(db, variableId);
        VariableIn data = variableFrom(after);
        checkVariableChange(db, v, data);
        v.name = data.name();
        v.description = data.description();
        v.source = data.source();
        v.value = "text".equals(data.source()) ? data.value() : "";
        v.column = "column".equals(data.source()) ? data.column() : null;
        v.aggregate = "column".equals(data.source()) ? data.aggregate() : null;
        return v.id;
    }

    static void checkVariableDelete(Db db, TemplateVariable v) {
        List<String> used = templatesUsing(db, v.name);
        if (!used.isEmpty()) {
            throw new ApiError(400, "{{" + v.name + "}} is used in: " + String.join(", ", used) + ". Remove it from those templates first.");
        }
    }

    static Integer applyVariableDelete(Db db, Map<String, Object> after, Integer variableId, String by) {
        TemplateVariable v = getVariableOr404(db, variableId);
        checkVariableDelete(db, v);
        db.delete(v);
        return variableId;
    }

    static {
        Approvals.register("variable", MainApi::variableSnapshot, Map.of(
                "create", MainApi::applyVariableCreate, "update", MainApi::applyVariableUpdate, "delete", MainApi::applyVariableDelete));
    }

    static Object createVariable(Req req) {
        User user = requireUser(req);
        Body b = Body.of(req);
        VariableIn data = readVariable(b);
        b.done();
        Db db = req.db();
        checkVariable(db, data, null);
        Map<String, Object> result = Approvals.submit(db, user, "variable", "create", null, "{{" + data.name() + "}}", data.dump());
        return templatesPayload(db, user, result);
    }

    static Object updateVariable(Req req) {
        User user = requireUser(req);
        int variableId = req.pathInt("variable_id");
        Body b = Body.of(req);
        VariableIn data = readVariable(b);
        b.done();
        Db db = req.db();
        TemplateVariable v = getVariableOr404(db, variableId);
        checkVariableChange(db, v, data);
        Map<String, Object> result = Approvals.submit(db, user, "variable", "update", variableId, "{{" + data.name() + "}}", data.dump());
        return templatesPayload(db, user, result);
    }

    static Object deleteVariable(Req req) {
        User user = requireUser(req);
        int variableId = req.pathInt("variable_id");
        Db db = req.db();
        TemplateVariable v = getVariableOr404(db, variableId);
        checkVariableDelete(db, v);
        Map<String, Object> result = Approvals.submit(db, user, "variable", "delete", variableId, "{{" + v.name + "}}", new LinkedHashMap<>());
        return templatesPayload(db, user, result);
    }

    // ---------------------------------------------------------------- uploads

    static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final Map<String, String> ROW_KINDS = Map.of("valid", "successful", "faulty", "skipped");

    static Object downloadUploadTemplate(Req req) {
        Auth.currentUser(req);
        return Res.file(ExcelParser.buildTemplate(), XLSX_TYPE, "attachment; filename=\"dispute_transactions_template.xlsx\"");
    }

    static final List<String> REPORTED_STATUSES = List.of("PENDING", "SENDING", "SENT");  // with an admin or gone out

    /** (partner, RRN) -> the email that reported it within the duplicate window (Email Configuration). */
    static Map<List<String>, EmailDispatch> reportedTransactions(Db db, Integer excludeUploadId) {
        int days = Settings.getInt("duplicate_window_days");
        Map<List<String>, EmailDispatch> reported = new HashMap<>();
        if (days == 0) return reported;
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        for (EmailDispatch d : db.all(EmailDispatch.class, "WHERE t.status IN ('PENDING', 'SENDING', 'SENT') AND t.upload_id != ? "
                        + "AND (t.sent_at >= ? OR (t.sent_at IS NULL AND t.submitted_at >= ?)) ORDER BY t.id",
                excludeUploadId == null ? 0 : excludeUploadId, since, since)) {
            for (Transaction t : dispatchTransactions(d)) reported.putIfAbsent(List.of(nameKey(t.partnerName), t.rrn), d);
        }
        return reported;
    }

    static List<Object> duplicatesOf(Db db, Upload upload) {
        List<Transaction> rows = new ArrayList<>();
        for (Transaction t : upload.transactions()) if (t.duplicateOf != null && t.duplicateOf != 0) rows.add(t);
        Map<Integer, EmailDispatch> emails = new HashMap<>();
        if (!rows.isEmpty()) {
            Set<Integer> ids = new LinkedHashSet<>();
            for (Transaction t : rows) ids.add(t.duplicateOf);
            for (EmailDispatch d : db.all(EmailDispatch.class, "WHERE t.id IN " + Db.in(ids), ids.toArray())) emails.put(d.id, d);
        }
        rows.sort(Comparator.comparing(t -> t.excelRow));
        List<Object> out = new ArrayList<>();
        for (Transaction t : rows) {
            EmailDispatch d = emails.get(t.duplicateOf);
            LocalDateTime when = d == null ? null : d.sentAt != null ? d.sentAt : d.submittedAt;
            out.add(Py.map("excel_row", t.excelRow, "rrn", t.rrn, "partner_name", t.partnerName, "mail_type", t.mailType,
                    "amount", Py.toDouble(t.amount), "email_id", t.duplicateOf, "email_status", d != null ? d.status : null,
                    "email_date", Py.iso(when), "email_upload", d != null ? d.upload().filename : null));
        }
        return out;
    }

    /** What the Home page shows about an upload (rowErrors: read again from the stored file if not given). */
    static Map<String, Object> uploadSummary(Db db, Upload upload, List<Map<String, Object>> rowErrors) {
        if (rowErrors == null) {
            rowErrors = new ArrayList<>();
            byte[] content = upload.content();
            if (content != null && content.length > 0) {
                try {
                    rowErrors = ExcelParser.parseTransactions(upload.filename, content).rowErrors();
                } catch (ExcelParser.FileValidationError ignored) {
                    // the stored file can't be read any more: no row errors shown
                }
            }
        }
        List<Transaction> txns = upload.transactions();
        List<Object> duplicates = duplicatesOf(db, upload);
        int inward = 0, outward = 0;
        for (Transaction t : txns) {
            boolean dup = t.duplicateOf != null && t.duplicateOf != 0;
            if ("inward".equals(t.mailType) && !dup) inward++;
            if ("outward".equals(t.mailType) && !dup) outward++;
        }
        return Py.map(
                "upload_id", upload.id,
                "filename", upload.filename,
                "uploaded_at", Py.iso(upload.uploadedAt),
                "total_rows", upload.totalRows,
                "valid_rows", upload.validRows,
                "inward_rows", inward,
                "outward_rows", outward,
                "skipped_rows", rowErrors.size(),
                "row_errors", rowErrors,
                "duplicate_rows", duplicates.size(),
                "duplicates", duplicates,
                "duplicate_window_days", Settings.get("duplicate_window_days"));
    }

    static Object uploadFile(Req req) throws IOException {
        User user = Auth.currentUser(req);
        Part file = req.file("file");
        byte[] content;
        try (InputStream in = file.getInputStream()) {
            content = in.readAllBytes();
        }
        String filename = file.getSubmittedFileName();
        ExcelParser.Parsed parsed;
        try {
            parsed = ExcelParser.parseTransactions(filename, content);
        } catch (ExcelParser.FileValidationError exc) {
            // whole file rejected -> nothing is saved
            throw new ApiError(400, Py.map("message", "File rejected", "errors", exc.errors));
        }
        Db db = req.db();
        Upload upload = new Upload();
        upload.filename = filename;
        upload.totalRows = parsed.totalRows();
        upload.validRows = parsed.validRows().size();
        upload.fileContent = content;
        upload.uploadedBy = user.username;
        // the same transaction reported to the partner recently (another upload): left out of the new emails
        Map<List<String>, EmailDispatch> reported = reportedTransactions(db, null);
        for (Transaction t : parsed.validRows()) {
            EmailDispatch d = reported.get(List.of(nameKey(t.partnerName), t.rrn));
            if (d != null) t.duplicateOf = d.id;
        }
        db.add(upload);
        db.flush();
        for (Transaction t : parsed.validRows()) {
            t.uploadId = upload.id;
            db.add(t);
        }
        db.commit();
        return uploadSummary(db, upload, parsed.rowErrors());
    }

    static Object listUploads(Req req) {
        Auth.currentUser(req);
        Db db = req.db();
        List<Upload> uploads = db.all(Upload.class, "ORDER BY t.id DESC LIMIT 100");
        List<Integer> ids = new ArrayList<>();
        for (Upload u : uploads) ids.add(u.id);
        if (ids.isEmpty()) ids.add(0);
        Map<Integer, Map<String, Object>> counts = new HashMap<>();
        for (Object[] row : db.rows("SELECT upload_id, status, COUNT(id) FROM email_dispatches WHERE upload_id IN " + Db.in(ids)
                + " GROUP BY upload_id, status", ids.toArray())) {
            counts.computeIfAbsent(((Number) row[0]).intValue(), k -> new LinkedHashMap<>()).put((String) row[1], ((Number) row[2]).longValue());
        }
        Map<Integer, Long> dups = new HashMap<>();
        for (Object[] row : db.rows("SELECT upload_id, COUNT(id) FROM transactions WHERE upload_id IN " + Db.in(ids)
                + " AND duplicate_of IS NOT NULL GROUP BY upload_id", ids.toArray())) {
            dups.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        List<String> who = new ArrayList<>();
        for (Upload u : uploads) who.add(u.uploadedBy);
        Map<String, String> names = userNames(db, who);
        List<Object> out = new ArrayList<>();
        for (Upload u : uploads) {
            out.add(Py.map("id", u.id, "filename", u.filename, "uploaded_at", Py.iso(u.uploadedAt),
                    "uploaded_by_name", name(names, u.uploadedBy), "total_rows", u.totalRows,
                    "valid_rows", u.validRows, "duplicate_rows", dups.getOrDefault(u.id, 0L), "emails", counts.getOrDefault(u.id, new LinkedHashMap<>())));
        }
        return out;
    }

    static Object getUploadSummary(Req req) {
        Auth.currentUser(req);
        int uploadId = req.pathInt("upload_id");
        Db db = req.db();
        return uploadSummary(db, getUploadOr404(db, uploadId), null);
    }

    static Object downloadUploadRows(Req req) throws ExcelParser.FileValidationError {
        Auth.currentUser(req);
        int uploadId = req.pathInt("upload_id");
        String kind = req.path("kind");
        if (!ROW_KINDS.containsKey(kind)) throw new ApiError(404, "Unknown row type. Use 'valid' or 'faulty'.");
        Db db = req.db();
        Upload upload = getUploadOr404(db, uploadId);
        byte[] content = upload.content();
        if (content == null) throw new ApiError(404, "The original file was not kept for this upload. Upload it again to download rows.");
        ExcelParser.Table[] split = ExcelParser.splitRows(upload.filename, content);
        String base = upload.filename.replaceAll("(?i)\\.(xlsx|csv)$", "");
        String filename = (base + "_" + ROW_KINDS.get(kind) + ".xlsx").replaceAll("[^\\w.\\- ]", "_");
        ExcelParser.Table table = kind.equals("valid") ? split[0] : split[1];
        return Res.file(ExcelParser.toXlsx(table, "Transactions"), XLSX_TYPE, "attachment; filename=\"" + filename + "\"");
    }

    static Object getUploadGroups(Req req) {
        Auth.currentUser(req);
        int uploadId = req.pathInt("upload_id");
        Db db = req.db();
        Upload upload = getUploadOr404(db, uploadId);
        Map<String, Partner> partners = partnersByName(db);
        List<Object> groups = new ArrayList<>();
        for (Map.Entry<List<String>, List<Transaction>> e : groupByPartner(upload).entrySet()) {
            String key = e.getKey().get(0), mailType = e.getKey().get(1);
            List<Transaction> txns = e.getValue();
            Partner partner = partners.get(key);
            double total = 0;
            List<Object> rows = new ArrayList<>();
            for (Transaction t : txns) {
                total += Py.toDouble(t.amount);
                rows.add(Py.map(
                        "excel_row", t.excelRow,
                        "rrn", t.rrn,
                        "approval_datetime", Py.or(t.approvalDatetimeText, Py.strftime(t.txnDatetime, "%d-%m-%Y %H:%M:%S")),
                        "txn_type", t.txnType,
                        "txn_id", t.txnId,
                        "status", t.status,
                        "amount", Py.toDouble(t.amount),
                        "from_account_name", t.fromAccountName,
                        "to_account_type", t.toAccountType,
                        "stan", t.stan,
                        "org_code", t.orgCode));
            }
            groups.add(Py.map(
                    "key", key + "|" + mailType,
                    "mail_type", mailType,
                    "mail_type_label", EmailService.MAIL_TYPES.get(mailType),
                    "partner_name", partner != null ? partner.name : txns.get(0).partnerName,
                    "partner_configured", partner != null,
                    "txn_count", txns.size(),
                    "total_amount", round2(total),
                    "transactions", rows));
        }
        return Py.map(
                "upload_id", upload.id,
                "filename", upload.filename,
                "total_rows", upload.totalRows,
                "valid_rows", upload.validRows,
                "groups", groups,
                "duplicates", duplicatesOf(db, upload),
                "duplicate_window_days", Settings.get("duplicate_window_days"));
    }

    /** round(x, 2) (the exact binary value, rounded like Python). */
    static double round2(double x) {
        return new java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    // ---------------------------------------------------------------- emails

    static Object prepareEmails(Req req) {
        Auth.currentUser(req);
        int uploadId = req.pathInt("upload_id");
        Body b = Body.optional(req);
        Map<String, Integer> templates = new LinkedHashMap<>();
        if (b != null) {
            Map<String, Object> raw = b.dict("templates", new LinkedHashMap<>());
            if (raw != null) {
                List<Map<String, Object>> errors = new ArrayList<>();
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    if (!e.getKey().equals("inward") && !e.getKey().equals("outward")) {
                        errors.add(Py.map("type", "literal_error", "loc", List.of("body", "templates", e.getKey(), "[key]"),
                                "msg", "Input should be 'inward' or 'outward'", "input", e.getKey()));
                        continue;
                    }
                    Integer id = intOf(e.getValue());
                    if (id == null) {
                        errors.add(Py.map("type", "int_parsing", "loc", List.of("body", "templates", e.getKey()),
                                "msg", "Input should be a valid integer, unable to parse string as an integer", "input", e.getValue()));
                        continue;
                    }
                    templates.put(e.getKey(), id);
                }
                if (!errors.isEmpty()) throw new com.nagad.npsb.web.ValidationError(errors);
            }
            b.done();
        }
        Db db = req.db();
        Upload upload = getUploadOr404(db, uploadId);
        Map<String, Partner> partners = partnersByName(db);
        Map<String, EmailTemplate> chosen = new HashMap<>();
        for (Map.Entry<String, Integer> e : templates.entrySet()) {
            EmailTemplate template = db.get(EmailTemplate.class, e.getValue());
            if (template == null || !template.mailType.equals(e.getKey()) || !"initial".equals(template.purpose)) {
                throw new ApiError(400, "Pick a " + EmailService.MAIL_TYPES.get(e.getKey()) + " template (it may have been deleted).");
            }
            chosen.put(e.getKey(), template);
        }

        List<String> errors = new ArrayList<>();
        List<String> notices = new ArrayList<>();  // emails prepared, but some addresses were left out
        List<TemplateVariable> variables = templateVariables(db);
        for (Map.Entry<List<String>, List<Transaction>> g : groupByPartner(upload).entrySet()) {
            String key = g.getKey().get(0), mailType = g.getKey().get(1);
            List<Transaction> txns = g.getValue();
            Partner partner = partners.get(key);

            // --- checks: skip this partner but continue with the others
            if (partner == null) {
                errors.add(txns.get(0).partnerName + " – " + EmailService.MAIL_TYPES.get(mailType) + ": no partner with this name in "
                        + "Partner Configuration. No email prepared.");
                continue;
            }
            String label = partner.name + " – " + EmailService.MAIL_TYPES.get(mailType);
            if (!Boolean.TRUE.equals(partner.isActive)) {
                errors.add(label + ": partner is inactive. No email prepared.");
                continue;
            }
            List<PartnerEmail> active = new ArrayList<>();
            for (PartnerEmail e : partner.emails()) if (Boolean.TRUE.equals(e.isActive)) active.add(e);
            List<String> toList = new ArrayList<>(), problems = new ArrayList<>();
            for (PartnerEmail e : active) {
                if (Py.isEmpty(e.problem)) toList.add(e.email);
                else problems.add(e.email + " (" + e.problem + ")");
            }
            if (active.isEmpty()) {
                errors.add(label + ": no active email address configured. No email prepared.");
                continue;
            }
            if (toList.isEmpty()) {
                errors.add(label + ": every active address failed before: " + String.join("; ", problems) + ". No email prepared.");
                continue;
            }

            EmailDispatch dispatch = db.first(EmailDispatch.class, "WHERE t.upload_id = ? AND t.partner_id = ? AND t.mail_type = ?",
                    upload.id, partner.id, mailType);
            if (dispatch != null && !"PREPARED".equals(dispatch.status)) continue;  // with an admin or already gone out: never rebuilt

            boolean isNew = dispatch == null;
            if (isNew) {
                dispatch = new EmailDispatch();
                dispatch.uploadId = upload.id;
                dispatch.partnerId = partner.id;
                dispatch.mailType = mailType;
                db.add(dispatch);
            }
            if (chosen.containsKey(mailType)) {
                dispatch.templateId = chosen.get(mailType).id;
                dispatch.isEdited = false;
            }
            if (!Boolean.TRUE.equals(dispatch.isEdited)) {  // keep the user's manual edits
                EmailTemplate template = templateFor(db, mailType, dispatch.templateId);
                dispatch.templateId = template != null ? template.id : null;
                String[] built = EmailService.buildEmail(partner.name, mailType, txns, template, variables);
                dispatch.subject = built[0];
                dispatch.bodyHtml = built[1];
                dispatch.isEdited = false;
            }
            dispatch.toEmails = String.join(",", toList);
            dispatch.ccEmails = String.join(",", Settings.getList("cc_emails"));
            dispatch.txnCount = txns.size();
            double total = 0;
            for (Transaction t : txns) total += Py.toDouble(t.amount);
            // the float total, stored in NUMERIC(14, 2) as MySQL rounds it
            dispatch.totalAmount = new java.math.BigDecimal(Py.floatRepr(total)).setScale(2, java.math.RoundingMode.HALF_UP);
            dispatch.status = "PREPARED";
            dispatch.error = null;
            dispatch.deliveryIssues = null;
            if (!problems.isEmpty()) notices.add(label + ": left out " + String.join("; ", problems) + ".");
        }
        db.commit();
        return Py.map("errors", errors, "notices", notices, "emails", listEmails(db, uploadId));
    }

    static Integer intOf(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        if (v instanceof Double d && d == Math.rint(d)) return d.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(Py.strip(s));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static List<Map<String, Object>> listEmails(Db db, int uploadId) {
        getUploadOr404(db, uploadId);
        return dispatchesToDicts(db, db.all(EmailDispatch.class, "WHERE t.upload_id = ? ORDER BY t.id", uploadId));
    }

    /** Drafts (PREPARED) can be changed by anyone; once sent for approval, only by admins. */
    static EmailDispatch getEditableDispatchOrError(Db db, int emailId, User user) {
        EmailDispatch dispatch = db.get(EmailDispatch.class, emailId);
        if (dispatch == null) throw new ApiError(404, "Email not found");
        if ("SENT".equals(dispatch.status)) throw new ApiError(400, "This email has already been sent and can no longer be edited.");
        if ("SENDING".equals(dispatch.status)) throw new ApiError(409, "This email is being sent right now and can no longer be edited.");
        if (!"PREPARED".equals(dispatch.status) && !"admin".equals(user.role)) {
            throw new ApiError(403, "This email is waiting for an admin. Only admins can change it now.");
        }
        return dispatch;
    }

    static Object downloadAttachment(Req req) {
        Auth.currentUser(req);
        int emailId = req.pathInt("email_id");
        EmailDispatch d = req.db().get(EmailDispatch.class, emailId);
        if (d == null) throw new ApiError(404, "Email not found");
        List<EmailService.Attachment> attached = attachmentFor(d);
        if (attached.isEmpty()) throw new ApiError(404, "This email has no transactions to attach.");
        EmailService.Attachment a = attached.get(0);
        return Res.file(a.data(), XLSX_TYPE, "attachment; filename=\"" + a.filename() + "\"");
    }

    static Object editEmail(Req req) {
        User user = Auth.currentUser(req);
        int emailId = req.pathInt("email_id");
        Body b = Body.of(req);
        String subject = b.check("subject", b.str("subject"), v -> {
            v = Py.strip(v);
            if (v.isEmpty()) throw new ValueError("Subject cannot be empty");
            if (v.length() > 300) throw new ValueError("Subject is too long (max 300 characters)");
            return v;
        });
        String bodyHtml = b.str("body_html");
        b.done();
        Db db = req.db();
        EmailDispatch dispatch = getEditableDispatchOrError(db, emailId, user);
        dispatch.subject = subject;
        dispatch.bodyHtml = bodyHtml;
        dispatch.isEdited = true;
        db.commit();
        return dispatchToDict(dispatch, null);
    }

    /**
     * Throw away manual edits. A draft is regenerated by the next prepare-emails call; an email waiting for an
     * admin is regenerated right away (prepare-emails no longer touches it).
     */
    static Object discardEmailEdits(Req req) {
        User user = Auth.currentUser(req);
        int emailId = req.pathInt("email_id");
        Db db = req.db();
        EmailDispatch dispatch = getEditableDispatchOrError(db, emailId, user);
        dispatch.isEdited = false;
        if (!"PREPARED".equals(dispatch.status)) {
            List<Transaction> txns = dispatchTransactions(dispatch);
            if (!txns.isEmpty()) {
                EmailTemplate template = templateFor(db, dispatch.mailType, dispatch.templateId);
                String[] built = EmailService.buildEmail(dispatch.partner().name, dispatch.mailType, txns, template, List.of());
                dispatch.subject = built[0];
                dispatch.bodyHtml = built[1];
            }
        }
        db.commit();
        return dispatchToDict(dispatch, null);
    }

    static Object applyTemplate(Req req) {
        User user = Auth.currentUser(req);
        int emailId = req.pathInt("email_id");
        Body b = Body.of(req);
        Integer templateId = b.integer("template_id");
        b.done();
        Db db = req.db();
        EmailDispatch dispatch = getEditableDispatchOrError(db, emailId, user);
        EmailTemplate template = db.get(EmailTemplate.class, templateId);
        if (template == null) throw new ApiError(404, "Template not found. It may have been deleted.");
        if (!template.mailType.equals(dispatch.mailType)) {
            throw new ApiError(400, "\"" + template.name + "\" is a " + EmailService.MAIL_TYPE_NAMES.get(template.mailType) + " template; "
                    + "this email is " + EmailService.MAIL_TYPE_NAMES.getOrDefault(dispatch.mailType, dispatch.mailType) + ".");
        }
        List<Transaction> txns = dispatchTransactions(dispatch);
        if (txns.isEmpty()) throw new ApiError(400, "This email has no transactions to build it from.");
        dispatch.templateId = template.id;
        String[] built = EmailService.buildEmail(dispatch.partner().name, dispatch.mailType, txns, template, List.of());
        dispatch.subject = built[0];
        dispatch.bodyHtml = built[1];
        dispatch.isEdited = false;
        db.commit();
        return dispatchToDict(dispatch, null);
    }

    static Object submitEmails(Req req) {
        User user = Auth.currentUser(req);
        int uploadId = req.pathInt("upload_id");
        Db db = req.db();
        getUploadOr404(db, uploadId);
        List<EmailDispatch> drafts = db.all(EmailDispatch.class, "WHERE t.upload_id = ? AND t.status = 'PREPARED' ORDER BY t.id", uploadId);
        if (drafts.isEmpty()) throw new ApiError(400, "There are no prepared emails to send.");
        LocalDateTime now = Py.now();
        for (EmailDispatch d : drafts) {
            d.status = "PENDING";
            d.submittedBy = user.username;
            d.submittedAt = now;
            EmailEvent e = new EmailEvent();
            e.dispatchId = d.id;
            e.kind = "submitted";
            e.subject = d.subject;
            e.createdBy = user.username;
            e.createdAt = now;
            db.add(e);
        }
        db.commit();
        return Py.map("submitted", drafts.size(), "emails", listEmails(db, uploadId));
    }

    /** An admin's claim on an email: if the server dies mid-send, another admin may claim it after this long */
    static final long CLAIM_EXPIRES_MINUTES = 10;

    /** PENDING and FAILED emails, plus SENDING ones (another admin is sending them right now). */
    static List<EmailDispatch> pendingQuery(Db db) {
        return db.all(EmailDispatch.class, "WHERE t.status IN ('PENDING', 'FAILED', 'SENDING') ORDER BY t.submitted_at, t.id");
    }

    static List<Map<String, Object>> listPendingEmails(Db db) {
        return dispatchesToDicts(db, pendingQuery(db));
    }

    /**
     * Mark one email as SENDING by this admin, in a single UPDATE. When two admins send the same email at the
     * same moment, the database lets only one UPDATE match, so only one of them sends it.
     */
    static boolean claim(Db db, int emailId, User admin) {
        LocalDateTime now = Py.now();
        int n = db.execute("UPDATE email_dispatches SET status = 'SENDING', sent_by = ?, sending_since = ? WHERE id = ? AND "
                        + "(status IN ('PENDING', 'FAILED') OR (status = 'SENDING' AND sending_since < ?))",
                admin.username, now, emailId, now.minusMinutes(CLAIM_EXPIRES_MINUTES));
        db.commit();
        return n == 1;
    }

    /** Send one email this admin has claimed, and record the result. Saved right away, so a crash never loses a "SENT". */
    static void deliver(Db db, EmailDispatch d, User admin) {
        try {
            EmailService.SendResult result = EmailService.sendEmail(Py.splitOn(d.toEmails, ","), Py.csv(d.ccEmails), d.subject,
                    d.bodyHtml, null, attachmentFor(d), null);
            d.status = "SENT";
            d.sentAt = Py.now();
            d.sentBy = admin.username;
            d.error = null;
            d.attempts += result.attempts();
            d.messageId = result.messageId();
            for (Map.Entry<String, String> r : result.refused().entrySet()) {  // some recipients refused: record, don't hide
                Delivery.recordRefused(d, r.getKey(), r.getValue());
            }
            EmailEvent e = new EmailEvent();
            e.dispatchId = d.id;
            e.kind = "sent";
            e.subject = d.subject;
            e.toEmails = d.toEmails;
            e.ccEmails = d.ccEmails;
            e.note = "Accepted by the mail server (attempt " + result.attempts() + ").";
            e.messageId = result.messageId();
            e.createdBy = admin.username;
            e.createdAt = d.sentAt;
            e.error = result.refused().isEmpty() ? null : "Refused: " + EmailService.joinRefused(result.refused());
            db.add(e);
        } catch (EmailService.RecipientsRefused exc) {
            d.status = "FAILED";
            d.error = exc.getMessage();
            d.attempts += 1;
            for (Map.Entry<String, String> r : exc.refused.entrySet()) Delivery.recordRefused(d, r.getKey(), r.getValue());
        } catch (Exception exc) {
            d.status = "FAILED";
            d.error = exc.getMessage() == null ? exc.toString() : exc.getMessage();
            d.attempts += Config.SMTP_MAX_ATTEMPTS();
        }
        if ("FAILED".equals(d.status)) {
            EmailEvent e = new EmailEvent();
            e.dispatchId = d.id;
            e.kind = "send_failed";
            e.subject = d.subject;
            e.toEmails = d.toEmails;
            e.ccEmails = d.ccEmails;
            e.ok = false;
            e.error = Py.cut(Py.or(d.error, ""), 1000);
            e.createdBy = admin.username;
            db.add(e);
        }
        d.sendingSince = null;
        db.commit();
        if ("FAILED".equals(d.status)) {
            Alerts.alert("send:" + d.id, "The email to " + d.partner().name + " could not be sent",
                    List.of("Subject: " + d.subject, "Error: " + d.error, "It is in Pending Mails; an admin can send it again."));
        }
    }

    static Object sendSelected(Req req) {
        User admin = Auth.requireAdmin(req);
        Body b = Body.of(req);
        List<Integer> ids = b.intList("ids");
        b.done();
        if (ids.isEmpty()) throw new ApiError(400, "Select at least one pending email to send.");
        Db db = req.db();
        int sent = 0, failed = 0;
        List<String> skipped = new ArrayList<>();
        for (Integer emailId : new LinkedHashSet<>(ids)) {  // each id once, in the order given
            EmailDispatch d = db.get(EmailDispatch.class, emailId);
            if (d == null) continue;
            if (!claim(db, emailId, admin)) {
                db.refresh(d);
                String who = name(userNames(db, java.util.Collections.singletonList(d.sentBy)), d.sentBy);
                skipped.add(d.partner().name + ": " + ("SENT".equals(d.status) ? "already sent by " + who
                        : "SENDING".equals(d.status) ? "being sent by " + who : "not waiting to be sent"));
                continue;
            }
            db.refresh(d);
            deliver(db, d, admin);
            if ("SENT".equals(d.status)) sent++;
            else failed++;
        }
        if (sent == 0 && failed == 0 && !skipped.isEmpty()) {
            throw new ApiError(409, "Nothing was sent. " + String.join("; ", skipped) + ".");
        }
        return Py.map("sent", sent, "failed", failed, "skipped", skipped, "emails", listPendingEmails(db));
    }

    /** A query on sent emails: SQL after "FROM email_dispatches t" and its arguments. */
    record Query(String sql, List<Object> args) {
        Query and(String condition, Object... more) {
            List<Object> a = new ArrayList<>(args);
            a.addAll(java.util.Arrays.asList(more));
            return new Query(sql + " AND " + condition, a);
        }
    }

    /** SENT emails matching the search / partner / date filters of the Sent Mails page. */
    static Query sentQuery(String q, Integer partnerId, LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new ApiError(400, "The 'from' date must be on or before the 'to' date.");
        }
        Query query = new Query("JOIN partners p ON t.partner_id = p.id JOIN uploads u ON t.upload_id = u.id WHERE t.status = 'SENT'",
                new ArrayList<>());
        if (partnerId != null) query = query.and("t.partner_id = ?", partnerId);
        if (dateFrom != null) query = query.and("t.sent_at >= ?", dateFrom.atStartOfDay());
        if (dateTo != null) query = query.and("t.sent_at < ?", dateTo.plusDays(1).atStartOfDay());
        List<String> words = Py.split(q);
        for (String word : words.subList(0, Math.min(words.size(), 10))) {
            String like = "%" + word.replace("%", "").replace("_", "") + "%";
            String names = "(SELECT username FROM users WHERE LOWER(full_name) LIKE LOWER(?))";
            query = query.and("(LOWER(p.name) LIKE LOWER(?) OR LOWER(t.subject) LIKE LOWER(?) OR LOWER(t.to_emails) LIKE LOWER(?) "
                            + "OR LOWER(t.cc_emails) LIKE LOWER(?) OR LOWER(u.filename) LIKE LOWER(?) OR LOWER(t.submitted_by) LIKE LOWER(?) "
                            + "OR LOWER(t.sent_by) LIKE LOWER(?) OR LOWER(t.remarks) LIKE LOWER(?) OR t.submitted_by IN " + names
                            + " OR t.sent_by IN " + names + ")",
                    like, like, like, like, like, like, like, like, like, like);
        }
        return query;
    }

    /** SQL condition for followupDue(). */
    static Query dueFilter(Query query) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Settings.getInt("followup_due_days"));
        return query.and("t.reply_status = 'not_received' AND ((t.last_followup_at IS NULL AND t.sent_at < ?) OR t.last_followup_at < ?)",
                cutoff, cutoff);
    }

    static final String[] REPLY_FILTERS = {"", "not_received", "received", "reconciled", "due"};

    static Query withReply(Query query, String reply) {
        if ("due".equals(reply)) return dueFilter(query);
        if (!reply.isEmpty()) return query.and("t.reply_status = ?", reply);
        return query;
    }

    static Object listSentEmails(Req req) {
        Auth.currentUser(req);
        String q = req.query("q", "");
        Integer partnerId = req.queryInt("partner_id", null);
        String sort = req.queryLiteral("sort", "newest", "newest", "oldest");
        LocalDate dateFrom = req.queryDate("date_from"), dateTo = req.queryDate("date_to");
        String reply = req.queryLiteral("reply", "", REPLY_FILTERS);
        int limit = Math.max(1, Math.min(req.queryInt("limit", 500), 2000));
        Query query = withReply(sentQuery(q, partnerId, dateFrom, dateTo), reply);
        String order = "oldest".equals(sort) ? " ORDER BY t.sent_at ASC, t.id ASC" : " ORDER BY t.sent_at DESC, t.id DESC";
        Db db = req.db();
        return dispatchesToDicts(db, db.all(EmailDispatch.class, query.sql() + order + " LIMIT " + limit, query.args().toArray()));
    }

    static Object sentSummary(Req req) {
        Auth.currentUser(req);
        String q = req.query("q", "");
        Integer partnerId = req.queryInt("partner_id", null);
        LocalDate dateFrom = req.queryDate("date_from"), dateTo = req.queryDate("date_to");
        Db db = req.db();
        Query base = sentQuery(q, partnerId, dateFrom, dateTo);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String status : new String[]{"not_received", "received", "reconciled"}) counts.put(status, 0L);
        for (Object[] row : db.rows("SELECT t.reply_status, COUNT(t.id) FROM email_dispatches t " + base.sql() + " GROUP BY t.reply_status",
                base.args().toArray())) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        Query due = dueFilter(base);
        long total = 0;
        for (Object v : counts.values()) total += ((Number) v).longValue();
        Map<String, Object> out = new LinkedHashMap<>(counts);
        out.put("due", db.count("SELECT COUNT(t.id) FROM email_dispatches t " + due.sql(), due.args().toArray()));
        out.put("total", total);
        out.put("due_after_days", Settings.get("followup_due_days"));
        return out;
    }

    // ---------------------------------------------------------------- follow-up of sent emails

    static final Map<String, String> REPLY_STATUS_LABELS = Map.of("not_received", "Awaiting reply", "received", "Reply received",
            "reconciled", "Reconciled");
    static final long FOLLOWUP_GAP_SECONDS = 60;  // one follow-up per email per minute: no double clicks / two admins at once

    static EmailDispatch getSentOr404(Db db, int emailId) {
        EmailDispatch d = db.get(EmailDispatch.class, emailId);
        if (d == null || !"SENT".equals(d.status)) throw new ApiError(404, "Sent email not found.");
        return d;
    }

    static Map<String, Object> eventToDict(EmailEvent e, Map<String, String> names) {
        String note = e.note;
        if (Inbox.REPLY.equals(e.kind) && !Py.isEmpty(note) && !note.startsWith("Reply from")) note = "Reply from " + note;  // stored before the wording changed
        return Py.map(
                "id", e.id, "kind", e.kind, "reply_status", e.replyStatus, "note", note, "reply_id", e.replyId,
                "subject", e.subject, "body_html", e.bodyHtml,
                "to", Py.csv(e.toEmails),
                "cc", Py.csv(e.ccEmails),
                "ok", e.ok, "error", e.error,
                "created_by", e.createdBy, "created_by_name", name(names, e.createdBy),
                "created_at", Py.iso(e.createdAt));
    }

    static Object listEmailEvents(Req req) {
        Auth.currentUser(req);
        int emailId = req.pathInt("email_id");
        Db db = req.db();
        EmailDispatch d = db.get(EmailDispatch.class, emailId);
        if (d == null) throw new ApiError(404, "Email not found.");
        List<EmailEvent> events = db.all(EmailEvent.class, "WHERE t.dispatch_id = ? ORDER BY t.created_at, t.id", emailId);
        List<String> who = new ArrayList<>();
        for (EmailEvent e : events) who.add(e.createdBy);
        who.add(d.submittedBy);
        who.add(d.sentBy);
        Map<String, String> names = userNames(db, who);
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> kinds = new HashSet<>();
        for (EmailEvent e : events) {
            items.add(eventToDict(e, names));
            kinds.add(e.kind);
        }
        if (!kinds.contains("submitted") && d.submittedAt != null) {
            Map<String, Object> item = baseEvent(d);
            item.put("id", "submitted-" + d.id);
            item.put("kind", "submitted");
            item.put("created_by", d.submittedBy);
            item.put("created_by_name", name(names, d.submittedBy));
            item.put("created_at", Py.iso(d.submittedAt));
            items.add(item);
        }
        if (!kinds.contains("sent") && d.sentAt != null) {
            Map<String, Object> item = baseEvent(d);
            item.put("id", "sent-" + d.id);
            item.put("kind", "sent");
            item.put("created_by", d.sentBy);
            item.put("created_by_name", name(names, d.sentBy));
            item.put("created_at", Py.iso(d.sentAt));
            item.put("to", Py.csv(d.toEmails));
            item.put("cc", Py.csv(d.ccEmails));
            items.add(item);
        }
        Map<String, Integer> order = Map.of("submitted", 0, "sent", 1, "send_failed", 1);  // same second: approval before sending
        items.sort(Comparator.<Map<String, Object>, String>comparing(i -> (String) i.get("created_at"))
                .thenComparing(i -> order.getOrDefault((String) i.get("kind"), 2)));
        return items;
    }

    private static Map<String, Object> baseEvent(EmailDispatch d) {
        return Py.map("reply_status", null, "note", null, "reply_id", null, "body_html", null, "ok", true, "error", null,
                "subject", d.subject, "to", new ArrayList<>(), "cc", new ArrayList<>());
    }

    static void addEvent(Db db, int dispatchId, String kind, String replyStatus, String note, String by) {
        EmailEvent e = new EmailEvent();
        e.dispatchId = dispatchId;
        e.kind = kind;
        e.replyStatus = replyStatus;
        e.note = note;
        e.createdBy = by;
        db.add(e);
    }

    static Object setReplyStatus(Req req) {
        User admin = Auth.requireAdmin(req);
        int emailId = req.pathInt("email_id");
        Body b = Body.of(req);
        String status = b.literal("status", null, "not_received", "received", "reconciled");
        String note = b.str("note", "");
        b.done();
        Db db = req.db();
        EmailDispatch d = getSentOr404(db, emailId);
        if (!status.equals(d.replyStatus)) {
            d.replyStatus = status;
            d.replyStatusBy = admin.username;
            d.replyStatusAt = Py.now();
            String n = Py.cut(Py.strip(note), 1000);
            addEvent(db, d.id, "status", status, n.isEmpty() ? null : n, admin.username);
            db.commit();
        }
        return dispatchToDict(d, null);
    }

    static Object addEmailNote(Req req) {
        User admin = Auth.requireAdmin(req);
        int emailId = req.pathInt("email_id");
        Body b = Body.of(req);
        String note = b.check("note", b.str("note"), v -> {
            v = Py.strip(v);
            if (v.isEmpty()) throw new ValueError("Write a note first");
            if (v.length() > 1000) throw new ValueError("A note can have at most 1000 characters");
            return v;
        });
        b.done();
        Db db = req.db();
        EmailDispatch d = getSentOr404(db, emailId);
        addEvent(db, d.id, "note", null, note, admin.username);
        db.commit();
        return Py.map("ok", true);
    }

    static Object setRemarks(Req req) {
        User admin = Auth.requireAdmin(req);
        int emailId = req.pathInt("email_id");
        Body b = Body.of(req);
        String remarks = b.check("remarks", b.str("remarks"), v -> {
            v = Py.strip(v);
            if (v.length() > 500) throw new ValueError("Remarks can have at most 500 characters");
            return v;
        });
        b.done();
        Db db = req.db();
        EmailDispatch d = getSentOr404(db, emailId);
        if (!Py.or(d.remarks, "").equals(remarks)) {
            d.remarks = remarks.isEmpty() ? null : remarks;
            d.remarksBy = admin.username;
            d.remarksAt = Py.now();
            addEvent(db, d.id, "remarks", null, remarks.isEmpty() ? null : remarks, admin.username);
            db.commit();
        }
        return dispatchToDict(d, null);
    }

    /** The partner's addresses now: active and not refused (they may have changed since the email was sent). */
    static List<String> currentRecipients(EmailDispatch d) {
        List<String> out = new ArrayList<>();
        if (!Boolean.TRUE.equals(d.partner().isActive)) return out;
        for (PartnerEmail e : d.partner().emails()) if (Boolean.TRUE.equals(e.isActive) && Py.isEmpty(e.problem)) out.add(e.email);
        return out;
    }

    static String[] followupContent(Db db, EmailDispatch d, int number) {
        return EmailService.buildFollowup(d.mailType, d.subject, d.sentAt, d.txnCount, Py.toDouble(d.totalAmount), d.replyStatus, number,
                reminderTemplateFor(db, d.mailType), d.partner().name, dispatchTransactions(d), templateVariables(db));
    }

    static void ensureFollowupAllowed(EmailDispatch d) {
        if ("reconciled".equals(d.replyStatus)) {
            throw new ApiError(409, "This email is reconciled, so follow-ups are closed. "
                    + "Change its reply status first if it needs another follow-up.");
        }
    }

    static Object followupDraft(Req req) {
        Auth.requireAdmin(req);
        int emailId = req.pathInt("email_id");
        Db db = req.db();
        EmailDispatch d = getSentOr404(db, emailId);
        ensureFollowupAllowed(d);
        String[] content = followupContent(db, d, d.followupCount + 1);
        List<String> to = currentRecipients(d);
        Set<String> toLower = new HashSet<>();
        for (String t : to) toLower.add(t.toLowerCase(java.util.Locale.ROOT));
        List<String> leftOut = new ArrayList<>();
        for (String a : Py.csv(d.toEmails)) if (!toLower.contains(a.toLowerCase(java.util.Locale.ROOT))) leftOut.add(a);
        List<String> note = new ArrayList<>();
        if (!leftOut.isEmpty()) note.add("Not sent to " + String.join(", ", leftOut) + " (no longer an active address of " + d.partner().name + ").");
        if (to.isEmpty()) note.add(d.partner().name + " has no active address: add one on the Partners page first.");
        return Py.map("to", to, "cc", Py.csv(d.ccEmails), "subject", content[0], "body_html", content[1],
                "number", d.followupCount + 1, "left_out", leftOut, "note", String.join(" ", note));
    }

    private static final Pattern FOLLOWUP_ADDRESS = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+", Pattern.UNICODE_CHARACTER_CLASS);

    static List<String> cleanAddresses(List<String> value) {
        List<String> cleaned = new ArrayList<>();
        for (String address : value) {
            address = Py.strip(address);
            if (address.isEmpty()) continue;
            if (!FOLLOWUP_ADDRESS.matcher(address).matches()) throw new ValueError("'" + address + "' is not a valid email address");
            boolean seen = false;
            for (String c : cleaned) if (c.toLowerCase(java.util.Locale.ROOT).equals(address.toLowerCase(java.util.Locale.ROOT))) seen = true;
            if (!seen) cleaned.add(address);
        }
        return cleaned;
    }

    static Object sendFollowup(Req req) {
        User admin = Auth.requireAdmin(req);
        int emailId = req.pathInt("email_id");
        Body b = Body.of(req);
        List<String> to = b.check("to", b.strList("to", null), MainApi::cleanAddresses);
        List<String> cc = b.check("cc", b.strList("cc", List.of()), MainApi::cleanAddresses);
        String subject = b.check("subject", b.str("subject"), v -> {
            v = Py.strip(v);
            if (v.isEmpty()) throw new ValueError("Subject cannot be empty");
            if (v.length() > 300) throw new ValueError("Subject is too long (max 300 characters)");
            return v;
        });
        String bodyHtml = b.str("body_html");
        b.done();
        if (to.isEmpty()) throw new ApiError(400, "Add at least one recipient.");
        if (Py.strip(bodyHtml).isEmpty()) throw new ApiError(400, "The email body is empty.");
        Db db = req.db();
        EmailDispatch d = getSentOr404(db, emailId);
        ensureFollowupAllowed(d);

        LocalDateTime previous = d.lastFollowupAt;
        if (!claimFollowup(db, d, null)) {
            ensureFollowupAllowed(d);  // reconciled in the meantime
            throw new ApiError(409, "A follow-up for this email was just sent. Check its history before sending another.");
        }
        List<String> refused;
        try {
            refused = deliverFollowup(db, d, previous, to, cc, subject, bodyHtml, admin.username);
        } catch (Exception exc) {
            throw new ApiError(502, "The follow-up could not be sent: " + exc.getMessage());
        }
        return Py.map("email", dispatchToDict(d, null), "refused", refused);
    }

    /**
     * Claim the email for one follow-up with a single UPDATE (sets last_followup_at to now). Fails when it is
     * reconciled, a follow-up went out less than a minute ago (a double click, two admins, or the automatic sender
     * at the same moment), or (dueOnly) it is no longer due.
     */
    static boolean claimFollowup(Db db, EmailDispatch d, LocalDateTime dueCutoff) {
        LocalDateTime now = Py.now();
        String sql = "UPDATE email_dispatches SET last_followup_at = ? WHERE id = ? AND status = 'SENT' AND reply_status != 'reconciled' "
                + "AND (last_followup_at IS NULL OR last_followup_at < ?)";
        List<Object> args = new ArrayList<>(List.of(now, d.id, now.minusSeconds(FOLLOWUP_GAP_SECONDS)));
        if (dueCutoff != null) {
            sql += " AND reply_status = 'not_received' AND ((last_followup_at IS NULL AND sent_at < ?) OR last_followup_at < ?)";
            args.add(dueCutoff);
            args.add(dueCutoff);
        }
        boolean claimed = db.execute(sql, args.toArray()) == 1;
        db.commit();
        db.refresh(d);
        return claimed;
    }

    /**
     * Send the follow-up of an email claimed with claimFollowup, in the original email's thread, and record it in
     * the history. previous: last_followup_at before the claim (restored when nothing went out). Returns the refused
     * recipients; throws when the email could not be sent.
     */
    static List<String> deliverFollowup(Db db, EmailDispatch d, LocalDateTime previous, List<String> to, List<String> cc,
                                        String subject, String bodyHtml, String by) {
        EmailEvent event = new EmailEvent();
        event.dispatchId = d.id;
        event.kind = "followup";
        event.subject = subject;
        event.bodyHtml = bodyHtml;
        event.toEmails = String.join(",", to);
        event.ccEmails = String.join(",", cc);
        event.createdBy = by;
        EmailService.SendResult result;
        try {
            result = EmailService.sendEmail(to, cc, subject, bodyHtml, d.messageId, attachmentFor(d), null);
        } catch (RuntimeException exc) {
            d.lastFollowupAt = previous;  // nothing went out
            event.ok = false;
            event.error = Py.cut(exc.getMessage() == null ? exc.toString() : exc.getMessage(), 1000);
            db.add(event);
            db.commit();
            throw exc;
        }
        d.followupCount += 1;
        event.messageId = result.messageId();  // a reply to the follow-up is matched to this email
        for (Map.Entry<String, String> r : result.refused().entrySet()) Delivery.recordRefused(d, r.getKey(), r.getValue());
        if (!result.refused().isEmpty()) event.error = "Refused: " + EmailService.joinRefused(result.refused());
        db.add(event);
        db.commit();
        return new ArrayList<>(result.refused().keySet());
    }

    // ---------------------------------------------------------------- automatic follow-ups

    static final long AUTO_RETRY_AFTER_HOURS = 1;  // an automatic follow-up that failed is tried again after this long
    private static volatile boolean stopped = false;
    private static final Object WAKE_FOLLOWUPS = new Object();  // System Settings changed the check interval: start again with it
    private static boolean woken = false;
    private static final ReentrantLock RUN_LOCK = new ReentrantLock();  // one run at a time ("Run now" while the timer runs)
    static final Map<String, Object> AUTO_STATUS = java.util.Collections.synchronizedMap(
            Py.map("last_run_at", null, "last_result", null, "next_run_at", null));

    /** Due emails that may still get an automatic follow-up (below the follow-up limit). */
    static List<EmailDispatch> autoCandidates(Db db, int maxFollowups) {
        Query q = dueFilter(new Query("WHERE t.status = 'SENT'", new ArrayList<>())).and("t.followup_count < ?", maxFollowups);
        return db.all(EmailDispatch.class, q.sql() + " ORDER BY t.sent_at, t.id", q.args().toArray());
    }

    /** Send the follow-up of every due email, when automatic follow-ups are switched on. */
    static Map<String, Object> runAutoFollowups() {
        RUN_LOCK.lock();
        try (Db db = Db.open()) {
            Map<String, Object> values = Settings.current(db);
            boolean enabled = Boolean.TRUE.equals(values.get("auto_followup_enabled"));
            List<String> errors = new ArrayList<>();
            int sent = 0, failed = 0;
            if (enabled) {
                LocalDateTime now = LocalDateTime.now();
                Set<Long> recentlyFailed = new HashSet<>(db.ids(  // kept in the database: survives a restart
                        "SELECT dispatch_id FROM email_events WHERE kind = 'followup' AND ok = ? AND created_by = ? AND created_at > ?",
                        false, SYSTEM_USER, now.minusHours(AUTO_RETRY_AFTER_HOURS)));
                for (EmailDispatch d : autoCandidates(db, ((Number) values.get("auto_followup_max")).intValue())) {
                    if (recentlyFailed.contains((long) d.id)) continue;
                    List<String> to = currentRecipients(d);
                    if (to.isEmpty()) {  // every address was refused, removed or switched off since the email was sent
                        EmailEvent e = new EmailEvent();
                        e.dispatchId = d.id;
                        e.kind = "followup";
                        e.ok = false;
                        e.createdBy = SYSTEM_USER;
                        e.error = "Not sent: " + d.partner().name + " has no active address.";
                        db.add(e);
                        db.commit();
                        failed++;
                        errors.add(d.partner().name + " (#" + d.id + "): no active address");
                        Alerts.alert("followup:" + d.id, "Follow-up to " + d.partner().name + " not sent",
                                List.of(d.partner().name + " has no active, working address. Add one on the Partners page."));
                        continue;
                    }
                    LocalDateTime previous = d.lastFollowupAt;
                    // claimed only while still due: an admin may have just followed up or changed the reply status
                    if (!claimFollowup(db, d, LocalDateTime.now().minusDays(Settings.getInt("followup_due_days")))) continue;
                    String[] content = followupContent(db, d, d.followupCount + 1);
                    try {
                        deliverFollowup(db, d, previous, to, Py.csv(d.ccEmails), content[0], content[1], SYSTEM_USER);
                        sent++;
                    } catch (Exception exc) {
                        failed++;
                        errors.add(Py.cut(d.partner().name + " (#" + d.id + "): " + exc.getMessage(), 300));
                        Alerts.alert("followup:" + d.id, "Automatic follow-up to " + d.partner().name + " failed",
                                List.of("Subject: " + content[0], "Error: " + exc.getMessage()));
                    }
                }
            }
            Map<String, Object> result = Py.map("enabled", enabled, "sent", sent, "failed", failed, "errors", errors);
            AUTO_STATUS.put("last_run_at", Py.isoSeconds(LocalDateTime.now()));
            AUTO_STATUS.put("last_result", result);
            if (sent > 0 || failed > 0) LOG.info("Automatic follow-ups: " + sent + " sent, " + failed + " failed");
            return result;
        } finally {
            RUN_LOCK.unlock();
        }
    }

    /** Background thread: look for due emails every AUTO_FOLLOWUP_CHECK_SECONDS. */
    public static void autoFollowupLoop() {
        while (!stopped) {
            try {
                runAutoFollowups();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Automatic follow-ups failed", e);
            }
            int seconds = Config.AUTO_FOLLOWUP_CHECK_SECONDS();
            AUTO_STATUS.put("next_run_at", Py.isoSeconds(LocalDateTime.now().plusSeconds(seconds)));
            synchronized (WAKE_FOLLOWUPS) {
                if (!woken && !stopped) {
                    try {
                        WAKE_FOLLOWUPS.wait(seconds * 1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                woken = false;
            }
        }
    }

    public static void wakeFollowups() {
        synchronized (WAKE_FOLLOWUPS) {
            woken = true;
            WAKE_FOLLOWUPS.notifyAll();
        }
    }

    public static void stopFollowups() {
        stopped = true;
        wakeFollowups();
    }
}
