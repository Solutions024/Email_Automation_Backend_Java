package com.nagad.npsb.service;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.service.dispatch.DispatchQueries;
import com.nagad.npsb.service.dispatch.DispatchViews;
import com.nagad.npsb.service.dispatch.FileDownload;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.SqlQuery;
import com.nagad.npsb.util.Py;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pending Mails and Sent Mails as Excel files. */
@Service
public class ExportService {
    private final DispatchQueries queries;
    private final DispatchViews views;
    private final Sql sql;

    public ExportService(DispatchQueries queries, DispatchViews views, Sql sql) {
        this.queries = queries;
        this.views = views;
        this.sql = sql;
    }

    static FileDownload xlsxResponse(List<Map<String, Object>> rows, String name) {
        List<String> headers;
        List<List<Object>> table = new ArrayList<>();
        if (rows.isEmpty()) {
            headers = List.of("");
            table.add(List.of("No emails"));
        } else {
            headers = new ArrayList<>(rows.get(0).keySet());
            for (Map<String, Object> r : rows) table.add(new ArrayList<>(r.values()));
        }
        String filename = name + "_" + Py.strftime(LocalDateTime.now(), "%Y-%m-%d_%H%M") + ".xlsx";
        return new FileDownload(Xlsx.write(headers, table, Py.cut(name, 31)), FileDownload.XLSX_TYPE, "attachment; filename=\"" + filename + "\"");
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object v) {
        return (List<String>) v;
    }

    static Object or(Object v) {
        return v == null || "".equals(v) ? "" : v;
    }

    /** Pending Mails as an Excel file. */
    public FileDownload exportPending() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> e : views.dispatchesToDicts(queries.pendingQuery())) {
            rows.add(Py.map(
                    "Email #", e.get("id"), "Case ID", or(e.get("case_id")), "Partner code", or(e.get("partner_code")), "Partner", e.get("partner_name"), "Mail type", e.get("mail_type_label"),
                    "To", String.join(", ", strings(e.get("to"))), "CC", String.join(", ", strings(e.get("cc"))),
                    "Subject", e.get("subject"), "File", e.get("upload_filename"), "Transactions", e.get("txn_count"),
                    "Amount (BDT)", e.get("total_amount"), "Status", e.get("status"), "Error", or(e.get("error")),
                    "Sent for approval by", or(e.get("submitted_by_name")), "Sent for approval at", or(e.get("submitted_at"))));
        }
        return xlsxResponse(rows, "Pending_Mails");
    }

    /** Sent Mails, with the same filters as the page, as an Excel file. */
    public FileDownload exportSent(String q, Integer partnerId, LocalDate dateFrom, LocalDate dateTo, String reply) {
        SqlQuery query = queries.withReply(DispatchQueries.sentQuery(q, partnerId, dateFrom, dateTo), reply);
        List<EmailDispatch> emails = sql.all(EmailDispatch.class, query.sql() + " ORDER BY t.sent_at DESC, t.id DESC LIMIT 5000",
                query.argArray());
        List<Integer> ids = new ArrayList<>();
        for (EmailDispatch d : emails) ids.add(d.getId());
        if (ids.isEmpty()) ids.add(0);
        Map<Integer, Long> replyCounts = new HashMap<>();
        for (Object[] row : sql.rows("SELECT dispatch_id, COUNT(id) FROM email_replies WHERE dispatch_id IN " + Sql.in(ids)
                + " AND kind = 'reply' GROUP BY dispatch_id", ids.toArray())) {
            replyCounts.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> e : views.dispatchesToDicts(emails)) {
            rows.add(Py.map(
                    "Email #", e.get("id"), "Case ID", or(e.get("case_id")), "Partner code", or(e.get("partner_code")), "Partner", e.get("partner_name"), "Mail type", e.get("mail_type_label"),
                    "To", String.join(", ", strings(e.get("to"))),
                    "Subject", e.get("subject"), "File", e.get("upload_filename"), "Transactions", e.get("txn_count"),
                    "Amount (BDT)", e.get("total_amount"), "Sent at", or(e.get("sent_at")), "Sent by", or(e.get("sent_by_name")),
                    "Reply status", SentEmailService.REPLY_STATUS_LABELS.getOrDefault((String) e.get("reply_status"), (String) e.get("reply_status")),
                    "Replies", replyCounts.getOrDefault((Integer) e.get("id"), 0L), "Follow-ups sent", e.get("followup_count"),
                    "Last follow-up", or(e.get("last_followup_at")), "Follow-up due", Boolean.TRUE.equals(e.get("followup_due")) ? "Yes" : "",
                    "Remarks", e.get("remarks")));
        }
        return xlsxResponse(rows, "Sent_Mails");
    }
}
