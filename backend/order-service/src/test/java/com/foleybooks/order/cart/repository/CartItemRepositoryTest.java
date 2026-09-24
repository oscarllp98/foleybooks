package com.foleybooks.order.cart.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.order.cart.domain.Cart;
import com.foleybooks.order.cart.domain.CartItem;
import com.foleybooks.testsupport.CartSqlProbe;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL via Testcontainers (C15) for the {@code cart_items} half of
 * order_db (OR-04, ADR-004). Everything the cart flows take for granted is pinned
 * here at rest: {@code uk_cart_items_cart_book} (LC-13's single line per book,
 * scoped per cart — LC-17), {@code ck_cart_items_quantity} ≥ 1 (FR-12's
 * set-to-zero must be a delete, not a stored zero), {@code fk_cart_items_cart}
 * with {@code ON DELETE CASCADE} (intra-service only), and — the C18 one —
 * {@code book_id} deliberately having <em>no</em> FK, which keeps a line whose
 * book vanished representable and is exactly the state LC-14 flags at read time.
 * {@code ddl-auto=validate} matching these columns is proven by the context
 * booting against the migrated schema (C16, C17).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = "ORDER_DB_PASSWORD=test-db-secret")
class CartItemRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    CartItemRepository cartItemRepository;

    @Autowired
    CartRepository cartRepository;

    @Autowired
    TestEntityManager entityManager;

    private Cart cart;
    private final UUID bookId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    @BeforeEach
    void setUp() {
        cart = cartRepository.saveAndFlush(new Cart(UUID.randomUUID()));
    }

    @Test
    void save_whenSameBookAddedTwiceToCart_throwsDataIntegrityViolation() {
        // uk_cart_items_cart_book: FR-10's "one line per book" and LC-13's
        // cross-device sum are enforced at rest — a second add of the same book
        // can never create a duplicate line, only this violation, which OR-06
        // handles by summing into the existing row.
        cartItemRepository.saveAndFlush(new CartItem(cart, bookId, 2));

        assertThatThrownBy(() -> cartItemRepository.saveAndFlush(new CartItem(cart, bookId, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenSameBookSitsInTwoDifferentCarts_persistsBothLines() {
        // The uniqueness is composite (cart_id, book_id), not global: two users'
        // carts may hold the same book independently — the per-user intent is the
        // whole model (LC-17, ADR-004).
        Cart otherCart = cartRepository.saveAndFlush(new Cart(UUID.randomUUID()));

        cartItemRepository.saveAndFlush(new CartItem(cart, bookId, 1));
        CartItem secondLine = cartItemRepository.saveAndFlush(new CartItem(otherCart, bookId, 3));

        assertThat(secondLine.getId()).isNotNull();
    }

    @Test
    void save_whenBookIdNamesNoCatalogRow_persistsTheLine() {
        // C18 made this state legal on purpose: book_id has no FK, so the database
        // cannot know whether the book exists. A line for a vanished book is a
        // representable fact (the V2-less, always-on MVP just never deletes), and
        // the read side flags it available=false (LC-14) — not the schema's job.
        CartItem line = cartItemRepository.saveAndFlush(new CartItem(cart, UUID.randomUUID(), 1));

        assertThat(cartItemRepository.findById(line.getId())).isPresent();
    }

    @Test
    void save_whenCartIdNamesNoCart_throwsDataIntegrityViolation() {
        // fk_cart_items_cart is *intra*-service, so unlike book_id it is expressible
        // and enforced: no line can outlive or outrun its cart (ADR-004).
        Cart phantom = entityManager.getEntityManager().getReference(Cart.class, UUID.randomUUID());

        assertThatThrownBy(() -> cartItemRepository.saveAndFlush(new CartItem(phantom, bookId, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest(name = "quantity_belowOne: {0} is unrepresentable at rest (FR-12, ADR-004)")
    @ValueSource(ints = {0, -1})
    void save_whenQuantityBelowOne_throwsDataIntegrityViolation(int quantity) {
        // ck_cart_items_quantity: the forbidden state is unrepresentable, not merely
        // unexpected — FR-12's "set quantity to 0 removes the line" must therefore be
        // a delete (OR-09), because an update to 0 lands on this violation first.
        assertThatThrownBy(() -> cartItemRepository.saveAndFlush(new CartItem(cart, bookId, quantity)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void update_whenQuantitySetToZero_throwsDataIntegrityViolation() {
        // The check guards UPDATEs too, via the one mutable field the entities expose.
        CartItem line = cartItemRepository.saveAndFlush(new CartItem(cart, bookId, 2));
        line.setQuantity(0);

        assertThatThrownBy(() -> cartItemRepository.flush())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void delete_whenCartRowIsRemoved_databaseCascadesToItsLines() {
        // ON DELETE CASCADE is a database guarantee, so it is proven through
        // CartSqlProbe, bypassing the JPA session: a raw DELETE on carts must take
        // the lines with it (Hibernate would delete them itself and the DB cascade
        // would never run).
        cartItemRepository.saveAndFlush(new CartItem(cart, bookId, 1));
        entityManager.flush();

        CartSqlProbe probe = new CartSqlProbe(entityManager.getEntityManager());
        int removed = probe.deleteCartRow(cart.getId());
        entityManager.flush();
        entityManager.clear();

        long linesLeft = probe.countCartItemsOf(cart.getId());

        assertThat(removed).isEqualTo(1);
        assertThat(linesLeft).isZero();
    }

    @Test
    void findByCartId_whenTwoCartsHoldLines_returnsOnlyThatCartsLines() {
        // The dominant FR-11 read rides the uk_cart_items_cart_book leftmost prefix:
        // one cart's lines, and never another user's (LC-17).
        Cart otherCart = cartRepository.saveAndFlush(new Cart(UUID.randomUUID()));
        cartItemRepository.saveAndFlush(new CartItem(cart, bookId, 2));
        cartItemRepository.saveAndFlush(new CartItem(cart, UUID.randomUUID(), 1));
        cartItemRepository.saveAndFlush(new CartItem(otherCart, bookId, 1));

        List<CartItem> lines = cartItemRepository.findByCartId(cart.getId());

        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line ->
                assertThat(line.getCart().getId()).isEqualTo(cart.getId()));
    }

    @Test
    void findByCartIdAndBookId_whenLineExists_returnsIt() {
        CartItem saved = cartItemRepository.saveAndFlush(new CartItem(cart, bookId, 4));

        entityManager.clear();
        Optional<CartItem> found = cartItemRepository.findByCartIdAndBookId(cart.getId(), bookId);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getId()).isEqualTo(saved.getId());
        assertThat(found.orElseThrow().getQuantity()).isEqualTo(4);
        assertThat(found.orElseThrow().getCreatedAt()).isNotNull();
        assertThat(found.orElseThrow().getUpdatedAt()).isNotNull();
    }

    @Test
    void findByCartIdAndBookId_whenBookNotInCart_returnsEmpty() {
        // LC-13's branch selector: no existing line means the add becomes an insert,
        // not a violation.
        cartItemRepository.saveAndFlush(new CartItem(cart, bookId, 1));

        assertThat(cartItemRepository.findByCartIdAndBookId(cart.getId(), UUID.randomUUID())).isEmpty();
    }
}
