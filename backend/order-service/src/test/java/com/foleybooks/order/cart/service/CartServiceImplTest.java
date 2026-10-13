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
 *
 * <p>OR-08 adds the change half — FR-12's set-not-sum semantics (plan §2's 200
 * CartResponse answers the resulting cart), the zero-removal that skips the
 * catalog entirely (which is what keeps an LC-14 line clearable), the local
 * line-not-found 404 that answers before the east-west hop, and the live stock
 * gate shared with {@link CartService#add} (LC-12, LC-16).
 *
 * <p>OR-09 adds the removal half — FR-13 through the DELETE verb: the same
 * local, catalog-free {@code removeLine} as the zero branch, with idempotence
 * as the contract (absent line and absent cart delete nothing and raise
 * nothing) and no composed read, because the plan §2 204 carries no body.
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

    // ------------------------------------------------------ plan §6.1 / OR-08: the change (FR-12)

    @Test
    void update_whenQuantityWithinStock_setsTheExactQuantityAndAnswersTheEnrichedCart() {
        // FR-12 is a SET, not FR-10's sum: 2 on the line, 5 requested → 5 stored,
        // never 7 (naming the resulting state twice cannot drift it). The 200
        // body is the full FR-11 read view of the changed cart (plan §2), so
        // totals recalculate live and no follow-up GET is the client's job.
        stubStock(12);
        stubPersistencePassThrough();
        Cart cart = existingCart();
        CartItem line = lineOf(cart, 2);
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.of(line));
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(List.of(line));
        when(catalogClient.batchBooks(List.of(BOOK_ID))).thenReturn(List.of(bookDto(
                BOOK_ID, "Clean Code", "31.99", 12)));

        CartResponse result = service.update(OWNER, BOOK_ID, 5);

        assertThat(captureSavedLine().getQuantity()).isEqualTo(5);
        assertThat(result).isEqualTo(new CartResponse(
                List.of(new CartItemResponse(BOOK_ID, "Clean Code", "Robert C. Martin", COVER_URL,
                        new BigDecimal("31.99"), 5, new BigDecimal("159.95"), 12, true, false)),
                new BigDecimal("159.95"), "EUR"));
        // One live lookup for the bound — the ADR-005 singular, never the batch —
        // plus the batch that answers the response. No insert: the line already exists.
        verify(catalogClient, times(1)).findBook(BOOK_ID);
        verify(cartItemRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_whenQuantityEqualsStock_setsExactlyTheFulfillableBoundary() {
        // LC-30's boundary, write-side: ordering all 12 of 12 is sufficient —
        // quantity == stock passes the gate and flags nothing.
        stubStock(12);
        stubPersistencePassThrough();
        Cart cart = existingCart();
        CartItem line = lineOf(cart, 2);
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.of(line));
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(List.of(line));
        when(catalogClient.batchBooks(anyCollection())).thenReturn(List.of(
                bookDto(BOOK_ID, "Clean Code", "31.99", 12)));

        CartResponse result = service.update(OWNER, BOOK_ID, 12);

        assertThat(captureSavedLine().getQuantity()).isEqualTo(12);
        assertThat(result.items().get(0).insufficientStock()).isFalse();
        assertThat(result.total()).isEqualTo(new BigDecimal("383.88"));
    }

    @ParameterizedTest(name = "requested {0} against stock {1} -> 422 availableStock {1} (FR-12, LC-12, LC-16)")
    @CsvSource({"13, 12", "4, 3", "99, 1", "1, 0"})
    void cart_update_aboveStock_rejectedWithAvailableStock(int requested, int stock) {
        // FR-12's half of the same gate FR-10 enforces (LC-12): above stock —
        // including stock 0, "an out-of-stock book" — is rejected with the live
        // bound and writes nothing. Unlike add's capped sum, an update has no
        // helpful interpretation: 13 of 12 is never quietly a 12.
        stubStock(stock);
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.of(lineOf(cart, 2)));

        assertThatThrownBy(() -> service.update(OWNER, BOOK_ID, requested))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getType()).isEqualTo("urn:foley-books:problem:insufficient-stock");
                    assertThat(ex.getProperties()).containsEntry("availableStock", stock);
                });

        verify(cartItemRepository, never()).save(any());
        verify(cartItemRepository, never()).saveAndFlush(any());
        verify(cartItemRepository, never()).delete(any());
        // A rejected change answers no cart: the enrichment read never runs.
        verify(cartItemRepository, never()).findByCartId(CART_ID);
        verify(catalogClient, never()).batchBooks(anyCollection());
    }

    @Test
    void update_whenQuantityNegative_rejectsAgainstLiveStockWithoutWriting() {
        // LC-16's negatives: the boundary's @Min(0) is the first wall (C23); this
        // is the service-level re-check that mirrors add's — no caller, however it
        // reached the rule, stores a non-positive intent via the set branch, and
        // the rejection still carries the live bound. Zero never lands here:
        // it is FR-12's removal branch, checked before the gate.
        stubStock(12);
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.of(lineOf(cart, 2)));

        assertThatThrownBy(() -> service.update(OWNER, BOOK_ID, -3))
                .isInstanceOfSatisfying(InsufficientStockException.class,
                        ex -> assertThat(ex.getProperties()).containsEntry("availableStock", 12));

        verify(cartItemRepository, never()).save(any());
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void update_whenLineIsAlreadyAboveStock_setsItBackWithinBounds() {
        // LC-30's remediation, the action FR-11 tells the user to take: the
        // defensive over-line (stock fell under quantity — legal at rest,
        // ADR-004) is corrected by an explicit set to the live bound. The
        // answer's flags recalculate from the new state: 12 of 12 is sufficient.
        stubStock(12);
        stubPersistencePassThrough();
        Cart cart = existingCart();
        CartItem line = lineOf(cart, 15);
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.of(line));
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(List.of(line));
        when(catalogClient.batchBooks(anyCollection())).thenReturn(List.of(
                bookDto(BOOK_ID, "Clean Code", "31.99", 12)));

        CartResponse result = service.update(OWNER, BOOK_ID, 12);

        assertThat(captureSavedLine().getQuantity()).isEqualTo(12);
        assertThat(result.items().get(0).insufficientStock()).isFalse();
        assertThat(result.total()).isEqualTo(new BigDecimal("383.88"));
    }

    @Test
    void update_whenQuantityZero_removesTheLineAndAnswersTheEmptiedCart() {
        // FR-12's explicit zero is a DELETE, never a stored zero (ADR-004's
        // CHECK makes one unrepresentable — updating to 0 first would be a
        // constraint-violation 500 instead of this 200). And it asks catalog
        // NOTHING: a removal addresses no stock bound, which is exactly what
        // keeps a vanished book's LC-14 line clearable through this verb.
        Cart cart = existingCart();
        CartItem line = lineOf(cart, 2);
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.of(line));
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(List.of());

        CartResponse result = service.update(OWNER, BOOK_ID, 0);

        verify(cartItemRepository).delete(line);
        verify(cartItemRepository, never()).save(any());
        verifyNoInteractions(catalogClient);
        // The emptied cart is FR-11's empty state: the cart row survives (carts
        // are never purged, ADR-004), items empty, total exactly 0.00.
        assertThat(result).isEqualTo(new CartResponse(List.of(), new BigDecimal("0.00"), "EUR"));
    }

    @Test
    void update_whenQuantityZeroForAnAbsentLine_isIdempotentSuccess() {
        // FR-13's idempotence lives in the zero route too: "already not there"
        // is the requested state, answered with the cart as it stands — no
        // delete attempted, no lookup, no error. (The positive branch is the
        // one that requires a line: see the 404 tests below.)
        Cart cart = existingCart();
        CartItem otherLine = lineOf(cart, BOOK_B, 1);
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.empty());
        when(cartItemRepository.findByCartId(CART_ID)).thenReturn(List.of(otherLine));
        when(catalogClient.batchBooks(anyCollection())).thenReturn(List.of(
                bookDto(BOOK_B, "Design Patterns", "54.99", 3)));

        CartResponse result = service.update(OWNER, BOOK_ID, 0);

        verify(cartItemRepository, never()).delete(any());
        assertThat(result.items()).containsExactly(
                new CartItemResponse(BOOK_B, "Design Patterns", "Robert C. Martin", COVER_URL,
                        new BigDecimal("54.99"), 1, new BigDecimal("54.99"), 3, true, false));
        verify(catalogClient, never()).findBook(any());
    }

    @Test
    void update_whenQuantityZeroAndUserHasNoCart_answersTheEmptyCart() {
        // No cart row and no line are one and the same nothing-to-remove (FR-11's
        // empty state reached through the zero branch): 200 empty, nothing written.
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.empty());

        CartResponse result = service.update(OWNER, BOOK_ID, 0);

        assertThat(result).isEqualTo(new CartResponse(List.of(), new BigDecimal("0.00"), "EUR"));
        verifyNoInteractions(catalogClient, cartItemRepository);
    }

    @Test
    void update_whenPositiveQuantityAddressesNoLine_rejectsBeforeAnyCatalogCall() {
        // FR-12 changes "a line's" quantity and never creates one — creation is
        // FR-10's POST — so a positive quantity for a book the cart does not
        // hold is 404 cart-line-not-found, answered LOCALLY, before the
        // east-west hop (and before the stock gate: quantity 4 of a stock-3
        // book names a missing line first; the bound is moot without a line).
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(OWNER, BOOK_ID, 4))
                .isInstanceOfSatisfying(CartLineNotFoundException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getType()).isEqualTo("urn:foley-books:problem:cart-line-not-found");
                    assertThat(ex.getProperties()).containsEntry("bookId", BOOK_ID.toString());
                });

        verifyNoInteractions(catalogClient);
        verify(cartItemRepository, never()).save(any());
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void update_whenUserHasNoCartAndQuantityPositive_rejectsLineNotFound() {
        // No cart row can hold the line: the same 404 as an absent line in a
        // present cart — the caller addresses something that is not there, and
        // no network call decides that.
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(OWNER, BOOK_ID, 1))
                .isInstanceOf(CartLineNotFoundException.class);

        verifyNoInteractions(catalogClient, cartItemRepository);
    }

    @Test
    void update_whenBookVanished_propagatesTheNotFoundWithoutWriting() {
        // The LC-14 line and a positive quantity: existence was settled locally,
        // then the live lookup says the book itself is gone — the decoder's
        // BookNotFoundException passes through untouched (ADR-005: the singular
        // read's absence is data), and the two 404s stay distinct: line-not-
        // found here never fires for a line that exists.
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.of(lineOf(cart, 2)));
        when(catalogClient.findBook(BOOK_ID)).thenThrow(BookNotFoundException.forId(BOOK_ID));

        assertThatThrownBy(() -> service.update(OWNER, BOOK_ID, 3))
                .isInstanceOf(BookNotFoundException.class);

        verify(cartItemRepository, never()).save(any());
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void update_whenCatalogUnreachableAtTheGate_propagatesTheTransportErrorWithoutWriting() {
        // ADR-005's load-bearing line on the write side too: an unreachable
        // catalog fails the change as the transport error GlobalExceptionHandler
        // renders 503 catalog-unavailable — never as an accepted set against an
        // invented bound (NFR-07).
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.of(lineOf(cart, 2)));
        when(catalogClient.findBook(BOOK_ID)).thenThrow(catalogDown());

        assertThatThrownBy(() -> service.update(OWNER, BOOK_ID, 3))
                .isInstanceOf(FeignException.ServiceUnavailable.class);

        verify(cartItemRepository, never()).save(any());
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void update_whenLineVanishesBetweenChecks_answersLineNotFoundInsteadOfResurrectingIt() {
        // The concurrent-removal race (the other session's DELETE is exactly
        // this): the existence check passed, the write unit re-loads and finds
        // nothing — it refuses rather than merging the stale detached line back
        // into existence (ADR-004: intent deleted is intent withdrawn).
        stubStock(12);
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID))
                .thenReturn(Optional.of(lineOf(cart, 2)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(OWNER, BOOK_ID, 5))
                .isInstanceOf(CartLineNotFoundException.class);

        verify(cartItemRepository, never()).save(any());
        // The answer is local again: the rejected write never reaches the read.
        verify(catalogClient, never()).batchBooks(anyCollection());
    }

    // ------------------------------------------------------ plan §6.1 / OR-09: the removal (FR-13)

    @Test
    void delete_whenLineExists_removesItWithoutAnyCatalogCallOrEnrichedRead() {
        // FR-13 through its own verb is the same local, catalog-free delete
        // FR-12's zero branch runs (ADR-010: a removal asserts no stock bound):
        // the line is deleted, catalog is never asked, and — unlike POST/PATCH —
        // no read composes behind the 204, so the enrichment batch never fires.
        Cart cart = existingCart();
        CartItem line = lineOf(cart, 2);
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.of(line));

        service.delete(OWNER, BOOK_ID);

        verify(cartItemRepository).delete(line);
        verifyNoInteractions(catalogClient);
        verify(cartItemRepository, never()).findByCartId(CART_ID);
    }

    @Test
    void delete_whenCartHoldsNoSuchLine_isAnIdempotentSuccessThatDeletesNothing() {
        // FR-13's second clause is the contract, not an error path: "removing a
        // non-existent line → success". The requested state — no line for this
        // book — already holds, so nothing is deleted and no not-found is raised
        // (ADR-010: DELETE must not add a third not-found semantics).
        Cart cart = existingCart();
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_ID)).thenReturn(Optional.empty());

        service.delete(OWNER, BOOK_ID);

        verify(cartItemRepository, never()).delete(any());
        verifyNoInteractions(catalogClient);
    }

    @Test
    void delete_whenUserHasNoCart_isAnIdempotentSuccessWithoutTouchingAnything() {
        // No cart row and no line are one and the same nothing-to-remove (FR-11's
        // empty state, reached through the removal verb): success, nothing
        // written, nothing read, nothing asked of catalog.
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.empty());

        service.delete(OWNER, BOOK_ID);

        verifyNoInteractions(catalogClient, cartItemRepository);
    }

    @Test
    void delete_whenLineAddressesAVanishedBook_stillRemovesItWithoutLookingItUp() {
        // The ADR-010 posture that keeps an LC-14 line actionable through FR-13:
        // the book is gone from catalog, but a removal never consults it, so a
        // mandatory findBook would have answered book-not-found and stranded the
        // line forever. Here the delete runs regardless of the vanished book.
        Cart cart = existingCart();
        CartItem goneLine = lineOf(cart, BOOK_GONE, 3);
        when(cartRepository.findByUserId(OWNER)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdAndBookId(CART_ID, BOOK_GONE)).thenReturn(Optional.of(goneLine));

        service.delete(OWNER, BOOK_GONE);

        verify(cartItemRepository).delete(goneLine);
        verifyNoInteractions(catalogClient);
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

    @Test
    void cartLineNotFoundException_whenRaised_carriesNotFoundAndEchoesTheBookId() {
        CartLineNotFoundException ex = CartLineNotFoundException.forBookId(BOOK_ID);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getType()).isEqualTo("urn:foley-books:problem:cart-line-not-found");
        assertThat(ex.getTitle()).isEqualTo("Cart line not found");
        assertThat(ex.getDetail()).isEqualTo("The cart holds no line for the given book.");
        // The plan §2 extra-property echo shape (bookId/availableStock/resendHint),
        // for NFR-06 traceability only — the cart is private state.
        assertThat(ex.getProperties()).containsOnlyKeys("bookId");
        assertThat(ex.getProperties()).containsEntry("bookId", BOOK_ID.toString());
    }

    @ParameterizedTest(name = "availableStock {0} phrases the detail from the same number (plan §2)")
    @CsvSource({"0, This book is out of stock.", "1, Only 1 unit left.", "12, Only 12 units left."})
    void insufficientStockException_detailIsPhrasedFromTheSameNumberThePropertyCarries(int stock, String detail) {
        InsufficientStockException ex = InsufficientStockException.forStock(stock);

        assertThat(ex.getDetail()).isEqualTo(detail);
        assertThat(ex.getProperties()).containsEntry("availableStock", stock);
    }
}
