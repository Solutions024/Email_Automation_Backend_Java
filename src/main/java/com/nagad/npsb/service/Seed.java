package com.nagad.npsb.service;

import com.nagad.npsb.entity.EmailTemplate;
import com.nagad.npsb.entity.Partner;
import com.nagad.npsb.entity.PartnerEmail;
import com.nagad.npsb.repository.EmailTemplateRepository;
import com.nagad.npsb.repository.PartnerEmailRepository;
import com.nagad.npsb.repository.PartnerRepository;
import com.nagad.npsb.util.Py;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** The standard email templates of a new database (and demo partners, for trying the app out). */
@Service
public class Seed {

    /** Demo partners for a new, empty database: the partners in the sample file, with their NPSB_ORG_CODE */
    private static final Object[][] DEMO_PARTNERS = {
        {"22002", "bKash", true, new Object[][]{{"email.example@gmail.com", true}}},
        {"22001", "Rocket", true, new Object[][]{{"email.example@gmail.com", true}}},
        {"22004", "TallyPay", true, new Object[][]{{"email.example@gmail.com", false}}},  // no ACTIVE email -> shows the error message
        // UPAY is in the sample file but intentionally NOT configured -> "no partner with the code ..."
    };

    private final EmailTemplateRepository templates;
    private final PartnerRepository partners;
    private final PartnerEmailRepository partnerEmails;

    public Seed(EmailTemplateRepository templates, PartnerRepository partners, PartnerEmailRepository partnerEmails) {
        this.templates = templates;
        this.partners = partners;
        this.partnerEmails = partnerEmails;
    }

    /** A new database gets the two standard templates, as the defaults (committed). */
    @Transactional
    public void seedTemplates() {
        if (templates.count() > 0) return;
        for (Map.Entry<String, EmailService.DefaultTemplate> e : EmailService.DEFAULT_TEMPLATES.entrySet()) {
            EmailTemplate t = new EmailTemplate();
            t.setName(e.getValue().name());
            t.setMailType(e.getKey());
            t.setSubject(e.getValue().subject());
            t.setBodyHtml(Py.strip(e.getValue().bodyHtml()));
            t.setDescription("Standard wording (docs/Email_Template.docx)");
            t.setIsDefault(true);
            templates.save(t);
        }
    }

    /** Demo partners for a new, empty database (committed). */
    @Transactional
    public void seedPartners() {
        if (partners.count() > 0) return;
        for (Object[] p : DEMO_PARTNERS) {
            Partner partner = new Partner();
            partner.setOrgCode((String) p[0]);
            partner.setName((String) p[1]);
            partner.setIsActive((Boolean) p[2]);
            partners.save(partner);  // inserted now: its id is set
            for (Object[] e : (Object[][]) p[3]) {
                PartnerEmail pe = new PartnerEmail();
                pe.setEmail((String) e[0]);
                pe.setIsActive((Boolean) e[1]);
                pe.setPartnerId(partner.getId());
                partnerEmails.save(pe);
            }
        }
    }
}
