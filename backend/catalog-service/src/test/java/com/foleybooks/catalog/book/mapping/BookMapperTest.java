package com.foleybooks.catalog.book.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.foleybooks.catalog.book.api.Availability;
import com.foleybooks.catalog.book.api.BookResponse;
import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.book.service.AvailabilityPolicy;
import com.foleybooks.catalog.category.api.CategoryResponse;
import com.foleybooks.catalog.category.domain.Category;
import com.foleybooks.catalog.category.mapping.CategoryMapperImpl;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The plan §2 book payload as the generated mapper produces it (FR-06, FR-07);
 * fixtures are real CA-04 seed rows so the JSON asserted here is the JSON the
 * running UI and Swagger will show. The load-bearing behaviour is the
 * *collaboration*: {@code availability} is not a column, so the mapper must
 * reach {@link AvailabilityPolicy} for it (C8, ADR-009) and must publish the
 * same quantity the badge came from, while the nested category arrives through
 * {@link CategoryMapper} rather than a second copy of that shape.
 */
class BookMapperTest {

    private static final UUID TECHNOLOGY_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca02");
    private static final UUID CLEAN_CODE_ID = UUID.fromString("00000000-0000-0000-0000-00000000cb06");
    private static final String CLEAN_CODE_ISBN = "9780132350884";

    private static final Category TECHNOLOGY = category("Technology", TECHNOLOGY_ID);

    private final BookMapper mapper =
            new BookMapperImpl(new CategoryMapperImpl(), new AvailabilityPolicy());

    private static Category category(String name, UUID id) {
        Category category = new Category(name);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    private static Book book(UUID id, String title, String author, String isbn,
                             BigDecimal price, int stockQuantity) {
        Book book = new Book(TECHNOLOGY, title, author, isbn, price, stockQuantity,
                "https://covers.openlibrary.org/b/isbn/" + isbn + "-L.jpg");
        ReflectionTestUtils.setField(book, "id", id);
        return book;
    }

    private static Book cleanCode(int stockQuantity) {
        return book(CLEAN_CODE_ID, "Clean Code", "Robert C. Martin", CLEAN_CODE_ISBN,
                new BigDecimal("31.99"), stockQuantity);
    }

    @Test
    void toResponse_whenSeedBookMapped_projectsEveryContractFieldOf() {
        BookResponse response = mapper.toResponse(cleanCode(12));

        assertThat(response.id()).isEqualTo(CLEAN_CODE_ID);
        assertThat(response.title()).isEqualTo("Clean Code");
        assertThat(response.author()).isEqualTo("Robert C. Martin");
        assertThat(response.isbn()).isEqualTo(CLEAN_CODE_ISBN);
        // D-08/NFR-07: the exact EUR decimal travels through unchanged, scale included.
        assertThat(response.price()).isEqualTo(new BigDecimal("31.99"));
        assertThat(response.price().scale()).isEqualTo(2);
        assertThat(response.coverUrl())
                .isEqualTo("https://covers.openlibrary.org/b/isbn/" + CLEAN_CODE_ISBN + "-L.jpg");
        assertThat(response.stockQuantity()).isEqualTo(12);
        assertThat(response.category()).isEqualTo(new CategoryResponse(TECHNOLOGY_ID, "Technology"));
    }

    @ParameterizedTest(name = "availability_thresholds through the mapper: {0} -> {1}")
    @CsvSource({
            "0, OUT_OF_STOCK",
            "2, LOW_STOCK",
            "5, LOW_STOCK",
            "6, IN_STOCK",
            "12, IN_STOCK"
    })
    void toResponse_whenStockVaries_derivesAvailabilityFromStockQuantityOf(
            int stockQuantity, Availability expected) {
        BookResponse response = mapper.toResponse(cleanCode(stockQuantity));

        // The badge is the service policy's answer about the very quantity
        // published beside it, so a client can never observe the two disagreeing
        // (D-09, ADR-003: one stored truth only).
        assertThat(response.availability()).isEqualTo(expected);
        assertThat(response.stockQuantity()).isEqualTo(stockQuantity);
    }

    @Test
    void toResponse_whenCategoryIsNested_mapsThroughTheSharedCategoryMapperOf() {
        BookResponse response = mapper.toResponse(book(
                UUID.fromString("00000000-0000-0000-0000-00000000cb07"), "The Pragmatic Programmer",
                "Andrew Hunt, David Thomas", "9780135957059", new BigDecimal("44.99"), 5));

        assertThat(response.category()).isEqualTo(new CategoryResponse(TECHNOLOGY_ID, "Technology"));
        assertThat(response.availability()).isEqualTo(Availability.LOW_STOCK);
    }
}
