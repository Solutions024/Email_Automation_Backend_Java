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

/** A browser where the user signed in with the emailed code and ticked "Keep me signed in". */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "trusted_devices")
public class TrustedDevice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer userId;
    private String tokenHash;
    private String userAgent = "";
    private String ipAddress = "";
    private LocalDateTime createdAt = Py.utcnow();
    private LocalDateTime lastUsedAt = Py.utcnow();
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
}
