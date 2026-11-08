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

/** A valid row from the uploaded file ("Final_Report_for_DMS_Claim" format; file column in the comments). */
@Table("transactions")
public class Transaction extends Entity {
    @Id public Integer id;
    public Integer uploadId;
    public Integer excelRow;
    public String rrn;  // NPSB_TXN_RRN
    public LocalDate approvalDate;  // APPROVAL_DATE
    public String approvalDatetimeText;  // APPROVAL_DATETIME as written
    public LocalDateTime txnDatetime;  // APPROVAL_DATETIME, read
    public String txnType;  // TXN_TYPE
    public String txnId;  // TXN_ID
    @Col("nagad_status") public String status;  // FIRST_TXN_STATUS_NAME
    public BigDecimal amount;  // TXN_AMOUNT
    public String fromAccountName;  // FROMAC_NAME
    public String toAccountType;  // TOAC_TYPE
    public String stan;  // ORG_TXN_STAN
    @Col("dest_partner_name") public String partnerName;  // KEY_ORG_NM
    @Col("dest_institution_code") public String orgCode;  // NPSB_ORG_CODE
    public String mailType;  // TXN_DIRECTION: inward / outward
    public Integer duplicateOf;  // older uploads: already reported to the partner in this email: left out
    public Integer duplicateOfTxn;  // the same RRN was in an earlier upload (this transaction): left out

    /** Left out of the emails: the transaction was already taken in from another file. */
    public boolean isDuplicate() {
        return (duplicateOf != null && duplicateOf != 0) || duplicateOfTxn != null;
    }
}
