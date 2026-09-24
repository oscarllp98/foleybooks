package com.foleybooks.order.cart.domain;

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
 * One cart line: the recorded intent "this user wants N of this book" (FR-10,
 * ADR-004) and nothing else — no title, cover, price or stock snapshot, because
 * those live in {@code catalog_db} and are re-read on every access (C18; a stored
 * copy would be the drift-prone second truth D-08/NFR-07 forbid). The database
 * owns the three invariants: one line per book per cart ({@code
 * uk_cart_items_cart_book} — the LC-13 summing anchor), quantity ≥ 1
 * ({@code ck_cart_items_quantity} — FR-12's set-to-zero is a delete, never a
 * stored zero), and intra-service cascade on cart removal. {@code bookId} is an
 * <b>opaque</b> UUID with no FK (C18): a line whose book vanished stays
 * representable, which is exactly the LC-14 {@code available=false} state.
 * Entities never cross an HTTP boundary (C9).
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    /** Catalog public identifier (AGENTS.md §6) — no cross-service FK, by design (C18). */
    @Column(name = "book_id", nullable = false)
    private UUID bookId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Doubles as the re-add marker: how support tells a fresh line from a long-lived one (ADR-004). */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CartItem() {
        // JPA proxy constructor.
    }

    public CartItem(Cart cart, UUID bookId, int quantity) {
        this.cart = cart;
        this.bookId = bookId;
        this.quantity = quantity;
    }

    public UUID getId() {
        return id;
    }

    public Cart getCart() {
        return cart;
    }

    public UUID getBookId() {
        return bookId;
    }

    public int getQuantity() {
        return quantity;
    }

    /**
     * Sole setter: quantity is the one field a service ever rewrites (LC-13
     * capping on add, FR-12 update). The ≥ 1 floor is the database's
     * ({@code ck_cart_items_quantity}); bounds against live stock are service
     * logic against a catalog answer, never entity code (C8).
     */
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
