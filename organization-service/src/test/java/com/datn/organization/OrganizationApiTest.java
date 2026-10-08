package com.datn.organization;

import com.datn.organization.client.IdentityResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false", "organization.events.enabled=false",
        "jwt.secret=" + OrganizationApiTest.SECRET, "organization.internal.qb-token=" + OrganizationApiTest.QB,
        "spring.task.scheduling.pool.size=1", "organization.events.expiry-sweep-ms=3600000"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "ORG_TEST_DATABASE", matches = "true")
class OrganizationApiTest {
    static final String SECRET = "test-only-jwt-key-32-bytes-or-longer-never-production";
    static final String QB = "isolated-test-qb-token";

    static final Map<UUID, IdentityResolver.Identity> DIRECTORY = new ConcurrentHashMap<>();
    static volatile boolean identityDown = false;

    @TestConfiguration
    static class Fakes {
        @Bean @Primary IdentityResolver identities() {
            return new IdentityResolver() {
                @Override public List<Identity> byUserIds(Collection<UUID> ids) {
                    if (identityDown) throw new Unavailable("down", null);
                    return ids.stream().map(DIRECTORY::get).filter(Objects::nonNull).toList();
                }
                @Override public List<Identity> byEmails(Collection<String> emails) {
                    if (identityDown) throw new Unavailable("down", null);
                    return DIRECTORY.values().stream().filter(i -> emails.contains(i.email())).toList();
                }
            };
        }
    }

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.datn.organization.service.OrganizationService service;

    record User(UUID id, String role, String email) {}
    User teacher, teacher2, admin, studentA, studentB, studentC;

    User user(String role, String name) {
        UUID id = UUID.randomUUID();
        String email = name.replace(" ", "").toLowerCase() + "-" + id + "@example.test";
        DIRECTORY.put(id, new IdentityResolver.Identity(id, name, email, role.equals("TEACHER") ? "TEACHER" : "STUDENT"));
        return new User(id, role, email);
    }

    @BeforeEach void users() {
        identityDown = false;
        teacher = user("TEACHER", "Teacher One"); teacher2 = user("TEACHER", "Teacher Two"); admin = user("ADMIN", "System Admin");
        studentA = user("STUDENT", "Student A"); studentB = user("STUDENT", "Student B"); studentC = user("STUDENT", "Student C");
    }

    String jwt(User u) {
        return "Bearer " + Jwts.builder().subject(u.id().toString()).claim("type", "access").claim("role", u.role())
                .expiration(new Date(System.currentTimeMillis() + 60000)).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    MvcResult call(MockHttpServletRequestBuilder b, User u, Object body) throws Exception {
        if (u != null) b.header("Authorization", jwt(u));
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        return http.perform(b).andReturn();
    }
    JsonNode ok(MvcResult r, int status) throws Exception {
        assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        String text = r.getResponse().getContentAsString();
        return text.isBlank() ? null : json.readTree(text);
    }
    int code(MvcResult r) { return r.getResponse().getStatus(); }

    // ---- fixtures
    JsonNode createOrg(User owner) throws Exception { return ok(call(post("/organizations"), owner, Map.of("name", "Org " + UUID.randomUUID())), 201); }
    UUID uuid(JsonNode n, String f) { return UUID.fromString(n.get(f).asText()); }

    /** Invites by id and accepts; returns the membership view. */
    JsonNode join(UUID org, User inviter, User invitee, String role) throws Exception {
        MvcResult created = call(post("/organizations/" + org + "/invitations"), inviter, Map.of("userId", invitee.id(), "role", role));
        JsonNode inv = ok(created, code(created) == 200 ? 200 : 201);   // 200 = duplicate-safe reuse of a pending invitation
        return ok(call(post("/organizations/invitations/" + inv.get("id").asText() + "/accept"), invitee, null), 200);
    }
    JsonNode authorize(UUID org, User u, String capability) throws Exception {
        return ok(internal(post("/organizations/internal/authorize"), Map.of("organizationId", org, "userId", u.id(), "capability", capability)), 200);
    }
    MvcResult internal(MockHttpServletRequestBuilder b, Object body) throws Exception {
        b.header("X-Service-Token", QB).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return http.perform(b).andReturn();
    }
    JsonNode member(UUID org, User viewer, UUID userId) throws Exception {
        JsonNode page = ok(call(get("/organizations/" + org + "/members?status=ALL&size=50"), viewer, null), 200);
        for (JsonNode m : page.get("items")) if (m.get("userId").asText().equals(userId.toString())) return m;
        return null;
    }

    // ================= tests =================

    @Test void onlyTeacherCreatesAndBecomesOwner() throws Exception {
        JsonNode org = createOrg(teacher);
        assertEquals("OWNER", org.get("me").get("role").asText());
        assertEquals(5, org.get("me").get("capabilities").size());
        assertEquals("ACTIVE", org.get("status").asText());
        assertEquals(403, code(call(post("/organizations"), studentA, Map.of("name", "x"))));
        assertEquals(403, code(call(post("/organizations"), admin, Map.of("name", "x"))));   // system ADMIN is not an organization actor
        assertEquals(400, code(call(post("/organizations"), teacher, Map.of("name", " "))));
        // multiple memberships per user
        createOrg(teacher);
        assertTrue(ok(call(get("/organizations"), teacher, null), 200).get("totalElements").asInt() >= 2);
    }

    @Test void identityComesOnlyFromVerifiedJwt() throws Exception {
        assertEquals(401, code(http.perform(post("/organizations").header("X-User-Id", teacher.id()).header("X-User-Role", "TEACHER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andReturn()));
        assertEquals(401, code(http.perform(get("/organizations").header("Authorization", "Bearer not.a.jwt")).andReturn()));
        String forged = "Bearer " + Jwts.builder().subject(teacher.id().toString()).claim("type", "access").claim("role", "TEACHER")
                .expiration(new Date(System.currentTimeMillis() + 60000)).signWith(Keys.hmacShaKeyFor("another-secret-another-secret-another-secret".getBytes())).compact();
        assertEquals(401, code(http.perform(get("/organizations").header("Authorization", forged)).andReturn()));
        String refresh = "Bearer " + Jwts.builder().subject(teacher.id().toString()).claim("type", "refresh").expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes())).compact();
        assertEquals(401, code(http.perform(get("/organizations").header("Authorization", refresh)).andReturn()));
        // service credential never authenticates public routes
        assertEquals(401, code(http.perform(get("/organizations").header("X-Service-Token", QB)).andReturn()));
        assertEquals(401, code(http.perform(get("/organizations").header("Authorization", jwt(teacher)).header("X-Service-Token", QB)).andReturn()));
        // non-member cannot even confirm existence
        JsonNode org = createOrg(teacher);
        assertEquals(404, code(call(get("/organizations/" + org.get("id").asText()), teacher2, null)));
        assertEquals(404, code(call(get("/organizations/" + org.get("id").asText()), admin, null)));   // SystemAdmin is not an automatic owner
    }

    @Test void invitationLifecycleRecipientDuplicatesAndExpiry() throws Exception {
        UUID org = uuid(createOrg(teacher), "id");
        JsonNode inv = ok(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("email", studentA.email(), "role", "MEMBER")), 201);
        assertEquals("PENDING", inv.get("status").asText());
        assertFalse(inv.toString().contains(studentA.email()), "email must not be echoed");
        long ttl = java.time.Duration.between(java.time.OffsetDateTime.parse(inv.get("createdAt").asText()), java.time.OffsetDateTime.parse(inv.get("expiresAt").asText())).toDays();
        assertEquals(7, ttl);
        // duplicate -> same invitation, no second row
        JsonNode again = ok(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentA.id(), "role", "MEMBER")), 200);
        assertEquals(inv.get("id").asText(), again.get("id").asText());
        assertEquals(409, code(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentA.id(), "role", "ADMIN"))));
        // unknown / unregistered, self, bad selector
        assertEquals(404, code(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("email", "nobody@example.test", "role", "MEMBER"))));
        assertEquals(400, code(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", teacher.id(), "role", "MEMBER"))));
        assertEquals(400, code(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentB.id(), "email", studentB.email(), "role", "MEMBER"))));
        assertEquals(400, code(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("role", "MEMBER"))));
        assertEquals(400, code(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentB.id(), "role", "OWNER"))));
        // only the JWT recipient can act; others cannot even see it
        String id = inv.get("id").asText();
        assertEquals(404, code(call(post("/organizations/invitations/" + id + "/accept"), studentB, null)));
        assertEquals(404, code(call(post("/organizations/invitations/" + id + "/accept"), teacher, null)));
        assertEquals(404, code(call(post("/organizations/invitations/" + id + "/decline"), admin, null)));
        assertEquals(1, ok(call(get("/organizations/invitations/mine?status=PENDING"), studentA, null), 200).get("totalElements").asInt());
        JsonNode joined = ok(call(post("/organizations/invitations/" + id + "/accept"), studentA, null), 200);
        assertEquals("MEMBER", joined.get("role").asText());
        assertEquals(409, code(call(post("/organizations/invitations/" + id + "/accept"), studentA, null)));
        assertEquals(409, code(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentA.id(), "role", "MEMBER"))));   // already member
        // decline path
        JsonNode inv2 = ok(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentB.id(), "role", "MEMBER")), 201);
        assertEquals("DECLINED", ok(call(post("/organizations/invitations/" + inv2.get("id").asText() + "/decline"), studentB, null), 200).get("status").asText());
        assertEquals(409, code(call(post("/organizations/invitations/" + inv2.get("id").asText() + "/accept"), studentB, null)));
        // revoke path
        JsonNode inv3 = ok(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentB.id(), "role", "MEMBER")), 201);
        assertEquals("REVOKED", ok(call(post("/organizations/" + org + "/invitations/" + inv3.get("id").asText() + "/revoke"), teacher, null), 200).get("status").asText());
        assertEquals(409, code(call(post("/organizations/invitations/" + inv3.get("id").asText() + "/accept"), studentB, null)));
        // expiry: lapsed invitation reads EXPIRED, cannot be accepted, a new one can be issued
        JsonNode inv4 = ok(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentB.id(), "role", "MEMBER")), 201);
        jdbc.update("UPDATE invitations SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", inv4.get("id").asText());
        assertEquals("EXPIRED", ok(call(get("/organizations/invitations/mine?status=PENDING"), studentB, null), 200).get("items").get(0).get("status").asText());
        assertEquals(409, code(call(post("/organizations/invitations/" + inv4.get("id").asText() + "/accept"), studentB, null)));
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?::uuid", String.class, inv4.get("id").asText()));
        JsonNode inv5 = ok(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentB.id(), "role", "MEMBER")), 201);
        jdbc.update("UPDATE invitations SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", inv5.get("id").asText());
        service.sweepExpired();
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?::uuid", String.class, inv5.get("id").asText()));
        // events were enqueued for the lifecycle (created/accepted/declined/revoked/expired)
        Set<String> types = new HashSet<>(jdbc.queryForList("SELECT DISTINCT event_type FROM outbox_events WHERE payload LIKE ?", String.class, "%" + org + "%"));
        assertTrue(types.containsAll(Set.of("organization.invitation.created", "organization.invitation.accepted", "organization.invitation.declined",
                "organization.invitation.revoked", "organization.invitation.expired")), types.toString());
    }

    @Test void identityProviderFailureFailsInviteClosed() throws Exception {
        UUID org = uuid(createOrg(teacher), "id");
        identityDown = true;
        assertEquals(503, code(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentA.id(), "role", "MEMBER"))));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitations WHERE organization_id = ?::uuid", Integer.class, org.toString()));
        // member listing degrades to no display name but still works (names are cosmetic)
        assertEquals(200, code(call(get("/organizations/" + org + "/members"), teacher, null)));
    }

    @Test void roleBoundariesAndDelegatedReview() throws Exception {
        JsonNode created = createOrg(teacher);
        UUID org = uuid(created, "id");
        JsonNode adminM = join(org, teacher, studentA, "ADMIN");
        JsonNode memberM = join(org, teacher, studentB, "MEMBER");
        UUID ownerM = uuid(created.get("me"), "membershipId");
        // Member capabilities, no manage/review
        assertEquals(List.of("BANK_READ", "BANK_USE", "BANK_SUBMIT"), caps(org, studentB));
        assertEquals(5, caps(org, studentA).size());
        // Member cannot manage
        assertEquals(403, code(call(post("/organizations/" + org + "/invitations"), studentB, Map.of("userId", studentC.id(), "role", "MEMBER"))));
        assertEquals(403, code(call(get("/organizations/" + org + "/audit"), studentB, null)));
        assertEquals(403, code(call(get("/organizations/" + org + "/invitations"), studentB, null)));
        assertEquals(403, code(call(patch("/organizations/" + org), studentB, Map.of("name", "n", "revision", 1))));
        // member roster shows only ACTIVE and no history
        assertEquals(403, code(call(get("/organizations/" + org + "/members?status=REVOKED"), studentB, null)));
        // Admin: invites MEMBER, not ADMIN; cannot touch OWNER; cannot promote
        assertEquals(403, code(call(post("/organizations/" + org + "/invitations"), studentA, Map.of("userId", studentC.id(), "role", "ADMIN"))));
        assertEquals(201, code(call(post("/organizations/" + org + "/invitations"), studentA, Map.of("userId", studentC.id(), "role", "MEMBER"))));
        assertEquals(403, code(call(patch("/organizations/" + org + "/members/" + ownerM), studentA, Map.of("canReview", false, "role", "MEMBER", "revision", 1))));
        assertEquals(403, code(call(patch("/organizations/" + org + "/members/" + memberM.get("membershipId").asText()), studentA, Map.of("role", "ADMIN", "revision", 1))));
        assertEquals(403, code(call(delete("/organizations/" + org + "/members/" + ownerM + "?revision=1"), studentA, null)));
        assertEquals(403, code(call(post("/organizations/" + org + "/archive"), studentA, Map.of("revision", 1))));
        assertEquals(403, code(call(post("/organizations/" + org + "/transfer-ownership"), studentA, Map.of("targetMembershipId", memberM.get("membershipId").asText(), "revision", 1))));
        // OWNER row cannot be edited even by OWNER (use transfer); role OWNER not assignable
        assertEquals(403, code(call(patch("/organizations/" + org + "/members/" + ownerM), teacher, Map.of("role", "MEMBER", "revision", 1))));
        assertEquals(400, code(call(patch("/organizations/" + org + "/members/" + memberM.get("membershipId").asText()), teacher, "{\"role\":\"OWNER\",\"revision\":1}")));
        // Admin grants delegated review to a MEMBER (explicit revision required)
        String mid = memberM.get("membershipId").asText();
        assertEquals(400, code(call(patch("/organizations/" + org + "/members/" + mid), studentA, Map.of("canReview", true))));
        JsonNode granted = ok(call(patch("/organizations/" + org + "/members/" + mid), studentA, Map.of("canReview", true, "revision", 1)), 200);
        assertTrue(granted.get("canReview").asBoolean());
        assertTrue(authorize(org, studentB, "BANK_REVIEW").get("allowed").asBoolean());
        assertFalse(authorize(org, studentB, "BANK_MANAGE").get("allowed").asBoolean());   // canReview grants review only
        assertEquals(409, code(call(patch("/organizations/" + org + "/members/" + mid), studentA, Map.of("canReview", false, "revision", 1))));   // stale revision
        // Owner promotes/demotes; canReview reset on role change; canReview invalid on ADMIN
        JsonNode promoted = ok(call(patch("/organizations/" + org + "/members/" + mid), teacher, Map.of("role", "ADMIN", "revision", granted.get("revision").asLong())), 200);
        assertEquals("ADMIN", promoted.get("role").asText());
        assertFalse(promoted.get("canReview").asBoolean());
        assertEquals(400, code(call(patch("/organizations/" + org + "/members/" + mid), teacher, Map.of("canReview", true, "revision", promoted.get("revision").asLong()))));
        // Admin can remove MEMBER but not ADMIN
        JsonNode cm = join(org, teacher, studentC, "MEMBER");
        assertEquals(403, code(call(delete("/organizations/" + org + "/members/" + mid + "?revision=" + promoted.get("revision").asLong()), studentA, null)));
        assertEquals(204, code(call(delete("/organizations/" + org + "/members/" + cm.get("membershipId").asText() + "?revision=" + cm.get("revision").asLong()), studentA, null)));
        // audit trail
        JsonNode audit = ok(call(get("/organizations/" + org + "/audit?size=50"), teacher, null), 200);
        Set<String> actions = new HashSet<>();
        audit.get("items").forEach(a -> actions.add(a.get("action").asText()));
        assertTrue(actions.containsAll(Set.of("ORGANIZATION_CREATED", "INVITATION_CREATED", "INVITATION_ACCEPTED", "REVIEW_GRANT_CHANGED", "ROLE_CHANGED", "MEMBER_REVOKED")), actions.toString());
    }

    List<String> caps(UUID org, User u) throws Exception {
        List<String> list = new ArrayList<>();
        ok(call(get("/organizations/" + org + "/capabilities"), u, null), 200).get("capabilities").forEach(c -> list.add(c.asText()));
        return list;
    }

    @Test void lastOwnerGuardsAndTransfer() throws Exception {
        JsonNode created = createOrg(teacher);
        UUID org = uuid(created, "id");
        UUID ownerM = uuid(created.get("me"), "membershipId");
        JsonNode m = join(org, teacher, studentA, "MEMBER");
        assertEquals(409, code(call(post("/organizations/" + org + "/leave"), teacher, null)));   // last owner must transfer first
        assertEquals(409, code(call(delete("/organizations/" + org + "/members/" + ownerM + "?revision=1"), teacher, null)));   // self removal -> leave
        // transfer: stale org revision, self, non-member target
        assertEquals(409, code(call(post("/organizations/" + org + "/transfer-ownership"), teacher, Map.of("targetMembershipId", m.get("membershipId").asText(), "revision", 99))));
        assertEquals(400, code(call(post("/organizations/" + org + "/transfer-ownership"), teacher, Map.of("targetMembershipId", ownerM.toString(), "revision", 1))));
        assertEquals(404, code(call(post("/organizations/" + org + "/transfer-ownership"), teacher, Map.of("targetMembershipId", UUID.randomUUID().toString(), "revision", 1))));
        JsonNode after = ok(call(post("/organizations/" + org + "/transfer-ownership"), teacher, Map.of("targetMembershipId", m.get("membershipId").asText(), "revision", 1)), 200);
        assertEquals("ADMIN", after.get("me").get("role").asText());
        assertEquals("OWNER", member(org, studentA, studentA.id()).get("role").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE organization_id = ?::uuid AND role='OWNER' AND status='ACTIVE'", Integer.class, org.toString()));
        // former owner (now ADMIN) can leave; history stays, permission gone immediately
        assertTrue(authorize(org, teacher, "BANK_MANAGE").get("allowed").asBoolean());
        assertEquals(204, code(call(post("/organizations/" + org + "/leave"), teacher, null)));
        JsonNode denied = authorize(org, teacher, "BANK_READ");
        assertFalse(denied.get("allowed").asBoolean());
        assertEquals("LEFT", member(org, studentA, teacher.id()).get("status").asText());
        assertEquals(404, code(call(get("/organizations/" + org), teacher, null)));
    }

    @Test void concurrentOwnershipAndDuplicateRacesKeepInvariants() throws Exception {
        JsonNode created = createOrg(teacher);
        UUID org = uuid(created, "id");
        JsonNode a = join(org, teacher, studentA, "MEMBER");
        JsonNode b = join(org, teacher, studentB, "MEMBER");
        // owner leave + two competing transfers, all at once
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 3; i++) jobs.add(() -> code(call(post("/organizations/" + org + "/leave"), teacher, null)));
        jobs.add(() -> code(call(post("/organizations/" + org + "/transfer-ownership"), teacher, Map.of("targetMembershipId", a.get("membershipId").asText(), "revision", 1))));
        jobs.add(() -> code(call(post("/organizations/" + org + "/transfer-ownership"), teacher, Map.of("targetMembershipId", b.get("membershipId").asText(), "revision", 1))));
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(jobs)) codes.add(f.get());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE organization_id = ?::uuid AND role='OWNER' AND status='ACTIVE'", Integer.class, org.toString()),
                "exactly one OWNER must survive: " + codes);
        assertEquals(1, codes.subList(3, 5).stream().filter(c -> c == 200).count(), codes.toString());
        // duplicate invitations for one recipient racing
        UUID org2 = uuid(createOrg(teacher2), "id");
        List<Callable<Integer>> invites = new ArrayList<>();
        for (int i = 0; i < 8; i++) invites.add(() -> code(call(post("/organizations/" + org2 + "/invitations"), teacher2, Map.of("userId", studentC.id(), "role", "MEMBER"))));
        List<Integer> inviteCodes = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(invites)) inviteCodes.add(f.get());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invitations WHERE organization_id = ?::uuid", Integer.class, org2.toString()), inviteCodes.toString());
        assertEquals(1, inviteCodes.stream().filter(c -> c == 201).count());
        assertTrue(inviteCodes.stream().allMatch(c -> c == 201 || c == 200), inviteCodes.toString());
        // concurrent accepts of the same invitation: one membership
        String invId = jdbc.queryForObject("SELECT id::text FROM invitations WHERE organization_id = ?::uuid", String.class, org2.toString());
        List<Callable<Integer>> accepts = new ArrayList<>();
        for (int i = 0; i < 6; i++) accepts.add(() -> code(call(post("/organizations/invitations/" + invId + "/accept"), studentC, null)));
        List<Integer> acceptCodes = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(accepts)) acceptCodes.add(f.get());
        pool.shutdown();
        assertEquals(1, acceptCodes.stream().filter(c -> c == 200).count(), acceptCodes.toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE organization_id = ?::uuid AND user_id = ?::uuid AND status='ACTIVE'", Integer.class, org2.toString(), studentC.id().toString()));
    }

    @Test void revokeLeaveAndArchiveTakeEffectOnNextCheck() throws Exception {
        JsonNode created = createOrg(teacher);
        UUID org = uuid(created, "id");
        JsonNode a = join(org, teacher, studentA, "MEMBER");
        JsonNode b = join(org, teacher, studentB, "MEMBER");
        assertTrue(authorize(org, studentA, "BANK_USE").get("allowed").asBoolean());
        assertEquals(409, code(call(delete("/organizations/" + org + "/members/" + a.get("membershipId").asText() + "?revision=7"), teacher, null)));   // stale
        assertEquals(204, code(call(delete("/organizations/" + org + "/members/" + a.get("membershipId").asText() + "?revision=" + a.get("revision").asLong()), teacher, null)));
        JsonNode revoked = authorize(org, studentA, "BANK_USE");
        assertFalse(revoked.get("allowed").asBoolean());
        assertFalse(revoked.has("membershipId"));
        assertEquals(404, code(call(get("/organizations/" + org), studentA, null)));
        assertEquals("REVOKED", member(org, teacher, studentA.id()).get("status").asText());
        // re-invite after revoke creates a fresh membership, history row stays
        join(org, teacher, studentA, "MEMBER");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE organization_id = ?::uuid AND user_id = ?::uuid", Integer.class, org.toString(), studentA.id().toString()));
        // archive: operations fail closed, history readable
        JsonNode archived = ok(call(post("/organizations/" + org + "/archive"), teacher, Map.of("revision", created.get("revision").asLong())), 200);
        assertEquals("ARCHIVED", archived.get("status").asText());
        assertEquals(409, code(call(post("/organizations/" + org + "/archive"), teacher, Map.of("revision", archived.get("revision").asLong()))));
        JsonNode useDenied = authorize(org, studentB, "BANK_USE");
        assertFalse(useDenied.get("allowed").asBoolean());
        assertEquals("ARCHIVED", useDenied.get("organizationStatus").asText());
        assertFalse(authorize(org, teacher, "BANK_MANAGE").get("allowed").asBoolean());
        assertTrue(authorize(org, studentB, "BANK_READ").get("allowed").asBoolean());
        assertEquals(409, code(call(post("/organizations/" + org + "/invitations"), teacher, Map.of("userId", studentC.id(), "role", "MEMBER"))));
        assertEquals(409, code(call(patch("/organizations/" + org), teacher, Map.of("name", "renamed", "revision", archived.get("revision").asLong()))));
        assertEquals(409, code(call(patch("/organizations/" + org + "/members/" + b.get("membershipId").asText()), teacher, Map.of("canReview", true, "revision", 1))));
        assertEquals(200, code(call(get("/organizations/" + org), studentB, null)));
        assertEquals("ARCHIVED", ok(call(get("/organizations?status=ARCHIVED"), studentB, null), 200).get("items").get(0).get("status").asText());
        // pending invitation cannot be accepted into an archived organization
    }

    @Test void organizationUpdateUsesRevision() throws Exception {
        JsonNode created = createOrg(teacher);
        UUID org = uuid(created, "id");
        JsonNode updated = ok(call(patch("/organizations/" + org), teacher, Map.of("name", "Renamed", "description", "d", "revision", 1)), 200);
        assertEquals("Renamed", updated.get("name").asText());
        assertEquals(2, updated.get("revision").asLong());
        assertEquals(409, code(call(patch("/organizations/" + org), teacher, Map.of("name", "Stale", "revision", 1))));
        assertEquals(400, code(call(patch("/organizations/" + org), teacher, Map.of("revision", 2))));
        // pagination + q filter
        ok(call(post("/organizations"), teacher, Map.of("name", "Zebra Lab")), 201);
        JsonNode page = ok(call(get("/organizations?q=zebra&size=1"), teacher, null), 200);
        assertEquals(1, page.get("totalElements").asInt());
        assertEquals(1, page.get("items").size());
        assertEquals(50, ok(call(get("/organizations?size=500"), teacher, null), 200).get("size").asInt());
    }

    @Test void internalAuthorizationTrustBoundaryAndDiscovery() throws Exception {
        JsonNode created = createOrg(teacher);
        UUID org = uuid(created, "id");
        join(org, teacher, studentA, "MEMBER");
        Map<String, Object> body = Map.of("organizationId", org, "userId", studentA.id(), "capability", "BANK_USE");
        String ok = json.writeValueAsString(body);
        var path = "/organizations/internal/authorize";
        // credential matrix
        assertEquals(401, code(http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(ok)).andReturn()));
        assertEquals(401, code(http.perform(post(path).header("X-Service-Token", "wrong").contentType(MediaType.APPLICATION_JSON).content(ok)).andReturn()));
        assertEquals(401, code(http.perform(post(path).header("X-Service-Token", QB).header("X-Service-Token", QB).contentType(MediaType.APPLICATION_JSON).content(ok)).andReturn()));
        assertEquals(401, code(http.perform(post(path).header("X-Service-Token", QB).header("Authorization", jwt(teacher)).contentType(MediaType.APPLICATION_JSON).content(ok)).andReturn()));
        assertEquals(401, code(http.perform(post(path).header("Authorization", jwt(teacher)).contentType(MediaType.APPLICATION_JSON).content(ok)).andReturn()));
        assertEquals(401, code(http.perform(post("/organizations/Internal/authorize").header("Authorization", jwt(teacher)).contentType(MediaType.APPLICATION_JSON).content(ok)).andReturn()));
        // X-User-* headers carry no authority; body userId decides
        MvcResult spoof = http.perform(post(path).header("X-Service-Token", QB).header("X-User-Id", teacher.id()).header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("organizationId", org, "userId", studentA.id(), "capability", "BANK_MANAGE")))).andReturn();
        assertFalse(ok(spoof, 200).get("allowed").asBoolean());
        // input errors
        assertEquals(400, code(internal(post(path), Map.of("organizationId", org, "userId", studentA.id(), "capability", "BANK_ROOT"))));
        assertEquals(400, code(internal(post(path), Map.of("organizationId", org, "userId", studentA.id()))));
        assertEquals(400, code(internal(post(path), Map.of("organizationId", "nope", "userId", studentA.id(), "capability", "BANK_USE"))));
        assertEquals(400, code(http.perform(post(path).header("X-Service-Token", QB).contentType(MediaType.APPLICATION_JSON).content("{bad")).andReturn()));
        assertEquals(413, code(http.perform(post(path).header("X-Service-Token", QB).contentType(MediaType.APPLICATION_JSON).content("x".repeat(9000))).andReturn()));
        assertEquals(404, code(internal(post("/organizations/internal/unknown"), body)));
        // user JWT cannot reach public routes through the internal segment either
        assertEquals(401, code(call(get("/organizations/internal/memberships"), teacher, null)));
        // decisions
        JsonNode allowed = authorize(org, studentA, "BANK_USE");
        assertTrue(allowed.get("allowed").asBoolean());
        assertEquals("MEMBER", allowed.get("role").asText());
        assertEquals("ACTIVE", allowed.get("membershipStatus").asText());
        assertEquals("ACTIVE", allowed.get("organizationStatus").asText());
        assertTrue(allowed.has("decisionAt") && allowed.has("membershipId"));
        assertFalse(authorize(org, studentA, "BANK_REVIEW").get("allowed").asBoolean());
        JsonNode stranger = authorize(org, studentB, "BANK_READ");
        assertFalse(stranger.get("allowed").asBoolean());
        assertFalse(stranger.has("role"));
        JsonNode unknownOrg = authorize(UUID.randomUUID(), studentA, "BANK_READ");
        assertFalse(unknownOrg.get("allowed").asBoolean());
        assertFalse(unknownOrg.has("organizationStatus"));
        // discovery: active memberships only, optional capability filter
        UUID org2 = uuid(createOrg(teacher2), "id");
        join(org2, teacher2, studentA, "MEMBER");
        JsonNode both = ok(internal(post("/organizations/internal/memberships"), Map.of("userId", studentA.id())), 200);
        assertEquals(2, both.get("memberships").size());
        assertFalse(both.get("truncated").asBoolean());
        JsonNode reviewers = ok(internal(post("/organizations/internal/memberships"), Map.of("userId", studentA.id(), "capability", "BANK_REVIEW")), 200);
        assertEquals(0, reviewers.get("memberships").size());
        assertEquals(1, ok(internal(post("/organizations/internal/memberships"), Map.of("userId", teacher.id(), "capability", "BANK_MANAGE")), 200).get("memberships").size());
        assertEquals(401, code(http.perform(post("/organizations/internal/memberships").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + studentA.id() + "\"}")).andReturn()));
    }

    @Test void blankServiceTokenFailsClosed() throws Exception {
        var filter = new com.datn.organization.security.RequestAuthentication(SECRET, "", json);
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/organizations/internal/authorize");
        request.addHeader("X-Service-Token", "");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test void schemaIsIndependentOfOtherServices() {
        Set<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name <> 'flyway_schema_history'", String.class)
                .stream().collect(Collectors.toSet());
        assertEquals(Set.of("organizations", "memberships", "invitations", "audit_events", "outbox_events"), tables);
        // userId columns are plain UUID references: no foreign key may point outside this schema
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.table_constraints tc JOIN information_schema.constraint_column_usage ccu "
                + "ON tc.constraint_name=ccu.constraint_name WHERE tc.constraint_type='FOREIGN KEY' AND ccu.table_name NOT IN ('organizations')", Integer.class));
    }
}
