package com.datn.auth.repository;

import com.datn.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    List<User> findByEmailIn(Collection<String> emails);
    boolean existsByEmail(String email);
    boolean existsByRoleAndPasswordHashNot(User.Role role, String passwordHash);
}
