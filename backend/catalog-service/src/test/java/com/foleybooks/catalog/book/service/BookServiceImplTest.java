package com.foleybooks.catalog.book.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.foleybooks.catalog.book.api.Availability;
import com.foleybooks.catalog.book.api.BookResponse;
import com.foleybooks.catalog.book.api.BookSort;
import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.book.mapping.BookMapperImpl;
import com.foleybooks.catalog.book.repository.BookRepository;
import com.foleybooks.catalog.category.api.CategoryResponse;
import com.foleybooks.catalog.category.domain.Category;
import com.foleybooks.catalog.category.mapping.CategoryMapperImpl;
import com.foleybooks.catalog.common.PageEnvelope;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * FR-06's browse rule at the repository seam (plan §6.1, LC-11): the exact
 * {@link Pageable} the service asks for, the sort it carries, and the envelope
 * that comes back. The plan §6.1 entry {@code pagination_clampsRanges} is carried
 * by {@link #pagination_clampsRanges(int, int, int, int)} plus the two
 * page-clamp-behind-a-second-fetch tests below: LC-11's numeric clamps that only
 * change the request live in the parameterized rows, while "page past the last →
 * last page" is only decidable after the first fetch, so it owns its own
 * scenarios. CA-07's sort reaches the service already whitelisted (the boundary
 * converter rejects anything else), so these tests prove the service turns a
 * {@link BookSort} into the right Spring Data {@code Sort} — and keeps that same
 * sort on the past-the-end re-fetch — while a {@code null} sort still answers
 * with the FR-06 title-ascending default. The clamp of a past-the-end page is
 * the whole point of fetching through the service rather than binding the
 * request straight into a {@code Pageable} argument resolver: Spring's own
 * resolver would happily serve an empty phantom window. The mapper is the real
 * CA-05 generated one, so the badge and category inside the envelope are derived
 * by the shipped wiring, not a mock's opinion (C8, ADR-009).
 */
class BookServiceImplTest {

    private static final UUID TECHNOLOGY_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca02");
    private static final UUID CLEAN_CODE_ID = UUID.fromString("00000000-0000-0000-0000-00000000cb06");

    private final BookRepository repository = mock(BookRepository.class);
    private final BookServiceImpl service =
            new BookServiceImpl(repository, new BookMapperImpl(new CategoryMapperImpl(), new AvailabilityPolicy()));

    private static Category technology() {
        Category category = new Category("Technology");
        ReflectionTestUtils.setField(category, "id", TECHNOLOGY_ID);
        return category;
    }

    private static Book cleanCode() {
        Book book = new Book(technology(), "Clean Code", "Robert C. Martin", "9780132350884",
                new BigDecimal("31.99"), 12, "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg");
        ReflectionTestUtils.setField(book, "id", CLEAN_CODE_ID);
        return book;
    }

    private void stubSinglePage(List<Book> content, int number, int size, long total) {
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, PageRequest.of(number, size), total));
    }

    private Pageable capturedRequest() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void listBooks_whenDefaultsRequested_fetchesFirstPageTwentySortedByTitleAscending() {
        // Total 25 at size 20 keeps the fixture a real middle-of-catalog window:
        // PageImpl recomputes a total its window could not produce (offset +
        // pageSize > total), and the envelope must publish the repository's own numbers.
        stubSinglePage(List.of(cleanCode()), 0, 20, 25);

        PageEnvelope<BookResponse> envelope = service.listBooks(0, 20, null);

        Pageable request = capturedRequest();
        assertThat(request.getPageNumber()).isZero();
        assertThat(request.getPageSize()).isEqualTo(BookService.DEFAULT_SIZE);
        Sort.Order titleOrder = request.getSort().getOrderFor("title");
        assertThat(titleOrder).isNotNull();
        assertThat(titleOrder.getDirection()).isEqualTo(Sort.Direction.ASC);
        verify(repository, times(1)).findAll(any(Pageable.class));

        // Plan §2 envelope: mapped content plus the page metadata of the window served.
        assertThat(envelope.content()).singleElement().satisfies(book -> {
            assertThat(book.id()).isEqualTo(CLEAN_CODE_ID);
            assertThat(book.title()).isEqualTo("Clean Code");
            assertThat(book.availability()).isEqualTo(Availability.IN_STOCK);
            assertThat(book.category()).isEqualTo(new CategoryResponse(TECHNOLOGY_ID, "Technology"));
        });
        assertThat(envelope.page().totalElements()).isEqualTo(25);
        assertThat(envelope.page().totalPages()).isEqualTo(2);
        assertThat(envelope.page().number()).isZero();
        assertThat(envelope.page().size()).isEqualTo(BookService.DEFAULT_SIZE);
    }

    @Test
    void listBooks_whenPriceDescendingRequested_sortsByPriceDescending() {
        // CA-07: a whitelisted BookSort is translated straight into the Spring
        // Data Sort the query uses — price, descending, with no title order.
        stubSinglePage(List.of(cleanCode()), 0, 20, 25);

        service.listBooks(0, 20, new BookSort(BookSort.Field.PRICE, BookSort.Direction.DESC));

        Sort.Order priceOrder = capturedRequest().getSort().getOrderFor("price");
        assertThat(priceOrder).isNotNull();
        assertThat(priceOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(capturedRequest().getSort().getOrderFor("title")).isNull();
    }

    @Test
    void listBooks_whenTitleAscendingRequested_sortsByTitleAscending() {
        stubSinglePage(List.of(cleanCode()), 0, 20, 25);

        service.listBooks(0, 20, new BookSort(BookSort.Field.TITLE, BookSort.Direction.ASC));

        Sort.Order titleOrder = capturedRequest().getSort().getOrderFor("title");
        assertThat(titleOrder).isNotNull();
        assertThat(titleOrder.getDirection()).isEqualTo(Sort.Direction.ASC);
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
        stubSinglePage(List.of(cleanCode()), effectivePage, effectiveSize, 12_000);

        service.listBooks(page, size, null);

        Pageable request = capturedRequest();
        assertThat(request.getPageNumber()).isEqualTo(effectivePage);
        assertThat(request.getPageSize()).isEqualTo(effectiveSize);
    }

    @Test
    void listBooks_whenPagePastLast_servesTheLastPageInsteadOfThePhantomOne() {
        // LC-11's remaining clause, page past the last → last page.
        // 12 books at size 2 end at page 5.
        PageImpl<Book> phantom = new PageImpl<>(List.of(), PageRequest.of(3, 2), 12);
        PageImpl<Book> lastPage = new PageImpl<>(List.of(cleanCode(), cleanCode()), PageRequest.of(5, 2), 12);
        when(repository.findAll(any(Pageable.class))).thenReturn(phantom, lastPage);

        PageEnvelope<BookResponse> envelope = service.listBooks(3, 2, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findAll(captor.capture());
        List<Pageable> requests = captor.getAllValues();
        assertThat(requests.get(0).getPageNumber()).isEqualTo(3);
        assertThat(requests.get(1).getPageNumber()).isEqualTo(5);
        assertThat(requests.get(1).getSort().getOrderFor("title")).isNotNull();

        // The envelope tells the truth about the page actually served.
        assertThat(envelope.content()).hasSize(2);
        assertThat(envelope.page().number()).isEqualTo(5);
        assertThat(envelope.page().totalPages()).isEqualTo(6);
    }

    @Test
    void listBooks_whenPastLastPageIsRefetched_preservesTheRequestedSort() {
        // CA-07 + LC-11 combined: the re-fetch that lands on the last page must
        // carry the client's chosen sort, not silently revert to the default.
        PageImpl<Book> phantom = new PageImpl<>(List.of(), PageRequest.of(3, 2), 12);
        PageImpl<Book> lastPage = new PageImpl<>(List.of(cleanCode()), PageRequest.of(5, 2), 12);
        when(repository.findAll(any(Pageable.class))).thenReturn(phantom, lastPage);

        service.listBooks(3, 2, new BookSort(BookSort.Field.PRICE, BookSort.Direction.DESC));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findAll(captor.capture());
        for (Pageable request : captor.getAllValues()) {
            Sort.Order priceOrder = request.getSort().getOrderFor("price");
            assertThat(priceOrder).isNotNull();
            assertThat(priceOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
        }
    }

    @Test
    void listBooks_whenCatalogIsEmpty_servesPageZeroWithAnEmptyEnvelope() {
        // An empty catalog has no "last page" — clamping past-the-end lands on
        // page 0, and the empty state stays a 200 with empty content (FR-06),
        // never a phantom page 3.
        PageImpl<Book> phantom = new PageImpl<>(List.of(), PageRequest.of(3, 20), 0);
        PageImpl<Book> firstPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(repository.findAll(any(Pageable.class))).thenReturn(phantom, firstPage);

        PageEnvelope<BookResponse> envelope = service.listBooks(3, 20, null);

        assertThat(envelope.content()).isEmpty();
        assertThat(envelope.page().number()).isZero();
        assertThat(envelope.page().totalElements()).isZero();
        assertThat(envelope.page().totalPages()).isZero();
    }

    @Test
    void listBooks_whenPageHasContent_doesNotRefetch() {
        // The re-fetch is only for past-the-end windows, so the FR-06 hot path
        // stays one repository call (NFR-02).
        Page<Book> page = new PageImpl<>(List.of(cleanCode()), PageRequest.of(2, 5), 12);
        when(repository.findAll(any(Pageable.class))).thenReturn(page);

        service.listBooks(2, 5, null);

        verify(repository, times(1)).findAll(any(Pageable.class));
    }
}
