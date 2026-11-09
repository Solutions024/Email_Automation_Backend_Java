package com.nagad.npsb.repository;

import com.nagad.npsb.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Integer> {
}
