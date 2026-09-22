package com.foleybooks.catalog.category.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.book.repository.BookRepository;
import com.foleybooks.catalog.category.domain.Category;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
 * Real PostgreSQL via Testcontainers (C15) for the categories half of the
 * schema: {@code uk_categories_name}, the LC-31 empty-category state and
 * {@code ON DELETE RESTRICT} (plan §6.3, ADR-003). Flyway applies V1+V2 and
 * {@code ddl-auto=validate} matching is proven by the context booting (C16,
 * C17); fixtures stay clear of the V2 seed's names so probes insert genuinely
 * new rows, while the seed itself is asserted by
 * {@link #seedCategories_whenMigrationsApplied_threeFixedCategoriesOwnTheirUuids}
 * (FR-14, mirroring auth's demo-user test).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = "CATALOG_DB_PASSWORD=test-db-secret")
class CategoryRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    BookRepository bookRepository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void seedCategories_whenMigrationsApplied_threeFixedCategoriesOwnTheirUuids() {
        // FR-14 / D-12 / ADR-003: the V2 seed ships exactly three categories with
        // fixed UUIDs (CA-06..CA-11 reference them by stable id, never by name),
        // so CA-04 cannot silently ship a fourth or rename one.
        List<Category> seeded = categoryRepository.findAll();

        assertThat(seeded).extracting(Category::getName)
                .containsExactlyInAnyOrder("Fiction", "Technology", "Business");
        assertThat(categoryRepository.findById(
                UUID.fromString("00000000-0000-0000-0000-00000000ca01"))).isPresent();
        assertThat(categoryRepository.findById(
                UUID.fromString("00000000-0000-0000-0000-00000000ca02"))).isPresent();
        assertThat(categoryRepository.findById(
                UUID.fromString("00000000-0000-0000-0000-00000000ca03"))).isPresent();
    }

    @Test
    void save_whenNameAlreadyExists_throwsDataIntegrityViolation() {
        // uk_categories_name: the name is the dedupe mechanism for the seed and
        // the FR-09 list reads as a set of distinct names (ADR-003). "Biography"
        // is deliberately *not* a seed name so the first insert is genuinely new.
        categoryRepository.saveAndFlush(new Category("Biography"));

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(new Category("Biography")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenCategoryPersisted_roundTripsNameAndAuditColumns() {
        Category saved = categoryRepository.saveAndFlush(new Category("Poetry"));

        entityManager.clear();
        Category found = categoryRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getName()).isEqualTo("Poetry");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_whenNoBookReferencesCategory_persistsEmptyState() {
        // LC-31 at rest: the FK lives on books, so an empty category is just a
        // row no book references — fully legal. The V2 seed puts books in every
        // category, so emptiness must be measured per category, not globally.
        Category empty = categoryRepository.saveAndFlush(new Category("Unshelved"));

        Long referencing = entityManager.getEntityManager()
                .createQuery("SELECT COUNT(b) FROM Book b WHERE b.category.id = :categoryId", Long.class)
                .setParameter("categoryId", empty.getId())
                .getSingleResult();

        assertThat(referencing).isZero();
        assertThat(categoryRepository.findById(empty.getId())).isPresent();
    }

    @Test
    void delete_whenCategoryStillHasBooks_throwsDataIntegrityViolation() {
        // fk_books_category ON DELETE RESTRICT: a category still holding books
        // can never be deleted — a loud failure, never silent orphaning (ADR-003).
        Category used = categoryRepository.saveAndFlush(new Category("History"));
        Book book = bookRepository.saveAndFlush(new Book(used, "The Pragmatic Programmer",
                "Hunt and Thomas", "9780201616224", new BigDecimal("49.99"), 4,
                "https://covers.openlibrary.org/b/isbn/9780201616224-L.jpg"));
        // Detach the referencing instance: otherwise Hibernate's flush-time check
        // refuses a managed entity pointing at a REMOVED one before any SQL is
        // sent — detaching lets the database constraint itself answer (C15).
        entityManager.detach(book);

        assertThatThrownBy(() -> {
            categoryRepository.delete(used);
            categoryRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
