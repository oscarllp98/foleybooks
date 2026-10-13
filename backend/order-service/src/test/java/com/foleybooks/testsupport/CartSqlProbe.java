package com.foleybooks.testsupport;

import jakarta.persistence.EntityManager;
import java.util.UUID;

/**
 * Test-only SQL probe for the OR-04 DB-guarantee gates — the repository layer's
 * shape applied to the test tree, so raw SQL stays inside a "repository" by the
 * letter of AGENTS.md §12.11 (production code never sees these statements).
 *
 * <p>These three operations deliberately bypass the JPA lifecycle, because that is
 * the only way to prove what the schema promises rather than what Hibernate
 * emulates: {@code ON DELETE CASCADE} cannot be shown through
 * {@code cartRepository.delete} (Hibernate deletes the children itself and the DB
 * never runs its cascade), and the {@code gen_random_uuid()}/{@code now()} column
 * defaults cannot be shown through an entity insert ({@code @UuidGenerator} and
 * {@code @CreationTimestamp} fill every value first). Every statement is a
 * bound-parameter literal with no user input. Lives outside the
 * {@code com.foleybooks.order} package on purpose: never component-scanned
 * (same convention as {@link SecurityProbeController}).
 */
public class CartSqlProbe {

    private final EntityManager entityManager;

    public CartSqlProbe(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Inserts a {@code carts} row providing only {@code user_id}: id and audit columns must come from DB defaults. */
    public void insertCartWithOnlyUserId(UUID userId) {
        entityManager
                .createNativeQuery("INSERT INTO carts (user_id) VALUES (:userId)")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /** Raw row delete on {@code carts} — the JPA-free path that lets the FK's ON DELETE CASCADE actually run. */
    public int deleteCartRow(UUID cartId) {
        return entityManager
                .createNativeQuery("DELETE FROM carts WHERE id = :cartId")
                .setParameter("cartId", cartId)
                .executeUpdate();
    }

    /** Row count straight from {@code cart_items}, read after the delete to observe the cascade. */
    public long countCartItemsOf(UUID cartId) {
        Number count = (Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM cart_items WHERE cart_id = :cartId")
                .setParameter("cartId", cartId)
                .getSingleResult();
        return count.longValue();
    }
}
