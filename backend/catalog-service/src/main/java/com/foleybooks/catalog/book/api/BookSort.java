package com.foleybooks.catalog.book.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * A validated sort selection for the FR-06 browse page (CA-07, D-06). It is the
 * boundary's answer to "which way should the list come back?", and it is only
 * ever produced by {@code BookSortConverter}, which restricts the field to the
 * whitelisted entity attributes {@link Field#TITLE} and {@link Field#PRICE} and
 * the direction to {@link Direction#ASC} or {@link Direction#DESC}. Because the
 * whitelist <em>is</em> the type — a closed enum, not a free string — no value
 * of this record can name a column the JPA query cannot resolve, so browsing
 * never dies on a {@code PropertyReferenceException} and never leaks a schema
 * name (LC-28). The two enums keep Spring Data's own {@code Sort} out of the web
 * layer entirely. As a request/response contract record it lives in {@code api/}
 * alongside {@link BookResponse} (AGENTS.md §3), and the service consumes it
 * exactly as it produces the response record.
 *
 * @param field     the whitelisted attribute to sort on
 * @param direction ascending or descending; ascending is the FR-06 default
 */
public record BookSort(Field field, Direction direction) {

    /**
     * The attributes a client may sort the catalog by (FR-06). The whitelist and
     * the entity property name are one and the same here: {@link #property()} is
     * both the accepted query token and the JPA property Spring Data sorts on,
     * so there is no second list to keep in step.
     */
    public enum Field {
        TITLE("title"),
        PRICE("price");

        private final String property;

        Field(String property) {
            this.property = property;
        }

        /** The query token and the JPA entity property name, identical by design. */
        public String property() {
            return property;
        }

        /**
         * Resolves a case-insensitive query token to a whitelisted field, or
         * empty for anything else — the single gate that turns "unknown sort
         * field" into a rejection rather than a passthrough.
         */
        public static Optional<Field> fromProperty(String token) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(field -> field.property.equals(normalized))
                    .findFirst();
        }
    }

    /** Sort direction, ascending by default (FR-06). */
    public enum Direction {
        ASC,
        DESC;

        /**
         * Resolves a case-insensitive query token ({@code asc} / {@code desc}) to
         * a direction, or empty for anything else.
         */
        public static Optional<Direction> fromToken(String token) {
            String normalized = token.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(direction -> direction.name().equals(normalized))
                    .findFirst();
        }
    }
}
