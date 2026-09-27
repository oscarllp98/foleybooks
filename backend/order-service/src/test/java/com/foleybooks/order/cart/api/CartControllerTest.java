package com.foleybooks.order.cart.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foleybooks.order.cart.service.BookNotFoundException;
import com.foleybooks.order.cart.service.CartLine;
import com.foleybooks.order.cart.service.CartLineNotFoundException;
import com.foleybooks.order.cart.service.CartService;
import com.foleybooks.order.cart.service.InsufficientStockException;
import com.foleybooks.order.common.ProblemDetailResponder;
import com.foleybooks.order.config.SecurityConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wire contract of the cart HTTP surface (plan §6.2, OR-08): the real
 * SecurityConfig runs in-slice — security is never disabled (C25) — so the
 * anonymous 401s below are the genuine LC-27 answer rendered by the chain
 * ({@code ProblemDetailResponder}, ADR-007) before any handler runs, and the
 * authenticated 200s prove the {@code @PreAuthorize("isAuthenticated()")}
 * guard shape the SecurityConfig probe pinned for OR-06..OR-09. The service is
 * mocked here — its rules (LC-12's gate, the zero-removal, the local 404) are
 * {@code CartServiceImplTest}'s ground — so what this slice owns is the
 * boundary: who the caller is (the token's {@code sub} and nothing else,
 * ADR-004), what shapes are refused before the service is touched (C23, LC-16:
 * negative and zero add-quantities, missing fields, non-numeric bodies, a
 * non-UUID path id — the D-07/LC-28 reading applied to a path variable), and
 * which status the service's exceptions render as (422 with
 * {@code availableStock}, 404 {@code book-not-found} /
 * {@code cart-line-not-found}, D-15, NFR-06).
 *
 * <p>OR-08 introduces the controller and its PATCH rule (FR-12: the
 * {@code UpdateQuantityRequest → 200 CartResponse / 422} row of plan §2)
 * alongside the GET and POST halves of the same map, whose services landed in
 * OR-06 and OR-07: a line is addressed by the book's public UUID (ADR-005),
 * quantities travel as JSON numbers, and every state-changing answer is the
 * full enriched cart, so totals recalculate server-side without a follow-up
 * read (D-08). OR-09 closes the map with the DELETE row (FR-13): an
 * authenticated removal answers the bare 204 — its idempotence is the
 * service's rule and can never surface as a 404 on this wire — so the
 * boundary's own rejections stay the two the other verbs already proved:
 * the chain's anonymous 401 and the binder's malformed-bookId 400. OR-10 closes
 * the slice's auth matrix: all four verbs now answer LC-27's anonymous 401 and
 * the plan §2 happy-path statuses (200 GET, 201 POST, 200 PATCH, 204 DELETE)
 * through the same customer {@code jwt()} post-processor.
 */
@WebMvcTest(CartController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class})
class CartControllerTest {

    private static final UUID OWNER = UUID.fromString("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a");
    private static final UUID BOOK_ID = UUID.fromString("00000000-0000-0000-0000-00000000cb06");
    private static final String COVER_URL = "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg";

    private static final CartItemResponse CLEAN_CODE_LINE = new CartItemResponse(
            BOOK_ID, "Clean Code", "Robert C. Martin", COVER_URL,
            new BigDecimal("31.99"), 2, new BigDecimal("63.98"), 12, true, false);

    private static final CartResponse CART =
            new CartResponse(List.of(CLEAN_CODE_LINE), new BigDecimal("63.98"), "EUR");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    private static JwtRequestPostProcessor customerJwt() {
        return jwt().jwt(jwt -> jwt.subject(OWNER.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    // ------------------------------------------------------------------ GET /api/v1/cart (FR-11)

    @Test
    void getCart_whenRequestedWithCustomerJwt_responds200WithTheEnrichedCart() throws Exception {
        when(cartService.read(OWNER)).thenReturn(CART);

        mockMvc.perform(get("/api/v1/cart").with(customerJwt()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items[0].bookId").value(BOOK_ID.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Clean Code"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(31.99))
                .andExpect(jsonPath("$.items[0].lineTotal").value(63.98))
                .andExpect(jsonPath("$.total").value(63.98))
                .andExpect(jsonPath("$.currency").value("EUR"));

        // The owner is the token's sub claim — there is no cart id to send (ADR-004).
        verify(cartService).read(OWNER);
    }

    @Test
    void getCart_whenRequestedAnonymously_deniesWithUnauthorizedProblemDetail() throws Exception {
        // LC-27 through the real cart handler: the chain rejects before the
        // controller runs, as the ProblemDetail the responder publishes — no
        // login redirect, no service call, no fabricated empty cart.
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"))
                .andExpect(jsonPath("$.instance").value("/api/v1/cart"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(cartService);
    }

    @Test
    void getCart_whenTokenSubjectIsNotAUserUuid_surfacesAsTraceableServerErrorWithoutTouchingAnyCart() throws Exception {
        // C26: the handler makes no authentication verdict — a bearer the chain
        // accepted but whose sub is not a UUID can only come from a broken or
        // misconfigured issuer (JwtIssuer signs UUIDs), so it stays the honest
        // server-side contract violation the advice renders: generic 500 +
        // traceId (NFR-06), never an application-made 401, and no cart is ever
        // read under a half-known identity (NFR-01).
        mockMvc.perform(get("/api/v1/cart")
                        .with(jwt().jwt(token -> token.subject("reader@example.com"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:internal-error"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(cartService);
    }

    // ------------------------------------------------------------------ POST /api/v1/cart/items (FR-10)

    @Test
    void addItem_whenValidPayload_responds201WithLocationAndTheEnrichedCart() throws Exception {
        // Plan §2's "AddItemRequest → 201 CartResponse": the add lands, then the
        // same FR-11 read view answers the result (plan §4's cart.add ends in
        // cart.read) — the client sees the summed line and live totals it just
        // changed, and Location points at the line's own address (bookId,
        // ADR-005: the line is addressed by book, never by an internal row id).
        when(cartService.add(OWNER, BOOK_ID, 2)).thenReturn(new CartLine(BOOK_ID, 2));
        when(cartService.read(OWNER)).thenReturn(CART);

        mockMvc.perform(post("/api/v1/cart/items")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookId": "%s", "quantity": 2}
                                """.formatted(BOOK_ID)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/cart/items/" + BOOK_ID))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.total").value(63.98))
                .andExpect(jsonPath("$.currency").value("EUR"));

        verify(cartService).add(OWNER, BOOK_ID, 2);
        verify(cartService).read(OWNER);
    }

    @ParameterizedTest(name = "add quantity {0} is refused at the boundary (FR-10, LC-16)")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void addItem_whenQuantityBelowOne_rejectedWith400WithoutTouchingTheService(int quantity) throws Exception {
        // Unlike PATCH, POST has no legal zero: removing belongs to the other
        // verbs, and silently adding nothing would be a fake success. The
        // @Min(1) wall is C23's — the service's stock gate is for above-stock.
        mockMvc.perform(post("/api/v1/cart/items")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookId": "%s", "quantity": %d}
                                """.formatted(BOOK_ID, quantity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].message").value("must be at least 1"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(cartService);
    }

    @Test
    void addItem_whenQuantityIsMissing_rejectedWith400RequiredField() throws Exception {
        // FR-10's "default 1" is the frontend's selector, not a guess the API
        // may make about someone's purchase intent: a cart write states a
        // quantity or is refused.
        mockMvc.perform(post("/api/v1/cart/items")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookId": "%s"}
                                """.formatted(BOOK_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));

        verifyNoInteractions(cartService);
    }

    @Test
    void addItem_whenQuantityIsNotNumeric_rejectedWith400MalformedBody() throws Exception {
        // LC-16's non-numeric half: Jackson refuses it as the shared malformed-
        // body ProblemDetail before validation even starts (D-15).
        mockMvc.perform(post("/api/v1/cart/items")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookId": "%s", "quantity": "two"}
                                """.formatted(BOOK_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:malformed-request-body"));

        verifyNoInteractions(cartService);
    }

    @Test
    void addItem_whenBookDoesNotExist_responds404AndReadsNothingBack() throws Exception {
        // Plan §2's POST row: 201 / 404 / 422. The decoder's 404 (FR-10)
        // propagates through the advice with its echoed bookId, and a failed
        // add never composes the 201 read — the response body stays a Problem.
        when(cartService.add(OWNER, BOOK_ID, 1)).thenThrow(BookNotFoundException.forId(BOOK_ID));

        mockMvc.perform(post("/api/v1/cart/items")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookId": "%s", "quantity": 1}
                                """.formatted(BOOK_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:book-not-found"))
                .andExpect(jsonPath("$.bookId").value(BOOK_ID.toString()));

        verify(cartService, never()).read(any());
    }

    @Test
    void addItem_whenRequestedAnonymously_deniesWithUnauthorizedProblemDetail() throws Exception {
        // LC-27 on the add verb (FR-10): the frontend's login prompt depends on
        // this exact 401, and the return-after-login redirect depends on it
        // being the chain's answer — the ProblemDetail the responder publishes
        // before any handler runs, so nothing is ever added to an
        // unresolvable owner and no empty-cart 200 can leak past security.
        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookId": "%s", "quantity": 1}
                                """.formatted(BOOK_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"))
                .andExpect(jsonPath("$.instance").value("/api/v1/cart/items"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(cartService);
    }

    // ------------------------------------------------------------------ PATCH /api/v1/cart/items/{bookId} (FR-12)

    @Test
    void updateItem_whenQuantityWithinStock_responds200WithTheResultingCart() throws Exception {
        // Plan §2's PATCH row: UpdateQuantityRequest → 200 CartResponse. The
        // quantity is forwarded verbatim (the service owns the set-vs-sum and
        // the stock gate, C8), and the body is the full enriched cart — no
        // Location, no wrapper, the same record the GET serves.
        when(cartService.update(OWNER, BOOK_ID, 5)).thenReturn(CART);

        mockMvc.perform(patch("/api/v1/cart/items/{bookId}", BOOK_ID)
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bookId").value(BOOK_ID.toString()))
                .andExpect(jsonPath("$.total").value(63.98))
                .andExpect(jsonPath("$.currency").value("EUR"));

        verify(cartService).update(OWNER, BOOK_ID, 5);
    }

    @Test
    void updateItem_whenQuantityIsZero_delegatesTheExplicitRemoval() throws Exception {
        // FR-12's zero reaches the service as zero, not as a 400 — the floor
        // that makes negatives validation errors (@Min(0)) is drawn exactly
        // here because zero is a removal, the operation the spec mandates.
        CartResponse emptied = new CartResponse(List.of(), new BigDecimal("0.00"), "EUR");
        when(cartService.update(OWNER, BOOK_ID, 0)).thenReturn(emptied);

        mockMvc.perform(patch("/api/v1/cart/items/{bookId}", BOOK_ID)
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0.00));

        verify(cartService).update(OWNER, BOOK_ID, 0);
    }

    @ParameterizedTest(name = "quantity {0} is refused at the boundary (FR-12, LC-16)")
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void updateItem_whenQuantityIsNegative_rejectedWith400WithoutTouchingTheService(int quantity) throws Exception {
        // "no other out-of-range value is accepted" — below zero is never a
        // removal or a capping, it is a validation error with the shared
        // errors[] shape, answered before the service exists to the request.
        mockMvc.perform(patch("/api/v1/cart/items/{bookId}", BOOK_ID)
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": %d}".formatted(quantity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be negative"))
                .andExpect(jsonPath("$.instance").value("/api/v1/cart/items/" + BOOK_ID))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(cartService);
    }

    @Test
    void updateItem_whenQuantityIsMissing_rejectedWith400RequiredField() throws Exception {
        // An update that names no resulting quantity states no intent: it is
        // refused, never defaulted to 1 (silently restocking a line the user
        // may have meant to clear would be the worst possible guess).
        mockMvc.perform(patch("/api/v1/cart/items/{bookId}", BOOK_ID)
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));

        verifyNoInteractions(cartService);
    }

    @ParameterizedTest(name = "bookId {0} is a 400 validation error (LC-28, D-07 path reading)")
    @ValueSource(strings = {"not-a-uuid", "00000000-0000-0000-0000", "cb06"})
    void updateItem_whenBookIdIsMalformed_rejectedWith400BeforeTheService(String rawBookId) throws Exception {
        // The catalog detail route's boundary asymmetry, now on the cart's own
        // path variable: a malformed id is the shared 400 (field bookId), so
        // only a well-formed UUID can reach the 404s (C23, D-07 as amended).
        mockMvc.perform(patch("/api/v1/cart/items/{bookId}", rawBookId)
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("bookId"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"));

        verifyNoInteractions(cartService);
    }

    @Test
    void updateItem_whenQuantityAboveStock_responds422CarryingAvailableStock() throws Exception {
        // Plan §6.2's cart row: the FR-12 gate's rejection is the LC-12 answer
        // — insufficient-stock with the live bound as an extra property and a
        // ProblemDetail body, never a silently smaller line and never a 200.
        when(cartService.update(OWNER, BOOK_ID, 13))
                .thenThrow(InsufficientStockException.forStock(12));

        mockMvc.perform(patch("/api/v1/cart/items/{bookId}", BOOK_ID)
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 13}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:insufficient-stock"))
                .andExpect(jsonPath("$.title").value("Insufficient stock"))
                .andExpect(jsonPath("$.detail").value("Only 12 units left."))
                .andExpect(jsonPath("$.availableStock").value(12))
                .andExpect(jsonPath("$.instance").value("/api/v1/cart/items/" + BOOK_ID))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void updateItem_whenCartHasNoSuchLine_responds404CartLineNotFound() throws Exception {
        // FR-12 changes a line and never creates one: the service's local 404
        // renders with its own URN (distinct from book-not-found) and echoes
        // the addressed bookId — the cart is private state, so naming the
        // missing line leaks nothing (NFR-06 traceability, not enumeration).
        when(cartService.update(OWNER, BOOK_ID, 2))
                .thenThrow(CartLineNotFoundException.forBookId(BOOK_ID));

        mockMvc.perform(patch("/api/v1/cart/items/{bookId}", BOOK_ID)
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:cart-line-not-found"))
                .andExpect(jsonPath("$.title").value("Cart line not found"))
                .andExpect(jsonPath("$.bookId").value(BOOK_ID.toString()))
                .andExpect(jsonPath("$.instance").value("/api/v1/cart/items/" + BOOK_ID))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void updateItem_whenBookVanished_responds404BookNotFoundForTheExistingLine() throws Exception {
        // The other 404 the same verb can meet: a line that exists addresses a
        // book catalog no longer has (LC-14). The decoder's exception rides the
        // advice unchanged — the two not-founds never collapse into one, so an
        // LC-14 line is never reported as a missing cart line.
        when(cartService.update(OWNER, BOOK_ID, 1)).thenThrow(BookNotFoundException.forId(BOOK_ID));

        mockMvc.perform(patch("/api/v1/cart/items/{bookId}", BOOK_ID)
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:book-not-found"))
                .andExpect(jsonPath("$.bookId").value(BOOK_ID.toString()));
    }

    @Test
    void updateItem_whenRequestedAnonymously_deniesWithUnauthorizedProblemDetail() throws Exception {
        // LC-27 on the write verb: changing quantities is the same authenticated
        // tree, and the 401 is the chain's ProblemDetail before any handler.
        mockMvc.perform(patch("/api/v1/cart/items/{bookId}", BOOK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 3}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(cartService);
    }

    // ------------------------------------------------------------------ DELETE /api/v1/cart/items/{bookId} (FR-13)

    @Test
    void removeItem_whenRequested_responds204AndComposesNoRead() throws Exception {
        // Plan §2's DELETE row → 204 (idempotent): no body, and — unlike the
        // POST/PATCH writes that answer the enriched cart — the handler composes
        // no read back, so the service's read view is never even asked for.
        mockMvc.perform(delete("/api/v1/cart/items/{bookId}", BOOK_ID).with(customerJwt()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // The owner is the token's sub; the removal never needs a cart id (ADR-004).
        verify(cartService).delete(OWNER, BOOK_ID);
        verify(cartService, never()).read(any());
    }

    @Test
    void removeItem_whenLineIsAlreadyGone_stillAnswers204ForTheIdempotentSuccess() throws Exception {
        // FR-13's idempotence is the service's rule (unit-tested in
        // CartServiceImplTest); the wire contract this row of plan §2 pins is
        // that the handler never inspects the outcome to invent a not-found —
        // a void delete plus no composed read answers the bare 204 regardless.
        // A mocked void delete already does nothing, which is exactly the
        // already-removed case, so no stubbing is needed (or wanted).
        mockMvc.perform(delete("/api/v1/cart/items/{bookId}", BOOK_ID).with(customerJwt()))
                .andExpect(status().isNoContent());

        verify(cartService).delete(OWNER, BOOK_ID);
    }

    @ParameterizedTest(name = "bookId {0} is a 400 validation error (LC-28, D-07 path reading)")
    @ValueSource(strings = {"not-a-uuid", "00000000-0000-0000-0000", "cb06"})
    void removeItem_whenBookIdIsMalformed_rejectedWith400BeforeTheService(String rawBookId) throws Exception {
        // The same boundary asymmetry as the other two verbs addressing a line
        // by public book UUID: a malformed id is the shared 400 (field bookId),
        // so it never reaches the idempotent service call (C23, D-07 as amended).
        mockMvc.perform(delete("/api/v1/cart/items/{bookId}", rawBookId).with(customerJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("bookId"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"));

        verifyNoInteractions(cartService);
    }

    @Test
    void removeItem_whenRequestedAnonymously_deniesWithUnauthorizedProblemDetail() throws Exception {
        // LC-27 on the removal verb: the whole cart tree is authenticated-only,
        // and the 401 is the chain's ProblemDetail before any handler runs — the
        // idempotent 204 never becomes an anonymous answer.
        mockMvc.perform(delete("/api/v1/cart/items/{bookId}", BOOK_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(cartService);
    }
}
