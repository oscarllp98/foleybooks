package com.foleybooks.order.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.foleybooks.order.cart.api.CartItemResponse;
import com.foleybooks.order.cart.api.CartResponse;
import com.foleybooks.order.cart.client.BookDto;
import com.foleybooks.order.cart.client.CatalogClient;
import com.foleybooks.order.cart.domain.Cart;
import com.foleybooks.order.cart.domain.CartItem;
import com.foleybooks.order.cart.repository.CartItemRepository;
import com.foleybooks.order.cart.repository.CartRepository;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * FR-10's add rule at the catalog and persistence seams (plan §6.1, OR-06): the
 * live stock gate (LC-12, LC-16), the capped summing upsert (LC-13), the
 * one-cart-per-user shared across sessions (LC-17) and the race-recovery
 * branches ADR-004 anchors on the two unique constraints. The Feign client and
 * both repositories are mocks — the constraint semantics they enforce at rest
 * are {@code CartRepositoryTest}/{@code CartItemRepositoryTest}'s Testcontainers
 * ground, and the plan §6.1 names these flow rules as unit tests. The
 * transaction template runs its callback inline (the boundary itself is
 * Spring's tested behavior, same seam as {@code UserServiceImplTest}); the
 * catalog answer is the real consumer DTO so the stock bound travels end to end.
 *
 * <p>The plan §6.1 entries carried here:
 * {@code cart_add_aboveStock_rejectedWithAvailableStock} (the gate, across
 * above-stock and out-of-stock quantities — LC-12, and LC-16's absurd inputs),
 * {@code cart_add_sameBookTwice_sumsCapped} (LC-13's sum capped at live stock —
 * including pulling a defensive LC-30 over-line back down, which FR-10 demands
 * of an explicit add and which FR-11's read deliberately never does, ADR-004)
 * and {@code cart_twoSessions_oneSharedCart} (LC-17: the cart is keyed by the
 * token's user, and a re-add reuses the committed line). The 404 is proven as
 * pass-through: the decoder's {@link BookNotFoundException} (its wire shape
 * pinned by {@code CatalogErrorDecoderTest}) leaves the service untouched and
 * writes nothing. The {@link CartLine} return is the AGENTS.md §4 records-out
 * seam: no assertion here can reach an entity, because none escapes.
 *
 * <p>OR-07 adds the read half's plan §6.1 entries —
 * {@code cart_read_unavailableBook_flagsLine} (LC-14) and
 * {@code cart_read_insufficientStock_flagsLine} (LC-30) pin the two flags and
 * that flagged lines are excluded from the total while an unreachable catalog
 * stays a propagated transport failure, never a fabricated flag (ADR-005) —
 * and {@code money_lineTotals_exact} (D-08, NFR-07), asserted through
 * scale-sensitive {@link BigDecimal#equals} so an off-scale sum cannot pass
 * even numerically. ADR-005's transaction posture — cart and lines loaded in
 * one consistent unit that has committed before the Feign hop — is pinned with
 * a self-tracking {@code TransactionOperations}, the same seam
 * {@code UserServiceImplTest} uses for post-commit mail dispatch.
 */
class CartServiceImplTest {

    private static final UUID OWNER = UUID.fromString("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a");
    private static final UUID BOOK_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID CART_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOOK_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOOK_C = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BOOK_GONE = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String COVER_URL = "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg";

    private final CatalogClient catalogClient = mock(CatalogClient.class);
    private final CartRepository cartRepository = mock(CartRepository.class);
    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);

    // Running the callback inline is all these unit tests need — transaction
    // semantics are Spring's own tested behavior (same seam as AU-11/AU-12).
    private final TransactionOperations inlineTransaction = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };

    private final CartServiceImpl service = new CartServiceImpl(
            catalogClient, cartRepository, cartItemRepository, inlineTransaction);

    private void stubStock(int stock) {
        when(catalogClient.findBook(BOOK_ID)).thenReturn(new BookDto(
                BOOK_ID, "Clean Code", "Robert C. Martin", COVER_URL, new BigDecimal("31.99"), stock));
    }

    /** JPA's contract: a save returns the managed instance it was given. */
    private void stubPersistencePassThrough() {
        when(cartItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartItemRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Cart existingCart() {
        Cart cart = new Cart(OWNER);
        ReflectionTestUtils.setField(cart, "id", CART_ID);
        return cart;
    }

    private CartItem lineOf(Cart cart, int quantity) {
        return lineOf(cart, BOOK_ID, quantity);
    }

    private CartItem lineOf(Cart cart, UUID bookId, int quantity) {
        return new CartItem(cart, bookId, quantity);
    }

    /** Deterministic ids for the > 100-line chunking fixture. */
    private static UUID bookIdOf(int index) {
        return new UUID(0L, index);
    }

    /** Wire up the local half of a read: the user's committed cart and its stored lines. */
    private void stubCartWithLines(Cart cart, CartItem... lines) {
        stubCartWithLines(cart, List.of(lines));
    }

    private void stubCartWithLines(Cart cart, List<CartItem> lines) {
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(lines);
    }

    private static BookDto bookDto(UUID id, String title, String price, int stock) {
        return new BookDto(id, title, "Robert C. Martin", COVER_URL, new BigDecimal(price), stock);
    }

    /** The 503 a Feign call raises when catalog is down — the ErrorDecoder's residual output. */
    private static FeignException catalogDown() {
        Request request = Request.create(Request.HttpMethod.GET,
                "http://catalog-service:8082/api/v1/books/batch", Map.of(), Request.Body.empty(), null);
        Response response = Response.builder()
                .status(503).reason("Service Unavailable").request(request)
                .headers(Map.of("Content-Type", List.of("application/problem+json")))
                .body("{ \"detail\": \"no healthy upstream\" }", StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("CatalogClient#batchBooks(Collection)", response);
    }

    private CartItem captureSavedLine() {
        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(captor.capture());
        return captor.getValue();
    }

    private CartItem captureInsertedLine() {
        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------ plan §6.1: the stock gate

    @ParameterizedTest(name = "requested {0} against stock {1} -> 422 availableStock {1} (FR-10, LC-12)")
    @CsvSource({"4, 3", "99, 1", "2, 0"})
    void cart_add_aboveStock_rejectedWithAvailableStock(int requested, int stock) {
        stubStock(stock);

        assertThatThrownBy(() -> service.add(OWNER, BOOK_ID, requested))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getType()).isEqualTo("urn:foley-books:problem:insufficient-stock");
                    assertThat(ex.getProperties()).containsEntry("availableStock", stock);
                });

        // FR-10: a rejected add never touches the cart — no cart, no line.
        verifyNoInteractions(cartRepository, cartItemRepository);
    }

    @ParameterizedTest(name = "absurd quantity {0} is a rejection, not a stored row (LC-16)")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void add_whenQuantityNotAboveOne_rejectsAgainstLiveStockWithoutWriting(int quantity) {
        // LC-16's negatives and zeros: the boundary's Bean Validation is the
        // first wall (C23); this is the service-level re-check that no caller,
        // however it reached the rule, can store a non-positive intent — the
        // rejection still carries the live bound.
        stubStock(12);

        assertThatThrownBy(() -> service.add(OWNER, BOOK_ID, quantity))
                .isInstanceOfSatisfying(InsufficientStockException.class,
                        ex -> assertThat(ex.getProperties()).containsEntry("availableStock", 12));

        verifyNoInteractions(cartRepository, cartItemRepository);
    }

    @Test
    void add_whenCatalogSaysBookMissing_propagatesTheNotFoundWithoutWriting() {
        // plan §4's `if book == null: return 404`, concrete (ADR-005): the
        // decoder's BookNotFoundException is already the right status and wire
        // shape — the service passes it through and writes nothing.
        when(catalogClient.findBook(BOOK_ID)).thenThrow(BookNotFoundException.forId(BOOK_ID));

        assertThatThrownBy(() -> service.add(OWNER, BOOK_ID, 1))
                .isInstanceOf(BookNotFoundException.class);

        verifyNoInteractions(cartRepository, cartItemRepository);
    }

    // ------------------------------------------------------ FR-10: the happy path

    @Test
    void add_whenCartIsNew_createsTheUsersCartAndInsertsTheRequestedQuantity() {
        stubStock(12);
        stubPersistencePassThrough();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.empty());
        when(cartRepository.saveAndFlush(any())).thenReturn(existingCart());
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.empty());

        CartLine result = service.add(OWNER, BOOK_ID, 2);

        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).saveAndFlush(cartCaptor.capture());
        assertThat(cartCaptor.getValue().getUserId()).isEqualTo(OWNER);
        assertThat(captureInsertedLine().getQuantity()).isEqualTo(2);
        assertThat(result).isEqualTo(new CartLine(BOOK_ID, 2));
        // ADR-005: the stock answer is the one live lookup — exactly one findBook.
        verify(catalogClient, times(1)).findBook(BOOK_ID);
    }

    @Test
    void add_whenUserAlreadyHasACart_reusesItWithoutInsertingASecondRow() {
        // uk_carts_user makes a second cart impossible; the service must not
        // even try (LC-17's find half).
        stubStock(12);
        stubPersistencePassThrough();
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.empty());

        service.add(OWNER, BOOK_ID, 1);

        verify(cartRepository, never()).saveAndFlush(any());
        assertThat(captureInsertedLine().getCart()).isSameAs(cart);
    }

    // ------------------------------------------------------ plan §6.1: LC-13 summing

    @Test
    void cart_add_sameBookTwice_sumsCapped() {
        stubStock(12);
        stubPersistencePassThrough();
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.of(lineOf(cart, 3)));

        service.add(OWNER, BOOK_ID, 4);

        // LC-13: one line, 3 + 4 = 7; the insert path is never taken.
        CartItem updated = captureSavedLine();
        assertThat(updated.getQuantity()).isEqualTo(7);
        verify(cartItemRepository, never()).saveAndFlush(any());
    }

    @Test
    void add_whenExistingSumWouldExceedStock_capsAtStock() {
        stubStock(12);
        stubPersistencePassThrough();
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.of(lineOf(cart, 10)));

        CartLine result = service.add(OWNER, BOOK_ID, 5);

        // min(10 + 5, 12) = 12: the sum is capped at the live stock (FR-10,
        // LC-13) — an add can never leave a line above what catalog has.
        assertThat(captureSavedLine().getQuantity()).isEqualTo(12);
        assertThat(result).isEqualTo(new CartLine(BOOK_ID, 12));
    }

    @Test
    void add_whenExistingLineIsAlreadyAboveStock_capsItBackToStock() {
        // FR-10's bound is live, not historical: the defensive LC-30 line —
        // stock fell under an existing quantity, unreachable in the static MVP
        // catalog (ADR-005) but legal at rest (ADR-004) — is pulled back by an
        // explicit new add. Writes enforce (this), reads only flag (FR-11):
        // ADR-004's "resist auto-clamping" forbids silent repair without a
        // user-initiated write, which this is.
        stubStock(12);
        stubPersistencePassThrough();
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.of(lineOf(cart, 15)));

        CartLine result = service.add(OWNER, BOOK_ID, 1);

        assertThat(captureSavedLine().getQuantity()).isEqualTo(12);
        assertThat(result.quantity()).isEqualTo(12);
    }

    // ------------------------------------------------------ plan §6.1: LC-17 + ADR-004 races

    @Test
    void cart_twoSessions_oneSharedCart() {
        // LC-17: the cart is selected by the token's user alone — API and
        // service never name a cart id — so a second session's add resolves to
        // the same committed cart and sums onto its one line.
        stubStock(12);
        stubPersistencePassThrough();
        Cart committed = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(committed));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.empty());

        CartLine firstSession = service.add(OWNER, BOOK_ID, 3);
        assertThat(firstSession).isEqualTo(new CartLine(BOOK_ID, 3));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.of(lineOf(committed, 3)));

        CartLine secondSession = service.add(OWNER, BOOK_ID, 4);

        assertThat(secondSession).isEqualTo(new CartLine(BOOK_ID, 7));
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void add_whenCartInsertLosesTheUniqueRace_fallsBackToTheWinnersCart() {
        // ADR-004/LC-17, the AU-12 pattern: the loser of uk_carts_user does not
        // fail — its save throws inside the rolled-back unit, the re-read
        // outside it sees the winner's committed cart, and the line lands there.
        stubStock(12);
        stubPersistencePassThrough();
        when(cartRepository.findByUserId(OWNER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingCart()));
        when(cartRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_carts_user"));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.empty());

        service.add(OWNER, BOOK_ID, 2);

        assertThat(captureInsertedLine().getCart().getUserId()).isEqualTo(OWNER);
        verify(cartRepository, times(2)).findByUserId(OWNER);
    }

    @Test
    void add_whenLineInsertLosesTheUniqueRace_sumsIntoTheWinnersLineCappedByStock() {
        // ADR-004: uk_cart_items_cart_book is the cross-device summing anchor —
        // the insert loser re-reads the winner's committed line and applies the
        // exact same capped sum as the update path (min(7 + 8, 12) = 12).
        stubStock(12);
        stubPersistencePassThrough();
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(lineOf(cart, 7)));
        when(cartItemRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uk_cart_items_cart_book"));

        CartLine result = service.add(OWNER, BOOK_ID, 8);

        CartItem summed = captureSavedLine();
        assertThat(summed.getQuantity()).isEqualTo(12);
        assertThat(result).isEqualTo(new CartLine(BOOK_ID, 12));
    }

    // ------------------------------------------------------ plan §6.1 / OR-07: the read (FR-11)

    @Test
    void read_whenUserHasNoCart_returnsEmptyCartWithoutCreatingOrCallingCatalog() {
        // FR-11's empty state / ADR-004: add creates carts, read never
        // materializes one — and no lines means no reason to touch the network.
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.empty());

        CartResponse result = service.read(OWNER);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.currency()).isEqualTo("EUR");
        verifyNoInteractions(catalogClient, cartItemRepository);
        verify(cartRepository, never()).save(any());
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void read_whenCartHasNoLines_returnsEmptyCartWithoutCallingCatalog() {
        stubCartWithLines(existingCart());

        CartResponse result = service.read(OWNER);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.currency()).isEqualTo("EUR");
        verifyNoInteractions(catalogClient);
    }

    @Test
    void read_whenCartAndLinesLoad_theyShareOneTransactionAndTheBatchRunsAfterItCloses() {
        // ADR-005 consequence ("the read is a two-source join... must run the
        // Feign call outside the DB transaction"): cart + lines load in ONE
        // committed snapshot, and batchBooks fires only after the unit has
        // closed — no DB connection held across the east-west hop, no torn
        // read if a concurrent add commits in between. A transaction that
        // tracks its own open flag, the same seam UserServiceImplTest uses.
        AtomicBoolean transactionOpen = new AtomicBoolean();
        TransactionOperations trackingTransaction = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                transactionOpen.set(true);
                try {
                    return action.doInTransaction(null);
                } finally {
                    transactionOpen.set(false);
                }
            }
        };
        CartServiceImpl trackingService = new CartServiceImpl(
                catalogClient, cartRepository, cartItemRepository, trackingTransaction);
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenAnswer(invocation -> {
            assertThat(transactionOpen).isTrue(); // cart load is inside the unit
            return Optional.of(cart);
        });
        when(cartItemRepository.findByCartId(CART_ID)).thenAnswer(invocation -> {
            assertThat(transactionOpen).isTrue(); // line load is inside the same unit
            return List.of(lineOf(cart, 2));
        });
        when(catalogClient.batchBooks(anyCollection())).thenAnswer(invocation -> {
            assertThat(transactionOpen).isFalse(); // the hop runs after the commit
            return List.of(bookDto(BOOK_ID, "Clean Code", "31.99", 12));
        });

        CartResponse result = trackingService.read(OWNER);

        assertThat(result.total()).isEqualTo(new BigDecimal("63.98"));
    }

    @Test
    void read_whenSingleSufficientLine_answersThePlanSectionTwoContract() {
        // The plan §2 cart JSON, field for field and to exactness: Clean Code
        // 31.99 × 2 → lineTotal 63.98, total 63.98, currency EUR. BigDecimal
        // equals is scale-sensitive, so this pins D-08's scale-2 shape too.
        Cart cart = existingCart();
        stubCartWithLines(cart, lineOf(cart, 2));
        when(catalogClient.batchBooks(List.of(BOOK_ID))).thenReturn(List.of(bookDto(
                BOOK_ID, "Clean Code", "31.99", 12)));

        CartResponse result = service.read(OWNER);

        assertThat(result).isEqualTo(new CartResponse(
                List.of(new CartItemResponse(BOOK_ID, "Clean Code", "Robert C. Martin", COVER_URL,
                        new BigDecimal("31.99"), 2, new BigDecimal("63.98"), 12, true, false)),
                new BigDecimal("63.98"), "EUR"));
    }

    @Test
    void cart_read_unavailableBook_flagsLine() {
        // LC-14, ADR-005: the batch's absent entry is the whole signal. The
        // line keeps only its stored intent (bookId + quantity) and the flag —
        // every catalog-sourced field stays null, never a made-up 0.00 price
        // or invented stock — and it contributes nothing to the total.
        Cart cart = existingCart();
        stubCartWithLines(cart, lineOf(cart, BOOK_ID, 2), lineOf(cart, BOOK_GONE, 3));
        when(catalogClient.batchBooks(anyCollection())).thenReturn(List.of(
                bookDto(BOOK_ID, "Clean Code", "31.99", 12)));

        CartResponse result = service.read(OWNER);

        assertThat(result.items().get(1)).isEqualTo(new CartItemResponse(
                BOOK_GONE, null, null, null, null, 3, null, null, false, false));
        assertThat(result.items().get(0).available()).isTrue();
        assertThat(result.total()).isEqualTo(new BigDecimal("63.98"));
    }

    @Test
    void cart_read_insufficientStock_flagsLine() {
        // LC-30: the book exists but stock fell under the stored quantity. The
        // line stays fully populated — the user needs the real price and the
        // real bound to act on it — while the grand total excludes it (D-10).
        Cart cart = existingCart();
        stubCartWithLines(cart,
                lineOf(cart, BOOK_ID, 2),
                lineOf(cart, BOOK_B, 5));
        when(catalogClient.batchBooks(anyCollection())).thenReturn(List.of(
                bookDto(BOOK_ID, "Clean Code", "31.99", 12),
                bookDto(BOOK_B, "Design Patterns", "12.50", 3)));

        CartResponse result = service.read(OWNER);

        CartItemResponse flagged = result.items().get(1);
        assertThat(flagged).isEqualTo(new CartItemResponse(BOOK_B, "Design Patterns", "Robert C. Martin",
                COVER_URL, new BigDecimal("12.50"), 5, new BigDecimal("62.50"), 3, true, true));
        // Only the unflagged line counts toward the total: 63.98, never 126.48.
        assertThat(result.total()).isEqualTo(new BigDecimal("63.98"));
    }

    @Test
    void read_whenQuantityEqualsStock_doesNotFlagTheLine() {
        // LC-30's boundary: insufficient is strictly quantity > stock —
        // ordering exactly the last copies is fulfillable, not a shortage.
        Cart cart = existingCart();
        stubCartWithLines(cart, lineOf(cart, 12));
        when(catalogClient.batchBooks(anyCollection())).thenReturn(List.of(
                bookDto(BOOK_ID, "Clean Code", "31.99", 12)));

        CartResponse result = service.read(OWNER);

        assertThat(result.items().get(0).insufficientStock()).isFalse();
        assertThat(result.total()).isEqualTo(new BigDecimal("383.88"));
    }

    @Test
    void money_lineTotals_exact() {
        // D-08/NFR-07: an integer multiplier adds no decimal places to a
        // scale-2 price, so every product and the sum are exact — asserted
        // through scale-sensitive BigDecimal.equals, with no rounding step
        // anywhere in the chain.
        Cart cart = existingCart();
        stubCartWithLines(cart,
                lineOf(cart, BOOK_ID, 2),
                lineOf(cart, BOOK_B, 3),
                lineOf(cart, BOOK_C, 7));
        when(catalogClient.batchBooks(anyCollection())).thenReturn(List.of(
                bookDto(BOOK_ID, "Clean Code", "31.99", 12),
                bookDto(BOOK_B, "Design Patterns", "12.50", 9),
                bookDto(BOOK_C, "The Pragmatic Programmer", "0.01", 40)));

        CartResponse result = service.read(OWNER);

        assertThat(result.items().get(0).lineTotal()).isEqualTo(new BigDecimal("63.98"));
        assertThat(result.items().get(1).lineTotal()).isEqualTo(new BigDecimal("37.50"));
        assertThat(result.items().get(2).lineTotal()).isEqualTo(new BigDecimal("0.07"));
        assertThat(result.total()).isEqualTo(new BigDecimal("101.55"));
        assertThat(result.total().scale()).isEqualTo(2);
    }

    @Test
    void read_enrichesEveryLineThroughOneBatchCall_ignoringBatchResponseOrder() {
        // D-10 / ADR-005: N lines, one keyed lookup — findBook is never the
        // enrichment path — and because batch order is not contractual
        // (ADR-009), the response order is the repository's, never the catalog's.
        Cart cart = existingCart();
        stubCartWithLines(cart,
                lineOf(cart, BOOK_ID, 1), lineOf(cart, BOOK_B, 1), lineOf(cart, BOOK_C, 1));
        when(catalogClient.batchBooks(anyCollection())).thenReturn(List.of(
                bookDto(BOOK_C, "The Pragmatic Programmer", "49.99", 7),
                bookDto(BOOK_ID, "Clean Code", "31.99", 12),
                bookDto(BOOK_B, "Design Patterns", "54.99", 3)));

        CartResponse result = service.read(OWNER);

        assertThat(result.items()).extracting(CartItemResponse::bookId)
                .containsExactly(BOOK_ID, BOOK_B, BOOK_C);
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.captor();
        verify(catalogClient, times(1)).batchBooks(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(BOOK_ID, BOOK_B, BOOK_C);
        verify(catalogClient, never()).findBook(any());
    }

    @Test
    void read_whenCartExceedsTheBatchCap_revalidatesEveryIdInChunks() {
        // ADR-005's partition: CA-11 refuses > 100 ids (400), so 150 lines
        // ride ceil(150/100) = 2 chunked calls — and FR-11 re-validates
        // <em>every</em> line, not the first 100: the 150th book is the
        // out-of-stock one, and its flag plus its exclusion from the total
        // prove the overflow was fetched, not skipped.
        Cart cart = existingCart();
        List<CartItem> lines = new ArrayList<>();
        for (int i = 1; i <= 150; i++) {
            lines.add(lineOf(cart, bookIdOf(i), 1));
        }
        stubCartWithLines(cart, lines);
        when(catalogClient.batchBooks(anyCollection())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> bookDto(id, "Seeded Book", "1.00", id.equals(bookIdOf(150)) ? 0 : 5))
                    .toList();
        });

        CartResponse result = service.read(OWNER);

        ArgumentCaptor<Collection<UUID>> chunks = ArgumentCaptor.captor();
        verify(catalogClient, times(2)).batchBooks(chunks.capture());
        assertThat(chunks.getAllValues()).extracting(Collection::size).containsExactly(100, 50);
        assertThat(result.items()).hasSize(150);
        CartItemResponse overflowLine = result.items().get(149);
        assertThat(overflowLine.available()).isTrue();
        assertThat(overflowLine.insufficientStock()).isTrue();
        // 149 sufficient lines at 1.00 each; the flagged overflow contributes nothing.
        assertThat(result.total()).isEqualTo(new BigDecimal("149.00"));
    }

    @Test
    void read_whenCatalogFails_propagatesTheTransportErrorWithoutFabricatingFlags() {
        // ADR-005's load-bearing line: never fabricate a cart from an
        // unavailable dependency. A 5xx/down catalog stays the transport
        // failure GlobalExceptionHandler renders as 503 catalog-unavailable —
        // not an empty cart, not all-lines-flagged with a zero total, because
        // "could not ask" is not "the book is gone" (NFR-07).
        Cart cart = existingCart();
        stubCartWithLines(cart, lineOf(cart, 2));
        when(catalogClient.batchBooks(anyCollection())).thenThrow(catalogDown());

        assertThatThrownBy(() -> service.read(OWNER))
                .isInstanceOf(FeignException.ServiceUnavailable.class);

        verify(catalogClient, times(1)).batchBooks(anyCollection());
    }

    // ------------------------------------------------------ exception wire shape

    @Test
    void insufficientStockException_whenStockRemains_carriesPlanSectionTwoShape() {
        InsufficientStockException ex = InsufficientStockException.forStock(3);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getType()).isEqualTo("urn:foley-books:problem:insufficient-stock");
        assertThat(ex.getTitle()).isEqualTo("Insufficient stock");
        assertThat(ex.getDetail()).isEqualTo("Only 3 units left.");
        assertThat(ex.getProperties()).containsOnlyKeys("availableStock");
        assertThat(ex.getProperties()).containsEntry("availableStock", 3);
    }

    @ParameterizedTest(name = "availableStock {0} phrases the detail from the same number (plan §2)")
    @CsvSource({"0, This book is out of stock.", "1, Only 1 unit left.", "12, Only 12 units left."})
    void insufficientStockException_detailIsPhrasedFromTheSameNumberThePropertyCarries(int stock, String detail) {
        InsufficientStockException ex = InsufficientStockException.forStock(stock);

        assertThat(ex.getDetail()).isEqualTo(detail);
        assertThat(ex.getProperties()).containsEntry("availableStock", stock);
    }
}
