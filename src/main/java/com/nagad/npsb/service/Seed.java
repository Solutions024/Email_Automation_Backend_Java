package com.nagad.npsb.service;

import com.nagad.npsb.Py;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.EmailTemplate;
import com.nagad.npsb.models.Partner;
import com.nagad.npsb.models.PartnerEmail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The standard email templates of a new database (and demo partners, for trying the app out). */
public final class Seed {
    private Seed() {}

    /** Demo partners for a new, empty database: the partners in the sample file, matched by name (KEY_ORG_NM) */
    private static final Object[][] DEMO_PARTNERS = {
        {"bKash", true, new Object[][]{{"email.example@gmail.com", true}}},
        {"Rocket", true, new Object[][]{{"email.example@gmail.com", true}}},
        {"TallyPay", true, new Object[][]{{"email.example@gmail.com", false}}},  // no ACTIVE email -> shows the error message
        // UPAY is in the sample file but intentionally NOT configured -> "no partner with this name"
    };

    /** A new database gets the two standard templates, as the defaults. */
    public static void seedTemplates(Db db) {
        if (db.count("SELECT COUNT(*) FROM email_templates") > 0) return;
        for (Map.Entry<String, EmailService.DefaultTemplate> e : EmailService.DEFAULT_TEMPLATES.entrySet()) {
            EmailTemplate t = new EmailTemplate();
            t.name = e.getValue().name();
            t.mailType = e.getKey();
            t.subject = e.getValue().subject();
            t.bodyHtml = Py.strip(e.getValue().bodyHtml());
            t.description = "Standard wording (docs/Email_Template.docx)";
            t.isDefault = true;
            db.add(t);
        }
        db.commit();
    }

    public static void seedPartners(Db db) {
        if (db.count("SELECT COUNT(*) FROM partners") > 0) return;
        for (Object[] p : DEMO_PARTNERS) {
            Partner partner = new Partner();
            partner.name = (String) p[0];
            partner.isActive = (Boolean) p[1];
            db.add(partner);
            db.flush();
            List<PartnerEmail> emails = new ArrayList<>();
            for (Object[] e : (Object[][]) p[2]) {
                PartnerEmail pe = new PartnerEmail();
                pe.email = (String) e[0];
                pe.isActive = (Boolean) e[1];
                emails.add(pe);
            }
            partner.replaceEmails(emails);
        }
        db.commit();
    }
}
