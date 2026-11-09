package com.nagad.npsb.service;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.EmailEvent;
import com.nagad.npsb.entity.EmailTemplate;
import com.nagad.npsb.entity.Partner;
import com.nagad.npsb.entity.PartnerEmail;
import com.nagad.npsb.entity.TemplateVariable;
import com.nagad.npsb.entity.Transaction;
import com.nagad.npsb.entity.Upload;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.EmailDispatchRepository;
import com.nagad.npsb.repository.EmailEventRepository;
import com.nagad.npsb.repository.EmailTemplateRepository;
import com.nagad.npsb.service.dispatch.DispatchViews;
import com.nagad.npsb.service.dispatch.FileDownload;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.TransactionGroups;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The emails of an upload before they go to an admin: one email per partner and mail type is built (preview / edit),
 * then a user's "Send" makes every prepared email wait for an admin (PREPARED -> PENDING).
 */
@Service
public class DraftService {
    private final UploadService uploads;
    private final TemplateService templateService;
    private final TransactionGroups groups;
    private final DispatchViews views;
    private final PartnerService partnerService;
    private final Settings settings;
    private final Sql sql;
    private final TransactionTemplate tx;
    private final EmailDispatchRepository dispatches;
    private final EmailEventRepository events;
    private final EmailTemplateRepository templates;

    public DraftService(UploadService uploads, TemplateService templateService, TransactionGroups groups, DispatchViews views,
                        PartnerService partnerService, Settings settings, Sql sql, TransactionTemplate tx,
                        EmailDispatchRepository dispatches, EmailEventRepository events, EmailTemplateRepository templates) {
        this.uploads = uploads;
        this.templateService = templateService;
        this.groups = groups;
        this.views = views;
        this.partnerService = partnerService;
        this.settings = settings;
        this.sql = sql;
        this.tx = tx;
        this.dispatches = dispatches;
        this.events = events;
        this.templates = templates;
    }

    /** Build (or rebuild) one email per partner and mail type. templates: the template chosen per mail type. */
    public Map<String, Object> prepareEmails(int uploadId, Map<String, Integer> chosenIds) {
        List<String> errors = new ArrayList<>();
        List<String> notices = new ArrayList<>();  // emails prepared, but some addresses were left out
        tx.executeWithoutResult(status -> {
            Upload upload = uploads.getUploadOr404(uploadId);
            Map<String, Partner> partners = groups.partnersByName();
            Map<String, EmailTemplate> chosen = new HashMap<>();
            for (Map.Entry<String, Integer> e : chosenIds.entrySet()) {
                EmailTemplate template = templates.findById(e.getValue()).orElse(null);
                if (template == null || !template.getMailType().equals(e.getKey()) || !"initial".equals(template.getPurpose())) {
                    throw new ApiException(400, "Pick a " + EmailService.MAIL_TYPES.get(e.getKey()) + " template (it may have been deleted).");
                }
                chosen.put(e.getKey(), template);
            }

            List<TemplateVariable> variables = templateService.templateVariables();
            for (Map.Entry<List<String>, List<Transaction>> g : groups.groupByPartner(upload).entrySet()) {
                String key = g.getKey().get(0), mailType = g.getKey().get(1);
                List<Transaction> txns = g.getValue();
                Partner partner = partners.get(key);

                // --- checks: skip this partner but continue with the others
                if (partner == null) {
                    errors.add(txns.get(0).getPartnerName() + " – " + EmailService.MAIL_TYPES.get(mailType) + ": no partner with this name in "
                            + "Partner Configuration. No email prepared.");
                    continue;
                }
                String label = partner.getName() + " – " + EmailService.MAIL_TYPES.get(mailType);
                if (!Boolean.TRUE.equals(partner.getIsActive())) {
                    errors.add(label + ": partner is inactive. No email prepared.");
                    continue;
                }
                List<PartnerEmail> active = new ArrayList<>();
                for (PartnerEmail e : partnerService.emailsOf(partner)) if (Boolean.TRUE.equals(e.getIsActive())) active.add(e);
                List<String> toList = new ArrayList<>(), problems = new ArrayList<>();
                for (PartnerEmail e : active) {
                    if (Py.isEmpty(e.getProblem())) toList.add(e.getEmail());
                    else problems.add(e.getEmail() + " (" + e.getProblem() + ")");
                }
                if (active.isEmpty()) {
                    errors.add(label + ": no active email address configured. No email prepared.");
                    continue;
                }
                if (toList.isEmpty()) {
                    errors.add(label + ": every active address failed before: " + String.join("; ", problems) + ". No email prepared.");
                    continue;
                }

                EmailDispatch dispatch = sql.first(EmailDispatch.class, "WHERE t.upload_id = ? AND t.partner_id = ? AND t.mail_type = ?",
                        upload.getId(), partner.getId(), mailType);
                if (dispatch != null && !"PREPARED".equals(dispatch.getStatus())) continue;  // with an admin or already gone out: never rebuilt

                boolean isNew = dispatch == null;
                if (isNew) {
                    dispatch = new EmailDispatch();
                    dispatch.setUploadId(upload.getId());
                    dispatch.setPartnerId(partner.getId());
                    dispatch.setMailType(mailType);
                }
                if (chosen.containsKey(mailType)) {
                    dispatch.setTemplateId(chosen.get(mailType).getId());
                    dispatch.setIsEdited(false);
                }
                if (!Boolean.TRUE.equals(dispatch.getIsEdited())) {  // keep the user's manual edits
                    EmailTemplate template = templateService.templateFor(mailType, dispatch.getTemplateId());
                    dispatch.setTemplateId(template != null ? template.getId() : null);
                    String[] built = EmailService.buildEmail(partner.getName(), mailType, txns, template, variables);
                    dispatch.setSubject(built[0]);
                    dispatch.setBodyHtml(built[1]);
                    dispatch.setIsEdited(false);
                }
                dispatch.setToEmails(String.join(",", toList));
                dispatch.setCcEmails(String.join(",", settings.getList("cc_emails")));
                dispatch.setTxnCount(txns.size());
                double total = 0;
                for (Transaction t : txns) total += Py.toDouble(t.getAmount());
                // the float total, stored in NUMERIC(14, 2) as MySQL rounds it
                dispatch.setTotalAmount(new BigDecimal(Py.floatRepr(total)).setScale(2, RoundingMode.HALF_UP));
                dispatch.setStatus("PREPARED");
                dispatch.setError(null);
                dispatch.setDeliveryIssues(null);
                if (isNew) dispatches.save(dispatch);  // db.add(dispatch): written with the commit, all fields set
                if (!problems.isEmpty()) notices.add(label + ": left out " + String.join("; ", problems) + ".");
            }
        });
        return Py.map("errors", errors, "notices", notices, "emails", listEmails(uploadId));
    }

    public List<Map<String, Object>> listEmails(int uploadId) {
        uploads.getUploadOr404(uploadId);
        return views.dispatchesToDicts(sql.all(EmailDispatch.class, "WHERE t.upload_id = ? ORDER BY t.id", uploadId));
    }

    /** Drafts (PREPARED) can be changed by anyone; once sent for approval, only by admins. */
    EmailDispatch getEditableDispatchOrError(int emailId, User user) {
        EmailDispatch dispatch = dispatches.findById(emailId).orElse(null);
        if (dispatch == null) throw new ApiException(404, "Email not found");
        if ("SENT".equals(dispatch.getStatus())) throw new ApiException(400, "This email has already been sent and can no longer be edited.");
        if ("SENDING".equals(dispatch.getStatus())) throw new ApiException(409, "This email is being sent right now and can no longer be edited.");
        if (!"PREPARED".equals(dispatch.getStatus()) && !"admin".equals(user.getRole())) {
            throw new ApiException(403, "This email is waiting for an admin. Only admins can change it now.");
        }
        return dispatch;
    }

    /** The Excel file attached to this email: its transactions. */
    public FileDownload downloadAttachment(int emailId) {
        EmailDispatch d = dispatches.findById(emailId).orElse(null);
        if (d == null) throw new ApiException(404, "Email not found");
        List<EmailService.Attachment> attached = groups.attachmentFor(d);
        if (attached.isEmpty()) throw new ApiException(404, "This email has no transactions to attach.");
        EmailService.Attachment a = attached.get(0);
        return new FileDownload(a.data(), FileDownload.XLSX_TYPE, "attachment; filename=\"" + a.filename() + "\"");
    }

    /** Save a manual edit. Edited emails are kept when previews are refreshed. */
    public Map<String, Object> editEmail(User user, int emailId, String subject, String bodyHtml) {
        EmailDispatch dispatch = tx.execute(status -> {
            EmailDispatch d = getEditableDispatchOrError(emailId, user);
            d.setSubject(subject);
            d.setBodyHtml(bodyHtml);
            d.setIsEdited(true);
            return d;
        });
        return views.dispatchToDict(dispatch, null);
    }

    /**
     * Throw away manual edits. A draft is regenerated by the next prepare-emails call; an email waiting for an
     * admin is regenerated right away (prepare-emails no longer touches it).
     */
    public Map<String, Object> discardEmailEdits(User user, int emailId) {
        EmailDispatch dispatch = tx.execute(status -> {
            EmailDispatch d = getEditableDispatchOrError(emailId, user);
            d.setIsEdited(false);
            if (!"PREPARED".equals(d.getStatus())) {
                List<Transaction> txns = groups.dispatchTransactions(d);
                if (!txns.isEmpty()) {
                    EmailTemplate template = templateService.templateFor(d.getMailType(), d.getTemplateId());
                    String[] built = EmailService.buildEmail(groups.partnerOf(d).getName(), d.getMailType(), txns, template, List.of());
                    d.setSubject(built[0]);
                    d.setBodyHtml(built[1]);
                }
            }
            return d;
        });
        return views.dispatchToDict(dispatch, null);
    }

    /** Rebuild this email from another template. Manual edits are replaced. */
    public Map<String, Object> applyTemplate(User user, int emailId, Integer templateId) {
        EmailDispatch dispatch = tx.execute(status -> {
            EmailDispatch d = getEditableDispatchOrError(emailId, user);
            EmailTemplate template = templateId == null ? null : templates.findById(templateId).orElse(null);
            if (template == null) throw new ApiException(404, "Template not found. It may have been deleted.");
            if (!template.getMailType().equals(d.getMailType())) {
                throw new ApiException(400, "\"" + template.getName() + "\" is a " + EmailService.MAIL_TYPE_NAMES.get(template.getMailType())
                        + " template; this email is " + EmailService.MAIL_TYPE_NAMES.getOrDefault(d.getMailType(), d.getMailType()) + ".");
            }
            List<Transaction> txns = groups.dispatchTransactions(d);
            if (txns.isEmpty()) throw new ApiException(400, "This email has no transactions to build it from.");
            d.setTemplateId(template.getId());
            String[] built = EmailService.buildEmail(groups.partnerOf(d).getName(), d.getMailType(), txns, template, List.of());
            d.setSubject(built[0]);
            d.setBodyHtml(built[1]);
            d.setIsEdited(false);
            return d;
        });
        return views.dispatchToDict(dispatch, null);
    }

    /** A user's "Send": every PREPARED email of this upload waits for an admin. */
    public Map<String, Object> submitEmails(User user, int uploadId) {
        List<EmailDispatch> drafts = tx.execute(status -> {
            uploads.getUploadOr404(uploadId);
            List<EmailDispatch> found = sql.all(EmailDispatch.class, "WHERE t.upload_id = ? AND t.status = 'PREPARED' ORDER BY t.id", uploadId);
            if (found.isEmpty()) throw new ApiException(400, "There are no prepared emails to send.");
            LocalDateTime now = Py.now();
            for (EmailDispatch d : found) {
                d.setStatus("PENDING");
                d.setSubmittedBy(user.getUsername());
                d.setSubmittedAt(now);
                EmailEvent e = new EmailEvent();
                e.setDispatchId(d.getId());
                e.setKind("submitted");
                e.setSubject(d.getSubject());
                e.setCreatedBy(user.getUsername());
                e.setCreatedAt(now);
                events.save(e);
            }
            return found;
        });
        return Py.map("submitted", drafts.size(), "emails", listEmails(uploadId));
    }
}
