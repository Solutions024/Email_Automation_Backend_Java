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

/** A sign-up waiting for its emailed code (the account is created when the code is entered). */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "pending_registrations")
public class PendingRegistration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String username;
    private String email;
    private String fullName = "";
    @Column(name = "`role`")
    private String role = "user";
    private String passwordHash;
    private String codeHash;
    private Integer attempts = 0;
    private LocalDateTime sentAt = Py.utcnow();  // last code emailed
    private Integer codesSent = 1;
    private LocalDateTime createdAt = Py.utcnow();
    private LocalDateTime expiresAt;
}
