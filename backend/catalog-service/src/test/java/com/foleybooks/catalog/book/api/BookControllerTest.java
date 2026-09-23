package com.foleybooks.catalog.book.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foleybooks.catalog.book.service.BookService;
import com.foleybooks.catalog.category.api.CategoryResponse;
import com.foleybooks.catalog.common.PageEnvelope;
import com.foleybooks.catalog.common.PageMeta;
import com.foleybooks.catalog.common.ProblemDetailResponder;
import com.foleybooks.catalog.config.SecurityConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wire contract of GET /api/v1/books (FR-06, plan §6.2): the real
 * SecurityConfig runs in-slice — security is never disabled (C25) — so the
 * anonymous 200 below doubles as the proof of the catalog GET's spot on the
 * public allowlist (C22), and {@link #pagination_malformed_rejected} — the
 * plan §6.1 entry, discharged here because a binder failure needs the real
 * MVC stack — proves LC-28 rides the binder + shared advice path (D-15), not
 * hand-rolled checks. The {@link BookSortConverter} is loaded in-slice (Spring
 * Boot registers {@code Converter} beans with the MVC conversion service, and
 * {@code @WebMvcTest} includes them), so the CA-07 sort whitelist is exercised
 * through the real binding path too: valid {@code field,direction} values are
 * forwarded as a parsed {@link BookSort}, and every value off the whitelist is
 * a 400 before the service is touched. What the binder hands over verbatim
 * (including out-of-range numbers) and what the envelope must look like are
 * asserted here; the clamping itself (LC-11) is the service's rule, proven in
 * {@code BookServiceImplTest}.
 */
@WebMvcTest(BookController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class, BookSortConverter.class})
class BookControllerTest {

    private static final UUID CLEAN_CODE_ID = UUID.fromString("00000000-0000-0000-0000-00000000cb06");
    private static final UUID TECHNOLOGY_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca02");

    private static final BookResponse CLEAN_CODE = new BookResponse(
            CLEAN_CODE_ID, "Clean Code", "Robert C. Martin", "9780132350884",
            new BigDecimal("31.99"), "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg",
            Availability.IN_STOCK, 12, new CategoryResponse(TECHNOLOGY_ID, "Technology"));

    private static final PageEnvelope<BookResponse> ONE_BOOK =
            new PageEnvelope<>(List.of(CLEAN_CODE), new PageMeta(12, 1, 0, BookService.DEFAULT_SIZE));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookService bookService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void listBooks_whenRequestedAnonymously_responds200WithPlanContractEnvelope() throws Exception {
        when(bookService.listBooks(0, BookService.DEFAULT_SIZE, null)).thenReturn(ONE_BOOK);

        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(CLEAN_CODE_ID.toString()))
                .andExpect(jsonPath("$.content[0].title").value("Clean Code"))
                .andExpect(jsonPath("$.content[0].author").value("Robert C. Martin"))
                .andExpect(jsonPath("$.content[0].isbn").value("9780132350884"))
                // D-08: price is a JSON number, exact at scale 2.
                .andExpect(jsonPath("$.content[0].price").value(31.99))
                .andExpect(jsonPath("$.content[0].coverUrl")
                        .value("https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg"))
                .andExpect(jsonPath("$.content[0].availability").value("IN_STOCK"))
                .andExpect(jsonPath("$.content[0].stockQuantity").value(12))
                .andExpect(jsonPath("$.content[0].category.id").value(TECHNOLOGY_ID.toString()))
                .andExpect(jsonPath("$.content[0].category.name").value("Technology"))
                .andExpect(jsonPath("$.page.totalElements").value(12))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(BookService.DEFAULT_SIZE));

        // The omitted-parameter defaults of AGENTS.md §6 are the binder's, not
        // the service's — the controller arrives at (0, 20) with no sort before
        // delegating, so the service applies the FR-06 title-ascending default.
        verify(bookService).listBooks(0, BookService.DEFAULT_SIZE, null);
    }

    @Test
    void listBooks_whenNumericParamsOutOfRange_delegatesThemVerbatimForClamping() throws Exception {
        // LC-11 vs LC-28 asymmetry at the boundary: numeric-but-out-of-range is
        // not a rejection, it is a clamp the service owns — so these values
        // must pass through the controller untouched.
        when(bookService.listBooks(-2, 500, null)).thenReturn(ONE_BOOK);

        mockMvc.perform(get("/api/v1/books").param("page", "-2").param("size", "500"))
                .andExpect(status().isOk());

        verify(bookService).listBooks(-2, 500, null);
    }

    @Test
    void listBooks_whenEmptyCatalog_responds200WithEmptyEnvelope() throws Exception {
        // FR-06: nothing to show is a state, never an error.
        when(bookService.listBooks(0, BookService.DEFAULT_SIZE, null))
                .thenReturn(new PageEnvelope<>(List.of(), new PageMeta(0, 0, 0, BookService.DEFAULT_SIZE)));

        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.number").value(0));
    }

    @ParameterizedTest(name = "pagination_malformed_rejected: {0}={1} is a 400, not a clamp (LC-28)")
    @CsvSource({
            "page, abc",
            "page, 99999999999999",
            "size, two",
            "size, 2.5"
    })
    void pagination_malformed_rejected(String parameter, String value) throws Exception {
        // LC-28: non-numeric page/size — including digit strings no int can
        // hold and the fractional 2.5 — are validation errors, never silently
        // coerced or clamped (only representable integers clamp, LC-11).
        mockMvc.perform(get("/api/v1/books").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value(parameter))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"))
                .andExpect(jsonPath("$.instance").value("/api/v1/books"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(bookService);
    }

    @ParameterizedTest(name = "sort_whitelisted: sort={0} resolves to {1}/{2} (FR-06, CA-07)")
    @CsvSource(delimiterString = ";", value = {
            "title; TITLE; ASC",
            "price; PRICE; ASC",
            "title,asc; TITLE; ASC",
            "price,desc; PRICE; DESC",
            "Price,DESC; PRICE; DESC",
            "' PRICE , ASC '; PRICE; ASC"
    })
    void listBooks_whenSortIsWhitelisted_forwardsParsedSelection(String rawSort,
            BookSort.Field field, BookSort.Direction direction) throws Exception {
        // The whitelist is a converter, not a service rule: a valid selection
        // reaches the service already parsed and normalized, and the request is
        // served normally (200), never rejected. Case and padding are trimmed.
        BookSort expected = new BookSort(field, direction);
        when(bookService.listBooks(0, BookService.DEFAULT_SIZE, expected)).thenReturn(ONE_BOOK);

        mockMvc.perform(get("/api/v1/books").param("sort", rawSort))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Clean Code"));

        verify(bookService).listBooks(0, BookService.DEFAULT_SIZE, expected);
    }

    @Test
    void listBooks_whenSortIsBlank_fallsBackToTheServiceDefault() throws Exception {
        // A blank sort (sort=) is "not specified", not an error: the converter
        // yields null and the FR-06 title-ascending default still applies.
        when(bookService.listBooks(0, BookService.DEFAULT_SIZE, null)).thenReturn(ONE_BOOK);

        mockMvc.perform(get("/api/v1/books").param("sort", ""))
                .andExpect(status().isOk());

        verify(bookService).listBooks(0, BookService.DEFAULT_SIZE, null);
    }

    @ParameterizedTest(name = "sort_whitelist_rejected: sort={0} is a 400 validation error (LC-28, CA-07)")
    @ValueSource(strings = {
            "rating",
            "author",
            "isbn",
            "id",
            "stockQuantity",
            "createdAt",
            "title,bogus",
            "price,ascending",
            "price,",
            ",asc",
            "title,asc,desc",
            "' OR 1=1--",
            "; drop table books"
    })
    void sort_unknownSelection_rejectedWith400Validation(String rawSort) throws Exception {
        // CA-07's security value: only title/price asc/desc exist. An unknown
        // field or direction is a boundary rejection — the same 400 validation
        // ProblemDetail (field "sort", "has an invalid value") as a malformed
        // page/size — before the query is built, so a bogus column can never
        // reach JPA as a 500 or hint at the schema (LC-28, D-06).
        mockMvc.perform(get("/api/v1/books").param("sort", rawSort))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("sort"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"))
                .andExpect(jsonPath("$.instance").value("/api/v1/books"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(bookService);
    }
}
