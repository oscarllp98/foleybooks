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
 * must combine as {@code AND}; CA-11's batch lookup (D-10) ships on the
 * inherited {@code findAllById} — a single {@code WHERE id IN (...)} that
 * returns only the rows present, which is exactly the absent-id semantics the
 * cart enrichment needs (LC-14), so it wants no custom finder either.
 */
public interface BookRepository extends JpaRepository<Book, UUID>, JpaSpecificationExecutor<Book> {
}
