package com.nagad.npsb.service;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.Partner;
import com.nagad.npsb.entity.Transaction;
import com.nagad.npsb.entity.Upload;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.EmailDispatchRepository;
import com.nagad.npsb.repository.TransactionRepository;
import com.nagad.npsb.repository.UploadRepository;
import com.nagad.npsb.service.dispatch.FileDownload;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.TransactionGroups;
import com.nagad.npsb.service.dispatch.UserNames;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.nagad.npsb.service.dispatch.TransactionGroups.nameKey;
import static com.nagad.npsb.service.dispatch.UserNames.name;

/**
 * Uploaded dispute files: upload &amp; validate, earlier uploads, the rows of an upload (valid / faulty) and its
 * transactions grouped by partner (one email each).
 */
@Service
public class UploadService {
    public static final Map<String, String> ROW_KINDS = Map.of("valid", "successful", "faulty", "skipped");

    private final Sql sql;
    private final TransactionGroups groups;
    private final UserNames userNames;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final UploadRepository uploads;
    private final TransactionRepository transactions;
    private final EmailDispatchRepository dispatches;

    public UploadService(Sql sql, TransactionGroups groups, UserNames userNames, EntityManager em, TransactionTemplate tx,
                         UploadRepository uploads, TransactionRepository transactions, EmailDispatchRepository dispatches) {
        this.sql = sql;
        this.groups = groups;
        this.userNames = userNames;
        this.em = em;
        this.tx = tx;
        this.uploads = uploads;
        this.transactions = transactions;
        this.dispatches = dispatches;
    }

    public Upload getUploadOr404(int uploadId) {
        Upload upload = uploads.findById(uploadId).orElse(null);
        if (upload == null) throw new ApiException(404, "Upload not found");
        return upload;
    }

    /** Sample upload file with the required columns. */
    public FileDownload uploadTemplate() {
        return new FileDownload(ExcelParser.buildTemplate(), FileDownload.XLSX_TYPE,
                "attachment; filename=\"dispute_transactions_template.xlsx\"");
    }

    /** RRN -> the first transaction taken in with it from an earlier file (rows left out as repeats don't count). */
    Map<String, Transaction> earlierTransactions(Collection<String> rrns) {
        Map<String, Transaction> found = new HashMap<>();
        List<String> all = new ArrayList<>(new LinkedHashSet<>(rrns));
        for (int i = 0; i < all.size(); i += 500) {
            List<String> chunk = all.subList(i, Math.min(i + 500, all.size()));
            for (Transaction t : sql.all(Transaction.class, "WHERE t.rrn IN " + Sql.in(chunk)
                    + " AND t.duplicate_of IS NULL AND t.duplicate_of_txn IS NULL ORDER BY t.id", chunk.toArray())) {
                found.putIfAbsent(t.getRrn(), t);
            }
        }
        return found;
    }

    /** The email (of the earlier file) that holds this transaction, if one was written. */
    EmailDispatch emailOf(Transaction t, Map<Integer, List<EmailDispatch>> byUpload) {
        List<EmailDispatch> emails = byUpload.computeIfAbsent(t.getUploadId(),
                id -> sql.all(EmailDispatch.class, "WHERE t.upload_id = ? ORDER BY t.id", id));
        for (EmailDispatch d : emails) {
            if (t.getMailType() != null && t.getMailType().equals(d.getMailType())
                    && TransactionGroups.belongsTo(t, groups.partnerOf(d))) {  // the same code (older data: name)
                return d;
            }
        }
        return null;
    }

    List<Object> duplicatesOf(Upload upload) {
        List<Transaction> rows = new ArrayList<>();
        for (Transaction t : groups.transactionsOf(upload)) if (t.isDuplicate()) rows.add(t);
        rows.sort(Comparator.comparing(Transaction::getExcelRow));
        Map<Integer, List<EmailDispatch>> emailsByUpload = new HashMap<>();
        Map<Integer, Upload> uploadsById = new HashMap<>();
        // the first copies of the repeated rows, read in batches (not one query per row)
        Map<Integer, Transaction> firsts = new HashMap<>();
        List<Integer> firstIds = new ArrayList<>();
        for (Transaction t : rows) if (t.getDuplicateOfTxn() != null) firstIds.add(t.getDuplicateOfTxn());
        firstIds = new ArrayList<>(new LinkedHashSet<>(firstIds));
        for (int i = 0; i < firstIds.size(); i += 500) {
            List<Integer> chunk = firstIds.subList(i, Math.min(i + 500, firstIds.size()));
            for (Transaction f : sql.all(Transaction.class, "WHERE t.id IN " + Sql.in(chunk), chunk.toArray())) firsts.put(f.getId(), f);
        }
        List<Object> out = new ArrayList<>();
        for (Transaction t : rows) {
            Transaction first = t.getDuplicateOfTxn() != null ? firsts.get(t.getDuplicateOfTxn()) : null;
            EmailDispatch d = first != null ? emailOf(first, emailsByUpload)
                    : t.getDuplicateOf() != null ? dispatches.findById(t.getDuplicateOf()).orElse(null) : null;
            Upload from = first != null ? uploadsById.computeIfAbsent(first.getUploadId(), id -> uploads.findById(id).orElse(null))
                    : d != null ? groups.uploadOf(d) : null;
            LocalDateTime when = d == null ? null : d.getSentAt() != null ? d.getSentAt() : d.getSubmittedAt();
            out.add(Py.map("excel_row", t.getExcelRow(), "rrn", t.getRrn(), "partner_name", t.getPartnerName(), "mail_type", t.getMailType(),
                    "amount", Py.toDouble(t.getAmount()),
                    "first_upload_id", from != null ? from.getId() : null, "first_upload", from != null ? from.getFilename() : null,
                    "first_uploaded_at", from != null ? Py.iso(from.getUploadedAt()) : null,
                    "first_row", first != null ? first.getExcelRow() : null,
                    "email_id", d != null ? d.getId() : null, "email_status", d != null ? d.getStatus() : null, "email_date", Py.iso(when)));
        }
        return out;
    }

    /** What the Home page shows about an upload (rowErrors: read again from the stored file if not given). */
    Map<String, Object> uploadSummary(Upload upload, List<Map<String, Object>> rowErrors) {
        if (rowErrors == null) {
            rowErrors = new ArrayList<>();
            byte[] content = uploads.findFileContent(upload.getId());
            if (content != null && content.length > 0) {
                try {
                    rowErrors = ExcelParser.parseTransactions(upload.getFilename(), content).rowErrors();
                } catch (ExcelParser.FileValidationError ignored) {
                    // the stored file can't be read any more: no row errors shown
                }
            }
        }
        List<Transaction> txns = groups.transactionsOf(upload);
        List<Object> duplicates = duplicatesOf(upload);
        int inward = 0, outward = 0;
        for (Transaction t : txns) {
            boolean dup = t.isDuplicate();
            if ("inward".equals(t.getMailType()) && !dup) inward++;
            if ("outward".equals(t.getMailType()) && !dup) outward++;
        }
        return Py.map(
                "upload_id", upload.getId(),
                "filename", upload.getFilename(),
                "uploaded_at", Py.iso(upload.getUploadedAt()),
                "total_rows", upload.getTotalRows(),
                "valid_rows", upload.getValidRows(),
                "inward_rows", inward,
                "outward_rows", outward,
                "skipped_rows", rowErrors.size(),
                "row_errors", rowErrors,
                "duplicate_rows", duplicates.size(),
                "duplicates", duplicates);
    }

    /** Validate and store an uploaded file with its valid rows (a rejected file: nothing is saved). */
    public Map<String, Object> uploadFile(User user, String filename, byte[] content) {
        ExcelParser.Parsed parsed;
        try {
            parsed = ExcelParser.parseTransactions(filename, content);
        } catch (ExcelParser.FileValidationError exc) {
            // whole file rejected -> nothing is saved
            throw new ApiException(400, Py.map("message", "File rejected", "errors", exc.errors));
        }
        Upload upload = tx.execute(status -> {
            Upload u = new Upload();
            u.setFilename(filename);
            u.setTotalRows(parsed.totalRows());
            u.setValidRows(parsed.validRows().size());
            u.setUploadedBy(user.getUsername());
            // a transaction (RRN) already taken in from an earlier file, in any upload: left out of the new emails
            List<String> rrns = new ArrayList<>();
            for (Transaction t : parsed.validRows()) rrns.add(t.getRrn());
            Map<String, Transaction> earlier = earlierTransactions(rrns);
            for (Transaction t : parsed.validRows()) {
                Transaction first = earlier.get(t.getRrn());
                if (first != null) t.setDuplicateOfTxn(first.getId());
            }
            uploads.save(u);
            uploads.storeFileContent(u.getId(), content);
            em.flush();
            for (Transaction t : parsed.validRows()) {
                t.setUploadId(u.getId());
                transactions.save(t);
            }
            return u;
        });
        return uploadSummary(upload, parsed.rowErrors());
    }

    /** Earlier uploads, newest first, with how far their emails have got. */
    public List<Object> listUploads() {
        List<Upload> recent = em.createQuery("SELECT t FROM Upload t ORDER BY t.id DESC", Upload.class).setMaxResults(100).getResultList();
        List<Integer> ids = new ArrayList<>();
        for (Upload u : recent) ids.add(u.getId());
        if (ids.isEmpty()) ids.add(0);
        Map<Integer, Map<String, Object>> counts = new HashMap<>();
        for (Object[] row : sql.rows("SELECT upload_id, status, COUNT(id) FROM email_dispatches WHERE upload_id IN " + Sql.in(ids)
                + " GROUP BY upload_id, status", ids.toArray())) {
            counts.computeIfAbsent(((Number) row[0]).intValue(), k -> new LinkedHashMap<>()).put((String) row[1], ((Number) row[2]).longValue());
        }
        Map<Integer, Long> dups = new HashMap<>();
        for (Object[] row : sql.rows("SELECT upload_id, COUNT(id) FROM transactions WHERE upload_id IN " + Sql.in(ids)
                + " AND (duplicate_of IS NOT NULL OR duplicate_of_txn IS NOT NULL) GROUP BY upload_id", ids.toArray())) {
            dups.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        List<String> who = new ArrayList<>();
        for (Upload u : recent) who.add(u.getUploadedBy());
        Map<String, String> names = userNames.userNames(who);
        List<Object> out = new ArrayList<>();
        for (Upload u : recent) {
            out.add(Py.map("id", u.getId(), "filename", u.getFilename(), "uploaded_at", Py.iso(u.getUploadedAt()),
                    "uploaded_by_name", name(names, u.getUploadedBy()), "total_rows", u.getTotalRows(),
                    "valid_rows", u.getValidRows(), "duplicate_rows", dups.getOrDefault(u.getId(), 0L),
                    "emails", counts.getOrDefault(u.getId(), new LinkedHashMap<>())));
        }
        return out;
    }

    /** An earlier upload, as shown right after uploading it. */
    public Map<String, Object> getUploadSummary(int uploadId) {
        return uploadSummary(getUploadOr404(uploadId), null);
    }

    /** The upload's valid or faulty rows as a new .xlsx. */
    public FileDownload downloadUploadRows(int uploadId, String kind) throws ExcelParser.FileValidationError {
        if (!ROW_KINDS.containsKey(kind)) throw new ApiException(404, "Unknown row type. Use 'valid' or 'faulty'.");
        Upload upload = getUploadOr404(uploadId);
        byte[] content = uploads.findFileContent(upload.getId());
        if (content == null) throw new ApiException(404, "The original file was not kept for this upload. Upload it again to download rows.");
        ExcelParser.Table[] split = ExcelParser.splitRows(upload.getFilename(), content);
        String base = upload.getFilename().replaceAll("(?i)\\.(xlsx|csv)$", "");
        String filename = (base + "_" + ROW_KINDS.get(kind) + ".xlsx").replaceAll("[^\\w.\\- ]", "_");
        ExcelParser.Table table = kind.equals("valid") ? split[0] : split[1];
        return new FileDownload(ExcelParser.toXlsx(table, "Transactions"), FileDownload.XLSX_TYPE, "attachment; filename=\"" + filename + "\"");
    }

    /** Transactions grouped by partner (NPSB_ORG_CODE) and mail type (TXN_DIRECTION). */
    public Map<String, Object> getUploadGroups(int uploadId) {
        Upload upload = getUploadOr404(uploadId);
        Map<String, Partner> partners = groups.partnersByKey();
        List<Object> out = new ArrayList<>();
        for (Map.Entry<List<String>, List<Transaction>> e : groups.groupByPartner(upload).entrySet()) {
            String key = e.getKey().get(0), mailType = e.getKey().get(1);
            List<Transaction> txns = e.getValue();
            Partner partner = partners.get(key);
            double total = 0;
            List<Object> rows = new ArrayList<>();
            for (Transaction t : txns) {
                total += Py.toDouble(t.getAmount());
                rows.add(Py.map(
                        "excel_row", t.getExcelRow(),
                        "rrn", t.getRrn(),
                        "approval_datetime", Py.or(t.getApprovalDatetimeText(), Py.strftime(t.getTxnDatetime(), "%d-%m-%Y %H:%M:%S")),
                        "txn_type", t.getTxnType(),
                        "txn_id", t.getTxnId(),
                        "status", t.getStatus(),
                        "amount", Py.toDouble(t.getAmount()),
                        "from_account_name", t.getFromAccountName(),
                        "to_account_type", t.getToAccountType(),
                        "stan", t.getStan(),
                        "org_code", t.getOrgCode()));
            }
            out.add(Py.map(
                    "key", key + "|" + mailType,
                    "mail_type", mailType,
                    "mail_type_label", EmailService.MAIL_TYPES.get(mailType),
                    "partner_code", partner != null && partner.getOrgCode() != null ? partner.getOrgCode() : TransactionGroups.codeOfKey(key),
                    "partner_name", partner != null ? partner.getName() : txns.get(0).getPartnerName(),
                    "partner_configured", partner != null,
                    "txn_count", txns.size(),
                    "total_amount", round2(total),
                    "transactions", rows));
        }
        return Py.map(
                "upload_id", upload.getId(),
                "filename", upload.getFilename(),
                "total_rows", upload.getTotalRows(),
                "valid_rows", upload.getValidRows(),
                "groups", out,
                "duplicates", duplicatesOf(upload));
    }

    /** round(x, 2) (the exact binary value, rounded like Python). */
    static double round2(double x) {
        return new BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
    }
}
