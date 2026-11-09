package com.nagad.npsb.service.dispatch;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.Partner;
import com.nagad.npsb.entity.Transaction;
import com.nagad.npsb.entity.Upload;
import com.nagad.npsb.repository.PartnerRepository;
import com.nagad.npsb.repository.TransactionRepository;
import com.nagad.npsb.repository.UploadRepository;
import com.nagad.npsb.service.EmailService;
import com.nagad.npsb.util.Py;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An upload's transactions grouped by partner and mail type: one email each. */
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

    /** Partners and KEY_ORG_NM are matched by name, ignoring case and extra spaces: ' UPAY ' == 'Upay'. */
    public static String nameKey(String name) {
        return Py.casefold(Py.squash(String.valueOf(name)));
    }

    /** An upload's valid rows, in file order. */
    public List<Transaction> transactionsOf(Upload upload) {
        return transactions.findByUploadIdOrderByIdAsc(upload.getId());
    }

    /** {(partner name key, mail type): [transactions]} for one upload: one email each. */
    public LinkedHashMap<List<String>, List<Transaction>> groupByPartner(Upload upload) {
        Map<List<String>, List<Transaction>> grouped = new HashMap<>();
        for (Transaction t : transactionsOf(upload)) {
            if (EmailService.MAIL_TYPES.containsKey(t.getMailType()) && !t.isDuplicate()) {  // already in an earlier file: left out
                grouped.computeIfAbsent(List.of(nameKey(t.getPartnerName()), t.getMailType()), k -> new ArrayList<>()).add(t);
            }
        }
        List<List<String>> keys = new ArrayList<>(grouped.keySet());
        keys.sort(Comparator.<List<String>, String>comparing(k -> k.get(0)).thenComparing(k -> MAIL_TYPE_ORDER.indexOf(k.get(1))));
        LinkedHashMap<List<String>, List<Transaction>> out = new LinkedHashMap<>();
        for (List<String> k : keys) out.put(k, grouped.get(k));
        return out;
    }

    /** The transactions an email was built from. */
    public List<Transaction> dispatchTransactions(EmailDispatch d) {
        String mailType = "outward_reminder".equals(d.getMailType()) ? "outward" : d.getMailType();
        List<Transaction> found = groupByPartner(uploadOf(d)).get(List.of(nameKey(partnerOf(d).getName()), mailType));
        return found == null ? new ArrayList<>() : found;
    }

    /** name key -> partner (the last one with that key wins, as before). */
    public Map<String, Partner> partnersByName() {
        Map<String, Partner> out = new HashMap<>();
        for (Partner p : sql.all(Partner.class, "ORDER BY t.id")) out.put(nameKey(p.getName()), p);
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
