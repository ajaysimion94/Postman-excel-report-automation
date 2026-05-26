package com.automation.filter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilterQueryParserTest {

    @Test
    void parsesCoreStatements() throws Exception {
        Path file = Files.createTempFile("sample", ".filter");
        Files.writeString(file, """
                COLLECTION jsonplaceholder;
                REQUESTS \"List posts\", \"List users\";
                OUTPUT_PREFIX daily;
                COLUMNS *: id, title;
                FILTER * WHERE userId = 1 AND status IN ('active','pending');
                DATE_CONFIG *.createdAt FORMAT yyyy-MM-dd TIMEZONE UTC;
                """);

        FilterSpec spec = FilterQueryParser.parse(file);

        assertNotNull(spec);
        assertEquals("jsonplaceholder", spec.collection());
        assertEquals(List.of("List posts", "List users"), spec.requests());
        assertEquals("daily", spec.outputPrefix());
        assertEquals(List.of(new ColumnSpec("id", null), new ColumnSpec("title", null)),
                spec.responseColumns().get("*"));

        RowFilterGroup where = spec.rowFilters().get("*");
        assertNull(where.logic());
        assertEquals(2, where.rules().size());
        assertEquals("userId", where.rules().get(0).field());
        assertEquals("EQ", where.rules().get(0).op());
        assertEquals("1", where.rules().get(0).value());
        assertEquals("status", where.rules().get(1).field());
        assertEquals("IN", where.rules().get(1).op());
        assertEquals("active,pending", where.rules().get(1).value());

        DateFieldConfig cfg = spec.dateConfig().get("*").get("createdAt");
        assertEquals("yyyy-MM-dd", cfg.format());
        assertEquals("UTC", cfg.timezone());
    }

    @Test
    void parsesMixedLogicIntoExpressionTree() throws Exception {
        Path file = Files.createTempFile("mixed", ".filter");
        Files.writeString(file, "FILTER * WHERE (a = 1 OR b = 2) AND c = 3;");

        FilterSpec spec = FilterQueryParser.parse(file);
        RowFilterGroup group = spec.rowFilters().get("*");
        assertNotNull(group.expression());
        assertEquals(3, group.rules().size());
    }

    @Test
    void parsesNotIntoExpressionTree() throws Exception {
        Path file = Files.createTempFile("not", ".filter");
        Files.writeString(file, "FILTER * WHERE NOT active = true;");

        FilterSpec spec = FilterQueryParser.parse(file);
        RowFilterGroup group = spec.rowFilters().get("*");
        assertNotNull(group.expression());
        assertEquals(1, group.rules().size());
    }

    @Test
    void parsesLikeAliasesToRegexRules() throws Exception {
        Path file = Files.createTempFile("like", ".filter");
        Files.writeString(file, "FILTER * WHERE name LIKE 'Jo%n' AND city NOT LIKE 'New%';");

        FilterSpec spec = FilterQueryParser.parse(file);
        RowFilterGroup group = spec.rowFilters().get("*");

        assertEquals(2, group.rules().size());
        assertEquals("REGEX", group.rules().get(0).op());
        assertEquals("REGEX", group.rules().get(1).op());
    }

    @Test
    void parsesShapeClause() throws Exception {
        Path file = Files.createTempFile("shape", ".filter");
        Files.writeString(file, "SHAPE * DISTINCT ORDER BY id DESC, name ASC LIMIT 5 OFFSET 2;");

        FilterSpec spec = FilterQueryParser.parse(file);
        DataShapeSpec shape = spec.dataShapes().get("*");

        assertNotNull(shape);
        assertEquals(true, shape.distinct());
        assertEquals(2, shape.orderBy().size());
        assertEquals("id", shape.orderBy().get(0).field());
        assertEquals(true, shape.orderBy().get(0).descending());
        assertEquals("name", shape.orderBy().get(1).field());
        assertEquals(false, shape.orderBy().get(1).descending());
        assertEquals(5, shape.limit());
        assertEquals(2, shape.offset());
    }

    @Test
    void parsesShapeGroupingAndHaving() throws Exception {
        Path file = Files.createTempFile("shape-group", ".filter");
        Files.writeString(file, "SHAPE * GROUP BY userId AGG COUNT(*) AS cnt, SUM(amount) AS total HAVING cnt > 1 ORDER BY total DESC;");

        FilterSpec spec = FilterQueryParser.parse(file);
        DataShapeSpec shape = spec.dataShapes().get("*");

        assertNotNull(shape);
        assertEquals(List.of("userId"), shape.groupBy());
        assertEquals(2, shape.aggregates().size());
        assertEquals("COUNT", shape.aggregates().get(0).function());
        assertEquals("cnt", shape.aggregates().get(0).alias());
        assertNotNull(shape.having());
    }

    @Test
    void parsesUnionStatement() throws Exception {
        Path file = Files.createTempFile("union", ".filter");
        Files.writeString(file, "UNION CombinedPosts FROM RequestA, RequestB ALL;");

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.unions());
        assertEquals(1, spec.unions().size());
        assertEquals("CombinedPosts", spec.unions().get(0).name());
        assertEquals(List.of("RequestA", "RequestB"), spec.unions().get(0).sources());
        assertEquals(true, spec.unions().get(0).all());
    }

    @Test
    void parsesLookupTableStatement() throws Exception {
        Path file = Files.createTempFile("lookup", ".filter");
        Files.writeString(file, """
                LOOKUP_TABLE "Items With Details"
                  FROM "List Items"
                  LOOKUP "Get Item Details"
                  BY id
                  WHERE detail.price >= 1000 AND detail.availability = in_stock
                  COLUMNS id, itemName, detail.price, detail.availability;
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.customTables());
        assertEquals(1, spec.customTables().size());

        CustomTableSpec table = spec.customTables().get(0);
        assertEquals("Items With Details", table.name());
        assertEquals("List Items", table.sourceRequest());
        assertEquals("Get Item Details", table.lookupRequest());
        assertEquals("id", table.lookupParam());
        assertEquals(
                List.of(
                        new ColumnSpec("id", null),
                        new ColumnSpec("itemName", null),
                        new ColumnSpec("detail.price", null),
                        new ColumnSpec("detail.availability", null)),
                table.columns());
        assertNotNull(table.where());
        assertEquals(2, table.where().rules().size());
    }

    @Test
    void selectsMatchingCollectionBlockWhenPreferredSelectorProvided() throws Exception {
        Path file = Files.createTempFile("multi", ".filter");
        Files.writeString(file, """
                REQUESTS \"Health\";
                COLLECTION posts;
                REQUESTS \"List posts\";
                FILTER \"List posts\" WHERE id > 1;
                COLLECTION users;
                REQUESTS \"List users\";
                FILTER \"List users\" WHERE id > 2;
                """);

        FilterSpec spec = FilterQueryParser.parse(file, "users");
        assertEquals("users", spec.collection());
        assertEquals(List.of("Health", "List users"), spec.requests());
        assertNotNull(spec.rowFilters().get("List users"));
    }

    @Test
    void parsesColumnRenameWithAs() throws Exception {
        Path file = Files.createTempFile("columns-rename", ".filter");
        Files.writeString(file, """
                COLUMNS "List posts": id AS "Post ID", userId AS User;
                LOOKUP_TABLE "Details"
                  FROM "List Items"
                  LOOKUP "Get Item Details"
                  BY id
                  COLUMNS id AS ID, detail.price AS Price;
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        assertEquals(new ColumnSpec("id", "Post ID"), spec.responseColumns().get("List posts").get(0));
        assertEquals(new ColumnSpec("userId", "User"), spec.responseColumns().get("List posts").get(1));

        CustomTableSpec table = spec.customTables().get(0);
        assertEquals(new ColumnSpec("id", "ID"), table.columns().get(0));
        assertEquals(new ColumnSpec("detail.price", "Price"), table.columns().get(1));
    }

    @Test
    void parsesSummarySection() throws Exception {
        Path file = Files.createTempFile("summary", ".filter");
        Files.writeString(file, """
                COLLECTION demo;
                REQUESTS "List posts";
                TITLE "Daily Report" COLOR DARK_BLUE;
                TEXT "Welcome";
                $POSTS = FILTER "List posts" WHERE id > 10;
                TABLE $POSTS;
                METRICS;
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.summary());
        assertEquals(4, spec.summary().items().size());
        assertNotNull(spec.summary().queries().get("POSTS"));
        SummaryQuerySource.FilterRows posts = (SummaryQuerySource.FilterRows) spec.summary().queries().get("POSTS").source();
        assertEquals("List posts", posts.requestKey());
        assertEquals("GT", posts.filter().rules().get(0).op());
    }

    @Test
    void throwsWhenMultipleCollectionBlocksExistWithoutSelector() throws Exception {
        Path file = Files.createTempFile("multi-ambiguous", ".filter");
        Files.writeString(file, """
                COLLECTION posts;
                REQUESTS \"List posts\";
                COLLECTION users;
                REQUESTS \"List users\";
                """);

        assertThrows(IllegalArgumentException.class, () -> FilterQueryParser.parse(file));
    }
}
