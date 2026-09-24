package com.foleybooks.order.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One user's persistent cart (FR-10/FR-11, ADR-004). The owner is the {@code sub}
 * claim of the validated access token (ADR-004/ADR-008) — a client can never name a
 * cart id or another user, because the API surface has neither. Exactly one cart
 * per user, shared by every session and device, is a database invariant
 * ({@code uk_carts_user}, LC-17), not a service convention, which also makes the
 * find-or-create race-safe: the loser catches the unique violation and re-reads
 * (same pattern as AU-12). Lines hang off {@code cart_items}; no collection is
 * mapped here because every read goes through the cart-items repository against
 * the {@code cart_id} index prefix. Entities never cross an HTTP boundary (C9).
 */
@Entity
@Table(name = "carts")
public class Cart {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Auth-service user UUID from the JWT {@code sub} claim — no FK, by design (C18). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Cart() {
        // JPA proxy constructor.
    }

    public Cart(UUID userId) {
        this.userId = userId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
