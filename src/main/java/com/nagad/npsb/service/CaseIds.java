package com.nagad.npsb.service;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.Upload;
import com.nagad.npsb.repository.EmailDispatchRepository;
import com.nagad.npsb.repository.UploadRepository;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.util.Py;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Case IDs: one per email to a partner, e.g. 202611 00 003 1 000 001 -> "202611000031000001":
 *   year (4) + month (2) + "00" + partner id (3, zero padded) + 1 Inward / 2 Outward + "000" + index (3).
 * The index counts this partner's emails of this mail type within the month, from 001. Shown in the app;
 * sent with every email (the first email, follow-ups, answers) as invisible text, so a partner's reply
 * that quotes our email carries it and is linked to the right case (Inbox).
 */
@Service
public class CaseIds {
    private static final Logger LOG = LoggerFactory.getLogger("npsb");

    /** How the case ID appears in the invisible text of a sent email. */
    public static final String LABEL = "NPSB Case ID:";
    private static final Pattern IN_TEXT = Pattern.compile(Pattern.quote(LABEL) + "\\s*([0-9]{18,24})");

    private final Sql sql;
    private final EmailDispatchRepository dispatches;
    private final UploadRepository uploads;
    private final TransactionTemplate tx;

    public CaseIds(Sql sql, EmailDispatchRepository dispatches, UploadRepository uploads, TransactionTemplate tx) {
        this.sql = sql;
        this.dispatches = dispatches;
        this.uploads = uploads;
        this.tx = tx;
    }

    public static String direction(String mailType) {
        return "inward".equals(mailType) ? "1" : "2";  // outward (and old outward_reminder emails)
    }

    /** Everything but the index: year, month, 00, partner, direction, 000. */
    public static String prefix(LocalDate month, int partnerId, String mailType) {
        return String.format("%04d%02d00%03d%s000", month.getYear(), month.getMonthValue(), partnerId, direction(mailType));
    }

    /**
     * The next case ID for this partner and mail type in this month (not saved: the caller sets it on the email).
     * Reads the database on the current transaction's connection, so it counts what that transaction has flushed.
     */
    public String next(LocalDate month, int partnerId, String mailType) {
        String prefix = prefix(month, partnerId, mailType);
        int max = 0;
        for (Object[] row : sql.rows("SELECT case_id FROM email_dispatches WHERE partner_id = ? AND case_id LIKE ?", partnerId, prefix + "%")) {
            String id = (String) row[0];
            String index = id.substring(prefix.length());
            if (index.matches("[0-9]+")) max = Math.max(max, Integer.parseInt(index));
        }
        return prefix + String.format("%03d", max + 1);
    }

    /** The invisible line added to every email we send: white, tiny text the partner doesn't see. */
    public static String hiddenHtml(String caseId) {
        if (Py.isEmpty(caseId)) return "";
        return "<div style=\"color:#ffffff;font-size:1px;line-height:1px;max-height:1px;overflow:hidden;opacity:0;"
                + "mso-hide:all\">" + LABEL + " " + caseId + "</div>";
    }

    /** The email's HTML with the invisible case ID at the end (inside the body wrapper if there is one). */
    public static String withHidden(String bodyHtml, String caseId) {
        String hidden = hiddenHtml(caseId);
        if (hidden.isEmpty() || bodyHtml == null) return bodyHtml;
        int end = bodyHtml.lastIndexOf("</div>");
        return end >= 0 ? bodyHtml.substring(0, end) + hidden + bodyHtml.substring(end) : bodyHtml + hidden;
    }

    /** A case ID found in a message's text (a reply quoting our email), or null. */
    public static String find(String text) {
        if (text == null) return null;
        Matcher m = IN_TEXT.matcher(text.replaceAll("<[^>]*>", " "));
        return m.find() ? m.group(1) : null;
    }

    /** At startup: give emails made before case IDs existed one (by the month they were uploaded, oldest first). */
    public void backfill() {
        Integer given = tx.execute(status -> {
            List<EmailDispatch> missing = dispatches.findByCaseIdIsNullOrderByIdAsc();
            for (EmailDispatch d : missing) {
                LocalDateTime when = d.getSubmittedAt() != null ? d.getSubmittedAt() : d.getSentAt();
                if (when == null) {
                    Upload upload = d.getUploadId() == null ? null : uploads.findById(d.getUploadId()).orElse(null);
                    when = upload != null && upload.getUploadedAt() != null ? upload.getUploadedAt() : Py.now();
                }
                d.setCaseId(next(when.toLocalDate(), d.getPartnerId(), d.getMailType()));
                dispatches.flush();  // the next one counts this one
            }
            return missing.size();
        });
        if (given != null && given > 0) LOG.info("Case IDs given to {} earlier email(s)", given);
    }
}
