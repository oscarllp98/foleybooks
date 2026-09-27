package com.foleybooks.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foleybooks.order.cart.api.CartItemResponse;
import com.foleybooks.order.cart.api.CartResponse;
import com.foleybooks.order.cart.client.BookDto;
import com.foleybooks.order.cart.client.CatalogClient;
import com.foleybooks.order.cart.domain.CartItem;
import com.foleybooks.order.cart.repository.CartItemRepository;
import com.foleybooks.order.cart.repository.CartRepository;
import com.foleybooks.order.cart.service.BookNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The critical cart flow end-to-end on real PostgreSQL (plan §6.4, OR-11):
 * add → summed re-add → change → remove → flags → totals through the public
 * HTTP surface, the real filter chain and the real Flyway schema
 * (FR-10..FR-13; LC-12, LC-13, LC-30). Everything a request can reach is
 * production wiring; exactly two seams are substituted, and each is the
 * boundary another suite already owns. {@link CatalogClient} is a
 * {@code @MockitoBean} over a live map — ADR-005's east-west wire shape is
 * {@code CatalogClientContractTest}'s ground — which lets this test mutate a
 * book's stock and delete a book <em>mid-flow</em>, the very drift LC-30 and
 * LC-14 are about. {@code JwtDecoder} is mocked the way
 * {@code SecurityConfigTest} mocks it (signature validation is auth-service's
 * job, AGENTS.md §5), so bearer traffic still runs the complete
 * resource-server filter chain, the real roles→{@code ROLE_} converter and
 * the cart's {@code @PreAuthorize("isAuthenticated()")} guards, with the
 * token's {@code sub} the only way to name a cart (ADR-004). The {@code .jwt()}
 * post-processor AGENTS.md §5 mandates is what the MockMvc controller slices
 * ({@code CartControllerTest}) use; this IT authenticates the way a real
 * client does — a literal {@code Authorization: Bearer} header through the
 * live {@code BearerTokenAuthenticationFilter} — standing in only the
 * signature-verifying decoder, because no auth-service (and no JWKS to
 * fetch) exists inside this context.
 *
 * <p>Every HTTP verdict is paired with a repository read-back: the database,
 * not the response echo, decides whether a line summed and capped (LC-13),
 * was set (FR-12), vanished (FR-12's zero / FR-13), or was never written at
 * all (LC-12's gate rejecting before {@code carts} exists). Money assertions
 * are scale-sensitive {@link BigDecimal#equals} (NFR-07) against the values
 * actually deserialized from the response JSON, and FR-11's read contract is
 * pinned on the mock: one keyed batch per non-empty read, zero per-line
 * lookups, and no catalog call whatsoever behind a removal (ADR-010).
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "EUREKA_USERNAME=test-user",
        "EUREKA_PASSWORD=test-secret",
        "ORDER_DB_PASSWORD=test-db-secret"
})
class CartFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Mock-decoder convention: {@code Bearer at-<uuid>} authenticates as subject {@code <uuid>}. */
    private static final String TOKEN_PREFIX = "at-";

    private static final UUID BOOK_ONE = UUID.fromString("00000000-0000-0000-0000-00000000cb01");
    private static final UUID BOOK_TWO = UUID.fromString("00000000-0000-0000-0000-00000000cb02");
    private static final UUID BOOK_THREE = UUID.fromString("00000000-0000-0000-0000-00000000cb03");
    private static final UUID BOOK_OUT_OF_STOCK = UUID.fromString("00000000-0000-0000-0000-00000000cb04");
    private static final UUID BOOK_UNKNOWN = UUID.fromString("00000000-0000-0000-0000-00000000cb05");

    /** The fake catalog: mutable between requests, so stock falls and books vanish mid-flow. */
    private final Map<UUID, BookDto> catalog = new HashMap<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CartRepository cartRepository;

    @Autowired
    CartItemRepository cartItemRepository;

    @MockitoBean
    CatalogClient catalogClient;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void wireTheTwoSeams() {
        given(jwtDecoder.decode(anyString())).willAnswer(invocation -> issuedJwt(invocation.getArgument(0)));
        // findBook mirrors CatalogErrorDecoder: a well-formed id naming no book is
        // BookNotFoundException, never null (ADR-005's absence contract).
        given(catalogClient.findBook(any())).willAnswer(invocation -> {
            UUID bookId = invocation.getArgument(0);
            BookDto book = catalog.get(bookId);
            if (book == null) {
                throw BookNotFoundException.forId(bookId);
            }
            return book;
        });
        // batchBooks mirrors CA-11: a gone book is absent from the array, never a 404.
        given(catalogClient.batchBooks(anyCollection())).willAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ids.stream().map(catalog::get).filter(Objects::nonNull).toList();
        });
    }

    // ------------------------------------------------------------------ FR-10..13: the full flow

    @Test
    void cart_whenAddChangeRemoveFlowRuns_persistsEveryStepAndAnswersLiveTotals() throws Exception {
        UUID user = UUID.randomUUID();
        stock(BOOK_ONE, "Clean Code", "20.00", 10);
        stock(BOOK_TWO, "Dune", "12.50", 4);

        // FR-11's empty state: no rows reads as an empty EUR cart, the read
        // materializes nothing and never asks catalog (ADR-004).
        CartResponse empty = readCart(user);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.total()).isEqualTo(new BigDecimal("0.00"));
        assertThat(empty.currency()).isEqualTo("EUR");
        assertThat(cartRepository.findByUserId(user)).isEmpty();
        verifyNoInteractions(catalogClient);

        // FR-10: the first add creates cart and line, answers 201 with the
        // line's own Location and the full read view (plan §2).
        ResultActions firstAdd = mockMvc.perform(addItem(user, BOOK_ONE, 2));
        firstAdd.andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/cart/items/" + BOOK_ONE));
        CartResponse afterFirstAdd = cartOf(firstAdd);
        assertThat(lineOf(afterFirstAdd, BOOK_ONE).quantity()).isEqualTo(2);
        assertThat(lineOf(afterFirstAdd, BOOK_ONE).lineTotal()).isEqualTo(new BigDecimal("40.00"));
        assertThat(afterFirstAdd.total()).isEqualTo(new BigDecimal("40.00"));
        assertThat(cartRepository.findByUserId(user)).isPresent();
        assertThat(storedQuantity(user, BOOK_ONE)).isEqualTo(2);

        // LC-13: the same book re-added stays ONE line, summed and capped by
        // live stock — 2 + 9 would be 11, the cap makes it exactly 10.
        mockMvc.perform(addItem(user, BOOK_ONE, 9))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(10))
                .andExpect(jsonPath("$.total").value(200.00));
        assertThat(linesOf(user)).hasSize(1);
        assertThat(storedQuantity(user, BOOK_ONE)).isEqualTo(10);

        // A second book, then FR-12's set: PATCH names the resulting quantity,
        // it does not sum — 10 becomes exactly 4, and the answer is the whole
        // cart with recalculated totals (200 CartResponse, plan §2).
        mockMvc.perform(addItem(user, BOOK_TWO, 1)).andExpect(status().isCreated());
        mockMvc.perform(updateItem(user, BOOK_ONE, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        assertThat(storedQuantity(user, BOOK_ONE)).isEqualTo(4);
        CartResponse afterSet = readCart(user);
        assertThat(afterSet.total()).isEqualTo(new BigDecimal("92.50"));
        assertThat(lineOf(afterSet, BOOK_TWO).lineTotal()).isEqualTo(new BigDecimal("12.50"));

        // FR-13: removal is the bare 204 with no body and no composed read;
        // the next GET carries the recalculated total.
        mockMvc.perform(removeItem(user, BOOK_ONE))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(storedQuantity(user, BOOK_ONE)).isNegative();
        CartResponse afterRemove = readCart(user);
        assertThat(afterRemove.items()).hasSize(1);
        assertThat(afterRemove.total()).isEqualTo(new BigDecimal("12.50"));

        // Emptying the last line leaves the cart row (ADR-004: carts are never
        // purged) and the exact scale-2 zero (NFR-07), and a later add reuses it.
        mockMvc.perform(removeItem(user, BOOK_TWO)).andExpect(status().isNoContent());
        CartResponse emptied = readCart(user);
        assertThat(emptied.items()).isEmpty();
        assertThat(emptied.total()).isEqualTo(new BigDecimal("0.00"));
        assertThat(cartRepository.findByUserId(user)).isPresent();
        assertThat(linesOf(user)).isEmpty();
        mockMvc.perform(addItem(user, BOOK_TWO, 1)).andExpect(status().isCreated());
        assertThat(linesOf(user)).hasSize(1);
    }

    // ------------------------------------------------------------------ LC-12: the stock gate

    @Test
    void cartWrites_whenStockGateIsMissed_rejectWithAvailableStockAndWriteNothing() throws Exception {
        UUID user = UUID.randomUUID();
        stock(BOOK_ONE, "Clean Code", "20.00", 10);
        stock(BOOK_OUT_OF_STOCK, "Dune", "12.50", 0);
        stock(BOOK_TWO, "Neuromancer", "15.00", 2);

        // FR-10 / LC-12: quantity above the live bound is the 422 carrying
        // that bound (plan §2), with the traceId of D-15 — and the gate runs
        // before any row exists, so not even the cart is created.
        mockMvc.perform(addItem(user, BOOK_ONE, 11))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:insufficient-stock"))
                .andExpect(jsonPath("$.detail").value("Only 10 units left."))
                .andExpect(jsonPath("$.availableStock").value(10))
                .andExpect(jsonPath("$.instance").value("/api/v1/cart/items"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
        assertThat(cartRepository.findByUserId(user)).isEmpty();

        // LC-12's other half: an out-of-stock book is the same rule with a
        // zero bound, not a different status.
        mockMvc.perform(addItem(user, BOOK_OUT_OF_STOCK, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.availableStock").value(0))
                .andExpect(jsonPath("$.detail").value("This book is out of stock."));

        // FR-10: a well-formed id catalog does not have is 404 book-not-found,
        // still writing nothing.
        mockMvc.perform(addItem(user, BOOK_UNKNOWN, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:book-not-found"))
                .andExpect(jsonPath("$.bookId").value(BOOK_UNKNOWN.toString()));
        assertThat(cartRepository.findByUserId(user)).isEmpty();

        // FR-12 / LC-12 on the set verb too: equality with stock passes
        // (2 == 2, the LC-30 boundary), one above is the same 422 — and the
        // stored line is untouched by the rejection.
        mockMvc.perform(addItem(user, BOOK_TWO, 2)).andExpect(status().isCreated());
        mockMvc.perform(updateItem(user, BOOK_TWO, 3))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.availableStock").value(2))
                .andExpect(jsonPath("$.instance").value("/api/v1/cart/items/" + BOOK_TWO));
        assertThat(storedQuantity(user, BOOK_TWO)).isEqualTo(2);
        assertThat(readCart(user).total()).isEqualTo(new BigDecimal("30.00"));
    }

    // ------------------------------------------------------------------ LC-30 / LC-14: flags and totals

    @Test
    void cartRead_whenLiveStockFellUnderStoredQuantity_flagsLinesAndExcludesThemFromTotal() throws Exception {
        UUID user = UUID.randomUUID();
        stock(BOOK_ONE, "Clean Code", "20.00", 10);
        stock(BOOK_TWO, "Dune", "12.50", 4);
        stock(BOOK_THREE, "Neuromancer", "8.00", 5);

        // A cart in which every line is currently orderable — BOOK_TWO sits
        // exactly AT stock, the LC-30 boundary that must NOT flag.
        mockMvc.perform(addItem(user, BOOK_ONE, 2)).andExpect(status().isCreated());
        mockMvc.perform(addItem(user, BOOK_TWO, 4)).andExpect(status().isCreated());
        mockMvc.perform(addItem(user, BOOK_THREE, 2)).andExpect(status().isCreated());
        CartResponse before = readCart(user);
        assertThat(before.total()).isEqualTo(new BigDecimal("106.00"));
        assertThat(before.items()).allSatisfy(item -> {
            assertThat(item.available()).isTrue();
            assertThat(item.insufficientStock()).isFalse();
        });

        // Stock moves under the user's feet: Dune drops to 1 unit (below the
        // stored quantity of 4 → LC-30) and Neuromancer leaves the catalog
        // entirely (absent from the batch answer → LC-14).
        stock(BOOK_TWO, "Dune", "12.50", 1);
        catalog.remove(BOOK_THREE);

        clearInvocations(catalogClient);
        CartResponse after = readCart(user);

        // FR-11: every line re-validated on this one read — the clean line
        // stays in, the overdrawn line is flagged but fully priced so the user
        // can act on it, the vanished line keeps only its stored intent, and
        // the total carries the unflagged line only (D-10).
        CartItemResponse clean = lineOf(after, BOOK_ONE);
        assertThat(clean.available()).isTrue();
        assertThat(clean.insufficientStock()).isFalse();
        assertThat(clean.lineTotal()).isEqualTo(new BigDecimal("40.00"));

        CartItemResponse insufficient = lineOf(after, BOOK_TWO);
        assertThat(insufficient.available()).isTrue();
        assertThat(insufficient.insufficientStock()).isTrue();
        assertThat(insufficient.unitPrice()).isEqualTo(new BigDecimal("12.50"));
        assertThat(insufficient.quantity()).isEqualTo(4);
        assertThat(insufficient.lineTotal()).isEqualTo(new BigDecimal("50.00"));
        assertThat(insufficient.stockQuantity()).isEqualTo(1);

        CartItemResponse vanished = lineOf(after, BOOK_THREE);
        assertThat(vanished.available()).isFalse();
        assertThat(vanished.title()).isNull();
        assertThat(vanished.unitPrice()).isNull();
        assertThat(vanished.lineTotal()).isNull();
        assertThat(vanished.stockQuantity()).isNull();
        assertThat(vanished.quantity()).isEqualTo(2);

        assertThat(after.total()).isEqualTo(new BigDecimal("40.00"));

        // FR-11's read contract (D-10): ONE keyed batch for all three lines,
        // never a per-line findBook — and the read rewrote nothing (ADR-004).
        verify(catalogClient, times(1)).batchBooks(anyCollection());
        verify(catalogClient, never()).findBook(any());
        assertThat(storedQuantity(user, BOOK_ONE)).isEqualTo(2);
        assertThat(storedQuantity(user, BOOK_TWO)).isEqualTo(4);
        assertThat(storedQuantity(user, BOOK_THREE)).isEqualTo(2);

        // FR-12's zero clears an LC-14 line the catalog can no longer name:
        // no findBook is even possible for it, the removal asks catalog nothing.
        clearInvocations(catalogClient);
        mockMvc.perform(updateItem(user, BOOK_THREE, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        verify(catalogClient, never()).findBook(any());
        verify(catalogClient, times(1)).batchBooks(anyCollection());

        // FR-13 through its own verb: a removal not only skips the singular
        // lookup, it touches catalog not at all (ADR-010).
        clearInvocations(catalogClient);
        mockMvc.perform(removeItem(user, BOOK_ONE)).andExpect(status().isNoContent());
        verifyNoInteractions(catalogClient);

        // The end state after both drift classes hit one cart: one visible,
        // still-flagged line and an exact zero total — a line that cannot be
        // ordered today contributes no money (LC-30 + D-10), and reading it
        // never deleted it.
        CartResponse finalCart = readCart(user);
        assertThat(finalCart.items()).hasSize(1);
        assertThat(lineOf(finalCart, BOOK_TWO).insufficientStock()).isTrue();
        assertThat(finalCart.total()).isEqualTo(new BigDecimal("0.00"));
        assertThat(cartRepository.findByUserId(user)).isPresent();
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private void stock(UUID bookId, String title, String price, int stockQuantity) {
        catalog.put(bookId, new BookDto(bookId, title, "Test Author",
                "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg",
                new BigDecimal(price), stockQuantity));
    }

    private MockHttpServletRequestBuilder addItem(UUID user, UUID bookId, int quantity) {
        return post("/api/v1/cart/items")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bookId": "%s", "quantity": %d}
                        """.formatted(bookId, quantity));
    }

    private MockHttpServletRequestBuilder updateItem(UUID user, UUID bookId, int quantity) {
        return patch("/api/v1/cart/items/{bookId}", bookId)
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": %d}".formatted(quantity));
    }

    private MockHttpServletRequestBuilder removeItem(UUID user, UUID bookId) {
        return delete("/api/v1/cart/items/{bookId}", bookId).header("Authorization", bearer(user));
    }

    private CartResponse readCart(UUID user) throws Exception {
        return cartOf(mockMvc.perform(get("/api/v1/cart").header("Authorization", bearer(user)))
                .andExpect(status().isOk()));
    }

    private CartResponse cartOf(ResultActions result) throws Exception {
        return objectMapper.readValue(
                result.andReturn().getResponse().getContentAsString(), CartResponse.class);
    }

    private static CartItemResponse lineOf(CartResponse cart, UUID bookId) {
        return cart.items().stream()
                .filter(item -> item.bookId().equals(bookId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the cart answer holds no line for " + bookId));
    }

    private List<CartItem> linesOf(UUID user) {
        return cartRepository.findByUserId(user)
                .map(cart -> cartItemRepository.findByCartId(cart.getId()))
                .orElse(List.of());
    }

    /** The persisted quantity of one line — the database's verdict, or -1 when no such line exists. */
    private int storedQuantity(UUID user, UUID bookId) {
        return linesOf(user).stream()
                .filter(line -> line.getBookId().equals(bookId))
                .map(CartItem::getQuantity)
                .findFirst()
                .orElse(-1);
    }

    private static String bearer(UUID user) {
        return "Bearer " + TOKEN_PREFIX + user;
    }

    /**
     * Stand-in for an auth-service-issued token: it carries only the claims this
     * service's security path consumes ({@code sub} + {@code roles}); the full
     * claim set JwtIssuer signs is auth-service's {@code JwtIssuerTest}'s ground.
     */
    private static Jwt issuedJwt(String tokenValue) {
        if (!tokenValue.startsWith(TOKEN_PREFIX)) {
            throw new InvalidBearerTokenException("not a token this IT ever presents");
        }
        Instant now = Instant.now();
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject(tokenValue.substring(TOKEN_PREFIX.length()))
                .claim("roles", List.of("CUSTOMER"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(15 * 60))
                .build();
    }
}
