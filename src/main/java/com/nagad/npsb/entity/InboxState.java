package com.nagad.npsb.entity;

import com.nagad.npsb.util.Py;
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

/** How far the mailbox has been read (IMAP UIDs; read_until was used by the old Microsoft 365 reader). */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "inbox_state")
public class InboxState implements Persistable<String> {
    @Id
    private String mailbox;
    private Long uidvalidity;
    private Long lastUid = 0L;
    private String readUntil;
    private LocalDateTime updatedAt = Py.now();

    /** The key is set by the code, so save() must be told when the row is new (then it INSERTs, else it UPDATEs). */
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean newRow = true;

    @Override
    public String getId() {
        return mailbox;
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
