package com.nagad.npsb.service;

import com.nagad.npsb.Py;
import com.nagad.npsb.PyJson;
import com.nagad.npsb.models.EmailDispatch;
import com.nagad.npsb.models.PartnerEmail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Recipients the mail server refused (when sending, or later in a bounce message). */
public final class Delivery {
    private Delivery() {}

    public static List<Map<String, Object>> issuesOf(EmailDispatch dispatch) {
        if (Py.isEmpty(dispatch.deliveryIssues)) return new ArrayList<>();
        return PyJson.loadsList(dispatch.deliveryIssues);
    }

    /**
     * The mail server refused this recipient: note it on the email, and flag the partner's address so it
     * is left out of new emails until someone fixes or clears it.
     */
    public static void recordRefused(EmailDispatch dispatch, String address, String reason) {
        address = Py.strip(address).toLowerCase(java.util.Locale.ROOT);
        var now = Py.now();
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Map<String, Object> i : issuesOf(dispatch)) if (!address.equals(i.get("email"))) issues.add(i);
        issues.add(Py.map("email", address, "reason", reason, "at", Py.isoSeconds(now)));
        dispatch.deliveryIssues = PyJson.dumps(issues);
        for (PartnerEmail partnerEmail : dispatch.partner().emails()) {
            if (Py.strip(partnerEmail.email).toLowerCase(java.util.Locale.ROOT).equals(address)) {
                partnerEmail.problem = Py.cut("Refused: " + reason, 255);
                partnerEmail.problemAt = now;
            }
        }
    }
}
