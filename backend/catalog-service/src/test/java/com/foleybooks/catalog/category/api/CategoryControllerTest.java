package com.foleybooks.catalog.category.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foleybooks.catalog.category.service.CategoryService;
import com.foleybooks.catalog.common.PageEnvelope;
import com.foleybooks.catalog.common.PageMeta;
import com.foleybooks.catalog.common.ProblemDetailResponder;
import com.foleybooks.catalog.config.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wire contract of GET /api/v1/categories (FR-09, CA-10, plan §6.2): the real
 * SecurityConfig runs in-slice — security is never disabled (C25) — so the
 * anonymous 200 below is the proof of the category GET's spot on the public
 * allowlist (C22), the same rule {@code BookControllerTest} proves for books.
 * The boundary split is the FR-06 one verbatim (AGENTS.md §6, LC-11/LC-28):
 * out-of-range-but-numeric page/size pass through untouched for the service to
 * clamp, while malformed values fail int conversion in the binder and are
 * answered with the shared 400 validation ProblemDetail (D-15) before the
 * service is touched — {@code pagination_malformed_rejected} is the plan §6.1
 * entry, discharged here because a binder failure needs the real MVC stack.
 *
 * <p>LC-31's empty state is asserted at the wire too: an empty category list is
 * a 200 with an empty {@code content} array and zeroed page metadata — never a
 * 404, never a 500 — because the FR-09 promise ("the category list is
 * available") includes catalogs that have no categories yet.
 */
@WebMvcTest(CategoryController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class})
class CategoryControllerTest {

    private static final UUID FICTION_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca01");
    private static final UUID TECHNOLOGY_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca02");

    private static final PageEnvelope<CategoryResponse> TWO_CATEGORIES = new PageEnvelope<>(
            List.of(new CategoryResponse(FICTION_ID, "Fiction"), new CategoryResponse(TECHNOLOGY_ID, "Technology")),
            new PageMeta(3, 2, 0, CategoryService.DEFAULT_SIZE));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void listCategories_whenRequestedAnonymously_responds200WithPlanContractEnvelope() throws Exception {
        when(categoryService.listCategories(0, CategoryService.DEFAULT_SIZE)).thenReturn(TWO_CATEGORIES);

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(FICTION_ID.toString()))
                .andExpect(jsonPath("$.content[0].name").value("Fiction"))
                .andExpect(jsonPath("$.content[1].id").value(TECHNOLOGY_ID.toString()))
                .andExpect(jsonPath("$.content[1].name").value("Technology"))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(CategoryService.DEFAULT_SIZE))
                // Nothing but the reviewed contract crosses the boundary (C9).
                .andExpect(jsonPath("$.content[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.content[0].updatedAt").doesNotExist());

        // The omitted-parameter defaults of AGENTS.md §6 are the binder's, not
        // the service's — the controller arrives at (0, 20) before delegating.
        verify(categoryService).listCategories(0, CategoryService.DEFAULT_SIZE);
    }

    @Test
    void listCategories_whenNumericParamsOutOfRange_delegatesThemVerbatimForClamping() throws Exception {
        // LC-11 vs LC-28 asymmetry at the boundary: numeric-but-out-of-range is
        // not a rejection, it is a clamp the service owns — so these values
        // must pass through the controller untouched.
        when(categoryService.listCategories(-2, 500)).thenReturn(TWO_CATEGORIES);

        mockMvc.perform(get("/api/v1/categories").param("page", "-2").param("size", "500"))
                .andExpect(status().isOk());

        verify(categoryService).listCategories(-2, 500);
    }

    @Test
    void listCategories_whenRequestedWithInRangeValues_delegatesTheParsedNumbers() throws Exception {
        PageEnvelope<CategoryResponse> window = new PageEnvelope<>(
                List.of(new CategoryResponse(TECHNOLOGY_ID, "Technology")),
                new PageMeta(3, 2, 1, 2));
        when(categoryService.listCategories(1, 2)).thenReturn(window);

        mockMvc.perform(get("/api/v1/categories").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2));

        verify(categoryService).listCategories(1, 2);
    }

    @Test
    void listCategories_whenCategoryTableIsEmpty_responds200WithEmptyEnvelope() throws Exception {
        // FR-09, LC-31 at the wire: an empty category list is an empty state,
        // never an error — same envelope shape, zeroed metadata.
        when(categoryService.listCategories(0, CategoryService.DEFAULT_SIZE))
                .thenReturn(new PageEnvelope<>(List.of(), new PageMeta(0, 0, 0, CategoryService.DEFAULT_SIZE)));

        mockMvc.perform(get("/api/v1/categories"))
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
        // coerced or clamped; the shared advice renders them (D-15).
        mockMvc.perform(get("/api/v1/categories").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value(parameter))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"))
                .andExpect(jsonPath("$.instance").value("/api/v1/categories"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(categoryService);
    }
}
