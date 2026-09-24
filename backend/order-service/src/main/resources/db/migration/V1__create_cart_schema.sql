-- OR-04 / ADR-004: order_db schema — carts, cart_items.
-- The cart stores *intent* only: (user, book_id, quantity). Prices, titles, covers
-- and stock belong to catalog_db and are re-read on every access (C18, ADR-005);
-- totals are computed server-side, never persisted (D-08). book_id and user_id are
-- opaque UUIDs with no FK — cross-service references are constitutionally illegal
-- (C18), and a line whose book vanished is a representable state LC-14 needs.
-- uk_cart_items_cart_book's leftmost prefix doubles as the index for the dominant
-- "all lines of this cart" read, so no separate ix_cart_items_cart exists (ADR-004).
-- No V2 seed: a cart is private user state; FR-14's demo accounts start empty.

CREATE TABLE carts (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_carts_user UNIQUE (user_id)
);

CREATE TABLE cart_items (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id    UUID        NOT NULL,
    book_id    UUID        NOT NULL,
    quantity   INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_cart_items_cart      FOREIGN KEY (cart_id) REFERENCES carts (id)
                                       ON DELETE CASCADE,
    CONSTRAINT uk_cart_items_cart_book UNIQUE (cart_id, book_id),
    CONSTRAINT ck_cart_items_quantity  CHECK (quantity >= 1)
);
