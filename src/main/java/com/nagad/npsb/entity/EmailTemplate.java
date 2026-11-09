package com.nagad.npsb.entity;

import com.nagad.npsb.util.Py;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Subject and body of the email to a partner (Email Templates page). Several per mail type; one default per mail type and purpose. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "email_templates")
public class EmailTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String name;
    private String mailType;  // inward / outward
    private String description = "";
    private String subject;
    private String bodyHtml;
    private String purpose = "initial";  // initial: the first email; reminder: follow-ups
    private Boolean isDefault = false;
    private String createdBy;
    private LocalDateTime createdAt = Py.now();
    private String updatedBy;
    private LocalDateTime updatedAt = Py.now();

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Py.now();
    }
}
