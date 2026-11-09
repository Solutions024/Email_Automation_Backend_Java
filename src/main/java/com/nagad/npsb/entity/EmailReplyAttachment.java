package com.nagad.npsb.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A file attached to a partner's message. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "email_reply_attachments")
public class EmailReplyAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer replyId;
    private String filename;
    private String contentType;
    private Integer size;
    @Basic(fetch = FetchType.LAZY)  // listings use AttachmentInfo (EmailReplyAttachmentRepository) instead
    private byte[] content;
}
