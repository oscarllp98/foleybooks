package com.foleybooks.catalog.category.repository;

import com.foleybooks.catalog.category.domain.Category;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads for catalog_db {@code categories} (ADR-003). The MVP catalog is a
 * seeded read model (FR-14) and an empty category is a valid read state
 * (LC-31), so there is no finder-specific invariant to declare: CA-10's
 * FR-09 paginated listing runs on {@code findAll(Pageable)} with the
 * name-ascending sort the service layer owns.
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {
}
