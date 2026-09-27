package com.foleybooks.order.cart.service;

import com.foleybooks.order.cart.api.CartResponse;
import java.util.UUID;

/**
 * The cart operations behind {@code /api/v1/cart} (FR-10..FR-13). The cart
 * is the only state order-service owns (ADR-004): what is stored here is
 * <em>intent</em> — "this user wants N of this book" — never product data,
 * because price, stock and identity live in {@code catalog_db} and are read
 * live per ADR-005. The caller is named by the authenticated {@code sub}
 * claim — never by a client-named id, so a request can address no cart but
 * its owner's (ADR-004).
 */
public interface CartService {

    /**
     * Add {@code quantity} of {@code bookId} to the user's cart (FR-10): the
     * book's live stock is fetched from catalog-service first — a well-formed
     * id naming no book answers 404, and a quantity outside what the catalog
     * currently has answers 422 with the bound, never a silently smaller cart
     * (LC-12, LC-16). An already-present book sums into its single line,
     * capped by stock — two devices adding the same book can never create two
     * lines or oversell it (LC-13, LC-17).
     *
     * @param userId   owner taken from the validated token's {@code sub} claim
     * @param bookId   catalog public identifier of the book to add
     * @param quantity requested add-time quantity, ≥ 1 (boundary-validated, C23)
     * @return the resulting line's record, holding the quantity actually stored
     * @throws BookNotFoundException     if catalog says the book does not exist
     * @throws InsufficientStockException if the request exceeds live stock
     */
    CartLine add(UUID userId, UUID bookId, int quantity);

    /**
     * Read the user's cart enriched with live catalog data (FR-11, plan §4's
     * {@code cart.read}): every stored line is re-validated on <b>every</b>
     * read against what catalog says <em>now</em> (D-08, D-10) — a line whose
     * book no longer exists is flagged {@code available=false} (LC-14) and an
     * overdrawn line {@code insufficientStock} (LC-30); flagged lines stay
     * visible but contribute nothing to the server-computed {@code total}
     * (D-10). The product data arrives through ADR-005's batch: one keyed
     * lookup for the whole cart, never one-per-line (NFR-02).
     *
     * <p>Reading is side-effect-free: a user with no cart gets the empty cart
     * (FR-11's empty state), and nothing is ever created, clamped or deleted
     * here (ADR-004: flags are computed at read time, never written back). A
     * catalog that cannot be reached stays a transport failure surfaced to
     * {@code GlobalExceptionHandler} — never laundered into fabricated flags
     * (ADR-005: absence is data, unavailability is an error).
     *
     * @param userId owner taken from the validated token's {@code sub} claim
     * @return the enriched cart: lines in repository order, exact scale-2
     *         {@code total} over the unflagged lines only, currency {@code EUR}
     */
    CartResponse read(UUID userId);

    /**
     * Change the quantity of the user's line for {@code bookId} to exactly
     * {@code quantity} (FR-12) — a <b>set</b>, not FR-10's sum: the caller names
     * the resulting state, so applying this twice cannot drift the line the way
     * repeated adds would. The live stock gate is FR-12's half of LC-12: a
     * positive quantity above what catalog currently has answers 422 with the
     * bound and writes nothing; the equality case (quantity == stock) is the
     * boundary LC-30 does <em>not</em> flag, and it passes.
     *
     * <p>The special zero is the explicit removal — "setting quantity to 0
     * explicitly removes the line" (FR-12) — and it is a delete, never a stored
     * zero: {@code ck_cart_items_quantity} makes zero unrepresentable at rest
     * (ADR-004). Removal addresses no stock bound (0 units can never exceed
     * anything) and no live book either, so it deliberately skips the east-west
     * lookup: clearing an LC-14 line whose book vanished is exactly the action
     * FR-11 tells the user to take, and a mandatory lookup would answer it 404
     * (ADR-005). Removing an absent line is the idempotent success FR-13
     * promises for DELETE — the requested state already holds — not an error.
     *
     * <p>A positive quantity naming a line the cart does not hold answers 404
     * {@code cart-line-not-found}: FR-12 changes "a line's" quantity and never
     * creates one (creation is {@link #add}), and the answer is local — the
     * stock of a book with no line to restock is nobody's business rule
     * (ADR-010 fixes this and the branch order).
     *
     * @param userId   owner taken from the validated token's {@code sub} claim
     * @param bookId   catalog public identifier of the book whose line changes
     * @param quantity resulting quantity: 0 removes, 1..stock sets, negative is
     *                 a boundary rejection (LC-16) re-checked defensively here
     * @return the full cart after the change — the same FR-11 read view, flags
     *         and server-computed total included (plan §2: 200 CartResponse)
     * @throws CartLineNotFoundException   if a positive quantity addresses no line
     * @throws BookNotFoundException       if a positive quantity addresses a line
     *                                      whose book catalog no longer has
     * @throws InsufficientStockException  if the quantity exceeds live stock
     */
    CartResponse update(UUID userId, UUID bookId, int quantity);
}
