package com.datn.auth.repository;

import com.datn.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByGoogleSubject(String googleSubject);
    List<User> findByEmailIn(Collection<String> emails);
    boolean existsByEmail(String email);
    boolean existsByRoleAndPasswordHashNot(User.Role role, String passwordHash);

    @org.springframework.data.jpa.repository.Query("select u from User u where :q = '' or lower(u.email) like concat('%', :q, '%') or lower(u.fullName) like concat('%', :q, '%')")
    org.springframework.data.domain.Page<User> search(@org.springframework.data.repository.query.Param("q") String q, org.springframework.data.domain.Pageable page);
}
