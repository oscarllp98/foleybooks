package com.foleybooks.order.cart.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One book as consumed from catalog-service over the east-west hop (ADR-005): the
 * <em>consumer-owned subset</em> of the nine-field {@code BookResponse} ADR-009
 * publishes — {@code isbn}, {@code availability} and the nested {@code category}
 * are deliberately dropped because neither a cart line nor the plan §2 cart JSON
 * shows them. The record is re-declared here instead of imported because
 * AGENTS.md §4 forbids a shared library module between services.
 *
 * <p>These six fields are the whole coupling between the two services: catalog may
 * add {@code BookResponse} fields freely (the Feign decoder tolerates unknown
 * properties — ADR-005, {@code config/FeignConfig}), but renaming or removing any
 * field below is a breaking change for cart enrichment, caught here at
 * deserialization. This is a transport type: it never crosses an HTTP boundary of
 * its own (C9) — {@code CartService} projects it into the response records.
 *
 * <p>{@code price} stays an exact EUR decimal at scale 2 and {@code stockQuantity}
 * an {@code int}, the same types the catalog publishes (D-08, ADR-009); money is
 * never widened to {@code double} anywhere across the hop (NFR-07).
 *
 * <p>{@code stockQuantity} stays a primitive: a response that omits it would
 * coerce to {@code 0} rather than fail, but ADR-009 pins the field on every
 * published {@code BookResponse}, so a null there is a catalog contract breach,
 * not a state to model — and a breach belongs on the decode-failure path, not in
 * a boxed field the service would have to null-guard forever (C1).
 */
public record BookDto(
        UUID id,
        String title,
        String author,
        String coverUrl,
        BigDecimal price,
        int stockQuantity) {
}
