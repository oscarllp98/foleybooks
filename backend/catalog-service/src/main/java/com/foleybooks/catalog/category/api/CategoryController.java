package com.foleybooks.catalog.category.api;

import com.foleybooks.catalog.category.service.CategoryService;
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
 * Public category listing (FR-09, CA-10). Thin by constitutional mandate (C8):
 * it binds the two numeric query parameters and hands them to {@link
 * CategoryService}, which owns the clamping (LC-11) and the name-ascending
 * order. The boundary rules are the same ones {@code GET /books} already
 * honors — this endpoint exposes nothing FR-06/LC-11/LC-28 do not already
 * define for a paged catalog read, with one subtraction: there is no
 * {@code sort} parameter, because FR-09 asks for a browsable list, not a
 * client-ordered one, and D-06's title/price whitelist belongs to books.
 *
 * <p>The empty state is a response, not an error (FR-09, LC-31): an empty
 * category table answers 200 with {@code content: []} — the frontend filter
 * (FE-12) renders that as nothing-to-show, exactly like a book search with no
 * matches. A malformed non-numeric {@code page}/{@code size} fails int
 * conversion in the MVC binder and is answered with the shared 400 validation
 * ProblemDetail (LC-28, D-15), never reaching the service.
 *
 * <p>The route sits on the SecurityConfig public GET allowlist (C22) beside
 * {@code /api/v1/books}, and the response is the standard pagination envelope
 * ({@link PageEnvelope}, plan §2) — one contract governs all paged catalog
 * reads, so the frontend's {@code Pagination} component (FE-11) consumes
 * either endpoint identically.
 */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Catalog", description = "Browse, search and inspect the book catalog and its categories "
        + "(FR-06..FR-09)")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(summary = "List categories",
            description = "Paginated category list sorted by name ascending: the ids a book browse filters by "
                    + "(categoryId). Numeric page/size values are clamped (page < 0 → 0, size < 1 → 20, "
                    + "size > 100 → 100, past-last page → last page); malformed non-numeric values are "
                    + "rejected with a validation error. An empty category table is an empty page, never "
                    + "an error.")
    public ResponseEntity<PageEnvelope<CategoryResponse>> listCategories(
            @Parameter(description = "Zero-based page index, clamped into range.")
            @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100.")
            @RequestParam(name = "size", defaultValue = CategoryService.DEFAULT_SIZE_TEXT) int size) {
        return ResponseEntity.ok(categoryService.listCategories(page, size));
    }
}
