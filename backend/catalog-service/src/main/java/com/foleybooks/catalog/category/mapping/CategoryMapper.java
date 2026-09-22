package com.foleybooks.catalog.category.mapping;

import com.foleybooks.catalog.category.api.CategoryResponse;
import com.foleybooks.catalog.category.domain.Category;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * {@link Category} → {@link CategoryResponse} (FR-09, AGENTS.md §3): id and
 * name, nothing else, and the single owner of that shape — {@link
 * com.foleybooks.catalog.book.mapping.BookMapper} reaches it through {@code
 * uses} instead of restating it (ADR-009). A list method is deliberately
 * absent: {@code GET /categories} (CA-10) maps a {@code Page}, and C1 says the
 * element mapper is the only half that is required today.
 *
 * <p>{@code unmappedTargetPolicy = ERROR} is what enforces "nothing else" —
 * {@code createdAt}/{@code updatedAt} can only reach a response if someone
 * widens the record and wires it on purpose, never by accident (C9).
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CategoryMapper {

    CategoryResponse toResponse(Category category);
}
