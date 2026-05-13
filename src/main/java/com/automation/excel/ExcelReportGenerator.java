package com.automation.excel;

import com.automation.model.ExecutionResult;
import com.automation.model.RuntimeConfig;
import com.automation.postman.PostmanCollection;
import com.automation.filter.CustomTableJoinCondition;
import com.automation.filter.CustomTableJoinSource;
import com.automation.filter.CustomTableSpec;
import com.automation.filter.DateFieldConfig;
import com.automation.filter.FilterSpec;
import com.automation.filter.RowConditionEvaluator;
import com.automation.filter.RowFilterGroup;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
            Set<String> usedSheetNames = new HashSet<>();
            createResponseDataSheets(workbook, styleFactory, results, config.filterSpec(), usedSheetNames);
            createCustomTableSheets(workbook, styleFactory, results, config.filterSpec(), usedSheetNames);

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

    private void createResponseDataSheets(Workbook workbook, SheetStyleFactory styleFactory, List<ExecutionResult> results, FilterSpec filterSpec, Set<String> usedNames) {
        ObjectMapper mapper = new ObjectMapper();

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

            // Apply row-level filters from filterSpec
            rows = applyRowFilter(rows, result.requestName(), filterSpec);

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

    // ── row filter helpers ────────────────────────────────────────────────────────

    /**
     * Filters {@code rows} against the row conditions defined in {@code filterSpec} for the
     * given {@code requestName}. Request-specific rules take priority over the wildcard.
     * Returns the original list unchanged when no matching rules are configured.
     */
    private List<ObjectNode> applyRowFilter(List<ObjectNode> rows, String requestName, FilterSpec filterSpec) {
        if (filterSpec == null || filterSpec.rowFilters() == null || filterSpec.rowFilters().isEmpty()) {
            return rows;
        }
        RowFilterGroup group = filterSpec.rowFilters().containsKey(requestName)
                ? filterSpec.rowFilters().get(requestName)
                : filterSpec.rowFilters().get("*");
        if (group == null) return rows;

        Map<String, DateFieldConfig> dateFields = resolveDateConfig(requestName, filterSpec);
        Instant now = Instant.now();
        return rows.stream()
                .filter(row -> RowConditionEvaluator.evaluate(row, group, dateFields, now))
                .collect(Collectors.toList());
    }

    /** Merges the wildcard and request-specific date configs, with the specific winning. */
    private Map<String, DateFieldConfig> resolveDateConfig(String requestName, FilterSpec filterSpec) {
        if (filterSpec == null || filterSpec.dateConfig() == null) return Collections.emptyMap();
        Map<String, Map<String, DateFieldConfig>> all = filterSpec.dateConfig();
        Map<String, DateFieldConfig> wildcard = all.getOrDefault("*", Collections.emptyMap());
        Map<String, DateFieldConfig> specific  = all.getOrDefault(requestName, Collections.emptyMap());
        if (specific.isEmpty()) return wildcard;
        if (wildcard.isEmpty()) return specific;
        Map<String, DateFieldConfig> merged = new LinkedHashMap<>(wildcard);
        merged.putAll(specific);
        return merged;
    }

    // ── custom table sheets ───────────────────────────────────────────────────────

    /**
     * Generates one sheet per {@link CustomTableSpec} defined in {@code filterSpec}.
     * Supports single-source tables and two-source inner-join tables.
     */
    private void createCustomTableSheets(Workbook workbook, SheetStyleFactory styleFactory,
                                         List<ExecutionResult> results, FilterSpec filterSpec,
                                         Set<String> usedNames) {
        if (filterSpec == null || filterSpec.customTables() == null || filterSpec.customTables().isEmpty()) {
            return;
        }

        // Build request-name → rows map from executed results (using raw response body)
        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<ObjectNode>> rowsByRequest = new LinkedHashMap<>();
        for (ExecutionResult result : results) {
            String body = result.responseBody();
            if (body == null || body.isBlank()) continue;
            try {
                JsonNode root = mapper.readTree(body);
                List<ObjectNode> rows = extractResponseRows(root);
                if (!rows.isEmpty()) {
                    rowsByRequest.put(result.requestName(), rows);
                }
            } catch (Exception ignored) {}
        }

        for (CustomTableSpec tableSpec : filterSpec.customTables()) {
            List<ObjectNode> tableRows;
            String sourceRequestName = null;

            if (tableSpec.sourceRequest() != null) {
                // ── single-source table ───────────────────────────────────────
                sourceRequestName = tableSpec.sourceRequest();
                tableRows = new ArrayList<>(rowsByRequest.getOrDefault(sourceRequestName, List.of()));
            } else {
                // ── multi-source join table ───────────────────────────────────
                tableRows = buildJoinedRows(tableSpec, rowsByRequest, filterSpec);
                sourceRequestName = null; // mixed sources; use null for date config
            }

            // Apply where-clause row filter
            if (tableSpec.where() != null && !tableRows.isEmpty()) {
                // For single source, use its date config. For joins use wildcard only.
                Map<String, DateFieldConfig> dateFields = sourceRequestName != null
                        ? resolveDateConfig(sourceRequestName, filterSpec)
                        : (filterSpec.dateConfig() != null
                                ? filterSpec.dateConfig().getOrDefault("*", Collections.emptyMap())
                                : Collections.emptyMap());
                Instant now = Instant.now();
                tableRows = tableRows.stream()
                        .filter(row -> RowConditionEvaluator.evaluate(row, tableSpec.where(), dateFields, now))
                        .collect(Collectors.toList());
            }

            if (tableRows.isEmpty()) {
                System.out.printf("[INFO] Custom table \"%s\" produced 0 rows after filtering — sheet skipped.%n", tableSpec.name());
                continue;
            }

            // Determine columns to display
            List<String> keyList = resolveCustomTableColumns(tableRows, tableSpec);

            // Create sheet
            String sheetName = uniqueSheetName(safeSheetName(tableSpec.name()), usedNames);
            usedNames.add(sheetName);
            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle titleStyle  = styleFactory.createTitleStyle(workbook, IndexedColors.VIOLET);
            CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.PLUM);
            CellStyle textStyle   = styleFactory.createTextStyle(workbook, false);

            createTitleRow(sheet, titleStyle, tableSpec.name() + " — Custom Table");
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < keyList.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(keyList.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 3;
            int written  = 0;
            for (ObjectNode rowNode : tableRows) {
                if (written >= MAX_DATA_ROWS) {
                    Row notice = sheet.createRow(rowIndex);
                    CellStyle noticeStyle = styleFactory.createStatusStyle(workbook, IndexedColors.ROSE);
                    Cell noticeCell = notice.createCell(0);
                    noticeCell.setCellValue(String.format(
                            "[TRUNCATED] %,d total rows exceeded the Excel limit of %,d.",
                            tableRows.size(), MAX_DATA_ROWS));
                    noticeCell.setCellStyle(noticeStyle);
                    break;
                }
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < keyList.size(); i++) {
                    // Support "alias.field" column references in joined tables
                    String colRef = keyList.get(i);
                    JsonNode val  = rowNode.get(colRef);
                    setCell(row, i, jsonNodeToString(val), textStyle);
                }
                written++;
            }

            sheet.createFreezePane(0, 3);
            if (!keyList.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(2, 2, 0, keyList.size() - 1));
            }
            autoSize(sheet, keyList.size());
        }
    }

    /**
     * Performs an inner join between the first two sources in {@code tableSpec.sources()}.
     * Merged rows store fields with {@code "alias.field"} keys to avoid collisions.
     * The left-side fields are also stored without prefix for convenience when no overlap exists.
     */
    private List<ObjectNode> buildJoinedRows(CustomTableSpec tableSpec,
                                             Map<String, List<ObjectNode>> rowsByRequest,
                                             FilterSpec filterSpec) {
        List<CustomTableJoinSource> sources = tableSpec.sources();
        if (sources == null || sources.size() < 2) {
            System.err.printf("[WARN] Custom table \"%s\" requires at least 2 sources for a join.%n", tableSpec.name());
            return List.of();
        }

        CustomTableJoinSource leftSrc  = sources.get(0);
        CustomTableJoinSource rightSrc = sources.get(1);
        String leftAlias  = leftSrc.as()  != null ? leftSrc.as()  : leftSrc.request();
        String rightAlias = rightSrc.as() != null ? rightSrc.as() : rightSrc.request();

        List<ObjectNode> leftRows  = rowsByRequest.getOrDefault(leftSrc.request(),  List.of());
        List<ObjectNode> rightRows = rowsByRequest.getOrDefault(rightSrc.request(), List.of());

        List<CustomTableJoinCondition> joinConditions = tableSpec.joinOn() != null
                ? tableSpec.joinOn() : List.of();

        // Build right-side index: rightField value → list of matching right rows (first condition)
        String rightKey = joinConditions.isEmpty() ? null : joinConditions.get(0).rightField();
        String leftKey  = joinConditions.isEmpty() ? null : joinConditions.get(0).leftField();

        Map<String, List<ObjectNode>> rightIndex = new HashMap<>();
        if (rightKey != null) {
            for (ObjectNode rightRow : rightRows) {
                JsonNode keyNode = rightRow.get(rightKey);
                if (keyNode != null && !keyNode.isNull()) {
                    rightIndex.computeIfAbsent(keyNode.asText(""), k -> new ArrayList<>()).add(rightRow);
                }
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> joined = new ArrayList<>();

        for (ObjectNode leftRow : leftRows) {
            String leftKeyVal = leftKey != null && leftRow.get(leftKey) != null
                    ? leftRow.get(leftKey).asText("") : null;

            List<ObjectNode> matchingRight = leftKeyVal != null
                    ? rightIndex.getOrDefault(leftKeyVal, List.of())
                    : rightRows; // no join key = cross join (for small sets)

            for (ObjectNode rightRow : matchingRight) {
                // Verify additional join conditions (conditions 2+)
                boolean conditionsMet = true;
                for (int c = 1; c < joinConditions.size(); c++) {
                    CustomTableJoinCondition cond = joinConditions.get(c);
                    JsonNode lv = leftRow.get(cond.leftField());
                    JsonNode rv = rightRow.get(cond.rightField());
                    if (lv == null || rv == null || !lv.asText("").equals(rv.asText(""))) {
                        conditionsMet = false;
                        break;
                    }
                }
                if (!conditionsMet) continue;

                // Merge: add both sides with alias prefixes
                ObjectNode merged = mapper.createObjectNode();
                leftRow.fieldNames().forEachRemaining(f -> merged.set(leftAlias + "." + f, leftRow.get(f)));
                rightRow.fieldNames().forEachRemaining(f -> merged.set(rightAlias + "." + f, rightRow.get(f)));
                // Also add un-prefixed versions for non-conflicting fields (convenience)
                Set<String> leftFields  = new LinkedHashSet<>();
                Set<String> rightFields = new LinkedHashSet<>();
                leftRow.fieldNames().forEachRemaining(leftFields::add);
                rightRow.fieldNames().forEachRemaining(rightFields::add);
                leftFields.stream().filter(f -> !rightFields.contains(f))
                        .forEach(f -> merged.set(f, leftRow.get(f)));
                rightFields.stream().filter(f -> !leftFields.contains(f))
                        .forEach(f -> merged.set(f, rightRow.get(f)));
                joined.add(merged);
            }
        }
        return joined;
    }

    /** Determines the final ordered column list for a custom table sheet. */
    private List<String> resolveCustomTableColumns(List<ObjectNode> rows, CustomTableSpec tableSpec) {
        if (tableSpec.columns() != null && !tableSpec.columns().isEmpty()) {
            // Use configured columns; keep only those present in any row
            LinkedHashSet<String> available = new LinkedHashSet<>();
            rows.forEach(r -> r.fieldNames().forEachRemaining(available::add));
            List<String> ordered = new ArrayList<>(tableSpec.columns());
            ordered.retainAll(available);
            return ordered;
        }
        // No column spec: use all keys discovered from rows (preserves insertion order)
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        rows.forEach(r -> r.fieldNames().forEachRemaining(keys::add));
        return new ArrayList<>(keys);
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