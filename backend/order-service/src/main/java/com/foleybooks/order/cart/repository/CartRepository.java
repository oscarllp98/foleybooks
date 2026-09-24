package com.foleybooks.order.cart.repository;

import com.foleybooks.order.cart.domain.Cart;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads for order_db {@code carts} (ADR-004). One cart per user is the database's
 * {@code uk_carts_user} invariant, so {@link #findByUserId} answers at most one row
 * by construction (LC-17) and doubles as the re-read half of the race-safe
 * find-or-create OR-06 performs — the insert loser catches the unique violation
 * and calls this (same pattern as AU-12).
 */
public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserId(UUID userId);
}
