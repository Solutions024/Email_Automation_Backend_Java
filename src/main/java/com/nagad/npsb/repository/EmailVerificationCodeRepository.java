package com.nagad.npsb.repository;

import com.nagad.npsb.entity.EmailVerificationCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Integer> {
    /** The newest unused code of this user for this purpose and address. */
    Optional<EmailVerificationCode> findFirstByUserIdAndPurposeAndEmailAndUsedAtIsNullOrderByIdDesc(Integer userId, String purpose, String email);
}
