package com.foleybooks.catalog.book.repository;

import com.foleybooks.catalog.book.domain.Book;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Reads for catalog_db {@code books} (ADR-003). Every integrity anchor the
 * read model relies on — {@code uk_books_isbn} (D-11 cover hotlinks), the
 * NOT NULL category FK (FR-09) — lives in the database, not in finders.
 * CA-06's browse page ships on the inherited {@code findAll(Pageable)} alone;
 * CA-08's search/category filter (D-06) composes through
 * {@link JpaSpecificationExecutor} over {@link BookSpecifications} rather than
 * a declared finder, because its two criteria are independently optional and
 * must combine as {@code AND}; the {@code /books/batch} lookup (D-10) arrives
 * with CA-11.
 */
public interface BookRepository extends JpaRepository<Book, UUID>, JpaSpecificationExecutor<Book> {
}
