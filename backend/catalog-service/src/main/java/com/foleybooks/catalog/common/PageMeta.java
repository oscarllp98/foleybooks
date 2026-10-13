package com.foleybooks.catalog.common;

/**
 * The {@code page} object of the pagination envelope (AGENTS.md §6, D-07):
 * total rows, total windows, the window actually served and its size. The
 * four fields are the contract the frontend {@code Pagination} component
 * renders (FE-11) — the served {@code number} may differ from the requested
 * one because out-of-range values are clamped, not rejected (LC-11), so a
 * client learns which page it actually got.
 *
 * <p>{@code totalElements} is a long because it counts rows, not a page; the
 * rest are ints by construction. This is a transport record, never a query
 * object — the clamping rules live in the service layer (C8).
 */
public record PageMeta(long totalElements, int totalPages, int number, int size) {
}
