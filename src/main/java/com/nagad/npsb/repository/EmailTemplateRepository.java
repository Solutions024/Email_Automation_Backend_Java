package com.nagad.npsb.repository;

import com.nagad.npsb.entity.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Integer> {
}
