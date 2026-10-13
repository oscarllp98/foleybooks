package com.foleybooks.catalog.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.foleybooks.catalog.category.api.CategoryResponse;
import com.foleybooks.catalog.category.domain.Category;
import com.foleybooks.catalog.category.mapping.CategoryMapperImpl;
import com.foleybooks.catalog.category.repository.CategoryRepository;
import com.foleybooks.catalog.common.PageEnvelope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * FR-09's category listing at the repository seam (CA-10, plan §6.1): the exact
 * {@link Pageable} the service asks for, the name-ascending order it carries,
 * and the envelope that comes back. The LC-11 clamp rules are asserted here as
 * the same contract {@code BookServiceImplTest} pins for books — AGENTS.md §6
 * writes them for pagination in general, and a second paged read honors them
 * independently of the first. LC-31's empty state is the load-bearing case of
 * this task: an empty {@code categories} table must answer an empty 200
 * envelope, not an error and not a phantom past-the-end page. The mapper is the
 * real CA-05/CA-10 generated one, so the published {@code { id, name }} shape
 * inside the envelope is the shipped wiring, not a mock's opinion (C8, ADR-009).
 */
class CategoryServiceImplTest {

    private static final UUID FICTION_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca01");
    private static final UUID TECHNOLOGY_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca02");
    private static final UUID BUSINESS_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca03");

    private final CategoryRepository repository = mock(CategoryRepository.class);
    private final CategoryServiceImpl service =
            new CategoryServiceImpl(repository, new CategoryMapperImpl());

    private static Category category(String name, UUID id) {
        Category category = new Category(name);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    private void stubSinglePage(List<Category> content, int number, int size, long total) {
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, PageRequest.of(number, size), total));
    }

    private Pageable capturedRequest() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void listCategories_whenDefaultsRequested_fetchesFirstPageTwentySortedByNameAscending() {
        // Total 25 at size 20 keeps the fixture a real middle-of-list window:
        // PageImpl recomputes a total its window could not produce, and the
        // envelope must publish the repository's own numbers (plan §2).
        stubSinglePage(List.of(category("Business", BUSINESS_ID)), 0, 20, 25);

        PageEnvelope<CategoryResponse> envelope = service.listCategories(0, CategoryService.DEFAULT_SIZE);

        Pageable request = capturedRequest();
        assertThat(request.getPageNumber()).isZero();
        assertThat(request.getPageSize()).isEqualTo(CategoryService.DEFAULT_SIZE);
        Sort.Order nameOrder = request.getSort().getOrderFor("name");
        assertThat(nameOrder).isNotNull();
        assertThat(nameOrder.getDirection()).isEqualTo(Sort.Direction.ASC);
        verify(repository, times(1)).findAll(any(Pageable.class));

        assertThat(envelope.content()).singleElement()
                .isEqualTo(new CategoryResponse(BUSINESS_ID, "Business"));
        assertThat(envelope.page().totalElements()).isEqualTo(25);
        assertThat(envelope.page().totalPages()).isEqualTo(2);
        assertThat(envelope.page().number()).isZero();
        assertThat(envelope.page().size()).isEqualTo(CategoryService.DEFAULT_SIZE);
    }

    @ParameterizedTest(name = "pagination_clampsRanges: page={0} size={1} -> effective page={2} size={3} (LC-11)")
    @CsvSource({
            "-1, 20, 0, 20",
            "-100, 1, 0, 1",
            "0, 0, 0, 20",
            "0, -5, 0, 20",
            "2, 5, 2, 5",
            "0, 100, 0, 100",
            "3, 101, 3, 100",
            "3, 100000, 3, 100"
    })
    void pagination_clampsRanges(int page, int size, int effectivePage, int effectiveSize) {
        // LC-11: numeric values out of range are clamped at the request, never
        // rejected — rejection is reserved for malformed ones (LC-28, web slice).
        // Stubbed content is non-empty, so no scenario here takes the
        // past-the-last-page re-fetch; that rule owns its own tests below.
        stubSinglePage(List.of(category("Fiction", FICTION_ID)), effectivePage, effectiveSize, 12_000);

        service.listCategories(page, size);

        Pageable request = capturedRequest();
        assertThat(request.getPageNumber()).isEqualTo(effectivePage);
        assertThat(request.getPageSize()).isEqualTo(effectiveSize);
    }

    @Test
    void listCategories_whenPagePastLast_servesTheLastPageInsteadOfThePhantomOne() {
        // LC-11's remaining clause, page past the last → last page.
        // The three V2 seed rows (ADR-003's fixed ca01-ca03 UUIDs) in
        // name-ascending order: 3 categories at size 2 end at page 1.
        List<Category> all = List.of(
                category("Business", BUSINESS_ID),
                category("Fiction", FICTION_ID),
                category("Technology", TECHNOLOGY_ID));
        PageImpl<Category> phantom = new PageImpl<>(List.of(), PageRequest.of(5, 2), 3);
        PageImpl<Category> lastPage = new PageImpl<>(all.subList(2, 3), PageRequest.of(1, 2), 3);
        when(repository.findAll(any(Pageable.class))).thenReturn(phantom, lastPage);

        PageEnvelope<CategoryResponse> envelope = service.listCategories(5, 2);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findAll(captor.capture());
        List<Pageable> requests = captor.getAllValues();
        assertThat(requests.get(0).getPageNumber()).isEqualTo(5);
        assertThat(requests.get(1).getPageNumber()).isEqualTo(1);
        // The re-fetch keeps the fixed name-ascending order, never a bare request.
        assertThat(requests.get(1).getSort().getOrderFor("name")).isNotNull();

        // The envelope tells the truth about the page actually served.
        assertThat(envelope.content()).containsExactly(new CategoryResponse(TECHNOLOGY_ID, "Technology"));
        assertThat(envelope.page().number()).isEqualTo(1);
        assertThat(envelope.page().totalPages()).isEqualTo(2);
    }

    @Test
    void listCategories_whenCategoryTableIsEmpty_servesEmptyEnvelopeNotAnError() {
        // FR-09, LC-31: "the category list is available" is satisfied by an
        // empty 200 exactly as much as by a full page — empty is a state the
        // frontend renders, never an error the service throws.
        stubSinglePage(List.of(), 0, CategoryService.DEFAULT_SIZE, 0);

        PageEnvelope<CategoryResponse> envelope = service.listCategories(0, CategoryService.DEFAULT_SIZE);

        assertThat(envelope.content()).isEmpty();
        assertThat(envelope.page().totalElements()).isZero();
        assertThat(envelope.page().totalPages()).isZero();
        assertThat(envelope.page().number()).isZero();
    }

    @Test
    void listCategories_whenPastLastPageOfEmptyTable_servesPageZero() {
        // The empty table has no "last page": a past-the-end clamp lands on
        // page 0, keeping LC-31's empty state free of phantom page numbers.
        PageImpl<Category> phantom = new PageImpl<>(List.of(), PageRequest.of(3, 20), 0);
        PageImpl<Category> firstPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(repository.findAll(any(Pageable.class))).thenReturn(phantom, firstPage);

        PageEnvelope<CategoryResponse> envelope = service.listCategories(3, 20);

        assertThat(envelope.content()).isEmpty();
        assertThat(envelope.page().number()).isZero();
        assertThat(envelope.page().totalElements()).isZero();
    }

    @Test
    void listCategories_whenPageHasContent_doesNotRefetch() {
        // The re-fetch is only for past-the-end windows, so the ordinary list
        // read stays one repository call (NFR-02).
        stubSinglePage(List.of(category("Fiction", FICTION_ID)), 0, 20, 1);

        service.listCategories(0, 20);

        verify(repository, times(1)).findAll(any(Pageable.class));
        verify(repository, never()).findAll();
    }
}
