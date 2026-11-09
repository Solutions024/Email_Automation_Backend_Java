package com.nagad.npsb.entity;

import com.nagad.npsb.util.AppLog;
import com.nagad.npsb.util.Py;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Audit log: sign-ins (successful and failed), sign-outs, password/email changes, account changes. */
@Getter
@Setter
@EntityListeners(AppLogListener.class)
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "auth_events")
public class AuthEvent implements AppLogged {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer userId;
    private String username = "";  // what was typed, for failed logins
    private String event;
    private Boolean success = true;
    private String detail = "";
    private String ipAddress = "";
    private String userAgent = "";
    private LocalDateTime createdAt = Py.utcnow();

    @Override
    public void appLog(boolean inserted) {
        if (inserted) AppLog.event("AUTH", AppLog.kv("event", event, "user", username,
                "ok", success, "ip", ipAddress, "detail", detail, "agent", userAgent));
    }
}
