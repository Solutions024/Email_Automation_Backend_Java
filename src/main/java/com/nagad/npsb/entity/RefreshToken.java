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

/** One signed-in session (browser/device). Only a SHA-256 hash of the token is stored. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer userId;
    private String tokenHash;
    private String familyId;  // same for all rotations of a session
    private Boolean rememberMe = false;
    private String userAgent = "";
    private String ipAddress = "";
    private LocalDateTime createdAt = Py.utcnow();
    private LocalDateTime lastUsedAt = Py.utcnow();
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Integer replacedById;
}
