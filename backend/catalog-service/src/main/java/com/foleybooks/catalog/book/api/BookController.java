package com.foleybooks.catalog.book.api;

import com.foleybooks.catalog.book.service.BookService;
import com.foleybooks.catalog.common.PageEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
  * Public browse endpoint (FR-06, CA-06). Thin by constitutional mandate (C8):
 * it binds the query parameters and hands them to {@link BookService}, which owns
 * the clamping (LC-11) and the title-ascending default sort. There is nothing to
 * validate by annotation here. The two numeric parameters ({@code page}, {@code
 * size}) fail int conversion in the MVC binder and are answered with the shared
 * 400 validation ProblemDetail (LC-28, D-15), while an out-of-range but numeric
 * value is clamped, never rejected — that asymmetry is exactly what spec
 * LC-11/LC-28 require. The {@code sort} parameter is turned into a
 * {@link BookSort} by {@code BookSortConverter}, whose whitelist (title or price,
 * asc or desc — CA-07, D-06) rejects any other value as the very same 400; a
 * {@code null} sort (omitted or blank) lets the service default apply.
 *
 * <p>The route sits on the SecurityConfig public GET allowlist (C22): browsing
 * the catalog presupposes no token, and the anonymous 200 in the slice test is
 * the proof of that spot (plan §6.2). The response is the standard pagination
 * envelope ({@code content} + {@code page}, {@link PageEnvelope}) of plan §2.
 */
@RestController
@RequestMapping("/api/v1/books")
@Tag(name = "Catalog", description = "Browse, search and inspect the book catalog (FR-06..FR-08)")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    @Operation(summary = "List books",
            description = "Paginated catalog sorted by title ascending by default. Numeric page/size values are "
                    + "clamped (page < 0 → 0, size < 1 → 20, size > 100 → 100, past-last page → last page); "
                    + "malformed non-numeric values are rejected with a validation error. sort accepts "
                    + "title or price with an optional ,asc/,desc direction; any other field is rejected.")
    public ResponseEntity<PageEnvelope<BookResponse>> listBooks(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = BookService.DEFAULT_SIZE_TEXT) int size,
            @Parameter(description = "Sort selection: 'title' or 'price', optionally with ',asc' or ',desc' "
                    + "(default title,asc). Values outside this whitelist are rejected with a 400.",
                    example = "price,desc")
            @RequestParam(name = "sort", required = false) BookSort sort) {
        return ResponseEntity.ok(bookService.listBooks(page, size, sort));
    }
}
