package com.foleybooks.order.cart.repository;

import com.foleybooks.order.cart.domain.CartItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and deletes for order_db {@code cart_items} (ADR-004). Both finders ride
 * the {@code uk_cart_items_cart_book} index: {@link #findByCartId} matches its
 * leftmost {@code cart_id} prefix — the dominant FR-11 "all lines of this cart"
 * read — and {@link #findByCartIdAndBookId} is the full-key probe that turns
 * FR-10's second add into an update of the existing line (LC-13) and addresses
 * the FR-12/FR-13 line mutations. Line *totals* are never queried here: they are
 * computed server-side from live catalog data (D-08, ADR-005), never persisted
 * and never summed in SQL.
 */
public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCartId(UUID cartId);

    Optional<CartItem> findByCartIdAndBookId(UUID cartId, UUID bookId);
}
