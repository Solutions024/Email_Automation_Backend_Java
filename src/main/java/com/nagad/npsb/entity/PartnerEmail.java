package com.nagad.npsb.entity;

import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** One recipient address of a partner. A partner can have many. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "partner_emails")
public class PartnerEmail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer partnerId;
    private String email;
    private Boolean isActive = true;
    private String problem;  // set when the mail server refused this address: left out of new emails until fixed
    private LocalDateTime problemAt;
}
