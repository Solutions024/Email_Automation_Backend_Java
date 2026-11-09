package com.nagad.npsb.entity;

import com.nagad.npsb.util.Py;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** A {{placeholder}} declared on the Email Templates page: a fixed text, or a value from the email's transactions. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "template_variables")
public class TemplateVariable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String name;
    private String description = "";
    private String source;  // text / column
    @Column(name = "`value`")
    private String value = "";  // source text
    @Column(name = "`column`")
    private String column;  // source column: file column
    private String aggregate;  // list / first / sum / count
    private String createdBy;
    private LocalDateTime createdAt = Py.now();
}
