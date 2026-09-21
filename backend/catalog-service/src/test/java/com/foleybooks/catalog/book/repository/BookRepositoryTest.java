package com.foleybooks.catalog.book.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.category.domain.Category;
import com.foleybooks.catalog.category.repository.CategoryRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL via Testcontainers (C15); Flyway applies V1 and
 * {@code ddl-auto=validate} (from application.yml) proves the entities match
 * the migrated schema — including {@code price} precision/scale (C16, C17,
 * plan §6.3, ADR-003).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = "CATALOG_DB_PASSWORD=test-db-secret")
class BookRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    BookRepository bookRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    TestEntityManager entityManager;

    private Category fiction;

    @BeforeEach
    void setUp() {
        fiction = categoryRepository.saveAndFlush(new Category("Fiction"));
    }

    private static Book book(Category category, String isbn, BigDecimal price, int stockQuantity) {
        return new Book(category, "Clean Code", "Robert C. Martin", isbn, price, stockQuantity,
                "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg");
    }

    @Test
    void save_whenBookPersisted_roundTripsColumnsCategoryAndAuditWithStoredPriceScale() {
        Book saved = bookRepository.saveAndFlush(book(fiction, "9780132350884", new BigDecimal("31.9"), 12));

        entityManager.clear();
        Book found = bookRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getTitle()).isEqualTo("Clean Code");
        assertThat(found.getAuthor()).isEqualTo("Robert C. Martin");
        assertThat(found.getIsbn()).isEqualTo("9780132350884");
        // D-08: NUMERIC(10,2) is the stored truth — 31.9 comes back as exactly 31.90.
        assertThat(found.getPrice()).isEqualByComparingTo("31.9");
        assertThat(found.getPrice().scale()).isEqualTo(2);
        assertThat(found.getStockQuantity()).isEqualTo(12);
        assertThat(found.getCoverUrl()).isEqualTo("https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg");
        assertThat(found.getCategory().getId()).isEqualTo(fiction.getId());
        assertThat(found.getCategory().getName()).isEqualTo("Fiction");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_whenIsbnAlreadyExists_throwsDataIntegrityViolation() {
        // uk_books_isbn: a duplicate ISBN would be two indistinguishable entries
        // of one real book — the anchor of the D-11 cover hotlinking (ADR-003).
        bookRepository.saveAndFlush(book(fiction, "9780132350884", new BigDecimal("31.99"), 5));

        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(fiction, "9780132350884", new BigDecimal("19.99"), 3)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenCategoryMissing_throwsDataIntegrityViolation() {
        // category_id NOT NULL: FR-09's "exactly one category" is a database
        // fact, not an application convention (ADR-003).
        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(null, "9780000000000", new BigDecimal("9.99"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenCategoryIdUnknown_throwsDataIntegrityViolation() {
        // fk_books_category: a book can never reference a category that does
        // not exist — no silent orphans at rest.
        Category phantom = entityManager.getEntityManager().getReference(Category.class, UUID.randomUUID());

        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(phantom, "9780000000001", new BigDecimal("9.99"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenPriceNegative_throwsDataIntegrityViolation() {
        // ck_books_price rejects negatives at rest.
        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(fiction, "9780000000002", new BigDecimal("-0.01"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenPriceZero_persistsFreeGiveawayTitle() {
        // ck_books_price allows 0.00: a free giveaway stays representable (ADR-003).
        Book saved = bookRepository.saveAndFlush(book(fiction, "9780000000003", BigDecimal.ZERO, 1));

        assertThat(bookRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void save_whenStockQuantityNegative_throwsDataIntegrityViolation() {
        // ck_books_stock: stock is never below zero at rest; availability (D-09)
        // derives from it, so there is only one number to be right about.
        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(fiction, "9780000000004", new BigDecimal("9.99"), -1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenCoverUrlMissing_throwsDataIntegrityViolation() {
        // cover_url NOT NULL: the V2 seed (CA-04) cannot silently ship a
        // coverless book — this constraint catches it (ADR-003).
        assertThatThrownBy(() -> bookRepository.saveAndFlush(new Book(
                fiction, "Clean Code", "Robert C. Martin", "9780000000005",
                new BigDecimal("9.99"), 1, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
