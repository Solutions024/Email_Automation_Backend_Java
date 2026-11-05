package com.nagad.npsb.models;

import com.nagad.npsb.Py;
import com.nagad.npsb.db.Col;
import com.nagad.npsb.db.Deferred;
import com.nagad.npsb.db.Entity;
import com.nagad.npsb.db.Id;
import com.nagad.npsb.db.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** A Bank / MFS / PSP that receives our emails. Matched to file rows by name (KEY_ORG_NM), ignoring case; names are unique. */
@Table("partners")
public class Partner extends Entity {
    @Id public Integer id;
    public String name;
    public Boolean isActive = true;

    /** The partner's addresses, oldest first. */
    public List<PartnerEmail> emails() {
        return many("emails", PartnerEmail.class, "WHERE t.partner_id = ? ORDER BY t.id", id);
    }

    /** Replace the address list (partner.emails = [...]): removed addresses are deleted, new ones added. */
    public void replaceEmails(List<PartnerEmail> rows) {
        for (PartnerEmail old : emails()) {
            if (!rows.contains(old)) session().delete(old);
        }
        for (PartnerEmail row : rows) {
            row.partnerId = id;
            session().add(row);
        }
        setMany("emails", new java.util.ArrayList<>(rows));
    }
}
