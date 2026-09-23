package com.foleybooks.catalog.category.service;

import com.foleybooks.catalog.category.api.CategoryResponse;
import com.foleybooks.catalog.category.domain.Category;
import com.foleybooks.catalog.category.mapping.CategoryMapper;
import com.foleybooks.catalog.category.repository.CategoryRepository;
import com.foleybooks.catalog.common.PageEnvelope;
import com.foleybooks.catalog.common.PageMeta;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The FR-09 category listing (CA-10), owned here exactly where C8 wants it —
 * never in the controller, never in the repository. The paging rules are
 * {@link com.foleybooks.catalog.book.service.BookServiceImpl}'s, reused
 * deliberately rather than reinvented: AGENTS.md §6 and LC-11 are written about
 * pagination in general, so a second list endpoint gets the same default size
 * 20, the same max 100, and the same clamp-decided-after-the-first-fetch rule
 * that turns a past-the-end request into the last real page (or page 0 of an
 * empty table) instead of a fabricated empty window. The one thing categories
 * get their own is the sort: name ascending, fixed. FR-09 asks for no sort
 * selection and FR-06's whitelist (D-06) is about title/price — a query whose
 * rows are single short names does not need a client-chosen order, so the
 * parameter is simply absent, not rejected.
 *
 * <p>The empty state (FR-09, LC-31) needs no special handling, and that is the
 * point: an empty {@code categories} table is a {@code Page} with
 * {@code totalElements: 0} and no content, which the shared envelope renders as
 * a 200 with {@code content: []} — the empty list is a state the frontend shows,
 * never an error the service throws.
 *
 * <p>Malformed non-numeric parameters never reach this class: the MVC binder
 * rejects them with a 400 at the boundary (LC-28, D-15). The projection goes
 * through {@link CategoryMapper#toResponseList} (ADR-009: the mapper owns the
 * shape at every cardinality) inside the read-only transaction, and the
 * {@link PageMeta} is assembled here — the wire records in {@code common}
 * never learn Spring Data exists (C7). No entity escapes (C9).
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    /**
     * FR-09's only ordering: name ascending, applied to every fetch. The seed
     * has three rows and the unique-name constraint (ADR-003) makes name the
     * natural display order; no client input reaches this constant.
     */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    public CategoryServiceImpl(CategoryRepository categoryRepository, CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageEnvelope<CategoryResponse> listCategories(int page, int size) {
        int effectiveSize = clampSize(size);
        int requestedPage = Math.max(page, 0);
        Page<Category> categories = categoryRepository.findAll(PageRequest.of(requestedPage, effectiveSize, DEFAULT_SORT));
        if (requestedPage > 0 && !categories.hasContent()) {
            categories = categoryRepository.findAll(PageRequest.of(lastPageIndex(categories), effectiveSize, DEFAULT_SORT));
        }
        return new PageEnvelope<>(categoryMapper.toResponseList(categories.getContent()), pageMeta(categories));
    }

    /** LC-11: {@code size < 1 → 20}, {@code size > 100 → 100}; everything else is honored. */
    private static int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /**
     * Last page of whatever the repository just described. An empty table has
     * no pages at all, so its "last page" is page 0 — that is what keeps the
     * clamp of a past-the-end request from returning a phantom window (LC-31's
     * empty state included).
     */
    private static int lastPageIndex(Page<Category> categories) {
        return Math.max(categories.getTotalPages() - 1, 0);
    }

    private static PageMeta pageMeta(Page<Category> categories) {
        return new PageMeta(categories.getTotalElements(), categories.getTotalPages(),
                categories.getNumber(), categories.getSize());
    }
}
