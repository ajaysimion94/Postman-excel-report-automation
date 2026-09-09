package com.automation.web;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkbookPreviewTest {
    @TempDir Path directory;

    @Test void carriesMergedCellContentsAcrossPageBoundaries() throws Exception {
        Path path = directory.resolve("merged.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Summary");
            sheet.createRow(0).createCell(0).setCellValue("A merged value across pages");
            sheet.createRow(249);
            sheet.addMergedRegion(new CellRangeAddress(0, 249, 0, 1));
            try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        }
        Map<String, Object> preview = WorkbookPreview.read(path, 0, 200, 200);
        List<?> merges = (List<?>) preview.get("merges");
        Map<?, ?> merge = (Map<?, ?>) merges.get(0);
        assertEquals("A merged value across pages", merge.get("text"));
        assertEquals(0, merge.get("firstRow"));
        assertEquals(249, merge.get("lastRow"));
        assertEquals(50, ((List<?>) preview.get("rows")).size());
    }
}
