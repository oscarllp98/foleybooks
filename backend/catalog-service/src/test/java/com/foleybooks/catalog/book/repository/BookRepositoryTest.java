package com.foleybooks.catalog.book.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.category.domain.Category;
import com.foleybooks.catalog.category.repository.CategoryRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL via Testcontainers (C15); Flyway applies V1+V2 and
 * {@code ddl-auto=validate} (from application.yml) proves the entities match
 * the migrated schema — including {@code price} precision/scale (C16, C17,
 * plan §6.3, ADR-003). Constraint-probe fixtures stay deliberately clear of
 * the V2 seed (its category names and ISBNs), so every insert below is a
 * genuinely new row; the seed itself is asserted by
 * {@link #seedBooks_whenMigrationsApplied_shipTwelveRealBooksAcrossEveryAvailabilityBucket}
 * (FR-14, mirroring auth's demo-user test).
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

    private Category fixture;

    @BeforeEach
    void setUp() {
        fixture = categoryRepository.saveAndFlush(new Category("Essays"));
    }

    private static Book book(Category category, String isbn, BigDecimal price, int stockQuantity) {
        return new Book(category, "Refactoring", "Martin Fowler", isbn, price, stockQuantity,
                "https://covers.openlibrary.org/b/isbn/9780134757599-L.jpg");
    }

    @Test
    void save_whenBookPersisted_roundTripsColumnsCategoryAndAuditWithStoredPriceScale() {
        Book saved = bookRepository.saveAndFlush(book(fixture, "9780134757599", new BigDecimal("31.9"), 12));

        entityManager.clear();
        Book found = bookRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getTitle()).isEqualTo("Refactoring");
        assertThat(found.getAuthor()).isEqualTo("Martin Fowler");
        assertThat(found.getIsbn()).isEqualTo("9780134757599");
        // D-08: NUMERIC(10,2) is the stored truth — 31.9 comes back as exactly 31.90.
        assertThat(found.getPrice()).isEqualByComparingTo("31.9");
        assertThat(found.getPrice().scale()).isEqualTo(2);
        assertThat(found.getStockQuantity()).isEqualTo(12);
        assertThat(found.getCoverUrl()).isEqualTo("https://covers.openlibrary.org/b/isbn/9780134757599-L.jpg");
        assertThat(found.getCategory().getId()).isEqualTo(fixture.getId());
        assertThat(found.getCategory().getName()).isEqualTo("Essays");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void seedBooks_whenMigrationsApplied_shipTwelveRealBooksAcrossEveryAvailabilityBucket() {
        // FR-14 / D-12 / ADR-003: the V2 seed is the whole demo catalog — 12 real
        // books in the seeded categories, each cover hotlinked from the row's own
        // ISBN (D-11), and stock spanning all three D-09 buckets including both
        // LOW/IN edges (5 and 6) so every availability state renders without
        // touching the database.
        List<Book> seeded = bookRepository.findAll();

        assertThat(seeded).hasSize(12);
        assertThat(seeded).allSatisfy(book -> {
            assertThat(book.getTitle()).isNotBlank();
            assertThat(book.getAuthor()).isNotBlank();
            assertThat(book.getCoverUrl())
                    .isEqualTo("https://covers.openlibrary.org/b/isbn/" + book.getIsbn() + "-L.jpg");
            assertThat(book.getCategory().getName()).isIn("Fiction", "Technology", "Business");
            // D-08: NUMERIC(10,2) keeps every seed price exact at scale 2.
            assertThat(book.getPrice().scale()).isEqualTo(2);
            assertThat(book.getCreatedAt()).isNotNull();
            assertThat(book.getUpdatedAt()).isNotNull();
        });

        // D-09 buckets at rest: OUT_OF_STOCK 0, LOW_STOCK 1–5, IN_STOCK >5.
        assertThat(seeded).filteredOn(book -> book.getStockQuantity() == 0).isNotEmpty();
        assertThat(seeded).filteredOn(book -> book.getStockQuantity() >= 1 && book.getStockQuantity() <= 5)
                .anyMatch(book -> book.getStockQuantity() == 5);
        assertThat(seeded).anyMatch(book -> book.getStockQuantity() == 6);

        // The plan §2 contract example is seed data: Clean Code, EUR 31.99, IN_STOCK.
        Book cleanCode = seeded.stream()
                .filter(book -> "9780132350884".equals(book.getIsbn()))
                .findFirst()
                .orElseThrow();
        assertThat(cleanCode.getId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-00000000cb06"));
        assertThat(cleanCode.getTitle()).isEqualTo("Clean Code");
        assertThat(cleanCode.getAuthor()).isEqualTo("Robert C. Martin");
        assertThat(cleanCode.getPrice()).isEqualByComparingTo("31.99");
        assertThat(cleanCode.getStockQuantity()).isGreaterThan(5);
    }

    @Test
    void findAll_whenPagedWithDefaultTitleSort_windowsSeedCatalogStably() {
        // CA-06's default sort is {@code Sort.by(ASC, "title")} on the entity
        // property — Spring Data raises PropertyReferenceException for a name
        // that does not resolve, so this call proves FR-06's sort path exists
        // on the real mapping, and the windowing proves the Page metadata the
        // envelope publishes is the repository's own.
        Sort titleAsc = Sort.by(Sort.Direction.ASC, "title");

        Page<Book> firstWindow = bookRepository.findAll(PageRequest.of(0, 5, titleAsc));
        List<String> fullOrder = bookRepository.findAll(PageRequest.of(0, 12, titleAsc))
                .getContent().stream().map(Book::getTitle).toList();

        assertThat(firstWindow.getContent()).extracting(Book::getTitle)
                .containsExactlyElementsOf(fullOrder.subList(0, 5));
        assertThat(firstWindow.getTotalElements()).isEqualTo(12);
        assertThat(firstWindow.getTotalPages()).isEqualTo(3);
        assertThat(firstWindow.getNumber()).isZero();
        assertThat(firstWindow.getSize()).isEqualTo(5);
    }

    @Test
    void findAll_whenPagedWithPriceSort_ordersSeedCatalogByPriceBothDirections() {
        // CA-07 whitelists "price" as a sortable field (FR-06). The query is built
        // from the entity property, so this proves the whitelisted name actually
        // resolves on the real mapping (Spring Data would raise PropertyReferenceException
        // otherwise) and returns a genuinely ordered window in both directions.
        List<BigDecimal> ascending = bookRepository
                .findAll(PageRequest.of(0, 12, Sort.by(Sort.Direction.ASC, "price")))
                .getContent().stream().map(Book::getPrice).toList();
        List<BigDecimal> descending = bookRepository
                .findAll(PageRequest.of(0, 12, Sort.by(Sort.Direction.DESC, "price")))
                .getContent().stream().map(Book::getPrice).toList();

        assertThat(ascending).isSorted().hasSize(12);
        assertThat(descending).isSortedAccordingTo(Comparator.reverseOrder()).hasSize(12);
    }

    @Test
    void save_whenIsbnAlreadyExists_throwsDataIntegrityViolation() {
        // uk_books_isbn: a duplicate ISBN would be two indistinguishable entries
        // of one real book — the anchor of the D-11 cover hotlinking (ADR-003).
        bookRepository.saveAndFlush(book(fixture, "9780134757599", new BigDecimal("31.99"), 5));

        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(fixture, "9780134757599", new BigDecimal("19.99"), 3)))
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
                book(fixture, "9780000000002", new BigDecimal("-0.01"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenPriceZero_persistsFreeGiveawayTitle() {
        // ck_books_price allows 0.00: a free giveaway stays representable (ADR-003).
        Book saved = bookRepository.saveAndFlush(book(fixture, "9780000000003", BigDecimal.ZERO, 1));

        assertThat(bookRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void save_whenStockQuantityNegative_throwsDataIntegrityViolation() {
        // ck_books_stock: stock is never below zero at rest; availability (D-09)
        // derives from it, so there is only one number to be right about.
        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(fixture, "9780000000004", new BigDecimal("9.99"), -1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenCoverUrlMissing_throwsDataIntegrityViolation() {
        // cover_url NOT NULL: the V2 seed (CA-04) cannot silently ship a
        // coverless book — this constraint catches it (ADR-003).
        assertThatThrownBy(() -> bookRepository.saveAndFlush(new Book(
                fixture, "Refactoring", "Martin Fowler", "9780000000005",
                new BigDecimal("9.99"), 1, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
