package com.foleybooks.catalog.book.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.catalog.book.api.BookSort.Direction;
import com.foleybooks.catalog.book.api.BookSort.Field;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CA-07's whitelist in isolation (plan §6.1): the converter is the only door a
 * {@code sort} query value passes on its way to {@link BookSort}, so it must
 * accept exactly {@code title}/{@code price} with an optional {@code asc}/{@code
 * desc}, normalise case and surrounding blanks, treat an empty/absent value as
 * "not specified" (null → the FR-06 default), and reject everything else — every
 * off-whitelist field, every bad direction, and the injection-flavoured strings
 * LC-28 worries about — with {@link IllegalArgumentException}, which the MVC
 * binder turns into a 400. The rejection cases are asserted here as a unit
 * (no Spring); the web slice proves the same exceptions surface as the shared
 * validation ProblemDetail.
 */
class BookSortConverterTest {

    private final BookSortConverter converter = new BookSortConverter();

    @ParameterizedTest(name = "convert({0}) -> {1}/{2}")
    @CsvSource(delimiterString = ";", value = {
            "title; TITLE; ASC",
            "price; PRICE; ASC",
            "title,asc; TITLE; ASC",
            "title,desc; TITLE; DESC",
            "price,desc; PRICE; DESC",
            "PRICE,DESC; PRICE; DESC",
            "Title, Asc; TITLE; ASC",
            "'  price  ,  desc  '; PRICE; DESC"
    })
    void convert_whenWhitelisted_returnsNormalizedSelection(String raw, Field field, Direction direction) {
        assertThat(converter.convert(raw)).isEqualTo(new BookSort(field, direction));
    }

    @ParameterizedTest(name = "convert({0}) is not specified -> null (FR-06 default)")
    @NullSource
    @EmptySource
    @ValueSource(strings = {"   ", "\t"})
    void convert_whenBlank_returnsNull(String raw) {
        // An absent or empty sort is not an error; null tells the service to apply
        // its title-ascending default.
        assertThat(converter.convert(raw)).isNull();
    }

    @ParameterizedTest(name = "convert({0}) is off the whitelist -> rejected (LC-28)")
    @ValueSource(strings = {
            "rating",
            "author",
            "isbn",
            "id",
            "createdAt",
            "stockQuantity",
            "category",
            "price,ascending",
            "title,bogus",
            "price,",
            ",asc",
            ",",
            "title,asc,desc",
            "' OR 1=1--",
            "; drop table books",
            "title;desc"
    })
    void convert_whenRejected_throwsIllegalArgument(String raw) {
        // Only the whitelisted fields and directions exist. Anything else — a
        // real-but-exposed column (author/isbn/id/stockQuantity), a made-up field,
        // a malformed direction or arity, or SQL-ish noise — is refused here so it
        // can never reach the JPA query.
        assertThatThrownBy(() -> converter.convert(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
