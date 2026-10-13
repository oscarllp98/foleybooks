package com.foleybooks.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foleybooks.auth.token.api.LoginRequest;
import com.foleybooks.auth.token.repository.ConfirmationTokenRepository;
import com.foleybooks.auth.token.service.ConfirmationTokenService;
import com.foleybooks.auth.user.api.ConfirmRequest;
import com.foleybooks.auth.user.api.RegisterRequest;
import com.foleybooks.auth.user.api.ResendRequest;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserStatus;
import com.foleybooks.auth.user.repository.UserRepository;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The account-enumeration barrier end-to-end on real PostgreSQL (plan §6.4,
 * AU-20): FR-01's register and FR-02's resend must answer every server-side
 * branch with the same status and the same bytes, so an outside caller can
 * never tell a fresh address from a taken one, a pending account from a
 * verified one, or a live send from a throttled one (LC-01, LC-19, LC-20;
 * NFR-01). Everything runs through the public HTTP surface with the real
 * security chain, real Flyway schema and real BCrypt-12; the only seam is
 * mail ({@code app.mail.transport=log}, ADR-001), which the responses must not
 * depend on either. Uniformity is asserted on the complete observable: a
 * captured pair of HTTP status plus raw response body, compared with record
 * equality — same fields in the same order with the same values, not a
 * hand-picked subset. Because the uniform mask must hide genuinely different
 * server work, each test also reads the persisted state back: duplicate
 * registration leaves exactly one account, a confirmed account stays VERIFIED
 * behind the constant {@code UNVERIFIED} envelope, and a live resend rotates
 * the confirmation hash where a throttled one leaves it untouched — proof the
 * branches really differ while their responses cannot.
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
class EnumerationSafetyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Per-run throwaway fixture passwords — never committed, never logged (C19, C24); hex UUIDs satisfy the FR-01 rules (≥8 chars, letter + digit). */
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String OTHER_PASSWORD = UUID.randomUUID().toString();

    /** Parallel duplicate-registration attackers for the LC-19 race probe. */
    private static final int RACERS = 4;
    private static final long REQUEST_TIMEOUT_SECONDS = 30;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ConfirmationTokenRepository confirmationTokenRepository;

    @Autowired
    ConfirmationTokenService confirmationTokenService;

    @Test
    void register_whenEveryDuplicateBranch_answersStatusAndBodyIdenticalToFreshRegistration() throws Exception {
        String email = "au20-branches@foleybooks.com";

        // The four FR-01 branches the spec enumerates: a fresh address (the
        // create + first-link path), an UNVERIFIED duplicate (rotate + fresh
        // link), the same duplicate inside the 60 s throttle (silent path),
        // and a VERIFIED duplicate (already-registered notice path).
        CapturedResponse fresh = register(email, PASSWORD);
        CapturedResponse unverifiedDuplicate = register("  AU20-BRANCHES@FoleyBooks.COM  ", OTHER_PASSWORD);
        CapturedResponse throttledDuplicate = register(email, OTHER_PASSWORD);
        confirmAccount(email);
        CapturedResponse verifiedDuplicate = register(email, OTHER_PASSWORD);

        // LC-01: none of them is distinguishable — same 201, same bytes, even
        // when the submitted casing, padding and password differ.
        assertThat(fresh.status()).isEqualTo(201);
        assertThat(unverifiedDuplicate).isEqualTo(fresh);
        assertThat(throttledDuplicate).isEqualTo(fresh);
        assertThat(verifiedDuplicate).isEqualTo(fresh);

        // The envelope itself is the fixed one FR-01 promises: exactly three
        // fields, the normalized address echoed back, and a constant
        // UNVERIFIED status even though this account is VERIFIED now.
        assertThat(fieldNames(fresh.body())).containsExactlyInAnyOrder("email", "status", "message");
        assertThat(stringField(fresh.body(), "email")).isEqualTo(email);
        assertThat(stringField(fresh.body(), "status")).isEqualTo("UNVERIFIED");

        // NFR-01/C24: neither a token nor a password ever crosses this boundary.
        assertThat(fresh.body()).doesNotContainPattern("(?i)[0-9a-f]{64}");
        assertThat(fresh.body()).doesNotContain(PASSWORD).doesNotContain(OTHER_PASSWORD);

        // The uniform mask hides real server work without being changed by it:
        // still exactly one account, still VERIFIED, and the ORIGINAL password
        // still signs in — a duplicate registration never overwrote the hash.
        assertThat(accountsWithEmail(email)).isEqualTo(1);
        assertThat(userRepository.findByEmail(email).orElseThrow().getStatus()).isEqualTo(UserStatus.VERIFIED);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(email, OTHER_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_whenConcurrentDuplicateRequests_exactlyOneAccountAndUniformResponses() throws Exception {
        String email = "au20-race@foleybooks.com";

        // LC-19: RACERS simultaneous registrations of one address. BCrypt-12
        // keeps every thread inside the pre-check window long enough that the
        // uk_users_email race is genuinely contested on real Postgres.
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<CapturedResponse>> attempts = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(RACERS)) {
            for (int i = 0; i < RACERS; i++) {
                attempts.add(pool.submit(() -> {
                    assertThat(startSignal.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                    return register(email, PASSWORD);
                }));
            }
            startSignal.countDown();
        }

        // Every loser of the race falls through to a duplicate branch, and
        // every branch answers the identical enumeration-safe 201 (LC-01).
        List<CapturedResponse> responses = new ArrayList<>();
        for (Future<CapturedResponse> attempt : attempts) {
            responses.add(attempt.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        CapturedResponse uniform = new CapturedResponse(201, responses.get(0).body());
        assertThat(responses).containsOnly(uniform);

        // And the race created exactly one account — the constraint held.
        assertThat(accountsWithEmail(email)).isEqualTo(1);
    }

    @Test
    void resend_whenAccountStatesDiffer_answersIdenticalBytesAcrossAddresses() throws Exception {
        String pendingEmail = "au20-resend-pending@foleybooks.com";
        register(pendingEmail, PASSWORD);
        UUID pendingId = userRepository.findByEmail(pendingEmail).orElseThrow().getId();
        String issuedHash = activeConfirmationHash(pendingId);

        String verifiedEmail = "au20-resend-verified@foleybooks.com";
        register(verifiedEmail, PASSWORD);
        confirmAccount(verifiedEmail);
        UUID verifiedId = userRepository.findByEmail(verifiedEmail).orElseThrow().getId();
        String verifiedHashBefore = activeConfirmationHash(verifiedId);

        // The four FR-02 resend branches — unknown address, live unverified
        // address, same address inside the 60 s D-04 throttle, verified
        // address — deliberately on three DIFFERENT addresses.
        CapturedResponse unknown = resend("au20-resend-unknown@foleybooks.com");
        CapturedResponse live = resend(pendingEmail);
        CapturedResponse throttled = resend(pendingEmail);
        CapturedResponse verified = resend(verifiedEmail);

        // LC-20: byte-identical 202s, across different submitted emails,
        // because the envelope does not even echo the address back.
        assertThat(unknown.status()).isEqualTo(202);
        assertThat(live).isEqualTo(unknown);
        assertThat(throttled).isEqualTo(unknown);
        assertThat(verified).isEqualTo(unknown);
        assertThat(fieldNames(unknown.body())).containsExactly("message");
        assertThat(unknown.body())
                .doesNotContainPattern("(?i)[0-9a-f]{64}")
                .doesNotContain("au20-");

        // Identical outside, different work inside: the live branch rotated
        // the active link, while the throttled and verified branches left
        // their accounts' hashes untouched (LC-04, FR-02).
        String rotatedHash = activeConfirmationHash(pendingId);
        assertThat(rotatedHash).isNotEqualTo(issuedHash);
        assertThat(activeConfirmationHash(pendingId)).isEqualTo(rotatedHash);
        assertThat(activeConfirmationHash(verifiedId)).isEqualTo(verifiedHashBefore);
    }

    /** FR-01 + FR-02 to a VERIFIED account, reusing the production surface end-to-end. */
    private void confirmAccount(String normalizedEmail) throws Exception {
        User account = userRepository.findByEmail(normalizedEmail).orElseThrow();
        mockMvc.perform(post("/api/v1/auth/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ConfirmRequest(confirmationTokenService.rotate(account)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"));
    }

    private CapturedResponse register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(email, password))))
                .andReturn();
        return capture(result);
    }

    private CapturedResponse resend(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ResendRequest(email))))
                .andReturn();
        return capture(result);
    }

    private static CapturedResponse capture(MvcResult result) throws UnsupportedEncodingException {
        return new CapturedResponse(result.getResponse().getStatus(),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** The digest currently resolvable for the account — the one active confirmation link (FR-02). */
    private String activeConfirmationHash(UUID accountId) {
        return confirmationTokenRepository.findByUserId(accountId).orElseThrow().getTokenHash();
    }

    private long accountsWithEmail(String normalizedEmail) {
        return userRepository.findAll().stream()
                .filter(account -> account.getEmail().equals(normalizedEmail))
                .count();
    }

    private List<String> fieldNames(String body) throws Exception {
        List<String> names = new ArrayList<>();
        objectMapper.readTree(body).fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String stringField(String body, String field) throws Exception {
        return objectMapper.readTree(body).get(field).asText();
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** The entire externally observable answer of one request: HTTP status plus raw response body. */
    private record CapturedResponse(int status, String body) {
    }
}
