package com.nagad.npsb.repository;

import com.nagad.npsb.entity.EmailDispatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailDispatchRepository extends JpaRepository<EmailDispatch, Integer> {
    /** The email with this case ID and status (CaseIds: a reply quoting our email). */
    Optional<EmailDispatch> findFirstByCaseIdAndStatus(String caseId, String status);

    /** Emails made before case IDs existed, oldest first (CaseIds.backfill). */
    List<EmailDispatch> findByCaseIdIsNullOrderByIdAsc();
}
