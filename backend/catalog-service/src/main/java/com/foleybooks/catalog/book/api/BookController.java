package com.foleybooks.catalog.book.api;

import com.foleybooks.catalog.book.service.BookService;
import com.foleybooks.catalog.common.PageEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
  * Public browse endpoint (FR-06, CA-06, CA-08). Thin by constitutional mandate
  * (C8): it binds and normalizes the query parameters and hands them to
  * {@link BookService}, which owns the clamping (LC-11) and the title-ascending
  * default sort. The two numeric parameters ({@code page}, {@code size}) fail
  * int conversion in the MVC binder and are answered with the shared 400
  * validation ProblemDetail (LC-28, D-15), while an out-of-range but numeric
  * value is clamped, never rejected — that asymmetry is exactly what spec
  * LC-11/LC-28 require. The {@code sort} parameter is turned into a
  * {@link BookSort} by {@code BookSortConverter}, whose whitelist (title or
  * price, asc or desc — CA-07, D-06) rejects any other value as the very same
  * 400; a {@code null} sort (omitted or blank) lets the service default apply.
  *
  * <p>CA-08's two filter parameters (FR-08, FR-09, D-06) get the same
  * boundary treatment, not repository-side handling: C23 says all external input
  * is validated at the boundary before it reaches the service layer, which is
  * exactly how {@code EmailNormalizingDeserializer} already trims and lowercases
  * auth's email input, and how {@code BookSortConverter} already trims and reads
  * a blank {@code sort} as "not specified" here. So {@code search} is trimmed
  * and a blank result is forwarded as {@code null}; its case-folding and LIKE
  * metacharacter escaping ({@link com.foleybooks.catalog.book.repository.BookSpecifications})
  * are query-execution mechanics the repository layer owns per D-06, not
  * validation, and they are deliberately left downstream. A {@code search} that
  * matches nothing is an empty page, never an error (LC-10); one longer than the
  * widest field it could ever match is a rejection, not a slow no-match (LC-28's
  * "extremely long query strings" clause, now reachable for the first time
  * because CA-08 adds the catalog's only free-text parameter): the
  * {@link Size @Size} cap is the {@code title} column width (ADR-003), since a
  * term no longer than the longest title or author it is tested against is a
  * term that can match nothing — enforcing that at the boundary keeps a
  * useless 1 MB search from ever reaching the database. {@code categoryId}
  * needs no converter either: Spring's own {@code String -> UUID} binding
  * already rejects a malformed value with the very same 400 validation
  * ProblemDetail a bad {@code page} earns (LC-28), while a well-formed id naming
  * no category is simply "no matches" (FR-09, LC-31) — that asymmetry is the
  * binder's, not a check to hand-roll here.
  *
 * <p>The route sits on the SecurityConfig public GET allowlist (C22): browsing
 * the catalog presupposes no token, and the anonymous 200 in the slice test is
 * the proof of that spot (plan §6.2). The response is the standard pagination
 * envelope ({@code content} + {@code page}, {@link PageEnvelope}) of plan §2.
 *
 * <p>{@code GET /books/{id}} (FR-07, CA-09) is the detail half of the same
 * public read model and gets the identical boundary treatment: a UUID-typed path
 * variable needs no converter, because Spring's own {@code String -> UUID}
 * binding rejects a malformed id with the shared 400 validation ProblemDetail —
 * the same LC-28 asymmetry that already separates an unparseable
 * {@code categoryId} from one that simply names no category. Here the
 * well-formed-but-unknown half is a 404, not an empty page: a list browse has a
 * legitimate nothing-to-show state (FR-06, LC-31) while a detail page for an id
 * the client was handed has none, so {@link com.foleybooks.catalog.book.service.BookService#getBook}
 * answers it and the shared advice renders {@code urn:foley-books:problem:book-not-found}.
 * The controller contributes no rule of its own (C8) — no id is ever a "not
 * found" by its shape, and no book list is consulted here to decide.
 */
@RestController
@RequestMapping("/api/v1/books")
@Tag(name = "Catalog", description = "Browse, search and inspect the book catalog and its categories "
        + "(FR-06..FR-09)")
public class BookController {

    /**
     * LC-28's "extremely long query strings" bound, set to the {@code title}
     * column's width (ADR-003, the wider of the two columns {@code search} is
     * matched against): a longer term cannot match anything, so it is refused
     * at the boundary as a validation error instead of reaching the database.
     */
    static final int MAX_SEARCH_LENGTH = 300;

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    @Operation(summary = "List and search books",
            description = "Paginated catalog sorted by title ascending by default, optionally narrowed by a "
                    + "case-insensitive partial match over title and author and/or an exact category — the two "
                    + "filters compose, and a match on neither leaves the whole catalog. Numeric page/size "
                    + "values are clamped (page < 0 → 0, size < 1 → 20, size > 100 → 100, past-last page → last "
                    + "page); malformed non-numeric values are rejected with a validation error. sort accepts "
                    + "title or price with an optional ,asc/,desc direction; any other field is rejected.")
    public ResponseEntity<PageEnvelope<BookResponse>> listBooks(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = BookService.DEFAULT_SIZE_TEXT) int size,
            @Parameter(description = "Sort selection: 'title' or 'price', optionally with ',asc' or ',desc' "
                    + "(default title,asc). Values outside this whitelist are rejected with a 400.",
                    example = "price,desc")
            @RequestParam(name = "sort", required = false) BookSort sort,
            @Parameter(description = "Case-insensitive partial match on title or author; blank or omitted "
                    + "means no text filter. LIKE metacharacters in the term are treated as literal text.",
                    example = "clean")
            @RequestParam(name = "search", required = false)
            @Size(max = MAX_SEARCH_LENGTH, message = "must be at most " + MAX_SEARCH_LENGTH + " characters")
            String search,
            @Parameter(description = "Exact category id to browse within; omit for the whole catalog. An id "
                    + "matching no category is an empty page, never an error.",
                    example = "00000000-0000-0000-0000-00000000ca02")
            @RequestParam(name = "categoryId", required = false) UUID categoryId) {
        return ResponseEntity.ok(bookService.listBooks(page, size, sort, normalizeSearch(search), categoryId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a book by id",
            description = "Full detail of one book (FR-07): title, ISBN, author, price in EUR, cover, category "
                    + "and the derived availability badge. The response shape is the same BookResponse the list "
                    + "serves, so a card and the page it links to cannot disagree. A well-formed id matching no "
                    + "book is a 404; a malformed, unparseable id is a validation error answered before the "
                    + "catalog is read.")
    public ResponseEntity<BookResponse> getBook(
            @Parameter(description = "Public UUID of the book, exactly as the list response published it.",
                    example = "00000000-0000-0000-0000-00000000cb06")
            @PathVariable(name = "id") UUID id) {
        return ResponseEntity.ok(bookService.getBook(id));
    }

    /**
     * C23's boundary rule, expressed for a free-text parameter the way
     * {@code EmailNormalizingDeserializer} expresses it for email: trim the
     * caller's padding, and read a blank remainder as "no filter" rather than
     * as a search for whitespace. Everything past this point — case-folding,
     * metacharacter escaping, matching — is the repository layer's rule (D-06),
     * not the boundary's.
     */
    private static String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
