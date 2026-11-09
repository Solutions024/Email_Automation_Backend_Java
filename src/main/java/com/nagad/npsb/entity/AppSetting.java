package com.nagad.npsb.entity;

import com.nagad.npsb.util.Py;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/** One setting changed in the app (Email Configuration, System Settings, alert times). Missing rows use the .env default. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "app_settings")
public class AppSetting implements Persistable<String> {
    @Id
    @Column(name = "`key`")
    private String key;
    @Column(name = "`value`")
    private String value;  // JSON
    private String updatedBy;
    private LocalDateTime updatedAt = Py.now();

    /** The key is set by the code, so save() must be told when the row is new (then it INSERTs, else it UPDATEs). */
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean newRow = true;

    @Override
    public String getId() {
        return key;
    }

    @Override
    public boolean isNew() {
        return newRow;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        newRow = false;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Py.now();
    }
}
