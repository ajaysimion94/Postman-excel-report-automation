package com.automation.excel;

import com.automation.model.ExecutionResult;
import com.automation.model.RuntimeConfig;
import com.automation.postman.PostmanCollection;
import com.automation.filter.FilterSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ExcelReportGenerator {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    /** Excel hard limit is 1,048,576 rows. Reserve 3 for title + blank + header. */
    private static final int MAX_DATA_ROWS = 1_048_573;

    public Path generate(PostmanCollection collection, List<ExecutionResult> results, RuntimeConfig config) throws IOException {
        Path outputPath = config.outputPath();
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            SheetStyleFactory styleFactory = new SheetStyleFactory();
            createSummarySheet(workbook, styleFactory, collection, results);
            createResultsSheet(workbook, styleFactory, results, config.includeResponseBody());
            createFolderSheets(workbook, styleFactory, results, config.includeResponseBody());
            createResponseDataSheets(workbook, styleFactory, results, config.filterSpec());

            try (OutputStream outputStream = Files.newOutputStream(outputPath)) {
                workbook.write(outputStream);
            }
        }

        return outputPath;
    }

    private void createSummarySheet(Workbook workbook, SheetStyleFactory styleFactory, PostmanCollection collection, List<ExecutionResult> results) {
        Sheet sheet = workbook.createSheet("Summary");
        CellStyle titleStyle = styleFactory.createTitleStyle(workbook, IndexedColors.DARK_BLUE);
        CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.BLUE_GREY);
        CellStyle textStyle = styleFactory.createTextStyle(workbook, false);

        createTitleRow(sheet, titleStyle, "Execution Summary");
        createKeyValueHeader(sheet, 2, headerStyle);

        long successCount = results.stream().filter(ExecutionResult::success).count();
        long failureCount = results.size() - successCount;
        long averageDuration = results.stream().mapToLong(ExecutionResult::durationMillis).sum() / Math.max(results.size(), 1);

        writeKeyValueRow(sheet, 3, "Collection", collection.name(), textStyle);
        writeKeyValueRow(sheet, 4, "Requests", String.valueOf(results.size()), textStyle);
        writeKeyValueRow(sheet, 5, "Passed", String.valueOf(successCount), textStyle);
        writeKeyValueRow(sheet, 6, "Failed", String.valueOf(failureCount), textStyle);
        writeKeyValueRow(sheet, 7, "Average Duration (ms)", String.valueOf(averageDuration), textStyle);
        writeKeyValueRow(sheet, 8, "Generated At", TIMESTAMP_FORMAT.format(java.time.Instant.now()), textStyle);

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    private void createResultsSheet(Workbook workbook, SheetStyleFactory styleFactory, List<ExecutionResult> results, boolean includeBody) {
        Sheet sheet = workbook.createSheet("Results");
        CellStyle titleStyle = styleFactory.createTitleStyle(workbook, IndexedColors.DARK_GREEN);
        CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.SEA_GREEN);
        CellStyle textStyle = styleFactory.createTextStyle(workbook, includeBody);
        CellStyle successStyle = styleFactory.createStatusStyle(workbook, IndexedColors.LIGHT_GREEN);
        CellStyle failureStyle = styleFactory.createStatusStyle(workbook, IndexedColors.ROSE);

        createTitleRow(sheet, titleStyle, "Request Results");

        Row headerRow = sheet.createRow(2);
        String[] headers = includeBody
                ? new String[]{"Folder", "Request", "Method", "URL", "Status", "Duration (ms)", "Success", "Executed At", "Error", "Assertions", "Response Body"}
                : new String[]{"Folder", "Request", "Method", "URL", "Status", "Duration (ms)", "Success", "Executed At", "Error", "Assertions"};

        for (int column = 0; column < headers.length; column++) {
            Cell cell = headerRow.createCell(column);
            cell.setCellValue(headers[column]);
            cell.setCellStyle(headerStyle);
        }

        int rowIndex = 3;
        for (ExecutionResult result : results) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            setCell(row, column++, result.folderPath(), textStyle);
            setCell(row, column++, result.requestName(), textStyle);
            setCell(row, column++, result.method(), textStyle);
            setCell(row, column++, result.url(), textStyle);
            setCell(row, column++, String.valueOf(result.statusCode()), result.success() ? successStyle : failureStyle);
            setCell(row, column++, String.valueOf(result.durationMillis()), textStyle);
            setCell(row, column++, String.valueOf(result.success()), result.success() ? successStyle : failureStyle);
            setCell(row, column++, TIMESTAMP_FORMAT.format(result.executedAt()), textStyle);
            setCell(row, column++, result.errorMessage(), textStyle);
            setCell(row, column++, String.join(" | ", result.assertions()), result.success() ? successStyle : failureStyle);
            if (includeBody) {
                setCell(row, column, result.displayBody(), textStyle);
            }
        }

        sheet.createFreezePane(0, 3);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(2, 2, 0, headers.length - 1));
        autoSize(sheet, headers.length);
    }

    private void createFolderSheets(Workbook workbook, SheetStyleFactory styleFactory, List<ExecutionResult> results, boolean includeBody) {
        Map<String, List<ExecutionResult>> grouped = results.stream()
                .filter(result -> result.folderPath() != null && !result.folderPath().isBlank())
                .collect(Collectors.groupingBy(ExecutionResult::folderPath, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<ExecutionResult>> entry : grouped.entrySet()) {
            String sheetName = safeSheetName(entry.getKey());
            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle titleStyle = styleFactory.createTitleStyle(workbook, IndexedColors.BROWN);
            CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.GOLD);
            CellStyle textStyle = styleFactory.createTextStyle(workbook, includeBody);

            createTitleRow(sheet, titleStyle, entry.getKey());
            Row headerRow = sheet.createRow(2);
            String[] headers = includeBody
                    ? new String[]{"Request", "Method", "Status", "Duration (ms)", "URL", "Error", "Assertions", "Response Body"}
                    : new String[]{"Request", "Method", "Status", "Duration (ms)", "URL", "Error", "Assertions"};

            for (int column = 0; column < headers.length; column++) {
                Cell cell = headerRow.createCell(column);
                cell.setCellValue(headers[column]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 3;
            for (ExecutionResult result : entry.getValue()) {
                Row row = sheet.createRow(rowIndex++);
                int column = 0;
                setCell(row, column++, result.requestName(), textStyle);
                setCell(row, column++, result.method(), textStyle);
                setCell(row, column++, String.valueOf(result.statusCode()), textStyle);
                setCell(row, column++, String.valueOf(result.durationMillis()), textStyle);
                setCell(row, column++, result.url(), textStyle);
                setCell(row, column++, result.errorMessage(), textStyle);
                setCell(row, column++, String.join(" | ", result.assertions()), textStyle);
                if (includeBody) {
                    setCell(row, column, result.displayBody(), textStyle);
                }
            }

            sheet.createFreezePane(0, 3);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(2, 2, 0, headers.length - 1));
            autoSize(sheet, headers.length);
        }
    }

    private void createTitleRow(Sheet sheet, CellStyle style, String title) {
        Row titleRow = sheet.createRow(0);
        Cell cell = titleRow.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(style);
    }

    private void createKeyValueHeader(Sheet sheet, int rowIndex, CellStyle headerStyle) {
        Row row = sheet.createRow(rowIndex);
        Cell keyCell = row.createCell(0);
        keyCell.setCellValue("Metric");
        keyCell.setCellStyle(headerStyle);
        Cell valueCell = row.createCell(1);
        valueCell.setCellValue("Value");
        valueCell.setCellStyle(headerStyle);
    }

    private void writeKeyValueRow(Sheet sheet, int rowIndex, String key, String value, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        setCell(row, 0, key, style);
        setCell(row, 1, value, style);
    }

    private void setCell(Row row, int columnIndex, String value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private void autoSize(Sheet sheet, int columns) {
        for (int index = 0; index < columns; index++) {
            sheet.autoSizeColumn(index);
            int currentWidth = sheet.getColumnWidth(index);
            sheet.setColumnWidth(index, Math.min(currentWidth + 1000, 20000));
        }
    }

    private void createResponseDataSheets(Workbook workbook, SheetStyleFactory styleFactory, List<ExecutionResult> results, FilterSpec filterSpec) {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> usedNames = new HashSet<>();

        for (ExecutionResult result : results) {
            String body = result.responseBody();
            if (body == null || body.isBlank()) continue;

            JsonNode root;
            try {
                root = mapper.readTree(body);
            } catch (Exception e) {
                continue;
            }

            List<ObjectNode> rows = extractResponseRows(root);
            if (rows.isEmpty()) continue;

            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (ObjectNode row : rows) {
                row.fieldNames().forEachRemaining(keys::add);
            }
            if (keys.isEmpty()) continue;

            String sheetName = uniqueSheetName(safeSheetName(result.requestName()), usedNames);
            usedNames.add(sheetName);

            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle titleStyle = styleFactory.createTitleStyle(workbook, IndexedColors.TEAL);
            CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.DARK_TEAL);
            CellStyle textStyle = styleFactory.createTextStyle(workbook, false);

            createTitleRow(sheet, titleStyle, result.requestName() + " — Response Data");

            // Apply column filter from FilterSpec
            List<String> keyList = new ArrayList<>(keys);
            if (filterSpec != null && filterSpec.responseColumns() != null) {
                List<String> allowed = filterSpec.responseColumns().getOrDefault(
                        result.requestName(),
                        filterSpec.responseColumns().get("*"));
                if (allowed != null) {
                    List<String> ordered = new ArrayList<>(allowed);
                    ordered.retainAll(keys); // keep only columns that actually exist
                    keyList = ordered;
                }
            }
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < keyList.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(keyList.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 3;
            int written = 0;
            for (ObjectNode rowNode : rows) {
                if (written >= MAX_DATA_ROWS) {
                    // Write a notice row so the user knows data was capped
                    Row notice = sheet.createRow(rowIndex);
                    CellStyle noticeStyle = styleFactory.createStatusStyle(workbook, IndexedColors.ROSE);
                    Cell noticeCell = notice.createCell(0);
                    noticeCell.setCellValue(String.format(
                            "[TRUNCATED] %,d total rows exceeded the Excel limit of %,d. Increase MAX_RESPONSE_MB or use --filter to narrow the result set.",
                            rows.size(), MAX_DATA_ROWS));
                    noticeCell.setCellStyle(noticeStyle);
                    break;
                }
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < keyList.size(); i++) {
                    JsonNode val = rowNode.get(keyList.get(i));
                    setCell(row, i, jsonNodeToString(val), textStyle);
                }
                written++;
            }

            sheet.createFreezePane(0, 3);
            sheet.setAutoFilter(new CellRangeAddress(2, 2, 0, keyList.size() - 1));
            autoSize(sheet, keyList.size());
        }
    }

    private List<ObjectNode> extractResponseRows(JsonNode root) {
        if (root.isArray()) {
            List<ObjectNode> list = new ArrayList<>();
            for (JsonNode item : root) {
                if (item.isObject()) list.add((ObjectNode) item);
            }
            return list;
        }
        if (root.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                JsonNode val = fields.next().getValue();
                if (val.isArray()) {
                    List<ObjectNode> list = new ArrayList<>();
                    for (JsonNode item : val) {
                        if (item.isObject()) list.add((ObjectNode) item);
                    }
                    if (!list.isEmpty()) return list;
                }
            }
            return List.of((ObjectNode) root);
        }
        return List.of();
    }

    private String jsonNodeToString(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual() || node.isNumber() || node.isBoolean()) return node.asText();
        return node.toString();
    }

    private String uniqueSheetName(String base, Set<String> used) {
        if (!used.contains(base)) return base;
        int i = 2;
        String candidate;
        do {
            String suffix = " " + i++;
            candidate = base.length() + suffix.length() <= 31
                ? base + suffix
                : base.substring(0, 31 - suffix.length()) + suffix;
        } while (used.contains(candidate));
        return candidate;
    }

    private String safeSheetName(String value) {
        String cleaned = value.replaceAll("[:\\\\/?*\\[\\]]", "-");
        if (cleaned.length() > 31) {
            return cleaned.substring(0, 31);
        }
        return cleaned;
    }
}