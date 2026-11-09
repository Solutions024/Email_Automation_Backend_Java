package com.nagad.npsb.repository;

import com.nagad.npsb.entity.RefreshToken;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Integer> {
    Optional<RefreshToken> findFirstByTokenHash(String tokenHash);

    /** The user's signed-in sessions, most recently used first. */
    List<RefreshToken> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(Integer userId, LocalDateTime now);
}
