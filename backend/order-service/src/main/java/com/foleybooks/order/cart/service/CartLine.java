package com.foleybooks.order.cart.service;

import java.util.UUID;

/**
 * The outcome of one cart write (FR-10): the book's single line now holds
 * {@code quantity} units — the requested quantity on a fresh line, the capped
 * sum on a re-add (LC-13). A service-layer record on purpose: the
 * {@code CartItem} entity never leaves {@code CartServiceImpl} (AGENTS.md §4,
 * C9 — entities cross neither HTTP boundaries nor the service layer), and the
 * wire projection with live product data is the read half's job (OR-07's
 * {@code CartResponse}, D-08: nothing about price or stock is cached here).
 */
public record CartLine(UUID bookId, int quantity) {
}
