package com.foleybooks.catalog.book.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.foleybooks.catalog.common.ApiException;
import com.foleybooks.catalog.common.PageEnvelope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * FR-06's browse rule at the repository seam (plan §6.1, LC-11): the exact
 * {@link Pageable} the service asks for, the sort it carries, the filter it
 * composes, and the envelope that comes back. The plan §6.1 entry
 * {@code pagination_clampsRanges} is carried by {@link
 * #pagination_clampsRanges(int, int, int, int)} plus the two
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
 *
 * <p>CA-08's {@code search}/{@code categoryId} filter (FR-08, FR-09, D-06) is a
 * repository-layer rule ({@code BookSpecifications}, proven against real
 * PostgreSQL in {@code BookRepositoryTest}), so this mock-based seam only proves
 * the two service-level obligations: the values are handed to the repository in
 * every fetch — the same filter object identity is reused across a
 * past-the-end re-fetch, so a clamp can never silently drop the search or the
 * category the client asked for — and an omitted filter is a well-formed
 * "no criteria" call, never a skipped one.
 *
 * <p>CA-09's detail read (FR-07) is the other half of this seam: one
 * {@code findById}, the same shipped mapper producing the plan §2 shape, and the
 * published 404 contract — status, URN, title, detail and the echoed
 * {@code bookId} — when the id resolves to no row. That exception is what the
 * shared advice renders as a ProblemDetail (D-15); its wire shape is asserted in
 * {@code BookControllerTest}, and the lazy-category resolution the in-transaction
 * projection guarantees is proven against real PostgreSQL by
 * {@code CatalogServiceApplicationTests}.
 *
 * <p>CA-11's batch read (D-10, FR-11) is the same seam with the 404 rule
 * inverted: one {@code findAllById}, the same shipped mapper so a batch line and
 * a detail line cannot drift (ADR-009), and — proved by the mocked repository
 * returning a subset — an id with no row is simply absent, never a thrown error,
 * which is the LC-14 signal the cart enrichment consumes. The empty/null request
 * short-circuits before the repository at all (FR-11's empty cart). The real
 * {@code IN}-query subset semantics are the PostgreSQL case in
 * {@code BookRepositoryTest}.
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
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, PageRequest.of(number, size), total));
    }

    private Pageable capturedRequest() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), captor.capture());
        return captor.getValue();
    }

    @Test
    void listBooks_whenDefaultsRequested_fetchesFirstPageTwentySortedByTitleAscending() {
        // Total 25 at size 20 keeps the fixture a real middle-of-catalog window:
        // PageImpl recomputes a total its window could not produce (offset +
        // pageSize > total), and the envelope must publish the repository's own numbers.
        stubSinglePage(List.of(cleanCode()), 0, 20, 25);

        PageEnvelope<BookResponse> envelope = service.listBooks(0, 20, null, null, null);

        Pageable request = capturedRequest();
        assertThat(request.getPageNumber()).isZero();
        assertThat(request.getPageSize()).isEqualTo(BookService.DEFAULT_SIZE);
        Sort.Order titleOrder = request.getSort().getOrderFor("title");
        assertThat(titleOrder).isNotNull();
        assertThat(titleOrder.getDirection()).isEqualTo(Sort.Direction.ASC);
        verify(repository, times(1)).findAll(any(Specification.class), any(Pageable.class));

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

        service.listBooks(0, 20, new BookSort(BookSort.Field.PRICE, BookSort.Direction.DESC), null, null);

        Sort.Order priceOrder = capturedRequest().getSort().getOrderFor("price");
        assertThat(priceOrder).isNotNull();
        assertThat(priceOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(capturedRequest().getSort().getOrderFor("title")).isNull();
    }

    @Test
    void listBooks_whenTitleAscendingRequested_sortsByTitleAscending() {
        stubSinglePage(List.of(cleanCode()), 0, 20, 25);

        service.listBooks(0, 20, new BookSort(BookSort.Field.TITLE, BookSort.Direction.ASC), null, null);

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

        service.listBooks(page, size, null, null, null);

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
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(phantom, lastPage);

        PageEnvelope<BookResponse> envelope = service.listBooks(3, 2, null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findAll(any(Specification.class), captor.capture());
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
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(phantom, lastPage);

        service.listBooks(3, 2, new BookSort(BookSort.Field.PRICE, BookSort.Direction.DESC), null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findAll(any(Specification.class), captor.capture());
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
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(phantom, firstPage);

        PageEnvelope<BookResponse> envelope = service.listBooks(3, 20, null, null, null);

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
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        service.listBooks(2, 5, null, null, null);

        verify(repository, times(1)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void listBooks_whenSearchAndCategoryGiven_composesOneFilterForEveryFetch() {
        // CA-08 + LC-11 combined: the past-the-end re-fetch must reuse the exact
        // same filter instance the first fetch used, never rebuild it from
        // defaults — a clamp that quietly drops the search or the category would
        // serve the client unfiltered books on the last page.
        PageImpl<Book> phantom = new PageImpl<>(List.of(), PageRequest.of(3, 2), 12);
        PageImpl<Book> lastPage = new PageImpl<>(List.of(cleanCode()), PageRequest.of(5, 2), 12);
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(phantom, lastPage);

        service.listBooks(3, 2, null, "clean", TECHNOLOGY_ID);

        ArgumentCaptor<Specification<Book>> captor = ArgumentCaptor.captor();
        verify(repository, times(2)).findAll(captor.capture(), any(Pageable.class));
        assertThat(captor.getAllValues().get(0)).isSameAs(captor.getAllValues().get(1));
    }

    @Test
    void listBooks_whenNoFilterGiven_stillPassesAFilterToTheRepository() {
        // An omitted search/category is "no criteria", never a skipped call: the
        // browse page (CA-06) and the filtered page (CA-08) must share one
        // repository path, so the FR-06 default sort and clamping above stay true
        // for filtered requests too, not just unfiltered ones.
        stubSinglePage(List.of(cleanCode()), 0, 20, 1);

        service.listBooks(0, 20, null, null, null);

        ArgumentCaptor<Specification<Book>> captor = ArgumentCaptor.captor();
        verify(repository).findAll(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isNotNull();
    }

    // ------------------------------------------------------------------ CA-09: detail (FR-07)

    @Test
    void getBook_whenIdMatchesABook_projectsTheSameShapeTheListServes() {
        // FR-07 through ADR-009's single-owner rule: the detail is one findById
        // plus the one entity-to-record projection the list uses, so every field
        // the BookDetail page renders — including the nested category, which the
        // entity keeps lazy — is published by the shipped mapper, not by a
        // hand-built copy that could drift from the card beside it.
        when(repository.findById(CLEAN_CODE_ID)).thenReturn(Optional.of(cleanCode()));

        BookResponse detail = service.getBook(CLEAN_CODE_ID);

        assertThat(detail).isEqualTo(new BookResponse(CLEAN_CODE_ID, "Clean Code", "Robert C. Martin",
                "9780132350884", new BigDecimal("31.99"),
                "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg",
                Availability.IN_STOCK, 12, new CategoryResponse(TECHNOLOGY_ID, "Technology")));
        verify(repository).findById(CLEAN_CODE_ID);
    }

    @ParameterizedTest(name = "detail_badge: stock={0} derives {1} on the FR-07 read (D-09)")
    @CsvSource({
            "0, OUT_OF_STOCK",
            "1, LOW_STOCK",
            "5, LOW_STOCK",
            "6, IN_STOCK",
            "12, IN_STOCK"
    })
    void getBook_whenStockVaries_derivesTheBadgeFromThePublishedQuantity(int stock, Availability expected) {
        // D-09's three buckets, observed through the detail read rather than only
        // through the policy: the badge and the exact quantity travel together in
        // one projection, so they cannot be observed disagreeing (FR-10/LC-12
        // bound the quantity selector by that same number).
        Book stocked = new Book(technology(), "Clean Code", "Robert C. Martin", "9780132350884",
                new BigDecimal("31.99"), stock, "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg");
        ReflectionTestUtils.setField(stocked, "id", CLEAN_CODE_ID);
        when(repository.findById(CLEAN_CODE_ID)).thenReturn(Optional.of(stocked));

        BookResponse detail = service.getBook(CLEAN_CODE_ID);

        assertThat(detail.stockQuantity()).isEqualTo(stock);
        assertThat(detail.availability()).isEqualTo(expected);
    }

    @Test
    void getBook_whenIdMatchesNoBook_throwsThePublishedNotFoundProblem() {
        // FR-07's 404 half: a well-formed id with no row is a declared
        // ApiException — status, URN, title, detail and the echoed bookId are part
        // of the wire contract (plan §2, D-15), which is what the shared advice
        // renders; the repository is consulted once and no projection is built.
        UUID missingId = UUID.fromString("00000000-0000-0000-0000-00000000cb99");
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        BookNotFoundException thrown = catchThrowableOfType(
                () -> service.getBook(missingId), BookNotFoundException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown).extracting(
                ex -> ((ApiException) ex).getStatus().value(),
                ApiException::getType, ApiException::getTitle, ApiException::getDetail)
                .containsExactly(404, "urn:foley-books:problem:book-not-found",
                        "Book not found", "No book exists with the given id.");
        assertThat(thrown.getProperties()).containsEntry("bookId", missingId.toString());
        verify(repository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    // ------------------------------------------------------------------ CA-11: batch (D-10, FR-11 enrichment)

    @Test
    void getBooks_whenIdsGiven_issuesOneFindAllByIdAndProjectsEveryRow() {
        // D-10's single-call rule at the repository seam (NFR-02 on the cart path):
        // the batch is exactly one findAllById, and every returned row goes
        // through the shipped BookMapperImpl (ADR-009) — so the badge, the exact
        // price and the embedded category a batch line carries are byte-for-byte
        // what the detail read would produce for the same row. A projection
        // hand-built here instead of through the mapper could silently drift from
        // the card beside it; using the real generated mapper is what proves it
        // cannot (C8, ADR-009).
        when(repository.findAllById(any())).thenReturn(List.of(cleanCode()));

        List<BookResponse> result = service.getBooks(List.of(CLEAN_CODE_ID));

        assertThat(result).singleElement().isEqualTo(new BookResponse(CLEAN_CODE_ID, "Clean Code",
                "Robert C. Martin", "9780132350884", new BigDecimal("31.99"),
                "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg",
                Availability.IN_STOCK, 12, new CategoryResponse(TECHNOLOGY_ID, "Technology")));
        ArgumentCaptor<Iterable<UUID>> captor = ArgumentCaptor.captor();
        verify(repository).findAllById(captor.capture());
        assertThat(captor.getValue()).containsExactly(CLEAN_CODE_ID);
        verify(repository, never()).findById(any());
    }

    @Test
    void getBooks_whenSomeIdsMatchNoBook_omitsThemWithoutThrowing() {
        // CA-11's deliberate inversion of getBook's 404 (LC-14): a requested id
        // with no row simply is not in the result the repository hands back, and
        // the service neither adds a placeholder nor throws — the cart reads the
        // absent entry as "this line's book vanished". Proven against the mocked
        // repo returning a subset; the real IN-query subset semantics are the
        // PostgreSQL case in BookRepositoryTest.
        UUID vanished = UUID.fromString("00000000-0000-0000-0000-00000000cb99");
        when(repository.findAllById(any())).thenReturn(List.of(cleanCode()));

        List<BookResponse> result = service.getBooks(List.of(CLEAN_CODE_ID, vanished));

        assertThat(result).extracting(BookResponse::id).containsExactly(CLEAN_CODE_ID);
        assertThat(result).noneMatch(book -> book.id().equals(vanished));
    }

    @Test
    void getBooks_whenIdsEmpty_returnsEmptyResultWithoutTouchingTheRepository() {
        // A blank ?ids= binds to an EMPTY list, so this is the HTTP-reachable empty
        // path (FR-11's empty cart): an empty array, never an error, and never a
        // database round-trip — findAllById over an empty collection is a query
        // Spring Data does not define, so the short-circuit avoids it. The web slice
        // proves the binder hands the endpoint exactly this empty list.
        List<BookResponse> result = service.getBooks(List.of());

        assertThat(result).isEmpty();
        verify(repository, never()).findAllById(any());
    }

    @Test
    void getBooks_whenIdsNull_returnsEmptyResultWithoutTouchingTheRepository() {
        // The other empty shape: an ABSENT ?ids= binds to null. Same no-query,
        // empty-array rule as the blank case above — both are HTTP-reachable, both
        // never a 400 "ids is required", proven once at the service and once at the
        // boundary (BookControllerTest) so neither layer re-decides.
        List<BookResponse> result = service.getBooks(null);

        assertThat(result).isEmpty();
        verify(repository, never()).findAllById(any());
    }
}
