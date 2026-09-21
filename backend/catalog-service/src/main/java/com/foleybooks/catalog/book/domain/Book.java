package com.foleybooks.catalog.book.domain;

import com.foleybooks.catalog.category.domain.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A catalogued book (FR-06..FR-09, ADR-003). Invariants the database owns and
 * this mapping mirrors: exactly one category ({@code category_id NOT NULL} +
 * FK), ISBN uniqueness ({@code uk_books_isbn} — covers are hotlinked by ISBN,
 * D-11), and exact EUR money via {@code NUMERIC(10,2)} ↔ {@code BigDecimal}
 * scale 2 (D-08 — the precision/scale here is what {@code validate} matches).
 * {@code stockQuantity} is the single stored representation of stock;
 * availability (D-09) is derived in the response layer, never persisted.
 * Entities never cross an HTTP boundary (C9).
 */
@Entity
@Table(name = "books")
public class Book {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "author", nullable = false, length = 200)
    private String author;

    /** Normalized (hyphen-free) ISBN-10/13; checksum validation is a future boundary rule (ADR-003). */
    @Column(name = "isbn", nullable = false, length = 20)
    private String isbn;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    @Column(name = "cover_url", nullable = false, length = 500)
    private String coverUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Book() {
        // JPA proxy constructor.
    }

    public Book(Category category, String title, String author, String isbn,
                BigDecimal price, int stockQuantity, String coverUrl) {
        this.category = category;
        this.title = title;
        this.author = author;
        this.isbn = isbn;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.coverUrl = coverUrl;
    }

    public UUID getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public String getIsbn() {
        return isbn;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
