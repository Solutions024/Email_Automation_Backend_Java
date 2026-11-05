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

/** How far the mailbox has been read (IMAP UIDs; read_until was used by the old Microsoft 365 reader). */
@Table("inbox_state")
public class InboxState extends Entity {
    @Id(auto = false) public String mailbox;
    public Long uidvalidity;
    public Long lastUid = 0L;
    public String readUntil;
    public LocalDateTime updatedAt = Py.now();

    @Override
    protected void beforeUpdate() {
        updatedAt = Py.now();
    }
}
