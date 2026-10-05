package com.datn.auth.config;

import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Environment environment;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwords, Environment environment,
            @Value("${auth.bootstrap.email:}") String email,
            @Value("${auth.bootstrap.password:}") String password) {
        this.users = users;
        this.passwords = passwords;
        this.environment = environment;
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() && password.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod")) &&
                    !users.existsByRoleAndPasswordHashNot(User.Role.ADMIN, "!disabled-legacy-seed!")) {
                throw new IllegalStateException("Configure Admin bootstrap credentials for a fresh production install");
            }
            return;
        }
        if (!email.contains("@") || password.length() < 16) {
            throw new IllegalStateException("Admin bootstrap requires an email and a password of at least 16 characters");
        }
        User admin = users.findByEmail(email).orElseGet(() -> User.builder()
                .email(email).fullName("Administrator").role(User.Role.ADMIN)
                .systemPrivilege(User.SystemPrivilege.ADMIN).build());
        if (admin.getRole() != User.Role.ADMIN || admin.getSystemPrivilege() != User.SystemPrivilege.ADMIN) {
            throw new IllegalStateException("Admin bootstrap email belongs to a non-admin account");
        }
        admin.setPasswordHash(passwords.encode(password));
        users.save(admin);
    }
}
