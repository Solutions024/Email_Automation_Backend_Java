package com.nagad.npsb.repository;

import com.nagad.npsb.entity.TrustedDevice;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Integer> {
    /** This browser's trusted-device record for this user, if it is still valid. */
    Optional<TrustedDevice> findFirstByTokenHashAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(String tokenHash, Integer userId, LocalDateTime now);
}
