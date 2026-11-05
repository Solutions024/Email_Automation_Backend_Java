package com.nagad.npsb.models;

import com.nagad.npsb.Py;
import com.nagad.npsb.db.Col;
import com.nagad.npsb.db.Deferred;
import com.nagad.npsb.db.Entity;
import com.nagad.npsb.db.Id;
import com.nagad.npsb.db.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** A file attached to a partner's message. */
@Table("email_reply_attachments")
public class EmailReplyAttachment extends Entity {
    @Id public Integer id;
    public Integer replyId;
    public String filename;
    public String contentType;
    public Integer size;
    @Deferred public byte[] content;

    /** The file (deferred: read only when needed). */
    public byte[] data() {
        if (content == null && persisted()) {
            content = session().bytes("SELECT content FROM email_reply_attachments WHERE id = ?", id);
        }
        return content;
    }
}
