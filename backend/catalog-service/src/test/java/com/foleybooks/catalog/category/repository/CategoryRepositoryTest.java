package com.foleybooks.catalog.category.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.book.repository.BookRepository;
import com.foleybooks.catalog.category.domain.Category;
import java.math.BigDecimal;
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
 * V1 schema: {@code uk_categories_name}, the LC-31 empty-category state and
 * {@code ON DELETE RESTRICT} (plan §6.3, ADR-003). Flyway +
 * {@code ddl-auto=validate} matching is proven by the context booting (C16, C17).
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
    void save_whenNameAlreadyExists_throwsDataIntegrityViolation() {
        // uk_categories_name: the name is the dedupe mechanism for the seed and
        // the FR-09 list reads as a set of distinct names (ADR-003).
        categoryRepository.saveAndFlush(new Category("Technology"));

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(new Category("Technology")))
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
        // row no book references — fully legal, and the seed may ship one.
        Category empty = categoryRepository.saveAndFlush(new Category("Unshelved"));

        assertThat(bookRepository.findAll()).isEmpty();
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
