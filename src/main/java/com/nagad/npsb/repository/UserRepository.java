package com.nagad.npsb.repository;

import com.nagad.npsb.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Integer> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    // ---- sign-in and accounts (AuthService)
    List<User> findByUsernameAndIdNot(String username, Integer id);

    List<User> findByEmailAndIdNot(String email, Integer id);

    List<User> findByUsernameOrEmail(String username, String email);

    Optional<User> findFirstByEmailAndUsernameNot(String email, String username);

    List<User> findByIdNotAndIsActive(Integer id, Boolean isActive);

    List<User> findAllByOrderByUsernameAsc();
}
