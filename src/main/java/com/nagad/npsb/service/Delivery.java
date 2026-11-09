package com.nagad.npsb.service;

import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.PartnerEmail;
import com.nagad.npsb.repository.PartnerEmailRepository;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.util.PyJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Recipients the mail server refused (when sending, or later in a bounce message). */
@Service
public class Delivery {

    private final PartnerEmailRepository partnerEmails;

    public Delivery(PartnerEmailRepository partnerEmails) {
        this.partnerEmails = partnerEmails;
    }

    public static List<Map<String, Object>> issuesOf(EmailDispatch dispatch) {
        if (Py.isEmpty(dispatch.getDeliveryIssues())) return new ArrayList<>();
        return PyJson.loadsList(dispatch.getDeliveryIssues());
    }

    /**
     * The mail server refused this recipient: note it on the email, and flag the partner's address so it
     * is left out of new emails until someone fixes or clears it.
     *
     * Call it inside the transaction that saves the email: the dispatch is changed in memory (written when the
     * caller's transaction commits, as before); the partner's addresses are written with that transaction (or with
     * one of their own when there is none).
     */
    @Transactional
    public void recordRefused(EmailDispatch dispatch, String address, String reason) {
        address = Py.strip(address).toLowerCase(java.util.Locale.ROOT);
        var now = Py.now();
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Map<String, Object> i : issuesOf(dispatch)) if (!address.equals(i.get("email"))) issues.add(i);
        issues.add(Py.map("email", address, "reason", reason, "at", Py.isoSeconds(now)));
        dispatch.setDeliveryIssues(PyJson.dumps(issues));
        // dispatch.partner().emails()
        List<PartnerEmail> emails = dispatch.getPartnerId() == null ? List.of()
                : partnerEmails.findByPartnerIdOrderByIdAsc(dispatch.getPartnerId());
        for (PartnerEmail partnerEmail : emails) {
            if (Py.strip(partnerEmail.getEmail()).toLowerCase(java.util.Locale.ROOT).equals(address)) {
                partnerEmail.setProblem(Py.cut("Refused: " + reason, 255));
                partnerEmail.setProblemAt(now);
            }
        }
    }
}
