package com.automation.filter;

import com.automation.postman.AuthDefinition;
import com.automation.postman.PostmanCollection;
import com.automation.postman.RequestSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilterValidatorTest {

    @Test
    void rejectsUnknownRequestNames() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null,
                List.of("Unknown request"),
                null, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsInvalidResponseColumnsKeys() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null, null,
                Map.of("Does not exist", List.of(new ColumnSpec("id", null))),
                null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsPartialApiKeyAuth() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null, null, null, null,
                new FilterAuthSpec(null, null, null, "secret", null),
                null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void acceptsValidFilter() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                "demo",
                List.of("List users"),
                Map.of("*", List.of(new ColumnSpec("id", null), new ColumnSpec("name", null))),
                "daily",
                new FilterAuthSpec("u", "p", null, null, null),
                Map.of("TEAM", "qa"),
                null, null, null);

        assertDoesNotThrow(() -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    // ── rowFilters validation ──────────────────────────────────────────────────────

    @Test
    void rejectsUnknownRowFilterKeys() {
        PostmanCollection collection = sampleCollection();
        RowFilterGroup group = new RowFilterGroup("AND",
                List.of(new RowFilterRule("id", "EQ", "1", null, null)));
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                Map.of("Unknown request", group),
                null, null);
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsUnknownDataShapeKeys() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null, null, null,
                Map.of("Unknown request", new DataShapeSpec(false, List.of(new SortSpec("id", false)), 10, 0))
        );

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsNegativeDataShapeLimit() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null, null, null,
                Map.of("*", new DataShapeSpec(false, List.of(new SortSpec("id", false)), -1, 0))
        );

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsUnsupportedAggregateFunction() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null, null, null,
                Map.of("*", new DataShapeSpec(
                        false,
                        List.of(),
                        null,
                        null,
                        List.of("id"),
                        List.of(new AggregateSpec("MEDIAN", "score", "median_score")),
                        null
                ))
        );

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsUnionWithUnknownSource() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null, null, null,
                null,
                List.of(new UnionSpec("Merged", List.of("List users", "Unknown"), false))
        );

        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

        @Test
    void rejectsInvalidRowFilterOperator() {
        PostmanCollection collection = sampleCollection();
        RowFilterGroup group = new RowFilterGroup("AND",
                List.of(new RowFilterRule("id", "BOGUS_OP", "1", null, null)));
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                Map.of("*", group),
                null, null);
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsUnknownDatePreset() {
        PostmanCollection collection = sampleCollection();
        RowFilterGroup group = new RowFilterGroup("AND",
                List.of(new RowFilterRule("ts", "DATE_PRESET", "UNKNOWN_PRESET", null, null)));
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                Map.of("*", group),
                null, null);
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsDateRangeWithoutFromAndTo() {
        PostmanCollection collection = sampleCollection();
        RowFilterGroup group = new RowFilterGroup("AND",
                List.of(new RowFilterRule("ts", "DATE_RANGE", null, null, null)));
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                Map.of("*", group),
                null, null);
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    // ── dateConfig validation ──────────────────────────────────────────────────────

    @Test
    void rejectsInvalidDateConfigFormatPattern() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null,
                Map.of("*", Map.of("ts", new DateFieldConfig("!!!invalid-pattern!!!", null))),
                null);
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsInvalidTimezone() {
        PostmanCollection collection = sampleCollection();
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null,
                Map.of("*", Map.of("ts", new DateFieldConfig(null, "Mars/Phobos"))),
                null);
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    // ── customTables validation ────────────────────────────────────────────────────

    @Test
    void rejectsCustomTableMissingSource() {
        PostmanCollection collection = sampleCollection();
        CustomTableSpec table = new CustomTableSpec("MyTable", null, null, null, null, null, null, null);
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null, null,
                List.of(table));
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsCustomTableWithBothSourceTypes() {
        PostmanCollection collection = sampleCollection();
        CustomTableSpec table = new CustomTableSpec(
                "MyTable",
                "List users",
                List.of(new CustomTableJoinSource("List users", "u")),
                null, null, null, null, null);
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null, null,
                List.of(table));
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsDuplicateCustomTableNames() {
        PostmanCollection collection = sampleCollection();
        CustomTableSpec t1 = new CustomTableSpec("Same", "List users", null, null, null, null, null, null);
        CustomTableSpec t2 = new CustomTableSpec("Same", "List users", null, null, null, null, null, null);
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null, null,
                List.of(t1, t2));
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void rejectsMultiSourceWithoutJoinOn() {
        PostmanCollection collection = sampleCollectionWithTwo();
        CustomTableSpec table = new CustomTableSpec(
                "JoinTable",
                null,
                List.of(new CustomTableJoinSource("List users", "u"),
                        new CustomTableJoinSource("Get user", "g")),
                null, null, null, null, null);
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                null, null,
                List.of(table));
        assertThrows(IllegalArgumentException.class,
                () -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    @Test
    void acceptsValidFilterWithAllNewFields() {
        PostmanCollection collection = sampleCollection();
        RowFilterGroup group = new RowFilterGroup("AND",
                List.of(new RowFilterRule("id", "GT", "0", null, null)));
        CustomTableSpec table = new CustomTableSpec(
                "Active Users", "List users",
                null, null, null, null,
                List.of(new ColumnSpec("id", null), new ColumnSpec("name", null)),
                new RowFilterGroup("AND", List.of(
                        new RowFilterRule("active", "IS_TRUE", null, null, null))));
        FilterSpec filter = new FilterSpec(
                null, null, null, null, null, null,
                Map.of("*", group),
                Map.of("*", Map.of("createdAt", new DateFieldConfig("yyyy-MM-dd", "UTC"))),
                List.of(table));
        assertDoesNotThrow(() -> FilterValidator.validate(filter, collection, Path.of("demo.json")));
    }

    private static PostmanCollection sampleCollection() {
        RequestSpec request = new RequestSpec(
                "Users",
                "List users",
                "GET",
                "https://example.com/users",
                List.of(),
                null,
                new AuthDefinition("noauth", Map.of()));
        return new PostmanCollection("Demo", Map.of(), List.of(request));
    }

    private static PostmanCollection sampleCollectionWithTwo() {
        RequestSpec r1 = new RequestSpec(
                "Users", "List users", "GET",
                "https://example.com/users", List.of(), null,
                new AuthDefinition("noauth", Map.of()));
        RequestSpec r2 = new RequestSpec(
                "Users", "Get user", "GET",
                "https://example.com/users/1", List.of(), null,
                new AuthDefinition("noauth", Map.of()));
        return new PostmanCollection("Demo", Map.of(), List.of(r1, r2));
    }
}
