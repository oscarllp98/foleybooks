package com.foleybooks.auth.token.domain;

import com.foleybooks.auth.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Email-confirmation token (FR-02, ADR-002). At most one row per account —
 * {@code uk_confirmation_tokens_user} enforces the single active link in the
 * DB: a resend replaces this row's hash/expiry, invalidating earlier links.
 * The row survives verification so a spent link stays resolvable (LC-03).
 * {@code lastResendAt} is null until the first resend and drives the 60 s
 * throttle (D-04).
 */
@Entity
@Table(name = "confirmation_tokens")
public class ConfirmationToken {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_resend_at")
    private Instant lastResendAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConfirmationToken() {
        // JPA proxy constructor.
    }

    public ConfirmationToken(User user, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastResendAt() {
        return lastResendAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Replaces the active link in place: the previous raw token stops
     * resolving because its hash is gone. Only ever called through
     * {@code ConfirmationTokenService}, which mints the hash.
     */
    public void rotateTo(String newTokenHash, Instant newExpiresAt) {
        this.tokenHash = newTokenHash;
        this.expiresAt = newExpiresAt;
    }

    /** Stamps the resend throttle; stays null until the first resend. */
    public void markResentAt(Instant resentAt) {
        this.lastResendAt = resentAt;
    }
}
