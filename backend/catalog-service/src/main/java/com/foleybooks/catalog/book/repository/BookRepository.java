package com.foleybooks.catalog.book.repository;

import com.foleybooks.catalog.book.domain.Book;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads for catalog_db {@code books} (ADR-003). Every integrity anchor the
 * read model relies on — {@code uk_books_isbn} (D-11 cover hotlinks), the
 * NOT NULL category FK (FR-09) — lives in the database, not in finders.
 * Search ({@code ILIKE}, D-06), whitelisted sorting (D-06) and the
 * {@code /books/batch} lookup (D-10) arrive with CA-06..CA-11.
 */
public interface BookRepository extends JpaRepository<Book, UUID> {
}
