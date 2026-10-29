package com.datn.auth.controller;

import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import com.datn.auth.service.PasswordResetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Account features on PostgreSQL (Flyway) + Redis: AUTH_TEST_DATABASE=true + SPRING_DATASOURCE_*. */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production", "spring.data.redis.password=",
        "auth.password-reset.log-links=true", "auth.password-reset.base-url=http://app.test"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AUTH_TEST_DATABASE", matches = "true")
class AccountPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired PasswordResetService resets;
    @Autowired ObjectMapper json;
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};

    JsonNode body(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString()); }
    JsonNode body(JsonNode n) { return n; }
    ResultActions post(String path, String content) throws Exception { return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(content)); }
    String bearer(JsonNode tokens) { return "Bearer " + tokens.get("accessToken").asText(); }
    String unique() { return UUID.randomUUID().toString().substring(0, 8); }

    @Test void registerNormalizesEmailAndProfileCanBeEdited() throws Exception {
        String id = unique();
        JsonNode t = body(post("/auth/register", "{\"email\":\"Hoc.Sinh." + id + "@Example.COM\",\"password\":\"secret123\",\"fullName\":\" An \",\"persona\":\"STUDENT\"}").andExpect(status().isCreated()));
        body(post("/auth/login", "{\"email\":\"hoc.sinh." + id + "@example.com\",\"password\":\"secret123\"}").andExpect(status().isOk()));
        String auth = bearer(t);
        mvc.perform(get("/auth/account").header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("hoc.sinh." + id + "@example.com")).andExpect(jsonPath("$.fullName").value("An"))
                .andExpect(jsonPath("$.passwordSet").value(true)).andExpect(jsonPath("$.googleLinked").value(false))
                .andExpect(jsonPath("$.lastLoginAt").isNotEmpty());
        mvc.perform(patch("/auth/account").header("Authorization", auth).contentType("application/json")
                        .content("{\"fullName\":\"Nguyễn Văn An\",\"phone\":\"0901 234 567\",\"school\":\"THPT Lê Quý Đôn\",\"bio\":\"Lớp 12A1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.school").value("THPT Lê Quý Đôn"));
        mvc.perform(patch("/auth/account").header("Authorization", auth).contentType("application/json").content("{\"fullName\":\"\",\"phone\":\"abc\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phone").exists());
        mvc.perform(get("/auth/me").header("Authorization", auth)).andExpect(jsonPath("$.fullName").value("Nguyễn Văn An"));

        // Avatar: bytes decide the type; anything else is refused.
        mvc.perform(put("/auth/account/avatar").header("Authorization", auth).contentType("image/png").content("not an image".getBytes()))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(put("/auth/account/avatar").header("Authorization", auth).contentType("image/jpeg").content(PNG))
                .andExpect(status().isOk()).andExpect(jsonPath("$.avatarUpdatedAt").isNotEmpty());
        mvc.perform(get("/auth/account/avatar").header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(PNG));
        mvc.perform(delete("/auth/account/avatar").header("Authorization", auth)).andExpect(jsonPath("$.avatarUpdatedAt").isEmpty());
        mvc.perform(get("/auth/account/avatar").header("Authorization", auth)).andExpect(status().isNotFound());
        mvc.perform(get("/auth/account")).andExpect(status().isBadRequest()); // no token
    }

    @Test void passwordChangeSignsOutOtherSessionsAndGoogleAccountsCanSetOne() throws Exception {
        String id = unique(), email = "doi.mk." + id + "@example.com";
        JsonNode first = body(post("/auth/register", "{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"B\",\"persona\":\"TEACHER\"}"));
        String auth = bearer(first);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/account/password").header("Authorization", auth).contentType("application/json")
                .content("{\"currentPassword\":\"wrong-one\",\"newPassword\":\"newsecret1\"}")).andExpect(status().isBadRequest());
        JsonNode next = body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/account/password").header("Authorization", auth).contentType("application/json")
                .content("{\"currentPassword\":\"secret123\",\"newPassword\":\"newsecret1\"}")).andExpect(status().isOk()));
        assertNotEquals(first.get("refreshToken").asText(), next.get("refreshToken").asText());
        post("/auth/refresh", "{\"refreshToken\":\"" + first.get("refreshToken").asText() + "\"}").andExpect(status().isUnauthorized());
        post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"secret123\"}").andExpect(status().isUnauthorized());
        post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"newsecret1\"}").andExpect(status().isOk());

        User g = users.save(User.builder().email("google." + id + "@gmail.com").fullName("G").passwordHash(passwords.encode("random-x"))
                .passwordSet(false).googleSubject("g-" + id).role(User.Role.STUDENT).personalPersona(User.Persona.STUDENT).systemPrivilege(User.SystemPrivilege.NONE).build());
        post("/auth/login", "{\"email\":\"google." + id + "@gmail.com\",\"password\":\"random-x\"}").andExpect(status().isUnauthorized());
        String ga = bearer(tokensFor(g));
        mvc.perform(delete("/auth/account/google").header("Authorization", ga)).andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/account/password").header("Authorization", ga).contentType("application/json")
                .content("{\"newPassword\":\"googlepass1\"}")).andExpect(status().isOk());
        mvc.perform(delete("/auth/account/google").header("Authorization", ga)).andExpect(status().isOk()).andExpect(jsonPath("$.googleLinked").value(false));
        post("/auth/login", "{\"email\":\"google." + id + "@gmail.com\",\"password\":\"googlepass1\"}").andExpect(status().isOk());
    }

    @Autowired com.datn.auth.service.AuthService auth;
    JsonNode tokensFor(User u) { return json.valueToTree(auth.issueTokens(u)); }

    @Test void tooManyWrongPasswordsPauseSignIn() throws Exception {
        String email = "brute." + unique() + "@example.com";
        post("/auth/register", "{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"C\",\"persona\":\"STUDENT\"}");
        for (int i = 0; i < 10; i++) post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"bad-" + i + "\"}").andExpect(status().isUnauthorized());
        post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"secret123\"}").andExpect(status().isTooManyRequests());
    }

    @Test void resetLinkWorksOnceAndSignsOut() throws Exception {
        String email = "quen.mk." + unique() + "@example.com";
        JsonNode t = body(post("/auth/register", "{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"D\",\"persona\":\"STUDENT\"}"));
        post("/auth/password/forgot", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
        post("/auth/password/forgot", "{\"email\":\"nobody-" + unique() + "@example.com\"}").andExpect(status().isAccepted());
        mvc.perform(get("/auth/providers")).andExpect(jsonPath("$.passwordReset").value(true));
        var link = resets.create(users.findByEmail(email).orElseThrow());
        assertTrue(link.url().startsWith("http://app.test/reset-password?token="));
        String token = link.url().substring(link.url().indexOf("token=") + 6);
        post("/auth/password/reset", "{\"token\":\"" + token + "\",\"newPassword\":\"short\"}").andExpect(status().isBadRequest());
        post("/auth/password/reset", "{\"token\":\"" + token + "\",\"newPassword\":\"brandnew1\"}").andExpect(status().isNoContent());
        post("/auth/password/reset", "{\"token\":\"" + token + "\",\"newPassword\":\"brandnew2\"}").andExpect(status().isBadRequest());
        post("/auth/refresh", "{\"refreshToken\":\"" + t.get("refreshToken").asText() + "\"}").andExpect(status().isUnauthorized());
        post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"brandnew1\"}").andExpect(status().isOk());
    }

    @Test void adminListsLocksAndCreatesResetLinks() throws Exception {
        String id = unique();
        User admin = users.save(User.builder().email("admin." + id + "@example.com").fullName("Admin").passwordHash(passwords.encode("adminpass1"))
                .role(User.Role.ADMIN).systemPrivilege(User.SystemPrivilege.ADMIN).build());
        JsonNode st = body(post("/auth/register", "{\"email\":\"khoa." + id + "@example.com\",\"password\":\"secret123\",\"fullName\":\"Học sinh " + id + "\",\"persona\":\"STUDENT\"}"));
        String student = users.findByEmail("khoa." + id + "@example.com").orElseThrow().getId().toString();
        String a = bearer(tokensFor(admin));
        mvc.perform(get("/auth/admin/users").param("q", id).header("Authorization", bearer(st))).andExpect(status().isForbidden());
        mvc.perform(get("/auth/admin/users").param("q", "HỌC SINH " + id).header("Authorization", a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].locked").value(false));
        mvc.perform(patch("/auth/admin/users/" + admin.getId()).header("Authorization", a).contentType("application/json").content("{\"locked\":true}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/auth/admin/users/" + student).header("Authorization", a).contentType("application/json").content("{\"locked\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true));
        post("/auth/login", "{\"email\":\"khoa." + id + "@example.com\",\"password\":\"secret123\"}").andExpect(status().isForbidden());
        post("/auth/refresh", "{\"refreshToken\":\"" + st.get("refreshToken").asText() + "\"}").andExpect(status().isUnauthorized()); // locking revoked it
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/admin/users/" + student + "/password-reset").header("Authorization", a)).andExpect(status().isConflict());
        mvc.perform(patch("/auth/admin/users/" + student).header("Authorization", a).contentType("application/json").content("{\"locked\":false}")).andExpect(jsonPath("$.locked").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/admin/users/" + student + "/password-reset").header("Authorization", a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("http://app.test/reset-password?token="))).andExpect(jsonPath("$.emailed").value(false));
        post("/auth/login", "{\"email\":\"khoa." + id + "@example.com\",\"password\":\"secret123\"}").andExpect(status().isOk());
    }
}
