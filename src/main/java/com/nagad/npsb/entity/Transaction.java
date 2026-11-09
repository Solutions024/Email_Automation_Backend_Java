package com.nagad.npsb.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** A valid row from the uploaded file ("Final_Report_for_DMS_Claim" format; file column in the comments). */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer uploadId;
    private Integer excelRow;
    private String rrn;  // NPSB_TXN_RRN
    private LocalDate approvalDate;  // APPROVAL_DATE
    private String approvalDatetimeText;  // APPROVAL_DATETIME as written
    private LocalDateTime txnDatetime;  // APPROVAL_DATETIME, read
    private String txnType;  // TXN_TYPE
    private String txnId;  // TXN_ID
    @Column(name = "nagad_status")
    private String status;  // FIRST_TXN_STATUS_NAME
    private BigDecimal amount;  // TXN_AMOUNT
    private String fromAccountName;  // FROMAC_NAME
    private String toAccountType;  // TOAC_TYPE
    private String stan;  // ORG_TXN_STAN
    @Column(name = "dest_partner_name")
    private String partnerName;  // KEY_ORG_NM
    @Column(name = "dest_institution_code")
    private String orgCode;  // NPSB_ORG_CODE
    private String mailType;  // TXN_DIRECTION: inward / outward
    private Integer duplicateOf;  // older uploads: already reported to the partner in this email: left out
    private Integer duplicateOfTxn;  // the same RRN was in an earlier upload (this transaction): left out

    /** Left out of the emails: the transaction was already taken in from another file. */
    public boolean isDuplicate() {
        return (duplicateOf != null && duplicateOf != 0) || duplicateOfTxn != null;
    }
}
