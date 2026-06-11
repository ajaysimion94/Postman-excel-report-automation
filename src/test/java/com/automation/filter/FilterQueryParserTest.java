package com.automation.filter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void parsesIntersectStatement() throws Exception {
        Path file = Files.createTempFile("intersect", ".filter");
        Files.writeString(file, "INTERSECT CommonRows FROM \"RequestA\", \"RequestB\";");

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.setOps());
        assertEquals(1, spec.setOps().size());
        assertEquals("CommonRows", spec.setOps().get(0).name());
        assertEquals("INTERSECT", spec.setOps().get(0).type());
        assertEquals(List.of("RequestA", "RequestB"), spec.setOps().get(0).sources());
    }

    @Test
    void parsesExceptStatement() throws Exception {
        Path file = Files.createTempFile("except", ".filter");
        Files.writeString(file, "EXCEPT OnlyA FROM \"RequestA\", \"RequestB\", \"RequestC\";");

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.setOps());
        assertEquals(1, spec.setOps().size());
        assertEquals("OnlyA", spec.setOps().get(0).name());
        assertEquals("EXCEPT", spec.setOps().get(0).type());
        assertEquals(List.of("RequestA", "RequestB", "RequestC"), spec.setOps().get(0).sources());
    }

    @Test
    void parsesDiffStatement() throws Exception {
        Path file = Files.createTempFile("diff", ".filter");
        Files.writeString(file, "DIFF Mismatches FROM \"RequestA\", \"RequestB\";");

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.setOps());
        assertEquals(1, spec.setOps().size());
        assertEquals("Mismatches", spec.setOps().get(0).name());
        assertEquals("DIFF", spec.setOps().get(0).type());
        assertEquals(List.of("RequestA", "RequestB"), spec.setOps().get(0).sources());
    }

    @Test
    void parsesCompareStatement() throws Exception {
        Path file = Files.createTempFile("compare", ".filter");
        Files.writeString(file, "COMPARE IdCompare ON id FROM \"RequestA\", \"RequestB\";");

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.compares());
        assertEquals(1, spec.compares().size());
        assertEquals("IdCompare", spec.compares().get(0).name());
        assertEquals("id", spec.compares().get(0).field());
        assertEquals(List.of("RequestA", "RequestB"), spec.compares().get(0).sources());
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

    // ── New feature tests ────────────────────────────────────────────────────────

    @Test
    void parsesStatusKeyword() throws Exception {
        Path file = Files.createTempFile("status", ".filter");
        Files.writeString(file, """
                REQUESTS "List posts";
                METRICS;
                STATUS;
                STATUS COLOR "#FF5500";
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.summary());
        assertEquals(3, spec.summary().items().size());

        // First STATUS item
        SummaryItem.Status status1 = (SummaryItem.Status) spec.summary().items().get(1);
        assertNull(status1.colorName());

        // Second STATUS item with hex color
        SummaryItem.Status status2 = (SummaryItem.Status) spec.summary().items().get(2);
        assertEquals("#FF5500", status2.colorName());
    }

    @Test
    void parsesQuickTableWithMultipleColumns() throws Exception {
        Path file = Files.createTempFile("qt-multi", ".filter");
        Files.writeString(file, """
                REQUESTS "List posts";
                QT "Summary" HEADERS Name, Score, Grade
                  ROW "Alice", $count + "items", "A"
                  ROW "Bob", $count + "items", "B";
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.summary());
        SummaryItem.QuickTable qt = (SummaryItem.QuickTable) spec.summary().items().get(0);
        assertEquals("Summary", qt.title());
        assertEquals(List.of("Name", "Score", "Grade"), qt.headers());
        assertEquals(2, qt.rows().size());
    }

    @Test
    void parsesQuickTableWithCOLUMNSAlias() throws Exception {
        Path file = Files.createTempFile("qt-columns", ".filter");
        Files.writeString(file, """
                REQUESTS "List posts";
                QT COLUMNS Col1, Col2, Col3, Col4
                  ROW "A", "B", "C", "D";
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        SummaryItem.QuickTable qt = (SummaryItem.QuickTable) spec.summary().items().get(0);
        assertEquals(List.of("Col1", "Col2", "Col3", "Col4"), qt.headers());
    }

    @Test
    void parsesIfElseInWhereClause() throws Exception {
        Path file = Files.createTempFile("ifelse", ".filter");
        Files.writeString(file, "FILTER * WHERE IF priority = high THEN (severity > 7) ELSE (severity > 3);");

        FilterSpec spec = FilterQueryParser.parse(file);
        RowFilterGroup group = spec.rowFilters().get("*");
        assertNotNull(group.expression());
        assertTrue(group.expression() instanceof RowFilterExpression.IfElse);
        RowFilterExpression.IfElse ifElse = (RowFilterExpression.IfElse) group.expression();
        assertTrue(ifElse.condition() instanceof RowFilterExpression.Predicate);
        assertTrue(ifElse.thenExpr() instanceof RowFilterExpression.Predicate);
        assertNotNull(ifElse.elseExpr());
        assertTrue(ifElse.elseExpr() instanceof RowFilterExpression.Predicate);
    }

    @Test
    void parsesIfElseWithoutElse() throws Exception {
        Path file = Files.createTempFile("ifelse-noelse", ".filter");
        Files.writeString(file, "FILTER * WHERE IF status = active THEN (score > 50);");

        FilterSpec spec = FilterQueryParser.parse(file);
        RowFilterGroup group = spec.rowFilters().get("*");
        RowFilterExpression.IfElse ifElse = (RowFilterExpression.IfElse) group.expression();
        assertNull(ifElse.elseExpr());
    }

    @Test
    void parsesIfElseNestedInAnd() throws Exception {
        Path file = Files.createTempFile("ifelse-and", ".filter");
        Files.writeString(file, "FILTER * WHERE IF type = A THEN (val > 10) ELSE (val > 5) AND category = premium;");

        FilterSpec spec = FilterQueryParser.parse(file);
        RowFilterGroup group = spec.rowFilters().get("*");
        // The AND should combine the IF/ELSE with the category predicate
        assertTrue(group.expression() instanceof RowFilterExpression.And);
    }

    @Test
    void parsesColorWithHexValue() throws Exception {
        Path file = Files.createTempFile("hex-color", ".filter");
        Files.writeString(file, """
                REQUESTS "List posts";
                TITLE "Report" COLOR "#336699";
                DESCRIPTION "Note" COLOR "FF4400";
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        SummaryItem.Title title = (SummaryItem.Title) spec.summary().items().get(0);
        assertEquals("#336699", title.colorName());

        SummaryItem.Description desc = (SummaryItem.Description) spec.summary().items().get(1);
        assertEquals("FF4400", desc.colorName());
    }

    // ── Summary IF/ELSE tests ──────────────────────────────────────────────────────

    @Test
    void parsesIfElseInSummaryText() throws Exception {
        Path file = Files.createTempFile("summary-if", ".filter");
        Files.writeString(file, """
                REQUESTS "List posts";
                $POSTS = FILTER "List posts" WHERE id > 0;
                TEXT IF $POSTS > 0 THEN $POSTS + " found" ELSE "none found";
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        assertNotNull(spec.summary());
        SummaryItem.Text text = (SummaryItem.Text) spec.summary().items().get(0);
        assertEquals(1, text.parts().size());
        assertTrue(text.parts().get(0) instanceof SummaryTextPart.IfElse);
        SummaryTextPart.IfElse ifElse = (SummaryTextPart.IfElse) text.parts().get(0);
        assertEquals("POSTS", ifElse.variableName());
        assertEquals(">", ifElse.op());
        assertEquals("0", ifElse.value());
        assertEquals(2, ifElse.thenParts().size()); // $POSTS + " found"
        assertEquals(1, ifElse.elseParts().size()); // "none found"
    }

    @Test
    void parsesIfElseInKeyValue() throws Exception {
        Path file = Files.createTempFile("kv-if", ".filter");
        Files.writeString(file, """
                REQUESTS "List posts";
                $POSTS = FILTER "List posts" WHERE id > 0;
                KV "Status" IF $POSTS > 0 THEN "Has posts" ELSE "Empty";
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        SummaryItem.KeyValue kv = (SummaryItem.KeyValue) spec.summary().items().get(0);
        assertEquals("Status", kv.label());
        assertTrue(kv.valueParts().get(0) instanceof SummaryTextPart.IfElse);
    }

    @Test
    void parsesIfElseInQuickTable() throws Exception {
        Path file = Files.createTempFile("qt-if", ".filter");
        Files.writeString(file, """
                REQUESTS "List posts";
                $POSTS = FILTER "List posts" WHERE id > 0;
                QT "Results" HEADERS Name, Level, Value
                  ROW "Count", IF $POSTS > 100 THEN "High" ELSE "Low", $POSTS;
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        SummaryItem.QuickTable qt = (SummaryItem.QuickTable) spec.summary().items().get(0);
        // Multi-column mode: third column is a variable
        List<List<SummaryTextPart>> cols = qt.rows().get(0).effectiveColumns();
        // Second column should contain IfElse
        assertTrue(cols.get(1).get(0) instanceof SummaryTextPart.IfElse);
    }

    @Test
    void parsesIfElseWithoutElseInSummary() throws Exception {
        Path file = Files.createTempFile("summary-if-noelse", ".filter");
        Files.writeString(file, """
                REQUESTS "List posts";
                $POSTS = FILTER "List posts" WHERE id > 0;
                TEXT IF $POSTS = 0 THEN "No data";
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        SummaryItem.Text text = (SummaryItem.Text) spec.summary().items().get(0);
        SummaryTextPart.IfElse ifElse = (SummaryTextPart.IfElse) text.parts().get(0);
        assertEquals(0, ifElse.elseParts().size()); // No ELSE → empty
    }

    @Test
    void parsesNestedIfElseInSummary() throws Exception {
        Path file = Files.createTempFile("nested-if", ".filter");
        Files.writeString(file, """
                REQUESTS "List posts";
                $POSTS = FILTER "List posts" WHERE id > 0;
                TEXT IF $POSTS > 100 THEN IF $POSTS > 500 THEN "Very High" ELSE "High" ELSE "Low";
                """);

        FilterSpec spec = FilterQueryParser.parse(file);
        SummaryItem.Text text = (SummaryItem.Text) spec.summary().items().get(0);
        SummaryTextPart.IfElse outer = (SummaryTextPart.IfElse) text.parts().get(0);
        // THEN branch contains a nested IF
        assertTrue(outer.thenParts().get(0) instanceof SummaryTextPart.IfElse);
        SummaryTextPart.IfElse inner = (SummaryTextPart.IfElse) outer.thenParts().get(0);
        assertEquals("500", inner.value());
    }
}
