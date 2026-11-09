package com.nagad.npsb.repository;

import com.nagad.npsb.entity.PartnerEmail;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerEmailRepository extends JpaRepository<PartnerEmail, Integer> {
    /** A partner's addresses, oldest first (partner.emails in the Python backend). */
    List<PartnerEmail> findByPartnerIdOrderByIdAsc(Integer partnerId);
}
