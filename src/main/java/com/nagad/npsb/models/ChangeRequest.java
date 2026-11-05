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

/** A change to partners, templates or template variables waiting for approval (maker-checker), and the change history. */
@Table("change_requests")
public class ChangeRequest extends Entity {
    @Id public Integer id;
    public String entity;  // partner / template / variable / settings
    public Integer entityId;
    public String action;  // create / update / delete / default
    public String summary = "";
    public String before;  // JSON
    public String after;  // JSON
    public String status = "pending";  // pending / approved / rejected / cancelled / applied
    public String requestedBy;
    public LocalDateTime requestedAt = Py.now();
    public String decidedBy;
    public LocalDateTime decidedAt;
    public String note;
}
