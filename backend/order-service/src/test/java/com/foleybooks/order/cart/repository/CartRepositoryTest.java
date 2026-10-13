package com.foleybooks.order.cart.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.order.cart.domain.Cart;
import com.foleybooks.testsupport.CartSqlProbe;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
 * Real PostgreSQL via Testcontainers (C15) for the {@code carts} half of order_db
 * (OR-04, ADR-004): {@code uk_carts_user} is LC-17's one-cart-per-user invariant and
 * the race anchor of OR-06's find-or-create; the audit columns' DB defaults keep
 * hand-written SQL valid, and every column of ADR-004's sketch is proven matchable
 * by {@code ddl-auto=validate} through the simple fact that this context boots
 * against the migrated schema (C16, C17). order_db has no V2 seed by design — a
 * cart is private user state, so FR-14's demo accounts start with the empty state
 * asserted below.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = "ORDER_DB_PASSWORD=test-db-secret")
class CartRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    CartRepository cartRepository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void save_whenUserAlreadyHasCart_throwsDataIntegrityViolation() {
        // uk_carts_user: one cart per user, shared by every session and device, is a
        // database invariant (LC-17, ADR-004) — the loser of a concurrent
        // find-or-create lands on exactly this violation and re-reads via findByUserId.
        cartRepository.saveAndFlush(new Cart(UUID.randomUUID()));

        UUID owner = UUID.randomUUID();
        cartRepository.saveAndFlush(new Cart(owner));

        assertThatThrownBy(() -> cartRepository.saveAndFlush(new Cart(owner)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByUserId_whenOwnerHasCart_returnsIt() {
        UUID owner = UUID.randomUUID();
        Cart saved = cartRepository.saveAndFlush(new Cart(owner));

        entityManager.clear();
        Optional<Cart> found = cartRepository.findByUserId(owner);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getId()).isEqualTo(saved.getId());
        assertThat(found.orElseThrow().getUserId()).isEqualTo(owner);
        assertThat(found.orElseThrow().getCreatedAt()).isNotNull();
        assertThat(found.orElseThrow().getUpdatedAt()).isNotNull();
    }

    @Test
    void findByUserId_whenUserNeverOpenedACart_returnsEmpty() {
        // FR-11's empty state at rest: no seed rows exist in order_db (ADR-004), so
        // a fresh user — like the FR-14 demo accounts — simply has no cart yet,
        // which the service turns into an empty cart, never a 404.
        assertThat(cartRepository.findByUserId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void insert_whenNativeSqlProvidesOnlyUserId_appliesIdAndAuditDefaults() {
        // ADR-004: id, created_at and updated_at all have DB defaults, so
        // hand-written SQL stays valid — proven by inserting through CartSqlProbe,
        // outside the JPA lifecycle, and reading the row back via the entity mapping.
        UUID owner = UUID.randomUUID();
        new CartSqlProbe(entityManager.getEntityManager()).insertCartWithOnlyUserId(owner);
        entityManager.flush();
        entityManager.clear();

        Cart found = cartRepository.findByUserId(owner).orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }
}
