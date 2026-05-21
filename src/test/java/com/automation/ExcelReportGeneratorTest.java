package com.automation;

import com.automation.excel.ExcelReportGenerator;
import com.automation.filter.CustomTableSpec;
import com.automation.filter.DataShapeSpec;
import com.automation.filter.FilterSpec;
import com.automation.filter.RowFilterGroup;
import com.automation.filter.RowFilterRule;
import com.automation.filter.SortSpec;
import com.automation.filter.AggregateSpec;
import com.automation.filter.ExpandSpec;
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
    void expandUnnestsNestedArrayIntoRows() throws Exception {
        Path output = Files.createTempFile("report-expand", ".xlsx");
        FilterSpec spec = new FilterSpec(null, null, null, null, null, null,
                null, null, null, null, null,
                Map.of("Category List", new ExpandSpec("items")));
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
                Map.of("Products", new ExpandSpec("variants", "extra")));
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
            assertEquals("Results", workbook.getSheetAt(1).getSheetName());
            assertEquals("Users", workbook.getSheetAt(2).getSheetName());
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
                List.of("id", "title"),
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
                        List.of("o.id", "u.name"),
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
                        List.of("o.id", "u.name", "t.teamName"),
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
}