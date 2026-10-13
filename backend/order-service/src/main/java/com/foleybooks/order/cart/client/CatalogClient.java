package com.foleybooks.order.cart.client;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The east-west book lookup against catalog-service (AGENTS.md §4, ADR-005): a
 * transport adapter and nothing more (C7, C8) — no stock math, no flagging, no
 * totals here; those are {@code CartService}'s rules. The target is the
 * <b>Eureka-registered service name</b>, resolved to a live instance by Spring
 * Cloud LoadBalancer (ADR-006 registry, ADR-005 addressing): never a host:port
 * literal, never the gateway's {@code lb://} indirection, never
 * {@code http://localhost} — only the gateway is north-south (C27).
 *
 * <p>The paths are gateway-identical: {@code /api/v1/books/**} is routed to
 * {@code lb://catalog-service} with no prefix rewrite and {@code BookController}
 * is mapped at {@code /api/v1/books}, so the absolute path order-service sends
 * east-west is the same one a browser sends through the gateway. The only
 * difference is the authority.
 *
 * <p>The two operations mirror ADR-009's asymmetry between the singular and the
 * keyed read, and the two flows that need them (ADR-005): {@link #findBook} is
 * FR-10/LC-12's and FR-12/LC-16's single named lookup — a well-formed id that
 * names no book arrives as {@code 404} with catalog's {@code book-not-found}
 * URN and is decoded by {@link CatalogErrorDecoder} into
 * {@link BookNotFoundException}; any other 404 stays a transport failure.
 * {@link #batchBooks} is FR-11's single enrichment call (D-10) — a gone book is
 * <b>absent from the array, never a 404</b> (CA-11), and the repeated
 * {@code ids=} form is exactly what the catalog's {@code List<UUID>} binder
 * consumes. Batch responses carry no contractual order: callers key by
 * {@code id} (ADR-009).
 *
 * <p>No token relay exists on this hop (ADR-005): catalog GETs are anonymous
 * (C22), the caller's bearer token is validated once at this service's own
 * security boundary (ADR-008) and never forwarded, so identity stays a
 * north-south concept. Codec tolerance and the 404 mapping live in
 * {@code config/FeignConfig} / {@link CatalogErrorDecoder}; a catalog that is
 * down or 5xx-ing stays a transport failure for {@code GlobalExceptionHandler}
 * (503 {@code catalog-unavailable}) — unavailability is never laundered into
 * absence (ADR-005, NFR-07).
 */
@FeignClient(name = "catalog-service")
public interface CatalogClient {

    @GetMapping("/api/v1/books/{id}")
    BookDto findBook(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/books/batch")
    List<BookDto> batchBooks(@RequestParam("ids") Collection<UUID> ids);
}
