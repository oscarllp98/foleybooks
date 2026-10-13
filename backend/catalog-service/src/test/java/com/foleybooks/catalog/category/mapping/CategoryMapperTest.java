package com.foleybooks.catalog.category.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.foleybooks.catalog.category.api.CategoryResponse;
import com.foleybooks.catalog.category.domain.Category;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The FR-09 read shape (plan §2): a category is {@code { id, name }} and nothing
 * else, and this mapper is its single owner (ADR-009) — {@link BookMapperTest}
 * proves the book payload reaches it instead of restating it. The audit columns
 * are the interesting failure mode, and they are held out at build time rather
 * than by assertion: {@code unmappedTargetPolicy = ERROR} makes a target
 * component that the entity cannot source a compile failure, so a future
 * {@code createdAt} can only appear after someone widens the record and unwidens
 * the policy on purpose (C9).
 */
class CategoryMapperTest {

    private static final UUID FICTION_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca01");

    private final CategoryMapper mapper = new CategoryMapperImpl();

    @Test
    void toResponse_whenSeedCategoryMapped_projectsIdAndNameOf() {
        Category fiction = new Category("Fiction");
        ReflectionTestUtils.setField(fiction, "id", FICTION_ID);

        CategoryResponse response = mapper.toResponse(fiction);

        assertThat(response).isEqualTo(new CategoryResponse(FICTION_ID, "Fiction"));
    }

    @Test
    void toResponseList_whenSeedPageMapped_projectsEveryElementThroughToResponse() {
        // CA-10 added the list overload (ADR-009: "list/page mapping is added
        // by the task that consumes it"). It must be the loop over the single
        // entity mapper it claims to be: same shape at list cardinality, order
        // preserved, empty page stays empty, never an error (LC-31).
        Category fiction = new Category("Fiction");
        ReflectionTestUtils.setField(fiction, "id", FICTION_ID);
        Category technology = new Category("Technology");
        ReflectionTestUtils.setField(technology, "id",
                UUID.fromString("00000000-0000-0000-0000-00000000ca02"));

        List<CategoryResponse> responses = mapper.toResponseList(List.of(fiction, technology));

        assertThat(responses).containsExactly(
                new CategoryResponse(FICTION_ID, "Fiction"),
                new CategoryResponse(UUID.fromString("00000000-0000-0000-0000-00000000ca02"), "Technology"));
        assertThat(mapper.toResponseList(List.of())).isEmpty();
    }
}
