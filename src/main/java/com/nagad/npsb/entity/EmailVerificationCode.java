package com.nagad.npsb.entity;

import com.nagad.npsb.util.Py;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** A 6-digit code emailed to prove an address (purpose login / change / recovery). Only its hash is stored. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "email_verification_codes")
public class EmailVerificationCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer userId;
    private String email;  // the address the code was sent to
    private String purpose;
    private String codeHash;
    private Integer attempts = 0;
    private LocalDateTime createdAt = Py.utcnow();
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;  // also set when replaced
}
