package com.nagad.npsb.repository;

import com.nagad.npsb.entity.ChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Integer> {
}
