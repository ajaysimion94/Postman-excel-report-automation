package com.automation;

import com.automation.excel.ExcelReportGenerator;
import com.automation.filter.CustomTableSpec;
import com.automation.filter.FilterSpec;
import com.automation.filter.RowFilterGroup;
import com.automation.filter.RowFilterRule;
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
    void generatesWorkbookWithStyledSheets() throws Exception {
        Path output = Files.createTempFile("report", ".xlsx");
        RuntimeConfig config = new RuntimeConfig(output, null, output, true, Map.of(), null);
        PostmanCollection collection = new PostmanCollection("Demo", Map.of(), List.of());
        List<ExecutionResult> results = List.of(
            new ExecutionResult("Users", "List Users", "GET", "https://example.com/users", 200, 120, true, "", "{\"ok\":true}", "{\"ok\":true}", Instant.now(), List.of("Status 2xx: PASS"))
        );

        new ExcelReportGenerator().generate(collection, results, config);

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

        new ExcelReportGenerator().generate(collection, results, config);

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
                null, null,
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

        new ExcelReportGenerator().generate(collection, results, config);

        try (InputStream is = Files.newInputStream(output);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertNotNull(wb.getSheet("My Custom Table"),
                    "Custom table sheet should be present");
            var sheet = wb.getSheet("My Custom Table");
            // Title row + header row + 1 data row (userId=1 only, and only id+title columns)
            assertEquals(3, sheet.getPhysicalNumberOfRows());
        }
    }
}