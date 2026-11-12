package com.nagad.npsb.repository;

import com.nagad.npsb.entity.Partner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PartnerRepository extends JpaRepository<Partner, Integer> {
    /** The partner with this NPSB_ORG_CODE (as stored: OrgCodes.normalize). */
    Optional<Partner> findByOrgCode(String orgCode);
}
