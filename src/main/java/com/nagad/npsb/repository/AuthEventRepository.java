package com.nagad.npsb.repository;

import com.nagad.npsb.entity.AuthEvent;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthEventRepository extends JpaRepository<AuthEvent, Integer> {
    /** My Activity: the user's last 25 events. */
    List<AuthEvent> findTop25ByUserIdOrderByCreatedAtDesc(Integer userId);

    /** The audit log: newest first. */
    List<AuthEvent> findAllByOrderByCreatedAtDescIdDesc(Limit limit);
}
