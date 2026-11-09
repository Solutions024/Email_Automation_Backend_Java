package com.nagad.npsb.repository;

import com.nagad.npsb.entity.PendingRegistration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, Integer> {
    /** The newest unfinished sign-up for this email that has not expired. */
    Optional<PendingRegistration> findFirstByEmailAndExpiresAtAfterOrderByIdDesc(String email, LocalDateTime now);

    /** The newest unfinished sign-up for this username that has not expired. */
    Optional<PendingRegistration> findFirstByUsernameAndExpiresAtAfterOrderByIdDesc(String username, LocalDateTime now);
}
