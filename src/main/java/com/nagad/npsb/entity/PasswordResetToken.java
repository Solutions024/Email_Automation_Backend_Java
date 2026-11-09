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

/** A one-time "forgot password" link. Only the SHA-256 hash is stored. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "password_reset_tokens")
public class PasswordResetToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer userId;
    private String tokenHash;
    private LocalDateTime createdAt = Py.utcnow();
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private String ipAddress = "";
}
