package com.foleybooks.catalog.common;

import java.util.List;

/**
 * The service-wide pagination envelope (AGENTS.md §6, D-07): {@code content}
 * plus the {@code page} metadata — the JSON shape every list endpoint answers
 * with ({@code GET /books} from CA-06, {@code GET /categories} from CA-10),
 * so one contract governs all paged reads. Elements are response records
 * only; entities never reach this list (C9).
 *
 * <p>Deliberately ignorant of Spring Data: the service copies the repository
 * page's four numbers into {@link PageMeta} itself, so the wire vocabulary in
 * {@code common} stays transport-only and the persistence seam ends at the
 * service boundary (C7). An empty page yields {@code totalElements: 0} and an
 * empty list — an empty state, never an error (FR-06).
 */
public record PageEnvelope<T>(List<T> content, PageMeta page) {
}
