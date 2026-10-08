package com.datn.auth.controller;

import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Chạy trên PostgreSQL test cô lập (Flyway thật): AUTH_TEST_DATABASE=true + SPRING_DATASOURCE_*. */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production",
        "auth.internal.notification-contact-token=test-only-notification-contact-token"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AUTH_TEST_DATABASE", matches = "true")
class InternalContactPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    @Test
    void returnsPersistedContactsInOneBatchWithoutPrivateFields() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User student = users.save(User.builder().email("student-" + suffix + "@example.com").fullName("Sinh Viên")
                .passwordHash("test-only-hash").role(User.Role.STUDENT).personalPersona(User.Persona.STUDENT)
                .systemPrivilege(User.SystemPrivilege.NONE).build());
        User teacher = users.save(User.builder().email("teacher-" + suffix + "@example.com").fullName("Giảng Viên")
                .passwordHash("test-only-hash").role(User.Role.TEACHER).personalPersona(User.Persona.TEACHER)
                .systemPrivilege(User.SystemPrivilege.NONE).build());
        UUID missing = UUID.randomUUID();

        mvc.perform(post("/auth/internal/contacts").contentType("application/json")
                        .header("X-Service-Token", "test-only-notification-contact-token")
                        .content("{\"userIds\":[\"" + teacher.getId() + "\",\"" + missing + "\",\""
                                + student.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value(teacher.getId().toString()))
                .andExpect(jsonPath("$[0].email").value(teacher.getEmail()))
                .andExpect(jsonPath("$[0].displayName").value("Giảng Viên"))
                .andExpect(jsonPath("$[1].userId").value(student.getId().toString()))
                .andExpect(jsonPath("$[0].length()").value(3))
                .andExpect(jsonPath("$[1].length()").value(3));

        users.deleteAllById(java.util.List.of(student.getId(), teacher.getId()));
    }
}
