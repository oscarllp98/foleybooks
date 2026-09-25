package com.foleybooks.order.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.foleybooks.order.cart.client.BookDto;
import com.foleybooks.order.cart.client.CatalogClient;
import com.foleybooks.order.cart.domain.Cart;
import com.foleybooks.order.cart.domain.CartItem;
import com.foleybooks.order.cart.repository.CartItemRepository;
import com.foleybooks.order.cart.repository.CartRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
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
 */
class CartServiceImplTest {

    private static final UUID OWNER = UUID.fromString("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a");
    private static final UUID BOOK_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID CART_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

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
                BOOK_ID, "Clean Code", "Robert C. Martin",
                "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg",
                new BigDecimal("31.99"), stock));
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
        return new CartItem(cart, BOOK_ID, quantity);
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
