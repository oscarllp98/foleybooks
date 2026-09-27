package com.foleybooks.order.cart.api;

import com.foleybooks.order.cart.service.CartLine;
import com.foleybooks.order.cart.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated cart's HTTP surface (FR-10..FR-12, plan §2), thin by
 * constitutional mandate (C8): bind and validate at the boundary, delegate to
 * {@link CartService}, map the result — every stock gate, flag and total is
 * the service's rule, and nothing here decides anything a request could talk
 * its way past. OR-08 opens the tree with its three delivered operations —
 * read (OR-07), add (OR-06) and change-quantity (this task);
 * {@code DELETE /cart/items/{bookId}} joins with OR-09, and the plan §2
 * endpoint map is otherwise complete here: paths are plural resources, the
 * verbs are the verbs (AGENTS.md §6), and a line is addressed by the
 * catalog's public book UUID, never by an internal row id (ADR-004: the cart
 * is addressed by identity, the line by book).
 *
 * <p><b>Ownership is never a parameter, and identity is a binding, not a
 * decision (C26).</b> The caller is named solely by the validated token's
 * {@code sub} claim — the same UUID {@code carts.user_id} stores (ADR-004,
 * ADR-008) — so no request can address another user's cart, and there is no
 * cart id anywhere in the surface to forge. The chain already requires
 * authentication for the whole tree (SecurityConfig); the mirrored
 * {@code @PreAuthorize("isAuthenticated()")} is the cart's own rule, the shape
 * the SecurityConfig probe was built to pin (D-14 method security), not a
 * second opinion on the route. Every 401/403 therefore comes from Spring
 * Security itself (ADR-007); the handler adds no authentication verdict of its
 * own — {@link #ownerId} merely converts the authenticated subject into the
 * UUID it is contracted to be (JwtIssuer signs only user-UUID subjects), and a
 * token that violates that contract is a server-side fault, answered by the
 * advice as a traceable 500 (NFR-06) with no cart ever read or written (NFR-01).
 *
 * <p>Responses follow plan §2 exactly: {@code GET /cart} and {@code PATCH
 * /cart/items/{bookId}} answer 200 with the full {@link CartResponse} —
 * FR-12's change returns the resulting cart, so totals recalculate live and
 * the client needs no follow-up read — while {@code POST /cart/items} answers
 * 201 with the same view plus a {@code Location} at the line's own address.
 * The 201 body composes add then read (plan §4's {@code cart.add} ends
 * {@code return cart.read(userId)}); a failed add never reaches the read. The
 * error statuses ride the service's exceptions through the shared advice:
 * 400 validation {@code errors[]} and malformed bodies (C23, LC-16 —
 * negative/missing quantity, a non-UUID {@code bookId} from the binder,
 * non-numeric bodies), 404 {@code book-not-found} and {@code
 * cart-line-not-found}, 422 {@code insufficient-stock} carrying {@code
 * availableStock} (LC-12), 503 {@code catalog-unavailable} (ADR-005) — all
 * ProblemDetail with traceId (D-15, NFR-06). LC-27's anonymous 401 happens in
 * the filter chain before any handler method runs (C25: never disabled,
 * never bypassed).
 */
@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Cart", description = "The caller's persistent per-user cart: read, add, change (FR-10..FR-12)")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(summary = "Read the cart",
            description = "The caller's full cart enriched with live catalog data (FR-11): every line re-validated "
                    + "on read, unavailable and insufficient lines flagged and excluded from the server-computed "
                    + "total. An empty cart — no rows yet, or all lines removed — is the empty state, never an error.")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponse> getCart(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(cartService.read(ownerId(jwt)));
    }

    @PostMapping("/items")
    @Operation(summary = "Add a book to the cart",
            description = "Adds quantity of a book to the caller's cart (FR-10): one line per book, a re-add sums "
                    + "capped by live stock. 404 for a book catalog does not have, 422 with availableStock for a "
                    + "quantity above stock or an out-of-stock book; nothing is written for a rejected add.")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponse> addItem(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddItemRequest request) {
        UUID owner = ownerId(jwt);
        CartLine added = cartService.add(owner, request.bookId(), request.quantity());
        return ResponseEntity.created(URI.create("/api/v1/cart/items/" + added.bookId()))
                .body(cartService.read(owner));
    }

    @PatchMapping("/items/{bookId}")
    @Operation(summary = "Change a line's quantity",
            description = "Sets the caller's line for this book to exactly quantity (FR-12): 0 removes the line, "
                    + "1..stock sets it, above stock is rejected with availableStock and writes nothing. Answers "
                    + "the resulting cart, totals included. 404 when the cart holds no such line (creation is the "
                    + "POST's job); negative quantities are validation errors.")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponse> updateItemQuantity(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Public UUID of the book whose line changes — the bookId the read view published.",
                    example = "00000000-0000-0000-0000-00000000cb06")
            @PathVariable(name = "bookId") UUID bookId,
            @Valid @RequestBody UpdateQuantityRequest request) {
        return ResponseEntity.ok(cartService.update(ownerId(jwt), bookId, request.quantity()));
    }

    /**
     * The owner is the validated token's {@code sub} and nothing else (ADR-004).
     * This is a binding, not an authentication verdict (C26): Spring Security
     * has already decided the bearer <em>is</em> a session, and auth-service's
     * JwtIssuer only ever signs UUID subjects, so a {@code sub} that is absent
     * or not a UUID cannot come from a client — only from a broken or
     * misconfigured issuer. Parsing it is therefore a server-side contract
     * violation and stays one: the advice renders the generic 500 with traceId
     * (NFR-06), never a 401 hand-made in application code, and no repository
     * call ever happens under a half-known identity (NFR-01).
     */
    private static UUID ownerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
