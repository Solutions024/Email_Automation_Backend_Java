package com.nagad.npsb.entity;

import com.nagad.npsb.util.Py;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/** Replies a user has opened; the others count as new. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@IdClass(EmailReplyRead.Key.class)
@Table(name = "email_reply_reads")
public class EmailReplyRead implements Persistable<EmailReplyRead.Key> {
    @Id
    private Integer replyId;
    @Id
    private Integer userId;
    private LocalDateTime readAt = Py.now();

    /** The primary key (replyId, userId). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Integer replyId;
        private Integer userId;
    }

    /** The key is set by the code, so save() must be told when the row is new (then it INSERTs, else it UPDATEs). */
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean newRow = true;

    @Override
    public EmailReplyRead.Key getId() {
        return new Key(replyId, userId);
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
}
