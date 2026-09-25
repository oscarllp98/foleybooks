package com.foleybooks.order.cart.service;

import com.foleybooks.order.cart.client.CatalogClient;
import com.foleybooks.order.cart.domain.Cart;
import com.foleybooks.order.cart.domain.CartItem;
import com.foleybooks.order.cart.repository.CartItemRepository;
import com.foleybooks.order.cart.repository.CartRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * FR-10's add rule, plan §4's {@code cart.add} made concrete (OR-06) — every
 * branch is a service rule (C8), never controller or entity code. The order of
 * operations is the plan's: ask catalog first, decide, then write. The
 * {@code findBook} lookup happens <em>outside</em> any transaction (ADR-005:
 * never hold a DB connection across the east-west hop), and its outcome steers
 * the response directly — a well-formed id naming no book surfaces from
 * {@code CatalogErrorDecoder} as {@link BookNotFoundException} and simply
 * propagates (plan §4's {@code if book == null: return 404}), while a
 * non-book failure stays a {@code FeignException} the advice renders as 503
 * {@code catalog-unavailable}: an unreachable catalog is never laundered into
 * a 404 or a 422 (ADR-005, NFR-07).
 *
 * <p>The stock gate (LC-12, LC-16) compares the request against the live
 * {@code stockQuantity} — {@code < 1} is the defensive re-check of what the
 * boundary's Bean Validation already enforced (C23), {@code > stock} covers
 * both "quantity above stock" and "out-of-stock book" (stock 0 leaves no
 * addable quantity, FR-10) — and the rejection carries the catalog's own
 * number as {@code availableStock} (plan §2) so the client learns the bound
 * it missed. Nothing is written before this gate passes.
 *
 * <p>What remains is the upsert (LC-13): sum into the book's one line, capped
 * by stock, and the cap is the database's rules with the service as their
 * reader — {@code uk_carts_user} makes "find-or-create the user's cart" a
 * race LC-17's two concurrent sessions can actually lose (the loser catches
 * the violation and re-reads the winner's row), and {@code
 * uk_cart_items_cart_book} means two devices adding the same book at the same
 * moment cannot create two lines: the insert loser re-reads the winner's line
 * and its sum is capped exactly like the update path (ADR-004: the constraint
 * is the cross-device summing anchor). The catalog answer is deliberately not
 * re-fetched in the retry — both attempts of one request bind themselves to
 * the same live stock read; a vanished-book race in milliseconds is a
 * defensive state FR-11's flags own (ADR-005), not this write's. Like FR-01
 * registration (AU-12), these caught-race flows use programmatic
 * per-unit-of-work transactions ({@link TransactionOperations}): a
 * request-scoped {@code @Transactional} boundary could not recover after the
 * constraint error aborted it. The stored quantity is
 * {@code min(existing + added, stock)} (FR-10, LC-13): an add can never grow
 * a line past stock, and because the request itself is gated at ≤ stock, a
 * capping sum only ever pulls down a line that was <em>already</em> above
 * live stock — the defensive LC-30 state FR-11 flags on reads but never
 * rewrites (ADR-004); an explicit new add is a different act, and there
 * FR-10's "can never exceed stock" decides. The {@link CartItem} entity
 * stays inside this class: the service hands the {@link CartLine} record
 * across the layer boundary (AGENTS.md §4, C9), and the response projection
 * is the read half's job (OR-07).
 */
@Service
public class CartServiceImpl implements CartService {

    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

    private final CatalogClient catalogClient;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final TransactionOperations transactionOperations;

    public CartServiceImpl(CatalogClient catalogClient,
                           CartRepository cartRepository,
                           CartItemRepository cartItemRepository,
                           TransactionOperations transactionOperations) {
        this.catalogClient = catalogClient;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public CartLine add(UUID userId, UUID bookId, int quantity) {
        // ADR-005: the east-west lookup runs before any transaction opens —
        // stock is catalog's live truth, and a down catalog fails here as a
        // 503-class dependency error, never as a fabricated 404/422.
        int stock = catalogClient.findBook(bookId).stockQuantity();
        if (quantity < 1 || quantity > stock) {
            // LC-12/LC-16: out-of-range or out-of-stock — rejected with the
            // bound the catalog just reported, and nothing written.
            log.debug("Cart add rejected for user {} (book {}, requested {}, available {})",
                    userId, bookId, quantity, stock);
            throw InsufficientStockException.forStock(stock);
        }

        Cart cart = findOrCreateCart(userId);
        CartItem line = upsertLine(cart, bookId, quantity, stock);
        return new CartLine(line.getBookId(), line.getQuantity());
    }

    /**
     * Find-or-create the user's one cart (ADR-004, LC-17). {@code uk_carts_user}
     * makes two first-adds from two devices resolve to one row: the loser's
     * {@code saveAndFlush} raises the caught {@link DataIntegrityViolationException}
     * as its unit rolls back, and the re-read happens in a fresh unit outside it
     * (same AU-12 race pattern), now seeing the winner's committed cart.
     */
    private Cart findOrCreateCart(UUID userId) {
        Optional<Cart> existing = cartRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return transactionOperations.execute(tx -> cartRepository.saveAndFlush(new Cart(userId)));
        } catch (DataIntegrityViolationException raceLost) {
            return cartRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Cart vanished between the unique-constraint race and the re-read for user " + userId));
        }
    }

    /**
     * The LC-13 upsert inside one transaction unit: an existing line takes
     * {@code min(existing + quantity, stock)} — one line per book, summed and
     * capped by the live stock the request was already gated against. With no
     * line yet, the insert can still lose {@code uk_cart_items_cart_book} to a
     * concurrent add of the same book from another session: the unit ends at
     * the caught violation and the retry outside it re-reads the winner's
     * committed line and sums into it with the same cap — one line, one
     * bounded quantity, whichever device wrote first. The {@link CartItem}
     * this returns is consumed inside {@link #add} and projected to a
     * {@link CartLine}; it never leaves the service layer (AGENTS.md §4).
     */
    private CartItem upsertLine(Cart cart, UUID bookId, int quantity, int stock) {
        try {
            return transactionOperations.execute(tx -> {
                Optional<CartItem> existing = cartItemRepository.findByCartIdAndBookId(cart.getId(), bookId);
                if (existing.isPresent()) {
                    CartItem line = existing.get();
                    line.setQuantity(Math.min(line.getQuantity() + quantity, stock));
                    return cartItemRepository.save(line);
                }
                return cartItemRepository.saveAndFlush(new CartItem(cart, bookId, quantity));
            });
        } catch (DataIntegrityViolationException raceLost) {
            return transactionOperations.execute(tx -> {
                CartItem winnerLine = cartItemRepository.findByCartIdAndBookId(cart.getId(), bookId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Cart line vanished between the unique-constraint race and the re-read in cart "
                                        + cart.getId()));
                winnerLine.setQuantity(Math.min(winnerLine.getQuantity() + quantity, stock));
                return cartItemRepository.save(winnerLine);
            });
        }
    }
}
