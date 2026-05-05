package com.automation;

import com.automation.excel.ExcelReportGenerator;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}