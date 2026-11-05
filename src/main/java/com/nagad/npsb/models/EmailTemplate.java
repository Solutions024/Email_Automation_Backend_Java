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

/** Subject and body of the email to a partner (Email Templates page). Several per mail type; one default per mail type and purpose. */
@Table("email_templates")
public class EmailTemplate extends Entity {
    @Id public Integer id;
    public String name;
    public String mailType;  // inward / outward
    public String description = "";
    public String subject;
    public String bodyHtml;
    public String purpose = "initial";  // initial: the first email; reminder: follow-ups
    public Boolean isDefault = false;
    public String createdBy;
    public LocalDateTime createdAt = Py.now();
    public String updatedBy;
    public LocalDateTime updatedAt = Py.now();

    @Override
    protected void beforeUpdate() {
        updatedAt = Py.now();
    }
}
