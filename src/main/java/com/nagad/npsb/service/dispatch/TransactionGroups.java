package com.nagad.npsb.service.dispatch;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.Partner;
import com.nagad.npsb.entity.Transaction;
import com.nagad.npsb.entity.Upload;
import com.nagad.npsb.repository.PartnerRepository;
import com.nagad.npsb.repository.TransactionRepository;
import com.nagad.npsb.repository.UploadRepository;
import com.nagad.npsb.service.EmailService;
import com.nagad.npsb.service.OrgCodes;
import com.nagad.npsb.util.Py;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An upload's transactions grouped by partner (its NPSB_ORG_CODE) and mail type: one email each. */
@Component
public class TransactionGroups {
    public static final List<String> MAIL_TYPE_ORDER = new ArrayList<>(EmailService.MAIL_TYPES.keySet());

    private final Sql sql;
    private final PartnerRepository partners;
    private final UploadRepository uploads;
    private final TransactionRepository transactions;

    public TransactionGroups(Sql sql, PartnerRepository partners, UploadRepository uploads, TransactionRepository transactions) {
        this.sql = sql;
        this.partners = partners;
        this.uploads = uploads;
        this.transactions = transactions;
    }

    /** A name compared ignoring case and extra spaces: ' UPAY ' == 'Upay' (partner names; older rows without a code). */
    public static String nameKey(String name) {
        return Py.casefold(Py.squash(String.valueOf(name)));
    }

    /** An upload's valid rows, in file order. */
    public List<Transaction> transactionsOf(Upload upload) {
        return transactions.findByUploadIdOrderByIdAsc(upload.getId());
    }

    /**
     * The partner key of a file row: its NPSB_ORG_CODE without leading zeros ("0103" -> "103"). Rows of older uploads
     * without a code: "name:" + their KEY_ORG_NM name key, as they were grouped before codes.
     */
    public static String partnerKey(Transaction t) {
        String code = OrgCodes.normalize(t.getOrgCode());
        return code != null ? code : NAME_KEY + nameKey(t.getPartnerName());
    }

    private static final String NAME_KEY = "name:";

    /** The NPSB_ORG_CODE of a partner key, or null for a name key (an older row without a code). */
    public static String codeOfKey(String key) {
        return key == null || key.startsWith(NAME_KEY) ? null : key;
    }

    /** Codes in number order ("7" before "103"), then the older name keys. */
    private static final Comparator<String> KEY_ORDER = Comparator
            .<String, Boolean>comparing(k -> k.startsWith(NAME_KEY))
            .thenComparingInt(String::length)
            .thenComparing(Comparator.naturalOrder());

    /** {(partner key, mail type): [transactions]} for one upload: one email each. The key is the code (partnerKey). */
    public LinkedHashMap<List<String>, List<Transaction>> groupByPartner(Upload upload) {
        Map<List<String>, List<Transaction>> grouped = new HashMap<>();
        for (Transaction t : transactionsOf(upload)) {
            if (EmailService.MAIL_TYPES.containsKey(t.getMailType()) && !t.isDuplicate()) {  // already in an earlier file: left out
                grouped.computeIfAbsent(List.of(partnerKey(t), t.getMailType()), k -> new ArrayList<>()).add(t);
            }
        }
        List<List<String>> keys = new ArrayList<>(grouped.keySet());
        keys.sort(Comparator.<List<String>, String>comparing(k -> k.get(0), KEY_ORDER)
                .thenComparing(k -> MAIL_TYPE_ORDER.indexOf(k.get(1))));
        LinkedHashMap<List<String>, List<Transaction>> out = new LinkedHashMap<>();
        for (List<String> k : keys) out.put(k, grouped.get(k));
        return out;
    }

    /**
     * Whether a file row belongs to this partner: the same NPSB_ORG_CODE ("0103" == "103"). A row or partner without a
     * code (older data) is matched by name, as before codes.
     */
    public static boolean belongsTo(Transaction t, Partner p) {
        if (p == null) return false;
        String rowCode = OrgCodes.normalize(t.getOrgCode());
        if (rowCode != null && p.getOrgCode() != null) return rowCode.equals(p.getOrgCode());
        return nameKey(t.getPartnerName()).equals(nameKey(p.getName()));
    }

    /** The transactions an email was built from: its partner's rows (by code) of its mail type in its upload. */
    public List<Transaction> dispatchTransactions(EmailDispatch d) {
        String mailType = "outward_reminder".equals(d.getMailType()) ? "outward" : d.getMailType();
        Partner p = partnerOf(d);
        Upload upload = uploadOf(d);
        if (p == null || upload == null) return new ArrayList<>();
        LinkedHashMap<List<String>, List<Transaction>> groups = groupByPartner(upload);
        List<Transaction> found = p.getOrgCode() != null ? groups.get(List.of(p.getOrgCode(), mailType)) : null;
        if (found != null) return found;
        // an email made before codes: its rows by the partner's name (whatever code they carry)
        List<Transaction> byName = new ArrayList<>();
        for (List<Transaction> rows : groups.values()) {
            for (Transaction t : rows) {
                if (mailType.equals(t.getMailType()) && nameKey(t.getPartnerName()).equals(nameKey(p.getName()))) byName.add(t);
            }
        }
        if (!byName.isEmpty() || p.getOrgCode() != null) return byName;
        return new ArrayList<>();
    }

    /**
     * partner key -> partner: every partner by its code, and (for older rows without a code) by "name:" + its name
     * key. A code wins over a name.
     */
    public Map<String, Partner> partnersByKey() {
        Map<String, Partner> out = new HashMap<>();
        List<Partner> all = sql.all(Partner.class, "ORDER BY t.id");
        for (Partner p : all) out.put(NAME_KEY + nameKey(p.getName()), p);
        for (Partner p : all) if (p.getOrgCode() != null) out.put(p.getOrgCode(), p);
        return out;
    }

    /** The email's transactions as an attached Excel file ("please find attached" in the templates). */
    public List<EmailService.Attachment> attachmentFor(EmailDispatch d) {
        List<Transaction> txns = dispatchTransactions(d);
        if (txns.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(List.of(new EmailService.Attachment(EmailService.attachmentName(partnerOf(d).getName(), d.getMailType(), txns),
                EmailService.transactionsXlsx(txns), null)));
    }

    /** d.partner() */
    public Partner partnerOf(EmailDispatch d) {
        return d.getPartnerId() == null ? null : partners.findById(d.getPartnerId()).orElse(null);
    }

    /** d.upload() */
    public Upload uploadOf(EmailDispatch d) {
        return d.getUploadId() == null ? null : uploads.findById(d.getUploadId()).orElse(null);
    }
}
