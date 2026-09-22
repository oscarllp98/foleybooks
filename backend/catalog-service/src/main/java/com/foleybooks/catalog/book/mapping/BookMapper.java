package com.foleybooks.catalog.book.mapping;

import com.foleybooks.catalog.book.api.BookResponse;
import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.book.service.AvailabilityPolicy;
import com.foleybooks.catalog.category.mapping.CategoryMapper;
import java.util.List;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * {@link Book} → {@link BookResponse} (FR-06, FR-07, AGENTS.md §3) — the
 * catalog's one entity-to-record projection, reused verbatim by every read path
 * (list, detail, search, and the {@code /books/batch} lookup of D-10), which is
 * what keeps those responses from drifting apart (ADR-009).
 *
 * <p>This mapper holds no rule of its own. The FR-07 badge is not a column, so
 * it is delegated to the service layer's single owner — {@link
 * AvailabilityPolicy#forStock(int)} (C8, D-09), bound explicitly by
 * {@code qualifiedByName} so a future int-returning collaborator can never be
 * picked implicitly. The nested category goes through {@link CategoryMapper}
 * for the same reason: one owner per shape. {@code unmappedTargetPolicy =
 * ERROR} makes that wiring a compile-time obligation — a new {@code
 * BookResponse} component fails the build instead of shipping a silent
 * {@code null}, and the entity's audit columns cannot be pulled across the HTTP
 * boundary (C9). Spring component model with constructor injection, so both
 * collaborators are present or the context fails to start.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        uses = {CategoryMapper.class, AvailabilityPolicy.class})
public interface BookMapper {

    @Mapping(target = "availability", source = "stockQuantity", qualifiedByName = "availabilityOfStock")
    BookResponse toResponse(Book book);

    /**
     * The list/page projection consumed by the read paths (ADR-009: "list/page
     * mapping is added by the task that consumes it" — CA-06 here, CA-10/CA-11
     * later). MapStruct generates it as a loop over {@link #toResponse(Book)},
     * so a collection can never drift from the single-entity contract: one
     * owner per shape, at every cardinality. A {@code null} list maps to
     * {@code null}; Spring Data hands out empty lists, never nulls.
     */
    List<BookResponse> toResponseList(List<Book> books);
}
