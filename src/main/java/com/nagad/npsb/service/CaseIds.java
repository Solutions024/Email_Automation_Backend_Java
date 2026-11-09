package com.nagad.npsb.service;

import com.nagad.npsb.Py;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.EmailDispatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Case IDs: one per email to a partner, e.g. 202611 00 003 1 000 001 -> "202611000031000001":
 *   year (4) + month (2) + "00" + partner id (3, zero padded) + 1 Inward / 2 Outward + "000" + index (3).
 * The index counts this partner's emails of this mail type within the month, from 001. Shown in the app;
 * sent with every email (the first email, follow-ups, answers) as invisible text, so a partner's reply
 * that quotes our email carries it and is linked to the right case (Inbox).
 */
public final class CaseIds {
    private CaseIds() {}

    private static final Logger LOG = Logger.getLogger("npsb");

    /** How the case ID appears in the invisible text of a sent email. */
    public static final String LABEL = "NPSB Case ID:";
    private static final Pattern IN_TEXT = Pattern.compile(Pattern.quote(LABEL) + "\s*([0-9]{18,24})");

    public static String direction(String mailType) {
        return "inward".equals(mailType) ? "1" : "2";  // outward (and old outward_reminder emails)
    }

    /** Everything but the index: year, month, 00, partner, direction, 000. */
    public static String prefix(LocalDate month, int partnerId, String mailType) {
        return String.format("%04d%02d00%03d%s000", month.getYear(), month.getMonthValue(), partnerId, direction(mailType));
    }

    /** The next case ID for this partner and mail type in this month (not saved: the caller sets it on the email). */
    public static String next(Db db, LocalDate month, int partnerId, String mailType) {
        String prefix = prefix(month, partnerId, mailType);
        int max = 0;
        for (Object[] row : db.rows("SELECT case_id FROM email_dispatches WHERE partner_id = ? AND case_id LIKE ?", partnerId, prefix + "%")) {
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
    public static void backfill(Db db) {
        List<EmailDispatch> missing = db.all(EmailDispatch.class, "WHERE t.case_id IS NULL ORDER BY t.id");
        if (missing.isEmpty()) return;
        for (EmailDispatch d : missing) {
            LocalDateTime when = d.submittedAt != null ? d.submittedAt : d.sentAt != null ? d.sentAt
                    : d.upload() != null && d.upload().uploadedAt != null ? d.upload().uploadedAt : Py.now();
            d.caseId = next(db, when.toLocalDate(), d.partnerId, d.mailType);
            db.flush();  // the next one counts this one
        }
        db.commit();
        LOG.info("Case IDs given to " + missing.size() + " earlier email(s)");
    }
}
