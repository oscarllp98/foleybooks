package com.foleybooks.order.cart.service;

import com.foleybooks.order.cart.api.CartItemResponse;
import com.foleybooks.order.cart.api.CartResponse;
import com.foleybooks.order.cart.client.BookDto;
import com.foleybooks.order.cart.client.CatalogClient;
import com.foleybooks.order.cart.domain.Cart;
import com.foleybooks.order.cart.domain.CartItem;
import com.foleybooks.order.cart.repository.CartItemRepository;
import com.foleybooks.order.cart.repository.CartRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * FR-10's add rule and FR-11's read rule, plan §4's {@code cart.add} /
 * {@code cart.read} made concrete (OR-06, OR-07) — every branch is a service
 * rule (C8), never controller or entity code. The order of
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
 * across the layer boundary (AGENTS.md §4, C9).
 *
 * <p>The read half ({@link #read}, OR-07) is the same two-source discipline in
 * the other direction, split across the network boundary the way {@link #add}
 * splits around its caught races: both local loads run inside one short
 * {@link TransactionOperations} unit — a consistent cart-and-lines snapshot,
 * which two un-wrapped finds could tear if an add commits in between — and
 * that unit has already committed before the Feign hop and the in-memory
 * projection run outside it, because ADR-005 forbids holding a DB connection
 * open across the east-west call and a request-scoped {@code @Transactional}
 * wrapper would do exactly that. Flags are computed, never written back:
 * reading a cart changes nothing in it, and a user with no cart row simply
 * gets the empty response (FR-11's empty state — {@code add} creates carts,
 * {@code read} never materializes one). Enrichment is ADR-005's numbered
 * procedure: one keyed {@code batchBooks} answer for all lines, partitioned
 * into chunks of {@value #CATALOG_BATCH_MAX_IDS} because CA-11 caps a batch
 * at that many ids — the chunking is a correctness backstop so a &gt; 100-line
 * cart cannot 400 the read <em>or</em> silently skip FR-11's stock
 * re-validation for the overflow, and it keeps the call count at
 * {@code ceil(lines / 100)}, never one-per-line (D-10). Batch responses carry
 * no contractual order (ADR-009), so the answer is keyed into a map by
 * {@code id} and lines are projected in repository order. A book absent from
 * the answer flags its line unavailable with every catalog-sourced field
 * {@code null} (LC-14, ADR-005's honest gap); a present book with
 * {@code quantity > stockQuantity} flags it insufficient while staying fully
 * populated (LC-30). The money is exact-decimal throughout (D-08, NFR-07):
 * {@code lineTotal = price × quantity} — an integer multiplier adds no decimal
 * places to a scale-2 price, so no rounding ever applies — and the grand
 * {@code total} sums only lines passing <em>both</em> gates (D-10), the final
 * {@code setScale(2)} a serialization guarantee that throws rather than
 * silently rounds if a scale ever were to drift. A {@code FeignException}
 * from the batch propagates untouched: an unreachable catalog fails the read
 * as a traceable 503-class error, never as a fabricated all-flagged cart whose
 * zero total would be a money lie (ADR-005, NFR-06, NFR-07).
 *
 * <p>The change half ({@link #update}, OR-08) is FR-12's explicit intent, and
 * its three branches differ from {@link #add} on purpose: the quantity is
 * <em>set</em>, never summed (naming the resulting state twice cannot drift it
 * — LC-13's summing belongs to adds alone); zero <em>deletes</em> the line
 * without consulting catalog at all, because {@code
 * ck_cart_items_quantity} makes a stored zero unrepresentable (ADR-004) and a
 * removal addresses no stock bound — skipping the lookup is what lets a user
 * clear an LC-14 line whose book vanished, the very action FR-11 tells them to
 * take; and a positive quantity naming no line is a 404 {@link
 * CartLineNotFoundException} answered <em>locally, before the network hop</em>
 * — changing a line that does not exist is a client bug regardless of what
 * stock says, and a catalog-first check would launder the vanished-book state
 * into a second, wrong 404 (ADR-005's absence rule). For a positive update the
 * live {@code findBook} bound then gates the write exactly like {@link #add}'s
 * stock gate does (LC-12, LC-16): above stock is a 422 carrying {@code
 * availableStock} with nothing written, equality (quantity == stock — the line
 * LC-30 does not flag) passes, and a vanished book propagates as the decoder's
 * 404. The line is re-loaded inside the write unit — the same {@link
 * TransactionOperations} posture as the other flows, no unit ever spanning the
 * Feign hop — so a concurrent removal from another session dies as the honest
 * 404 here instead of resurrecting the line through a merge of stale state,
 * and a concurrent <em>set</em> is last-write-wins by definition: FR-12 names
 * no order between two explicit user intents. The response is the full {@link
 * #read} view after the change (plan §2: 200 CartResponse), so totals
 * recalculate live and the client never re-fetches to see the effect.
 */
@Service
public class CartServiceImpl implements CartService {

    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

    /** CA-11's batch cap (ADR-009): ids beyond it must be fetched in another chunk, never dropped. */
    private static final int CATALOG_BATCH_MAX_IDS = 100;

    /** System-wide money convention, never a stored column (ADR-004). */
    private static final String CURRENCY_EUR = "EUR";

    private static final BigDecimal EMPTY_TOTAL = BigDecimal.ZERO.setScale(2);

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

    @Override
    public CartResponse read(UUID userId) {
        // One short TransactionOperations unit loads both local tables as a
        // consistent snapshot and closes before the network is touched: a
        // request-scoped @Transactional would hold a DB connection across the
        // Feign hop, which ADR-005 forbids; two un-wrapped finds would let a
        // concurrent add commit in between and torn the snapshot (same
        // programmatic seam as TokenServiceImpl's rotation and add's races).
        List<CartItem> lines = transactionOperations.execute(tx -> cartRepository.findByUserId(userId)
                .map(cart -> cartItemRepository.findByCartId(cart.getId()))
                .orElseGet(List::of));
        if (lines.isEmpty()) {
            // FR-11's empty state: no cart row and a cart with no lines are
            // indistinguishable to the caller, and neither reaches the catalog.
            return emptyCart();
        }

        Map<UUID, BookDto> booksById = fetchBooksById(lines);
        List<CartItemResponse> items = new ArrayList<>(lines.size());
        for (CartItem line : lines) {
            items.add(enrich(line, booksById.get(line.getBookId())));
        }
        // D-08/NFR-07: computed server-side, flagged lines excluded (D-10), and
        // scale-2 addends sum exactly — a final setScale(2) that would throw
        // rather than round if the exactness invariant ever broke upstream.
        BigDecimal total = items.stream()
                .filter(item -> item.available() && !item.insufficientStock())
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        return new CartResponse(items, total, CURRENCY_EUR);
    }

    @Override
    public CartResponse update(UUID userId, UUID bookId, int quantity) {
        if (quantity == 0) {
            // FR-12's explicit removal: a delete, never a stored zero (ADR-004's
            // CHECK makes zero unrepresentable). No east-west lookup — a removal
            // needs no stock bound, and this is what keeps an LC-14 line whose
            // book vanished removable at all. Absent line/cart: the requested
            // state already holds (FR-13's idempotence), still a 200 read view.
            removeLine(userId, bookId);
            return read(userId);
        }
        // A positive quantity changes a line that must exist, and that answer is
        // local and precedes the network: no line → 404 regardless of stock
        // (creation is add's job, FR-10). Then the live bound gates the write
        // exactly like add's gate does (LC-12, LC-16): ADR-005's vanished book
        // propagates as book-not-found, an unreachable catalog as a 503-class
        // transport error — never as a fabricated acceptance or a stored row.
        requireLine(userId, bookId);
        int stock = catalogClient.findBook(bookId).stockQuantity();
        if (quantity < 0 || quantity > stock) {
            // quantity < 0 is the defensive re-check of the boundary's @Min(0)
            // (C23, LC-16) — an update names its result, so no out-of-range
            // value is ever accepted, not even capped.
            log.debug("Cart update rejected for user {} (book {}, requested {}, available {})",
                    userId, bookId, quantity, stock);
            throw InsufficientStockException.forStock(stock);
        }
        setLineQuantity(userId, bookId, quantity);
        return read(userId);
    }

    /**
     * FR-11's empty state (ADR-004: demo accounts start here): an empty item
     * list and an exact {@code 0.00} total — a cart with no rows and no cart
     * row at all are indistinguishable to the caller, and neither is an error.
     */
    private static CartResponse emptyCart() {
        return new CartResponse(List.of(), EMPTY_TOTAL, CURRENCY_EUR);
    }

    /**
     * One keyed batch lookup (or {@code ceil(lines / 100)} of them — D-10
     * forbids one call per line, CA-11's {@code MAX_SIZE} forbids one call per
     * bigger list; ADR-005's partition is the correctness backstop that keeps
     * FR-11's promise of re-validating <em>every</em> line). The answer is
     * keyed by {@code id} because batch order is not contractual (ADR-009);
     * an id absent from every chunk is LC-14's gap, read later as
     * {@code available=false}. A failed call propagates as the transport
     * exception it is — absence is data, unavailability is an error (ADR-005).
     */
    private Map<UUID, BookDto> fetchBooksById(List<CartItem> lines) {
        List<UUID> bookIds = lines.stream().map(CartItem::getBookId).toList();
        Map<UUID, BookDto> booksById = new HashMap<>();
        for (int from = 0; from < bookIds.size(); from += CATALOG_BATCH_MAX_IDS) {
            List<UUID> chunk = bookIds.subList(from, Math.min(from + CATALOG_BATCH_MAX_IDS, bookIds.size()));
            for (BookDto book : catalogClient.batchBooks(chunk)) {
                booksById.put(book.id(), book);
            }
        }
        return booksById;
    }

    /**
     * ADR-005's per-line derivation. {@code book == null} is the batch's
     * absent entry: the line keeps only its stored intent ({@code bookId},
     * {@code quantity}) plus {@code available=false}, every catalog-sourced
     * field {@code null} — there is no book to read, and a made-up price or
     * bound would be a second truth (LC-14). A present line is fully
     * populated, with {@code insufficientStock} comparing the stored quantity
     * against the live stock (LC-30 — equality is still sufficient) and
     * {@code lineTotal} the exact scale-2 product of live price and quantity.
     */
    private static CartItemResponse enrich(CartItem line, BookDto book) {
        if (book == null) {
            return new CartItemResponse(line.getBookId(), null, null, null, null,
                    line.getQuantity(), null, null, false, false);
        }
        return new CartItemResponse(line.getBookId(), book.title(), book.author(), book.coverUrl(),
                book.price(), line.getQuantity(),
                book.price().multiply(BigDecimal.valueOf(line.getQuantity())),
                book.stockQuantity(), true, line.getQuantity() > book.stockQuantity());
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

    /**
     * FR-12's existence precondition, read as one consistent cart-and-lines
     * snapshot (the same unit shape {@link #read} uses): a positive quantity
     * addresses a line, and no network hop runs until the local answer says it
     * exists — the 404 for a line that was never there cannot depend on what
     * catalog happens to stock, and never depends on whether it answers at all.
     */
    private void requireLine(UUID userId, UUID bookId) {
        transactionOperations.execute(tx -> cartRepository.findByUserId(userId)
                .flatMap(cart -> cartItemRepository.findByCartIdAndBookId(cart.getId(), bookId))
                .orElseThrow(() -> CartLineNotFoundException.forBookId(bookId)));
    }

    /**
     * The FR-12 set inside one transaction unit that re-reads the line rather
     * than merging the earlier detached copy: a line concurrently removed (the
     * other session's OR-09 DELETE is exactly this race) dies here as the same
     * honest 404 instead of being resurrected from stale state. No unique
     * constraint is at stake — the row already exists, its {@code
     * uk_cart_items_cart_book} identity is what was loaded — so unlike {@link
     * #upsertLine} there is no insert race to recover from, and unlike FR-10's
     * sum the requested quantity is applied verbatim: a set is idempotent by
     * construction, twice the same intent, the same line.
     */
    private void setLineQuantity(UUID userId, UUID bookId, int quantity) {
        transactionOperations.execute(tx -> {
            CartItem line = cartRepository.findByUserId(userId)
                    .flatMap(cart -> cartItemRepository.findByCartIdAndBookId(cart.getId(), bookId))
                    .orElseThrow(() -> CartLineNotFoundException.forBookId(bookId));
            line.setQuantity(quantity);
            return cartItemRepository.save(line);
        });
    }

    /**
     * FR-12's zero branch: delete the addressed line when present, in one unit
     * (ADR-004 — the schema has no zero row to write). Absent cart or absent
     * line is a no-op, never an error: the requested state is the current one,
     * the idempotence FR-13 spells for DELETE lives here for the set-to-zero
     * route too. The {@code cart_items} row's deletion is the whole of the
     * user's intent — the cart row itself survives (ADR-004: carts are never
     * purged in the MVP), so a later add reuses it.
     */
    private void removeLine(UUID userId, UUID bookId) {
        transactionOperations.execute(tx -> cartRepository.findByUserId(userId)
                .flatMap(cart -> cartItemRepository.findByCartIdAndBookId(cart.getId(), bookId))
                .map(line -> {
                    cartItemRepository.delete(line);
                    return line;
                })
                .orElse(null));
    }
}
