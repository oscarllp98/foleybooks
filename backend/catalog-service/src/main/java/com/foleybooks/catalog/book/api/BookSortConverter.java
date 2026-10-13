package com.foleybooks.catalog.book.api;

import java.util.Arrays;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Turns the raw {@code sort} query parameter of {@code GET /api/v1/books} into a
 * whitelisted {@link BookSort} (CA-07, D-06), at the boundary and before the
 * service sees it (C23). Accepted grammar is {@code field} or {@code field,direction}:
 * the field is one of {@link BookSort.Field} and the optional direction is
 * {@code asc}/{@code desc} (ascending when omitted, FR-06). Both are matched
 * case-insensitively and trimmed, so {@code Price,DESC} and {@code price} are
 * valid.
 *
 * <p>Anything that is not on the whitelist throws {@link IllegalArgumentException},
 * which Spring's argument binder wraps as a {@code MethodArgumentTypeMismatchException}
 * — the same {@code 400} validation ProblemDetail (field {@code sort},
 * {@code "has an invalid value"}) that a malformed {@code page}/{@code size} already
 * produces (LC-28, D-15). This is the whole point of a whitelist converter rather
 * than Spring's built-in {@code String → Sort}: Spring would accept any property,
 * so {@code ?sort=rating,asc} would sail into the JPA query and surface as a
 * {@code 500}, and {@code ?sort=<column>} would hint at the schema. A blank value
 * ({@code sort=}) is treated as "not specified" (null) so the FR-06 default still
 * applies. No secret is ever put in an exception message.
 *
 * <p>Living in the feature's {@code api/} package beside the controller and the
 * {@link BookSort} it binds (AGENTS.md §3, mirroring auth's
 * {@code user/api/EmailNormalizingDeserializer} as the boundary input-shaping
 * component). Registered as a {@link Component} so Spring Boot adds it to the MVC
 * conversion service automatically, and so {@code @WebMvcTest} — which includes
 * {@code Converter} beans in its slice — exercises the real binding path.
 */
@Component
public class BookSortConverter implements Converter<String, BookSort> {

    @Override
    public BookSort convert(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        // -1 keeps trailing empty tokens so "price," is a malformed direction, not a silent ASC.
        String[] tokens = source.split(",", -1);
        if (tokens.length > 2) {
            throw new IllegalArgumentException("sort must be 'field' or 'field,direction'");
        }
        BookSort.Field field = BookSort.Field.fromProperty(tokens[0])
                .orElseThrow(BookSortConverter::unknownField);
        BookSort.Direction direction = tokens.length == 1
                ? BookSort.Direction.ASC
                : BookSort.Direction.fromToken(tokens[1]).orElseThrow(BookSortConverter::unknownDirection);
        return new BookSort(field, direction);
    }

    private static IllegalArgumentException unknownField() {
        String allowed = String.join(", ",
                Arrays.stream(BookSort.Field.values()).map(BookSort.Field::property).toList());
        return new IllegalArgumentException("unknown sort field; allowed values are: " + allowed);
    }

    private static IllegalArgumentException unknownDirection() {
        String allowed = String.join(", ",
                Arrays.stream(BookSort.Direction.values())
                        .map(direction -> direction.name().toLowerCase(Locale.ROOT)).toList());
        return new IllegalArgumentException("unknown sort direction; allowed values are: " + allowed);
    }
}
