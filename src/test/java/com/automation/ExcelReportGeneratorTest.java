package com.automation;

import com.automation.excel.ExcelReportGenerator;
import com.automation.filter.CustomTableSpec;
import com.automation.filter.DataShapeSpec;
import com.automation.filter.ColumnSpec;
import com.automation.filter.FilterQueryParser;
import com.automation.filter.FilterSpec;
import com.automation.filter.RowFilterGroup;
import com.automation.filter.RowFilterRule;
import com.automation.filter.SortSpec;
import com.automation.filter.SummaryItem;
import com.automation.filter.AggregateSpec;
import com.automation.filter.ExpandSpec;
import com.automation.filter.SetOpSpec;
import com.automation.filter.CompareSpec;
import com.automation.http.RequestExecutor;
import com.automation.model.ExecutionResult;
import com.automation.model.RuntimeConfig;
import com.automation.postman.PostmanCollection;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExcelReportGeneratorTest {

    @Test
    void guidedNestedDatasetDefinitionFiltersNamesWithinTheSameStudentRow() throws Exception {
        Path output = Files.createTempFile("report-guided-school", ".xlsx");
        FilterSpec spec = FilterQueryParser.parseSource("""
                COLLECTION "school";
                REQUESTS "Get school";
                EXPAND "Get school" ON School.class.students;
                FILTER "Get school" WHERE School.class.students.student.age < 13;
                COLUMNS "Get school": School.class.students.student.name AS "Student name";
                """, Path.of("guided-school.filter"), null);
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("School", Map.of(), List.of());
        String body = """
                {"School":{"class":{"students":[
                  {"student":{"name":"Asha","age":12}},
                  {"student":{"name":"Ben","age":14}},
                  {"student":{"name":"Mina","age":11}}
                ]}}}
                """;
        List<ExecutionResult> results = List.of(new ExecutionResult("Root", "Get school", "GET",
                "https://example.test/school", 200, 8, true, "", body, body, Instant.now(), List.of()));

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream input = Files.newInputStream(output); XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            var sheet = workbook.getSheet("Get school");
            assertNotNull(sheet);
            StringBuilder text = new StringBuilder();
            sheet.forEach(row -> row.forEach(cell -> text.append(cell.toString()).append('\n')));
            assertTrue(text.toString().contains("Student name"));
            assertTrue(text.toString().contains("Asha"));
            assertTrue(text.toString().contains("Mina"));
            assertFalse(text.toString().contains("Ben"));
        }
    }

    @Test
    void expandUnnestsNestedArrayIntoRows() throws Exception {
        Path output = Files.createTempFile("report-expand", ".xlsx");
        FilterSpec spec = new FilterSpec(null, null, null, null, null, null,
                null, null, null, null, null,
                Map.of("Category List", new ExpandSpec("items")), null);
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        // Wrapped response: top-level object with a "category list" array.
        // Each category row has an "items" array to expand.
        String body = "{\"category list\":[" +
                "{\"category\":\"fruits\",\"items\":[{\"itemid\":1,\"name\":\"apple\",\"price\":20},{\"itemid\":2,\"name\":\"orange\",\"price\":10}]}," +
                "{\"category\":\"veg\",\"items\":[{\"itemid\":1,\"name\":\"carrot\",\"price\":5},{\"itemid\":2,\"name\":\"tomato\",\"price\":10}]}" +
                "]}";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "Category List", "GET", "https://example.com/categories",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var sheet = wb.getSheet("Category List");
            assertNotNull(sheet, "Response data sheet for Category List should exist");
            // title row + 2 header rows (hierarchical: parent group + leaf) + 4 data rows (2 fruits + 2 veg)
            assertEquals(7, sheet.getPhysicalNumberOfRows(),
                    "Sheet should have title + 2 header rows + 4 expanded item rows");
        }
    }

    @Test
    void expandWithSparseChildFieldsPutsExtrasInExceptionColumns() throws Exception {
        Path output = Files.createTempFile("report-expand-sparse", ".xlsx");
        FilterSpec spec = new FilterSpec(null, null, null, null, null, null,
                null, null, null, null, null,
                Map.of("Products", new ExpandSpec("variants", "extra")), null);
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        // Second variant has an extra field "organic" not in the first — should go to extra.organic
        String body = "[{\"product\":\"A\",\"variants\":[" +
                "{\"sku\":\"A1\",\"price\":10}," +
                "{\"sku\":\"A2\",\"price\":20,\"organic\":true}" +
                "]}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "Products", "GET", "https://example.com/products",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var sheet = wb.getSheet("Products");
            assertNotNull(sheet);
            // title + 2 header rows (hierarchical) + 2 data rows
            assertEquals(5, sheet.getPhysicalNumberOfRows());
            // The leaf header row (index 3) should contain the sparse column leaf label "organic"
            // (the parent group label "extra" is rendered in the parent header row at index 2).
            int headerRowIndex = 3;
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(headerRowIndex);
            boolean foundSparseColumn = false;
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.getCell(i);
                if (cell != null && "organic".equals(cell.getStringCellValue())) {
                    foundSparseColumn = true;
                    break;
                }
            }
            assertTrue(foundSparseColumn, "Sparse leaf column 'organic' should appear in the leaf header row");
        }
    }

    @Test
    void generatesWorkbookWithStyledSheets() throws Exception {
        Path output = Files.createTempFile("report", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), null);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());
        List<ExecutionResult> results = List.of(
            new ExecutionResult("Users", "List Users", "GET", "https://example.com/users", 200, 120, true, "", "{\"ok\":true}", "{\"ok\":true}", Instant.now(), List.of("Status 2xx: PASS"))
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        assertTrue(Files.exists(output));
        try (InputStream inputStream = Files.newInputStream(output);
             XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            assertEquals("Summary", workbook.getSheetAt(0).getSheetName());
            assertEquals("Index", workbook.getSheetAt(1).getSheetName());
            assertEquals("Results", workbook.getSheetAt(2).getSheetName());
            assertEquals("Users", workbook.getSheetAt(3).getSheetName());
        }
    }

    @Test
    void rendersJsonArrayAsTableInResultsResponseBody() throws Exception {
        Path output = Files.createTempFile("report-results-response", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), null);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());
        String body = "{\"page\":1,\"data\":[{\"id\":1,\"email\":\"george@example.com\"},"
                + "{\"id\":2,\"email\":\"janet@example.com\"}]}";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Users", "List Users", "GET", "https://example.com/users",
                        200, 120, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream inputStream = Files.newInputStream(output);
             XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            var resultRow = workbook.getSheet("Results").getRow(3);
            String rendered = resultRow.getCell(10).getStringCellValue();
            assertEquals("data (2 rows)\nid | email\n1 | george@example.com\n2 | janet@example.com", rendered);
            assertTrue(resultRow.getHeightInPoints() >= 60, "The response table should not be clipped in the workbook preview");
        }
    }

    @Test
    void rowFilterReducesRowsInResponseDataSheet() throws Exception {
        Path output = Files.createTempFile("report-filter", ".xlsx");
        // Only include rows where userId == "1"
        RowFilterGroup filter = new RowFilterGroup("AND",
                List.of(new RowFilterRule("userId", "EQ", "1", null, null)));
        FilterSpec spec = new FilterSpec(null, null, null, null, null, null,
                Map.of("List Posts", filter), null, null);
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        // Two rows: one with userId=1 (passes), one with userId=2 (filtered out)
        String body = "[{\"id\":1,\"userId\":1,\"title\":\"A\"},{\"id\":2,\"userId\":2,\"title\":\"B\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            // Find the "List Posts" response data sheet
            var sheet = wb.getSheet("List Posts");
            assertNotNull(sheet, "Response data sheet for List Posts should exist");
            // Title row + header row + 1 data row (userId=1 only, userId=2 filtered out)
            assertEquals(3, sheet.getPhysicalNumberOfRows(),
                    "Sheet should have title + header + 1 data row after filtering");
        }
    }

    @Test
    void customTableSheetCreatedWithCorrectName() throws Exception {
        Path output = Files.createTempFile("report-custom", ".xlsx");
        CustomTableSpec table = new CustomTableSpec(
                "My Custom Table",
                "List Posts",
                null, null, null, null,
                List.of(new ColumnSpec("id", null), new ColumnSpec("title", null)),
                new RowFilterGroup("AND",
                        List.of(new RowFilterRule("userId", "EQ", "1", null, null))));
        FilterSpec spec = new FilterSpec(null, null, null, null, null, null,
                null, null, List.of(table));
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String body = "[{\"id\":1,\"userId\":1,\"title\":\"A\"},{\"id\":2,\"userId\":2,\"title\":\"B\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertNotNull(wb.getSheet("My Custom Table"),
                    "Custom table sheet should be present");
            var sheet = wb.getSheet("My Custom Table");
            // Title row + header row + 1 data row (userId=1 only, and only id+title columns)
            assertEquals(3, sheet.getPhysicalNumberOfRows());
        }
    }

        @Test
        void shapeAppliesDistinctOrderAndLimitOffset() throws Exception {
                Path output = Files.createTempFile("report-shape", ".xlsx");
                FilterSpec spec = new FilterSpec(
                                null, null, null, null, null, null,
                                null, null, null,
                                Map.of("List Posts", new DataShapeSpec(
                                                true,
                                                List.of(new SortSpec("id", true)),
                                                2,
                                                1
                                ))
                );
                RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
                PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

                String body = "[{\"id\":1,\"title\":\"A\"},{\"id\":3,\"title\":\"C\"},{\"id\":2,\"title\":\"B\"},{\"id\":3,\"title\":\"C\"}]";
                List<ExecutionResult> results = List.of(
                                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                                                200, 50, true, "", body, body, Instant.now(), List.of())
                );

                new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

                try (InputStream is = Files.newInputStream(output);
                         XSSFWorkbook wb = new XSSFWorkbook(is)) {
                        var sheet = wb.getSheet("List Posts");
                        assertNotNull(sheet);
                        // title + header + 2 rows after DISTINCT then ORDER DESC then OFFSET 1 LIMIT 2 -> ids [2,1]
                        assertEquals(4, sheet.getPhysicalNumberOfRows());

                        int headerRowIndex = 2;
                        int idCol = -1;
                        for (int i = 0; i < sheet.getRow(headerRowIndex).getLastCellNum(); i++) {
                                if ("id".equals(sheet.getRow(headerRowIndex).getCell(i).getStringCellValue())) {
                                        idCol = i;
                                        break;
                                }
                        }
                        assertTrue(idCol >= 0);

                        assertEquals("2", sheet.getRow(3).getCell(idCol).getStringCellValue());
                        assertEquals("1", sheet.getRow(4).getCell(idCol).getStringCellValue());
                }
        }

            @Test
            void shapeAppliesGroupingAggregatesAndHaving() throws Exception {
                Path output = Files.createTempFile("report-shape-group", ".xlsx");
                FilterSpec spec = new FilterSpec(
                        null, null, null, null, null, null,
                        null, null, null,
                        Map.of("List Posts", new DataShapeSpec(
                                false,
                                List.of(new SortSpec("total", true)),
                                1,
                                0,
                                List.of("userId"),
                                List.of(
                                        new AggregateSpec("COUNT", "*", "cnt"),
                                        new AggregateSpec("SUM", "amount", "total")
                                ),
                                new RowFilterGroup("AND", List.of(new RowFilterRule("cnt", "GT", "1", null, null)))
                        ))
                );
                RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
                PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

                String body = "[{\"id\":1,\"userId\":1,\"amount\":10},{\"id\":2,\"userId\":1,\"amount\":30},{\"id\":3,\"userId\":2,\"amount\":20},{\"id\":4,\"userId\":2,\"amount\":40},{\"id\":5,\"userId\":3,\"amount\":99}]";
                List<ExecutionResult> results = List.of(
                        new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                                200, 50, true, "", body, body, Instant.now(), List.of())
                );

                new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

                try (InputStream is = Files.newInputStream(output);
                     XSSFWorkbook wb = new XSSFWorkbook(is)) {
                    var sheet = wb.getSheet("List Posts");
                    assertNotNull(sheet);
                    // After group/having there are two groups (userId 1 and 2), then ORDER BY total DESC and LIMIT 1 -> one data row
                    assertEquals(3, sheet.getPhysicalNumberOfRows());
                }
            }

            @Test
            void customTableLeftJoinKeepsUnmatchedLeftRows() throws Exception {
                Path output = Files.createTempFile("report-left-join", ".xlsx");
                CustomTableSpec table = new CustomTableSpec(
                        "Orders With Users",
                        null,
                        List.of(
                                new com.automation.filter.CustomTableJoinSource("List Orders", "o"),
                                new com.automation.filter.CustomTableJoinSource("List Users", "u")
                        ),
                        "LEFT",
                        List.of(new com.automation.filter.CustomTableJoinCondition("userId", "id")),
                        null,
                        null,
                        null,
                        List.of(new ColumnSpec("o.id", null), new ColumnSpec("u.name", null)),
                        null
                );
                FilterSpec spec = new FilterSpec(null, null, null, null, null, null,
                        null, null, List.of(table), null);
                RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
                PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

                String orders = "[{\"id\":100,\"userId\":1},{\"id\":101,\"userId\":999}]";
                String users = "[{\"id\":1,\"name\":\"Jane\"}]";
                List<ExecutionResult> results = List.of(
                        new ExecutionResult("Root", "List Orders", "GET", "https://example.com/orders",
                                200, 50, true, "", orders, orders, Instant.now(), List.of()),
                        new ExecutionResult("Root", "List Users", "GET", "https://example.com/users",
                                200, 50, true, "", users, users, Instant.now(), List.of())
                );

                new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

                try (InputStream is = Files.newInputStream(output);
                     XSSFWorkbook wb = new XSSFWorkbook(is)) {
                    var sheet = wb.getSheet("Orders With Users");
                    assertNotNull(sheet);
                    // title + header + 2 data rows (unmatched left row retained)
                    assertEquals(4, sheet.getPhysicalNumberOfRows());
                }
            }

            @Test
            void customTableSupportsThreeSourceJoinChain() throws Exception {
                Path output = Files.createTempFile("report-3way-join", ".xlsx");
                CustomTableSpec table = new CustomTableSpec(
                        "Orders Users Teams",
                        null,
                        List.of(
                                new com.automation.filter.CustomTableJoinSource("List Orders", "o"),
                                new com.automation.filter.CustomTableJoinSource("List Users", "u"),
                                new com.automation.filter.CustomTableJoinSource("List Teams", "t")
                        ),
                        "LEFT",
                        List.of(
                                new com.automation.filter.CustomTableJoinCondition("userId", "id"),
                                new com.automation.filter.CustomTableJoinCondition("teamId", "id")
                        ),
                        null,
                        null,
                        null,
                        List.of(
                                new ColumnSpec("o.id", null),
                                new ColumnSpec("u.name", null),
                                new ColumnSpec("t.teamName", null)),
                        null
                );
                FilterSpec spec = new FilterSpec(null, null, null, null, null, null,
                        null, null, List.of(table), null);
                RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
                PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

                String orders = "[{\"id\":100,\"userId\":1,\"teamId\":10},{\"id\":101,\"userId\":2,\"teamId\":11}]";
                String users = "[{\"id\":1,\"name\":\"Jane\"}]";
                String teams = "[{\"id\":10,\"teamName\":\"Core\"},{\"id\":11,\"teamName\":\"Ops\"}]";
                List<ExecutionResult> results = List.of(
                        new ExecutionResult("Root", "List Orders", "GET", "https://example.com/orders",
                                200, 50, true, "", orders, orders, Instant.now(), List.of()),
                        new ExecutionResult("Root", "List Users", "GET", "https://example.com/users",
                                200, 50, true, "", users, users, Instant.now(), List.of()),
                        new ExecutionResult("Root", "List Teams", "GET", "https://example.com/teams",
                                200, 50, true, "", teams, teams, Instant.now(), List.of())
                );

                new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

                try (InputStream is = Files.newInputStream(output);
                     XSSFWorkbook wb = new XSSFWorkbook(is)) {
                    var sheet = wb.getSheet("Orders Users Teams");
                    assertNotNull(sheet);
                    // title + header + 2 data rows from chained joins
                    assertEquals(4, sheet.getPhysicalNumberOfRows());
                }
            }

            @Test
            void unionAndUnionAllSheetsAreGenerated() throws Exception {
                Path output = Files.createTempFile("report-union", ".xlsx");
                FilterSpec spec = new FilterSpec(
                        null, null, null, null, null, null,
                        null, null, null,
                        null,
                        List.of(
                                new com.automation.filter.UnionSpec("MergedDistinct", List.of("Req A", "Req B"), false),
                                new com.automation.filter.UnionSpec("MergedAll", List.of("Req A", "Req B"), true)
                        )
                );

                RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
                PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

                String a = "[{\"id\":1,\"name\":\"X\"},{\"id\":2,\"name\":\"Y\"}]";
                String b = "[{\"id\":2,\"name\":\"Y\"},{\"id\":3,\"name\":\"Z\"}]";
                List<ExecutionResult> results = List.of(
                        new ExecutionResult("Root", "Req A", "GET", "https://example.com/a",
                                200, 10, true, "", a, a, Instant.now(), List.of()),
                        new ExecutionResult("Root", "Req B", "GET", "https://example.com/b",
                                200, 10, true, "", b, b, Instant.now(), List.of())
                );

                new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

                try (InputStream is = Files.newInputStream(output);
                     XSSFWorkbook wb = new XSSFWorkbook(is)) {
                    var distinctSheet = wb.getSheet("MergedDistinct");
                    var allSheet = wb.getSheet("MergedAll");
                    assertNotNull(distinctSheet);
                    assertNotNull(allSheet);

                    // Distinct: title + header + 3 rows
                    assertEquals(5, distinctSheet.getPhysicalNumberOfRows());
                    // All: title + header + 4 rows
                    assertEquals(6, allSheet.getPhysicalNumberOfRows());
                }
            }

    @Test
    void intersectSheetShowsOnlyCommonRows() throws Exception {
        Path output = Files.createTempFile("report-intersect", ".xlsx");
        FilterSpec spec = new FilterSpec(
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                List.of(new SetOpSpec("CommonRows", "INTERSECT", List.of("Req A", "Req B"))),
                null
        );

        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String a = "[{\"id\":1,\"name\":\"X\"},{\"id\":2,\"name\":\"Y\"},{\"id\":3,\"name\":\"Z\"}]";
        String b = "[{\"id\":2,\"name\":\"Y\"},{\"id\":3,\"name\":\"Z\"},{\"id\":4,\"name\":\"W\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "Req A", "GET", "https://example.com/a",
                        200, 10, true, "", a, a, Instant.now(), List.of()),
                new ExecutionResult("Root", "Req B", "GET", "https://example.com/b",
                        200, 10, true, "", b, b, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var sheet = wb.getSheet("CommonRows");
            assertNotNull(sheet);
            // title + header + 2 common rows (id=2 and id=3)
            assertEquals(4, sheet.getPhysicalNumberOfRows());
        }
    }

    @Test
    void exceptSheetShowsOnlyRowsInFirstSource() throws Exception {
        Path output = Files.createTempFile("report-except", ".xlsx");
        FilterSpec spec = new FilterSpec(
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                List.of(new SetOpSpec("OnlyInA", "EXCEPT", List.of("Req A", "Req B"))),
                null
        );

        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String a = "[{\"id\":1,\"name\":\"X\"},{\"id\":2,\"name\":\"Y\"},{\"id\":3,\"name\":\"Z\"}]";
        String b = "[{\"id\":2,\"name\":\"Y\"},{\"id\":3,\"name\":\"Z\"},{\"id\":4,\"name\":\"W\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "Req A", "GET", "https://example.com/a",
                        200, 10, true, "", a, a, Instant.now(), List.of()),
                new ExecutionResult("Root", "Req B", "GET", "https://example.com/b",
                        200, 10, true, "", b, b, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var sheet = wb.getSheet("OnlyInA");
            assertNotNull(sheet);
            // title + header + 1 row (id=1, only in Req A)
            assertEquals(3, sheet.getPhysicalNumberOfRows());
        }
    }

    @Test
    void diffSheetShowsUniqueRowsPerSourceWithSectionLabels() throws Exception {
        Path output = Files.createTempFile("report-diff", ".xlsx");
        FilterSpec spec = new FilterSpec(
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                List.of(new SetOpSpec("Mismatches", "DIFF", List.of("Req A", "Req B"))),
                null
        );

        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String a = "[{\"id\":1,\"name\":\"X\"},{\"id\":2,\"name\":\"Y\"},{\"id\":3,\"name\":\"Z\"}]";
        String b = "[{\"id\":2,\"name\":\"Y\"},{\"id\":3,\"name\":\"Z\"},{\"id\":4,\"name\":\"W\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "Req A", "GET", "https://example.com/a",
                        200, 10, true, "", a, a, Instant.now(), List.of()),
                new ExecutionResult("Root", "Req B", "GET", "https://example.com/b",
                        200, 10, true, "", b, b, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var sheet = wb.getSheet("Mismatches");
            assertNotNull(sheet);
            // title + header + section label "Req A" + 1 data row (id=1)
            //   + section label "Req B" + 1 data row (id=4) = 6 rows
            assertEquals(6, sheet.getPhysicalNumberOfRows());
        }
    }

    @Test
    void compareSheetShowsValueMatrixAcrossSources() throws Exception {
        Path output = Files.createTempFile("report-compare", ".xlsx");
        FilterSpec spec = new FilterSpec(
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                null,
                List.of(new CompareSpec("IdCompare", "id", List.of("Req A", "Req B"), null, null))
        );

        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String a = "[{\"id\":1,\"name\":\"X\"},{\"id\":2,\"name\":\"Y\"},{\"id\":3,\"name\":\"Z\"}]";
        String b = "[{\"id\":2,\"name\":\"Y\"},{\"id\":3,\"name\":\"Z\"},{\"id\":4,\"name\":\"W\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "Req A", "GET", "https://example.com/a",
                        200, 10, true, "", a, a, Instant.now(), List.of()),
                new ExecutionResult("Root", "Req B", "GET", "https://example.com/b",
                        200, 10, true, "", b, b, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var sheet = wb.getSheet("IdCompare");
            assertNotNull(sheet);
            // title + header + 4 unique values (1,2,3,4)
            assertEquals(6, sheet.getPhysicalNumberOfRows());
        }
    }

    @Test
    void columnRenameAppliesDisplayHeadersOnResponseSheet() throws Exception {
        Path output = Files.createTempFile("report-column-rename", ".xlsx");
        FilterSpec spec = new FilterSpec(
                null, null,
                Map.of("List Posts", List.of(
                        new ColumnSpec("id", "Post ID"),
                        new ColumnSpec("title", "Title"))),
                null, null, null, null, null, null, null, null, null, null);
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String body = "[{\"id\":1,\"title\":\"A\"},{\"id\":2,\"title\":\"B\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var sheet = wb.getSheet("List Posts");
            assertNotNull(sheet);
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(2);
            assertEquals("Post ID", headerRow.getCell(0).getStringCellValue());
            assertEquals("Title", headerRow.getCell(1).getStringCellValue());
            assertEquals("1", sheet.getRow(3).getCell(0).getStringCellValue());
        }
    }

    @Test
    void customSummaryEmbedsFilteredTable() throws Exception {
        Path filterFile = Files.createTempFile("summary-filter", ".filter");
        Files.writeString(filterFile, """
                REQUESTS "List Posts";
                TITLE "Post Report";
                TEXT "Matching posts: " + $POSTS;
                $POSTS = FILTER "List Posts" WHERE id > 1;
                $POSTS;
                """);
        FilterSpec spec = FilterQueryParser.parse(filterFile);
        Path output = Files.createTempFile("report-custom-summary", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String body = "[{\"id\":1,\"title\":\"A\"},{\"id\":2,\"title\":\"B\"},{\"id\":3,\"title\":\"C\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertEquals("Summary", wb.getSheetAt(0).getSheetName());
            assertEquals("Index", wb.getSheetAt(1).getSheetName());
            var summary = wb.getSheet("Summary");
            assertNotNull(summary);
            assertTrue(summary.getRow(0).getCell(0).getStringCellValue().contains("Post Report"));
            assertEquals("Matching posts:", summary.getRow(1).getCell(0).getStringCellValue());
            assertEquals("2", summary.getRow(1).getCell(1).getStringCellValue());
            assertTrue(spec.summary().items().stream().anyMatch(SummaryItem.Table.class::isInstance));
        }
    }

    @Test
    void statusSummaryItemShowsRequestStatus() throws Exception {
        Path filterFile = Files.createTempFile("status-filter", ".filter");
        Files.writeString(filterFile, """
                REQUESTS "List Posts", "List Users";
                METRICS;
                STATUS;
                """);
        FilterSpec spec = FilterQueryParser.parse(filterFile);
        Path output = Files.createTempFile("report-status", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", "[]", "[]", Instant.now(), List.of()),
                new ExecutionResult("Root", "List Users", "GET", "https://example.com/users",
                        500, 120, false, "Timeout", "", "", Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var summary = wb.getSheet("Summary");
            assertNotNull(summary);
            // Find the STATUS block — it should contain "Request Status" as a section header
            boolean foundStatusHeader = false;
            for (int i = 0; i < summary.getPhysicalNumberOfRows(); i++) {
                var cell = summary.getRow(i).getCell(0);
                if (cell != null && "Request Status".equals(cell.getStringCellValue())) {
                    foundStatusHeader = true;
                    break;
                }
            }
            assertTrue(foundStatusHeader, "STATUS block should contain 'Request Status' header");
        }
    }

    @Test
    void quickTableWithMultipleColumnsRendersAllColumns() throws Exception {
        Path filterFile = Files.createTempFile("qt-multi", ".filter");
        Files.writeString(filterFile, """
                REQUESTS "List Posts";
                QT "Scoreboard" HEADERS Name, Score, Grade
                  ROW "Alice", $count + "pts", "A"
                  ROW "Bob", $count + "pts", "B";
                METRICS;
                """);
        FilterSpec spec = FilterQueryParser.parse(filterFile);
        Path output = Files.createTempFile("report-qt-multi", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", "[]", "[]", Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var summary = wb.getSheet("Summary");
            assertNotNull(summary);
            // Find the QuickTable header row with "Name", "Score", "Grade"
            boolean foundHeaders = false;
            for (int i = 0; i < summary.getPhysicalNumberOfRows(); i++) {
                var row = summary.getRow(i);
                if (row != null && row.getCell(0) != null && "Name".equals(row.getCell(0).getStringCellValue())
                        && row.getCell(1) != null && "Score".equals(row.getCell(1).getStringCellValue())
                        && row.getCell(2) != null && "Grade".equals(row.getCell(2).getStringCellValue())) {
                    foundHeaders = true;
                    break;
                }
            }
            assertTrue(foundHeaders, "QuickTable should have 3-column headers: Name, Score, Grade");
        }
    }

    @Test
    void summaryIfElseRendersCorrectBranch() throws Exception {
        Path filterFile = Files.createTempFile("summary-ifelse", ".filter");
        Files.writeString(filterFile, """
                REQUESTS "List Posts";
                $POSTS = FILTER "List Posts" WHERE id > 0;
                KV "Result" IF $POSTS > 0 THEN $POSTS + " posts found" ELSE "No posts found";
                """);
        FilterSpec spec = FilterQueryParser.parse(filterFile);
        Path output = Files.createTempFile("report-ifelse", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        // 3 posts in the response → $POSTS resolves to row count = 3 (> 0 → THEN branch)
        String body = "[{\"id\":1,\"title\":\"A\"},{\"id\":2,\"title\":\"B\"},{\"id\":3,\"title\":\"C\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var summary = wb.getSheet("Summary");
            assertNotNull(summary);
            // Find the KV row with label "Result"
            boolean foundIfElseResult = false;
            for (int i = 0; i < summary.getPhysicalNumberOfRows(); i++) {
                var row = summary.getRow(i);
                if (row != null && row.getCell(0) != null && "Result".equals(row.getCell(0).getStringCellValue())) {
                    String value = row.getCell(1).getStringCellValue();
                    // $POSTS = 3, condition 3 > 0 is true → THEN: "3 posts found"
                    assertEquals("3 posts found", value);
                    foundIfElseResult = true;
                    break;
                }
            }
            assertTrue(foundIfElseResult, "Should find the IF/ELSE KV row");
        }
    }

    @Test
    void summaryIfElseRendersElseBranchWhenConditionFails() throws Exception {
        Path filterFile = Files.createTempFile("summary-ifelse-else", ".filter");
        Files.writeString(filterFile, """
                REQUESTS "List Posts";
                $POSTS = FILTER "List Posts" WHERE id > 999;
                KV "Result" IF $POSTS > 0 THEN $POSTS + " posts found" ELSE "No posts found";
                """);
        FilterSpec spec = FilterQueryParser.parse(filterFile);
        Path output = Files.createTempFile("report-ifelse-else", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        // No posts match id > 999 → $POSTS = 0 (0 is not > 0 → ELSE branch)
        String body = "[{\"id\":1,\"title\":\"A\"},{\"id\":2,\"title\":\"B\"}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var summary = wb.getSheet("Summary");
            assertNotNull(summary);
            for (int i = 0; i < summary.getPhysicalNumberOfRows(); i++) {
                var row = summary.getRow(i);
                if (row != null && row.getCell(0) != null && "Result".equals(row.getCell(0).getStringCellValue())) {
                    String value = row.getCell(1).getStringCellValue();
                    assertEquals("No posts found", value);
                    return;
                }
            }
            throw new AssertionError("Should find the IF/ELSE KV row");
        }
    }

    // ── Variables across queries (B1/B2/B3) ─────────────────────────────────────────

    private static String kvValue(org.apache.poi.ss.usermodel.Sheet summary, String label) {
        for (int i = 0; i <= summary.getLastRowNum(); i++) {
            var row = summary.getRow(i);
            if (row != null && row.getCell(0) != null && label.equals(row.getCell(0).getStringCellValue())) {
                return row.getCell(1) == null ? null : row.getCell(1).getStringCellValue();
            }
        }
        return null;
    }

    private static boolean anyCellEquals(org.apache.poi.ss.usermodel.Sheet sheet, String text) {
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            var row = sheet.getRow(i);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                var cell = row.getCell(c);
                if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && text.equals(cell.getStringCellValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void summaryCapturesCompareVariableIntoTable() throws Exception {
        Path filterFile = Files.createTempFile("var-compare", ".filter");
        Files.writeString(filterFile, """
                REQUESTS "List Posts", "List Users";
                $CMP = COMPARE ON id FROM "List Posts", "List Users";
                TABLE $CMP TITLE "Compared";
                """);
        FilterSpec spec = FilterQueryParser.parse(filterFile);
        Path output = Files.createTempFile("report-var-compare", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String posts = "[{\"id\":1},{\"id\":2}]";
        String users = "[{\"id\":2},{\"id\":3}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", posts, posts, Instant.now(), List.of()),
                new ExecutionResult("Root", "List Users", "GET", "https://example.com/users",
                        200, 50, true, "", users, users, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var summary = wb.getSheet("Summary");
            assertNotNull(summary);
            // The captured COMPARE matrix should render its provenance columns into the summary table.
            assertTrue(anyCellEquals(summary, "_count"), "Summary should contain the COMPARE _count column");
            assertTrue(anyCellEquals(summary, "Compared"), "Summary should contain the table title");
        }
    }

    @Test
    void summaryDerivedVariableFiltersBaseVariable() throws Exception {
        Path filterFile = Files.createTempFile("var-derived", ".filter");
        Files.writeString(filterFile, """
                REQUESTS "List Posts";
                $ALL = FILTER "List Posts" WHERE id > 0;
                $TOP = FILTER $ALL WHERE id > 2;
                KV "All" $ALL;
                KV "Top" $TOP;
                """);
        FilterSpec spec = FilterQueryParser.parse(filterFile);
        Path output = Files.createTempFile("report-var-derived", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String body = "[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var summary = wb.getSheet("Summary");
            assertNotNull(summary);
            assertEquals("5", kvValue(summary, "All"));   // all 5 rows
            assertEquals("3", kvValue(summary, "Top"));   // derived: id > 2 → 3,4,5
        }
    }

    @Test
    void whereValueVariableResolvesFromRuntimeVariables() throws Exception {
        Path filterFile = Files.createTempFile("var-where", ".filter");
        Files.writeString(filterFile, """
                REQUESTS "List Posts";
                $M = FILTER "List Posts" WHERE userId = $targetUser;
                KV "Matches" $M;
                """);
        FilterSpec spec = FilterQueryParser.parse(filterFile);
        Path output = Files.createTempFile("report-var-where", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of("targetUser", "1"), spec);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());

        String body = "[{\"id\":1,\"userId\":1},{\"id\":2,\"userId\":1},{\"id\":3,\"userId\":1},"
                + "{\"id\":4,\"userId\":2},{\"id\":5,\"userId\":2}]";
        List<ExecutionResult> results = List.of(
                new ExecutionResult("Root", "List Posts", "GET", "https://example.com/posts",
                        200, 50, true, "", body, body, Instant.now(), List.of())
        );

        new ExcelReportGenerator().generate(collection, results, config, new RequestExecutor());

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            var summary = wb.getSheet("Summary");
            assertNotNull(summary);
            // $targetUser resolves to "1" from runtime variables → 3 rows match userId == 1
            assertEquals("3", kvValue(summary, "Matches"));
        }
    }
}
