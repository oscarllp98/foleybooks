package com.foleybooks.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foleybooks.auth.token.api.LoginRequest;
import com.foleybooks.auth.token.api.LogoutRequest;
import com.foleybooks.auth.token.api.RefreshRequest;
import com.foleybooks.auth.token.api.TokenPair;
import com.foleybooks.auth.token.domain.ConfirmationToken;
import com.foleybooks.auth.token.domain.RefreshToken;
import com.foleybooks.auth.token.domain.RefreshTokenStatus;
import com.foleybooks.auth.token.repository.ConfirmationTokenRepository;
import com.foleybooks.auth.token.repository.RefreshTokenRepository;
import com.foleybooks.auth.token.service.ConfirmationTokenService;
import com.foleybooks.auth.token.service.RsaKeyPairProvider;
import com.foleybooks.auth.user.api.ConfirmRequest;
import com.foleybooks.auth.user.api.RegisterRequest;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserStatus;
import com.foleybooks.auth.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The critical auth flow end-to-end on real PostgreSQL (plan §6.4, AU-19):
 * register → confirm → login → refresh → reuse of the rotated value → every
 * session of the account revoked, plus FR-05's current-session-only logout
 * (FR-01..FR-05; LC-01..LC-09, LC-15, LC-21, LC-23). Everything runs through
 * the public HTTP surface with the real security chain, real Flyway schema,
 * real BCrypt-12 and real RS256 signing — the only seam substituted is mail:
 * {@code app.mail.transport=log} keeps the stack Mailpit-free (ADR-001) and
 * the raw confirmation link stays an email-only payload (C24). Because the DB
 * holds only the SHA-256 digest, the test reads its confirmation token back
 * through the same {@link ConfirmationTokenService} port that issues it: a
 * re-issue syncs the row's hash with the returned raw value, so the confirm
 * request resolves exactly like a clicked link against the persisted state.
 * Every token is then bound to its persisted row by digest
 * ({@code sha256Hex(presented) == token_hash}), which is what "token from DB"
 * means under ADR-002's hashed-at-rest contract.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "EUREKA_USERNAME=test-user",
        "EUREKA_PASSWORD=test-secret",
        "AUTH_DB_PASSWORD=test-db-secret",
        "app.mail.transport=log"
})
class AuthFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Per-run throwaway fixture password — never committed, never logged (C19, C24); hex UUID satisfies the FR-01 rules (≥8 chars, letter + digit). */
    private static final String PASSWORD = UUID.randomUUID().toString();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ConfirmationTokenService confirmationTokenService;

    @Autowired
    ConfirmationTokenRepository confirmationTokenRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    RsaKeyPairProvider keyPairProvider;

    @Test
    void refresh_whenRotatedTokenIsReused_revokesAllSessionsOfAccount() throws Exception {
        // FR-01: registration normalizes the address at the boundary (LC-15)
        // and answers the fixed enumeration-safe 201 (LC-01).
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("  AU19-Lifecycle@FoleyBooks.com ", PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("au19-lifecycle@foleybooks.com"))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"));

        User account = userRepository.findByEmail("au19-lifecycle@foleybooks.com").orElseThrow();
        assertThat(account.getStatus()).isEqualTo(UserStatus.UNVERIFIED);

        // LC-05: the right password on an UNVERIFIED account is a 403, not a token.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("au19-lifecycle@foleybooks.com", PASSWORD))))
                .andExpect(status().isForbidden());

        String confirmationToken = confirmationTokenService.rotate(account);

        // FR-02: the live link verifies, and re-opening the spent one is the
        // idempotent success of LC-03/LC-23, never an error.
        mockMvc.perform(post("/api/v1/auth/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConfirmRequest(confirmationToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"));
        mockMvc.perform(post("/api/v1/auth/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConfirmRequest(confirmationToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_CONFIRMED"));
        assertThat(userRepository.findByEmail("au19-lifecycle@foleybooks.com").orElseThrow().getStatus())
                .isEqualTo(UserStatus.VERIFIED);

        // The literal "token from DB" of ADR-002: the confirmation_tokens row persisted
        // nothing but the SHA-256 digest of exactly the value the confirm request presented.
        ConfirmationToken persistedLink = confirmationTokenRepository
                .findByUserId(account.getId()).orElseThrow();
        assertThat(persistedLink.getTokenHash()).isEqualTo(sha256Hex(confirmationToken));

        // FR-03: login opens a rotating session with a real RS256 access JWT —
        // the claim set AGENTS.md §5 mandates, verifiable with the public half.
        TokenPair login = login("au19-lifecycle@foleybooks.com");
        assertThat(login.tokenType()).isEqualTo(TokenPair.BEARER);
        assertThat(login.expiresIn()).isEqualTo(15 * 60);
        assertThat(login.refreshToken()).isNotBlank();
        Claims claims = Jwts.parser()
                .verifyWith(keyPairProvider.publicKey())
                .build()
                .parseSignedClaims(login.accessToken())
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo(account.getId().toString());
        assertThat(claims.get("email", String.class)).isEqualTo("au19-lifecycle@foleybooks.com");
        assertThat(asRoles(claims.get("roles", List.class))).containsExactly("CUSTOMER");

        // FR-04: the refresh rotates — the presented value is spent, a fresh
        // ACTIVE successor takes its place (LC-22).
        TokenPair rotated = expectTokenPair(
                mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(login.refreshToken())))), 200);
        assertThat(rotated.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(sessionStatusFor(account.getId(), login.refreshToken()))
                .isEqualTo(RefreshTokenStatus.ROTATED);
        assertThat(sessionStatusFor(account.getId(), rotated.refreshToken()))
                .isEqualTo(RefreshTokenStatus.ACTIVE);

        // LC-08: reusing the rotated predecessor is the theft signal — generic
        // 401, and every still-ACTIVE session of the account is revoked while
        // the spent row stays as ROTATED evidence (ADR-002).
        expectRefreshRejectedWith(login.refreshToken());
        expectRefreshRejectedWith(rotated.refreshToken());
        assertThat(sessionStatusesOf(account.getId())).doesNotContain(RefreshTokenStatus.ACTIVE);
        assertThat(sessionStatusFor(account.getId(), login.refreshToken()))
                .isEqualTo(RefreshTokenStatus.ROTATED);
        assertThat(sessionStatusFor(account.getId(), rotated.refreshToken()))
                .isEqualTo(RefreshTokenStatus.REVOKED);
    }

    @Test
    void logout_whenCurrentSessionEnds_otherDeviceSessionsSurvive() throws Exception {
        registerAndConfirm("au19-logout@foleybooks.com");
        UUID accountId = userRepository.findByEmail("au19-logout@foleybooks.com").orElseThrow().getId();

        // Two logins = two devices = two independent ACTIVE sessions.
        TokenPair firstDevice = login("au19-logout@foleybooks.com");
        TokenPair secondDevice = login("au19-logout@foleybooks.com");
        assertThat(sessionStatusesOf(accountId)).containsOnly(RefreshTokenStatus.ACTIVE);

        // FR-05, D-05, LC-21: logout kills exactly the presented session — no
        // access token required, no cascade over the account's other sessions.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LogoutRequest(firstDevice.refreshToken()))))
                .andExpect(status().isNoContent());
        expectRefreshRejectedWith(firstDevice.refreshToken());
        expectTokenPair(
                mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(secondDevice.refreshToken())))), 200);

        // LC-09: already-gone and never-issued values are the same idempotent 204.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LogoutRequest(firstDevice.refreshToken()))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LogoutRequest("f".repeat(64)))))
                .andExpect(status().isNoContent());
    }

    /** FR-01 + FR-02 to a VERIFIED account, reusing the production services end-to-end. */
    private void registerAndConfirm(String normalizedEmail) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(normalizedEmail, PASSWORD))))
                .andExpect(status().isCreated());
        User account = userRepository.findByEmail(normalizedEmail).orElseThrow();
        mockMvc.perform(post("/api/v1/auth/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConfirmRequest(confirmationTokenService.rotate(account)))))
                .andExpect(status().isOk());
    }

    private TokenPair login(String email) throws Exception {
        return expectTokenPair(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(email, PASSWORD)))), 200);
    }

    private TokenPair expectTokenPair(ResultActions result, int expectedStatus) throws Exception {
        result.andExpect(status().is(expectedStatus));
        return objectMapper.readValue(
                result.andReturn().getResponse().getContentAsString(), TokenPair.class);
    }

    /** The single generic 401 every dead session answers with — spent, revoked or unknown (FR-04). */
    private void expectRefreshRejectedWith(String refreshToken) throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Hash-bound read-back: resolve the session row through the digest lookup the
     * service itself uses (ADR-002) — a raw value matching no {@code token_hash}
     * is an assertion failure — and check the account before returning its status.
     */
    private RefreshTokenStatus sessionStatusFor(UUID accountId, String rawRefreshToken) {
        RefreshToken session = refreshTokenRepository.findByTokenHash(sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new AssertionError(
                        "No refresh_tokens row is bound to the digest of the presented session token"));
        assertThat(session.getUser().getId()).isEqualTo(accountId);
        return session.getStatus();
    }

    /** Test-local mirror of the service's SHA-256-at-rest digest ({@code OpaqueTokens} is package-private in main). */
    private static String sha256Hex(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // Every Java platform ships SHA-256; its absence means a broken JVM.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private List<RefreshTokenStatus> sessionStatusesOf(UUID accountId) {
        return refreshTokenRepository.findAll().stream()
                .filter(session -> session.getUser().getId().equals(accountId))
                .map(RefreshToken::getStatus)
                .toList();
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asRoles(List<?> roles) {
        return (List<String>) roles;
    }
}
