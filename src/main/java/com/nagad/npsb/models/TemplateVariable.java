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

/** A {{placeholder}} declared on the Email Templates page: a fixed text, or a value from the email's transactions. */
@Table("template_variables")
public class TemplateVariable extends Entity {
    @Id public Integer id;
    public String name;
    public String description = "";
    public String source;  // text / column
    public String value = "";  // source text
    public String column;  // source column: file column
    public String aggregate;  // list / first / sum / count
    public String createdBy;
    public LocalDateTime createdAt = Py.now();
}
