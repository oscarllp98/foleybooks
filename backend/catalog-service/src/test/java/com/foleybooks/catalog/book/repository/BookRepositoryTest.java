package com.foleybooks.catalog.book.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.category.domain.Category;
import com.foleybooks.catalog.category.repository.CategoryRepository;
import com.foleybooks.testsupport.SqlRecordingInspector;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL via Testcontainers (C15); Flyway applies V1+V2 and
 * {@code ddl-auto=validate} (from application.yml) proves the entities match
 * the migrated schema — including {@code price} precision/scale (C16, C17,
 * plan §6.3, ADR-003). Constraint-probe fixtures stay deliberately clear of
 * the V2 seed (its category names and ISBNs), so every insert below is a
 * genuinely new row; the seed itself is asserted by
 * {@link #seedBooks_whenMigrationsApplied_shipTwelveRealBooksAcrossEveryAvailabilityBucket}
 * (FR-14, mirroring auth's demo-user test).
 *
 * <p>CA-08's filter (FR-08, FR-09, D-06, LC-10) is exercised against the seed
 * itself rather than synthetic fixtures: {@code BookSpecifications} is the
 * single owner of the matching rules — PostgreSQL {@code ILIKE}, the
 * metacharacter escaping, and the {@code AND} composition — and only a real
 * database can prove those actually do what the specification says. The seed
 * is deliberately stable and hand-checked (CA-04), so the exact result sets
 * below are pinned by title, not just by "some non-empty subset" — a search
 * that silently starts matching unrelated rows, or stops matching one it
 * should, fails here loudly. Trimming and the blank-means-no-filter decision
 * are the boundary's rule (C23), not this layer's, so they are proven in
 * {@code BookControllerTest} instead and every term passed to
 * {@code BookSpecifications.matching} below is already trimmed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "CATALOG_DB_PASSWORD=test-db-secret",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.foleybooks.testsupport.SqlRecordingInspector",
})
class BookRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID FICTION_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca01");
    private static final UUID TECHNOLOGY_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca02");

    @Autowired
    BookRepository bookRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    TestEntityManager entityManager;

    private Category fixture;

    @BeforeEach
    void setUp() {
        fixture = categoryRepository.saveAndFlush(new Category("Essays"));
    }

    private static Book book(Category category, String isbn, BigDecimal price, int stockQuantity) {
        return new Book(category, "Refactoring", "Martin Fowler", isbn, price, stockQuantity,
                "https://covers.openlibrary.org/b/isbn/9780134757599-L.jpg");
    }

    @Test
    void save_whenBookPersisted_roundTripsColumnsCategoryAndAuditWithStoredPriceScale() {
        Book saved = bookRepository.saveAndFlush(book(fixture, "9780134757599", new BigDecimal("31.9"), 12));

        entityManager.clear();
        Book found = bookRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getTitle()).isEqualTo("Refactoring");
        assertThat(found.getAuthor()).isEqualTo("Martin Fowler");
        assertThat(found.getIsbn()).isEqualTo("9780134757599");
        // D-08: NUMERIC(10,2) is the stored truth — 31.9 comes back as exactly 31.90.
        assertThat(found.getPrice()).isEqualByComparingTo("31.9");
        assertThat(found.getPrice().scale()).isEqualTo(2);
        assertThat(found.getStockQuantity()).isEqualTo(12);
        assertThat(found.getCoverUrl()).isEqualTo("https://covers.openlibrary.org/b/isbn/9780134757599-L.jpg");
        assertThat(found.getCategory().getId()).isEqualTo(fixture.getId());
        assertThat(found.getCategory().getName()).isEqualTo("Essays");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void seedBooks_whenMigrationsApplied_shipTwelveRealBooksAcrossEveryAvailabilityBucket() {
        // FR-14 / D-12 / ADR-003: the V2 seed is the whole demo catalog — 12 real
        // books in the seeded categories, each cover hotlinked from the row's own
        // ISBN (D-11), and stock spanning all three D-09 buckets including both
        // LOW/IN edges (5 and 6) so every availability state renders without
        // touching the database.
        List<Book> seeded = bookRepository.findAll();

        assertThat(seeded).hasSize(12);
        assertThat(seeded).allSatisfy(book -> {
            assertThat(book.getTitle()).isNotBlank();
            assertThat(book.getAuthor()).isNotBlank();
            assertThat(book.getCoverUrl())
                    .isEqualTo("https://covers.openlibrary.org/b/isbn/" + book.getIsbn() + "-L.jpg");
            assertThat(book.getCategory().getName()).isIn("Fiction", "Technology", "Business");
            // D-08: NUMERIC(10,2) keeps every seed price exact at scale 2.
            assertThat(book.getPrice().scale()).isEqualTo(2);
            assertThat(book.getCreatedAt()).isNotNull();
            assertThat(book.getUpdatedAt()).isNotNull();
        });

        // D-09 buckets at rest: OUT_OF_STOCK 0, LOW_STOCK 1–5, IN_STOCK >5.
        assertThat(seeded).filteredOn(book -> book.getStockQuantity() == 0).isNotEmpty();
        assertThat(seeded).filteredOn(book -> book.getStockQuantity() >= 1 && book.getStockQuantity() <= 5)
                .anyMatch(book -> book.getStockQuantity() == 5);
        assertThat(seeded).anyMatch(book -> book.getStockQuantity() == 6);

        // The plan §2 contract example is seed data: Clean Code, EUR 31.99, IN_STOCK.
        Book cleanCode = seeded.stream()
                .filter(book -> "9780132350884".equals(book.getIsbn()))
                .findFirst()
                .orElseThrow();
        assertThat(cleanCode.getId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-00000000cb06"));
        assertThat(cleanCode.getTitle()).isEqualTo("Clean Code");
        assertThat(cleanCode.getAuthor()).isEqualTo("Robert C. Martin");
        assertThat(cleanCode.getPrice()).isEqualByComparingTo("31.99");
        assertThat(cleanCode.getStockQuantity()).isGreaterThan(5);
    }

    @Test
    void findAll_whenPagedWithDefaultTitleSort_windowsSeedCatalogStably() {
        // CA-06's default sort is {@code Sort.by(ASC, "title")} on the entity
        // property — Spring Data raises PropertyReferenceException for a name
        // that does not resolve, so this call proves FR-06's sort path exists
        // on the real mapping, and the windowing proves the Page metadata the
        // envelope publishes is the repository's own.
        Sort titleAsc = Sort.by(Sort.Direction.ASC, "title");

        Page<Book> firstWindow = bookRepository.findAll(PageRequest.of(0, 5, titleAsc));
        List<String> fullOrder = bookRepository.findAll(PageRequest.of(0, 12, titleAsc))
                .getContent().stream().map(Book::getTitle).toList();

        assertThat(firstWindow.getContent()).extracting(Book::getTitle)
                .containsExactlyElementsOf(fullOrder.subList(0, 5));
        assertThat(firstWindow.getTotalElements()).isEqualTo(12);
        assertThat(firstWindow.getTotalPages()).isEqualTo(3);
        assertThat(firstWindow.getNumber()).isZero();
        assertThat(firstWindow.getSize()).isEqualTo(5);
    }

    @Test
    void findAll_whenPagedWithPriceSort_ordersSeedCatalogByPriceBothDirections() {
        // CA-07 whitelists "price" as a sortable field (FR-06). The query is built
        // from the entity property, so this proves the whitelisted name actually
        // resolves on the real mapping (Spring Data would raise PropertyReferenceException
        // otherwise) and returns a genuinely ordered window in both directions.
        List<BigDecimal> ascending = bookRepository
                .findAll(PageRequest.of(0, 12, Sort.by(Sort.Direction.ASC, "price")))
                .getContent().stream().map(Book::getPrice).toList();
        List<BigDecimal> descending = bookRepository
                .findAll(PageRequest.of(0, 12, Sort.by(Sort.Direction.DESC, "price")))
                .getContent().stream().map(Book::getPrice).toList();

        assertThat(ascending).isSorted().hasSize(12);
        assertThat(descending).isSortedAccordingTo(Comparator.reverseOrder()).hasSize(12);
    }

    @Test
    void findAll_whenSearchingByTitleFragment_ignoresCaseAndMatchesSubstring() {
        // FR-08 / D-06: partial, case-insensitive over title. "the" is a
        // deliberate substring probe — it must find every seed title containing
        // "The" regardless of casing, and nothing else; no title or author in
        // the seed contains "the" as a stray interior substring outside those
        // three, so the expected set is exact, not "at least".
        List<Book> matches = bookRepository.findAll(BookSpecifications.matching("the", null));

        assertThat(matches).extracting(Book::getTitle).containsExactlyInAnyOrder(
                "The Great Gatsby", "The Catcher in the Rye", "The Pragmatic Programmer");
    }

    @ParameterizedTest(name = "search_caseInsensitive: \"{0}\" finds Clean Code (FR-08, D-06)")
    @ValueSource(strings = {"clean", "CLEAN", "Clean"})
    void findAll_whenSearchTermCasingVaries_ignoresCaseAndMatchesSubstring(String term) {
        // FR-08, D-06 verbatim: PostgreSQL's own ILIKE operator, not a
        // hand-rolled LOWER(col) LIKE LOWER(term) workaround — its
        // case-insensitive, partial-match semantics are proven here against
        // every casing of the same word.
        List<Book> matches = bookRepository.findAll(BookSpecifications.matching(term, null));

        assertThat(matches).extracting(Book::getTitle).containsExactly("Clean Code");
    }

    @Test
    void findAll_whenSearchMatchesNothing_returnsEmptyResultNotAnError() {
        // FR-08, LC-10: no match is a browse state, not a failure.
        List<Book> matches = bookRepository.findAll(BookSpecifications.matching("zzzznotabook", null));

        assertThat(matches).isEmpty();
    }

    @ParameterizedTest(name = "search_wildcardsAreLiteral: \"{0}\" cannot match everything (LC-10)")
    @ValueSource(strings = {"%", "_", "\\%"})
    void findAll_whenSearchContainsLikeMetacharacters_treatsThemAsLiteralText(String term) {
        // LC-10's "special characters ... literal handling": unescaped, "%" or
        // "_" would match every row in the catalog; escaped, they are just text,
        // and no seed title or author contains either, so the answer is empty.
        List<Book> matches = bookRepository.findAll(BookSpecifications.matching(term, null));

        assertThat(matches).isEmpty();
    }

    @Test
    void findAll_whenSearchingByAuthorFragment_findsBooksThroughTheAuthorColumnToo() {
        // FR-08's "title and author" is an OR, not title-only: "Martin" appears
        // in no seed title, only in two authors (Robert C. Martin, Martin
        // Kleppmann), so a hit here proves the second disjunct is live.
        List<Book> matches = bookRepository.findAll(BookSpecifications.matching("martin", null));

        assertThat(matches).extracting(Book::getTitle).containsExactlyInAnyOrder(
                "Clean Code", "Designing Data-Intensive Applications");
    }

    @Test
    void findAll_whenFilteredByCategory_returnsExactlyThatCategorysBooks() {
        // FR-09: browsing a category shows only its books. The V2 seed puts four
        // titles in Technology and eight across the other two categories, so a
        // filter leak would show up as a size mismatch, not a subtle ordering bug.
        List<Book> technology = bookRepository.findAll(BookSpecifications.matching(null, TECHNOLOGY_ID));

        assertThat(technology).extracting(Book::getTitle).containsExactlyInAnyOrder(
                "Clean Code", "The Pragmatic Programmer",
                "Designing Data-Intensive Applications", "Introduction to Algorithms");
        assertThat(technology).allSatisfy(book -> assertThat(book.getCategory().getId()).isEqualTo(TECHNOLOGY_ID));
    }

    @Test
    void findAll_whenSearchGiven_compilesToTheNativeIlikeOperator() {
        // D-06 names PostgreSQL's ILIKE as the operator. Hibernate's plain
        // CriteriaBuilder.like has no case-insensitive mode at all, so a
        // silent regression to the portable "lower(col) like" workaround would
        // still return identical results — every other test here would stay
        // green — and only the statement's own shape catches it. Asserted on
        // the real generated SQL, exactly like the category-filter test above.
        SqlRecordingInspector.clear();

        bookRepository.findAll(BookSpecifications.matching("the", null));

        assertThat(SqlRecordingInspector.executed())
                .anySatisfy(sql -> assertThat(sql).containsPattern("(?i)ilike"));
    }

    @Test
    void findAll_whenFilteredByCategory_comparesTheFkColumnWithoutJoiningCategories() {
        // Proves a claim the rest of the suite only trusts: BookSpecifications
        // compares `Book.category`'s id, and ADR-003's index decision assumes
        // Hibernate turns that into a plain `books.category_id = ?` predicate
        // rather than opening a join to `categories` — a join would be correct
        // SQL but would quietly stop being the one V1's `ix_books_category`
        // exists to serve. Asserted on the actual statement, not the result,
        // since the result is identical either way.
        SqlRecordingInspector.clear();

        bookRepository.findAll(BookSpecifications.matching(null, TECHNOLOGY_ID));

        assertThat(SqlRecordingInspector.executed())
                .anySatisfy(sql -> assertThat(sql)
                        .contains("category_id")
                        .doesNotContainPattern("(?i)join\\s+categories")
                        .doesNotContainPattern("(?i)from\\s+categories"));
    }

    @Test
    void findAll_whenSearchAndCategoryAreCombined_appliesBothAsAND() {
        // FR-08 / D-06 "composable": "the" matches three seed titles spanning two
        // categories; narrowed to Fiction it must drop the single Technology
        // match rather than, say, ignoring the category filter or the search
        // term.
        List<Book> fictionMatches = bookRepository.findAll(BookSpecifications.matching("the", FICTION_ID));
        List<Book> technologyMatches = bookRepository.findAll(BookSpecifications.matching("the", TECHNOLOGY_ID));

        assertThat(fictionMatches).extracting(Book::getTitle).containsExactlyInAnyOrder(
                "The Great Gatsby", "The Catcher in the Rye");
        assertThat(technologyMatches).extracting(Book::getTitle).containsExactly("The Pragmatic Programmer");
    }

    @Test
    void findAll_whenSearchLooksLikeSqlInjection_isBoundAsPlainTextAndFindsNothing() {
        // D-06 "parameterized", LC-28: the same noise strings the sort
        // whitelist rejects are, for search, simply text that appears in no
        // book — they reach the database as a bound parameter, never as SQL,
        // and never as an error page (LC-10).
        List<Book> matches = bookRepository.findAll(BookSpecifications.matching("' OR 1=1 --", null));

        assertThat(matches).isEmpty();
    }

    @Test
    void findAll_whenCategoryHasNoBooks_returnsEmptyResultNotAnError() {
        // FR-09 / LC-31 at the read side: a category that exists but holds no
        // book is browsable and empty, exactly like a search with no match —
        // the same "empty state, never an error" rule, reached through the
        // filter rather than through an unseeded listing.
        Category unshelved = categoryRepository.saveAndFlush(new Category("Unshelved"));

        List<Book> matches = bookRepository.findAll(BookSpecifications.matching(null, unshelved.getId()));

        assertThat(matches).isEmpty();
    }

    @Test
    void findAll_whenCategoryDoesNotExist_returnsEmptyResultNotAnError() {
        // A well-formed id naming no category at all is not a 404 and not a
        // query failure: the FK column simply matches zero rows.
        List<Book> matches = bookRepository.findAll(BookSpecifications.matching(null, UUID.randomUUID()));

        assertThat(matches).isEmpty();
    }

    @Test
    void findAll_whenFilteredAndPagedAndSorted_windowsMatchesWithTheGivenSortAndTotal() {
        // FR-08's "results paginated and sortable (same rules as FR-06)": the
        // filter composes with PageRequest's own sort and window, and the
        // Page metadata reflects the filtered total, not the whole catalog.
        Page<Book> matches = bookRepository.findAll(
                BookSpecifications.matching("the", null),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "title")));

        assertThat(matches.getTotalElements()).isEqualTo(3);
        assertThat(matches.getTotalPages()).isEqualTo(2);
        assertThat(matches.getContent()).extracting(Book::getTitle)
                .containsExactly("The Catcher in the Rye", "The Great Gatsby");
    }

    @Test
    void findAllById_whenSomeIdsMatchNoBook_returnsOnlyTheExistingRows() {
        // CA-11/D-10's subset semantics, only provable against real SQL (the
        // service unit test mocks this repository): findAllById is one
        // `WHERE id IN (...)` that answers the rows present and silently omits
        // the ids that name none — exactly the LC-14 "absent line" the cart
        // enrichment consumes. Seeded ids resolve to rows; a random UUID does not.
        // The result is asserted as a set because IN-order is not contractual, and
        // the empty-collection case is deliberately NOT probed here — Spring Data
        // leaves findAllById over an empty iterable undefined, which is the exact
        // reason BookServiceImpl.getBooks short-circuits it before reaching the
        // repository (proven in the service unit test), so the repository contract
        // legitimately starts at one id.
        UUID cleanCodeId = UUID.fromString("00000000-0000-0000-0000-00000000cb06");
        UUID gatsbyId = UUID.fromString("00000000-0000-0000-0000-00000000cb03");
        UUID vanishedId = UUID.randomUUID();

        List<Book> found = bookRepository.findAllById(List.of(cleanCodeId, vanishedId, gatsbyId));

        assertThat(found).extracting(Book::getId)
                .containsExactlyInAnyOrder(cleanCodeId, gatsbyId);
    }

    @Test
    void save_whenIsbnAlreadyExists_throwsDataIntegrityViolation() {
        // uk_books_isbn: a duplicate ISBN would be two indistinguishable entries
        // of one real book — the anchor of the D-11 cover hotlinking (ADR-003).
        bookRepository.saveAndFlush(book(fixture, "9780134757599", new BigDecimal("31.99"), 5));

        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(fixture, "9780134757599", new BigDecimal("19.99"), 3)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenCategoryMissing_throwsDataIntegrityViolation() {
        // category_id NOT NULL: FR-09's "exactly one category" is a database
        // fact, not an application convention (ADR-003).
        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(null, "9780000000000", new BigDecimal("9.99"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenCategoryIdUnknown_throwsDataIntegrityViolation() {
        // fk_books_category: a book can never reference a category that does
        // not exist — no silent orphans at rest.
        Category phantom = entityManager.getEntityManager().getReference(Category.class, UUID.randomUUID());

        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(phantom, "9780000000001", new BigDecimal("9.99"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenPriceNegative_throwsDataIntegrityViolation() {
        // ck_books_price rejects negatives at rest.
        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(fixture, "9780000000002", new BigDecimal("-0.01"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenPriceZero_persistsFreeGiveawayTitle() {
        // ck_books_price allows 0.00: a free giveaway stays representable (ADR-003).
        Book saved = bookRepository.saveAndFlush(book(fixture, "9780000000003", BigDecimal.ZERO, 1));

        assertThat(bookRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void save_whenStockQuantityNegative_throwsDataIntegrityViolation() {
        // ck_books_stock: stock is never below zero at rest; availability (D-09)
        // derives from it, so there is only one number to be right about.
        assertThatThrownBy(() -> bookRepository.saveAndFlush(
                book(fixture, "9780000000004", new BigDecimal("9.99"), -1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenCoverUrlMissing_throwsDataIntegrityViolation() {
        // cover_url NOT NULL: the V2 seed (CA-04) cannot silently ship a
        // coverless book — this constraint catches it (ADR-003).
        assertThatThrownBy(() -> bookRepository.saveAndFlush(new Book(
                fixture, "Refactoring", "Martin Fowler", "9780000000005",
                new BigDecimal("9.99"), 1, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
