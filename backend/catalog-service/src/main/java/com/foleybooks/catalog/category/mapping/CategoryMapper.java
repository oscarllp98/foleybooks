package com.foleybooks.catalog.category.mapping;

import com.foleybooks.catalog.category.api.CategoryResponse;
import com.foleybooks.catalog.category.domain.Category;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * {@link Category} → {@link CategoryResponse} (FR-09, AGENTS.md §3): id and
 * name, nothing else, and the single owner of that shape — {@link
 * com.foleybooks.catalog.book.mapping.BookMapper} reaches it through {@code
 * uses} instead of restating it (ADR-009). The list overload below joins it
 * with CA-10, the task that consumes it (ADR-009).
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

    /**
     * The list/page projection consumed by {@code GET /categories} (CA-10).
     * MapStruct generates it as a loop over {@link #toResponse(Category)}, so a
     * page of categories can never drift from the {@code { id, name }} object
     * {@code BookResponse.category} already embeds: one owner per shape, at
     * every cardinality. A {@code null} list maps to {@code null}; Spring Data
     * hands out empty lists, never nulls.
     */
    List<CategoryResponse> toResponseList(List<Category> categories);
}
