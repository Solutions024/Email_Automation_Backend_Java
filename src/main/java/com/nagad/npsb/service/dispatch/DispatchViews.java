package com.nagad.npsb.service.dispatch;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.EmailEvent;
import com.nagad.npsb.service.Delivery;
import com.nagad.npsb.service.EmailService;
import com.nagad.npsb.service.Inbox;
import com.nagad.npsb.service.Settings;
import com.nagad.npsb.util.Py;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.nagad.npsb.service.dispatch.UserNames.name;

/** Emails and their history as the pages show them. */
@Component
public class DispatchViews {
    private final UserNames userNames;
    private final TransactionGroups groups;
    private final Settings settings;

    public DispatchViews(UserNames userNames, TransactionGroups groups, Settings settings) {
        this.userNames = userNames;
        this.groups = groups;
        this.settings = settings;
    }

    public List<Map<String, Object>> dispatchesToDicts(List<EmailDispatch> dispatches) {
        List<String> who = new ArrayList<>();
        for (EmailDispatch d : dispatches) {
            who.add(d.getSubmittedBy());
            who.add(d.getSentBy());
            who.add(d.getReplyStatusBy());
            who.add(d.getRemarksBy());
        }
        Map<String, String> names = userNames.userNames(who);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmailDispatch d : dispatches) out.add(dispatchToDict(d, names));
        return out;
    }

    public Map<String, Object> dispatchToDict(EmailDispatch d, Map<String, String> names) {
        if (names == null) {
            names = userNames.userNames(Arrays.asList(d.getSubmittedBy(), d.getSentBy(), d.getReplyStatusBy(), d.getRemarksBy()));
        }
        return Py.map(
                "id", d.getId(),
                "partner_name", groups.partnerOf(d).getName(),
                "to", Py.splitOn(d.getToEmails(), ","),
                "cc", Py.csv(d.getCcEmails()),
                "subject", d.getSubject(),
                "body_html", d.getBodyHtml(),
                "is_edited", d.getIsEdited(),
                "template_id", d.getTemplateId(),
                "txn_count", d.getTxnCount(),
                "total_amount", Py.toDouble(d.getTotalAmount()),
                "status", d.getStatus(),
                "attempts", d.getAttempts(),
                "error", d.getError(),
                "sent_at", Py.iso(d.getSentAt()),
                "delivery_issues", Delivery.issuesOf(d),
                "upload_id", d.getUploadId(),
                "upload_filename", groups.uploadOf(d).getFilename(),
                "mail_type", d.getMailType(),
                "mail_type_label", EmailService.MAIL_TYPE_NAMES.getOrDefault(d.getMailType(), d.getMailType()),
                "submitted_by", d.getSubmittedBy(),
                "submitted_by_name", name(names, d.getSubmittedBy()),
                "submitted_at", Py.iso(d.getSubmittedAt()),
                "sent_by", d.getSentBy(),                 // while SENDING: the admin sending it right now
                "sent_by_name", name(names, d.getSentBy()),
                // follow-up
                "reply_status", d.getReplyStatus(),
                "reply_status_by_name", name(names, d.getReplyStatusBy()),
                "reply_status_at", Py.iso(d.getReplyStatusAt()),
                "followup_count", d.getFollowupCount(),
                "last_followup_at", Py.iso(d.getLastFollowupAt()),
                "followup_due", followupDue(d),
                "remarks", Py.or(d.getRemarks(), ""),
                "remarks_by_name", name(names, d.getRemarksBy()),
                "remarks_at", Py.iso(d.getRemarksAt()));
    }

    /** Sent, no reply, and nothing sent to the partner for "followup_due_days" (Email Configuration). */
    public boolean followupDue(EmailDispatch d) {
        LocalDateTime lastContact = d.getLastFollowupAt() != null ? d.getLastFollowupAt() : d.getSentAt();
        return "SENT".equals(d.getStatus()) && "not_received".equals(d.getReplyStatus()) && lastContact != null
                && lastContact.isBefore(LocalDateTime.now().minusDays(settings.getInt("followup_due_days")));
    }

    public static Map<String, Object> eventToDict(EmailEvent e, Map<String, String> names) {
        String note = e.getNote();
        if (Inbox.REPLY.equals(e.getKind()) && !Py.isEmpty(note) && !note.startsWith("Reply from")) note = "Reply from " + note;  // stored before the wording changed
        return Py.map(
                "id", e.getId(), "kind", e.getKind(), "reply_status", e.getReplyStatus(), "note", note, "reply_id", e.getReplyId(),
                "subject", e.getSubject(), "body_html", e.getBodyHtml(),
                "to", Py.csv(e.getToEmails()),
                "cc", Py.csv(e.getCcEmails()),
                "ok", e.getOk(), "error", e.getError(),
                "created_by", e.getCreatedBy(), "created_by_name", name(names, e.getCreatedBy()),
                "created_at", Py.iso(e.getCreatedAt()));
    }
}
