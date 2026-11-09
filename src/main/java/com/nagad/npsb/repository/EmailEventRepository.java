package com.nagad.npsb.repository;

import com.nagad.npsb.entity.EmailEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailEventRepository extends JpaRepository<EmailEvent, Integer> {
}
