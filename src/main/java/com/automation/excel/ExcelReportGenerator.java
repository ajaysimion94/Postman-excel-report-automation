package com.automation.excel;

import com.automation.model.ExecutionResult;
import com.automation.model.RuntimeConfig;
import com.automation.postman.PostmanCollection;
import com.automation.postman.RequestSpec;
import com.automation.filter.CustomTableJoinCondition;
import com.automation.filter.CustomTableJoinSource;
import com.automation.filter.CustomTableSpec;
import com.automation.filter.AggregateSpec;
import com.automation.filter.DataShapeSpec;
import com.automation.filter.DateFieldConfig;
import com.automation.filter.FilterSpec;
import com.automation.filter.RowConditionEvaluator;
import com.automation.filter.RowFilterGroup;
import com.automation.filter.SortSpec;
import com.automation.filter.UnionSpec;
import com.automation.http.RequestExecutor;
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
import java.util.Comparator;
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

    public Path generate(PostmanCollection collection, List<ExecutionResult> results,
                         RuntimeConfig config, RequestExecutor executor) throws IOException {
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
            createCustomTableSheets(workbook, styleFactory, results, config.filterSpec(), usedSheetNames,
                    collection, config, executor);
                createUnionSheets(workbook, styleFactory, results, config.filterSpec(), usedSheetNames);

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
            rows = applyDataShape(rows, result.requestName(), filterSpec);

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

    private List<ObjectNode> applyDataShape(List<ObjectNode> rows, String key, FilterSpec filterSpec) {
        if (rows == null || rows.isEmpty() || filterSpec == null || filterSpec.dataShapes() == null) {
            return rows;
        }
        DataShapeSpec shape = filterSpec.dataShapes().getOrDefault(key, filterSpec.dataShapes().get("*"));
        if (shape == null) {
            return rows;
        }

        List<ObjectNode> shaped = new ArrayList<>(rows);

        if ((shape.groupBy() != null && !shape.groupBy().isEmpty()) || (shape.aggregates() != null && !shape.aggregates().isEmpty())) {
            shaped = applyGrouping(shaped, shape);
            if (shape.having() != null) {
                Instant now = Instant.now();
                shaped = shaped.stream()
                        .filter(row -> RowConditionEvaluator.evaluate(row, shape.having(), Collections.emptyMap(), now))
                        .collect(Collectors.toList());
            }
        }

        if (shape.distinct()) {
            Map<String, ObjectNode> unique = new LinkedHashMap<>();
            for (ObjectNode row : shaped) {
                unique.putIfAbsent(row.toString(), row);
            }
            shaped = new ArrayList<>(unique.values());
        }

        if (shape.orderBy() != null && !shape.orderBy().isEmpty()) {
            Comparator<ObjectNode> comparator = null;
            for (SortSpec sort : shape.orderBy()) {
                Comparator<ObjectNode> c = (left, right) -> compareField(left, right, sort.field(), sort.descending());
                comparator = comparator == null ? c : comparator.thenComparing(c);
            }
            shaped.sort(comparator);
        }

        int from = shape.offset() == null ? 0 : Math.max(0, shape.offset());
        if (from > shaped.size()) {
            return List.of();
        }
        int to = shaped.size();
        if (shape.limit() != null) {
            to = Math.min(shaped.size(), from + Math.max(0, shape.limit()));
        }
        return new ArrayList<>(shaped.subList(from, to));
    }

    private List<ObjectNode> applyGrouping(List<ObjectNode> rows, DataShapeSpec shape) {
        List<String> groupFields = shape.groupBy() == null ? List.of() : shape.groupBy();
        List<AggregateSpec> aggregates = shape.aggregates() == null ? List.of() : shape.aggregates();

        if (groupFields.isEmpty() && aggregates.isEmpty()) {
            return rows;
        }

        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode row : rows) {
            String key = buildGroupKey(row, groupFields);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> out = new ArrayList<>();
        for (List<ObjectNode> members : groups.values()) {
            ObjectNode grouped = mapper.createObjectNode();
            ObjectNode first = members.get(0);
            for (String field : groupFields) {
                JsonNode value = first.get(field);
                if (value != null) {
                    grouped.set(field, value);
                }
            }
            for (AggregateSpec aggregate : aggregates) {
                applyAggregate(grouped, members, aggregate);
            }
            out.add(grouped);
        }
        return out;
    }

    private String buildGroupKey(ObjectNode row, List<String> fields) {
        if (fields.isEmpty()) {
            return "__all__";
        }
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            JsonNode value = row.get(field);
            sb.append(value == null || value.isNull() ? "<null>" : value.asText(""))
                    .append('\u001F');
        }
        return sb.toString();
    }

    private void applyAggregate(ObjectNode target, List<ObjectNode> members, AggregateSpec aggregate) {
        String fn = aggregate.function().toUpperCase();
        String field = aggregate.field();
        String alias = aggregate.alias();

        switch (fn) {
            case "COUNT" -> {
                long count;
                if ("*".equals(field)) {
                    count = members.size();
                } else {
                    count = members.stream()
                            .map(row -> row.get(field))
                            .filter(node -> node != null && !node.isNull())
                            .count();
                }
                target.put(alias, count);
            }
            case "SUM", "AVG" -> {
                double sum = 0;
                int count = 0;
                for (ObjectNode row : members) {
                    JsonNode node = row.get(field);
                    if (node == null || node.isNull()) continue;
                    try {
                        sum += Double.parseDouble(node.asText(""));
                        count++;
                    } catch (NumberFormatException ignored) {
                    }
                }
                if ("AVG".equals(fn)) {
                    target.put(alias, count == 0 ? 0 : (sum / count));
                } else {
                    target.put(alias, sum);
                }
            }
            case "MIN", "MAX" -> {
                String best = null;
                for (ObjectNode row : members) {
                    JsonNode node = row.get(field);
                    if (node == null || node.isNull()) continue;
                    String value = node.asText("");
                    if (best == null) {
                        best = value;
                        continue;
                    }
                    int cmp = comparePossiblyNumeric(value, best);
                    if (("MIN".equals(fn) && cmp < 0) || ("MAX".equals(fn) && cmp > 0)) {
                        best = value;
                    }
                }
                target.put(alias, best == null ? "" : best);
            }
            default -> throw new IllegalArgumentException("Unsupported aggregate function: " + aggregate.function());
        }
    }

    private int compareField(ObjectNode left, ObjectNode right, String field, boolean descending) {
        JsonNode lv = left.get(field);
        JsonNode rv = right.get(field);

        int result;
        if (lv == null || lv.isNull()) {
            result = (rv == null || rv.isNull()) ? 0 : 1;
        } else if (rv == null || rv.isNull()) {
            result = -1;
        } else {
            result = comparePossiblyNumeric(lv.asText(""), rv.asText(""));
        }
        return descending ? -result : result;
    }

    private int comparePossiblyNumeric(String a, String b) {
        try {
            double ad = Double.parseDouble(a);
            double bd = Double.parseDouble(b);
            return Double.compare(ad, bd);
        } catch (NumberFormatException ignored) {
            return a.compareToIgnoreCase(b);
        }
    }

    // ── custom table sheets ───────────────────────────────────────────────────────

    /**
     * Generates one sheet per {@link CustomTableSpec} defined in {@code filterSpec}.
     * Supports single-source tables and two-source inner-join tables.
     */
    private void createCustomTableSheets(Workbook workbook, SheetStyleFactory styleFactory,
                                         List<ExecutionResult> results, FilterSpec filterSpec,
                                         Set<String> usedNames,
                                         PostmanCollection collection, RuntimeConfig config,
                                         RequestExecutor executor) {
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

            if (tableSpec.lookupRequest() != null) {
                // ── lookup (nested) table ─────────────────────────────────────
                sourceRequestName = tableSpec.sourceRequest();
                List<ObjectNode> sourceRows = new ArrayList<>(rowsByRequest.getOrDefault(sourceRequestName, List.of()));
                tableRows = buildLookupRows(tableSpec, sourceRows, collection, config, executor, mapper);
            } else if (tableSpec.sourceRequest() != null) {
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

            tableRows = applyDataShape(tableRows, tableSpec.name(), filterSpec);

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

    private void createUnionSheets(Workbook workbook, SheetStyleFactory styleFactory,
                                   List<ExecutionResult> results, FilterSpec filterSpec,
                                   Set<String> usedNames) {
        if (filterSpec == null || filterSpec.unions() == null || filterSpec.unions().isEmpty()) {
            return;
        }

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
            } catch (Exception ignored) {
            }
        }

        for (UnionSpec union : filterSpec.unions()) {
            List<ObjectNode> unionRows = new ArrayList<>();
            for (String source : union.sources()) {
                List<ObjectNode> sourceRows = new ArrayList<>(rowsByRequest.getOrDefault(source, List.of()));
                sourceRows = applyRowFilter(sourceRows, source, filterSpec);
                unionRows.addAll(sourceRows);
            }

            if (!union.all()) {
                Map<String, ObjectNode> unique = new LinkedHashMap<>();
                for (ObjectNode row : unionRows) {
                    unique.putIfAbsent(row.toString(), row);
                }
                unionRows = new ArrayList<>(unique.values());
            }

            unionRows = applyDataShape(unionRows, union.name(), filterSpec);
            if (unionRows.isEmpty()) {
                continue;
            }

            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (ObjectNode row : unionRows) {
                row.fieldNames().forEachRemaining(keys::add);
            }
            List<String> keyList = new ArrayList<>(keys);

            String sheetName = uniqueSheetName(safeSheetName(union.name()), usedNames);
            usedNames.add(sheetName);

            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle titleStyle = styleFactory.createTitleStyle(workbook, IndexedColors.DARK_RED);
            CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.RED);
            CellStyle textStyle = styleFactory.createTextStyle(workbook, false);

            createTitleRow(sheet, titleStyle, union.name() + " — UNION");
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < keyList.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(keyList.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 3;
            int written = 0;
            for (ObjectNode rowNode : unionRows) {
                if (written >= MAX_DATA_ROWS) {
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
        ObjectMapper mapper = new ObjectMapper();
        String joinType = tableSpec.joinType() == null ? "INNER" : tableSpec.joinType().toUpperCase();

        CustomTableJoinSource first = sources.get(0);
        String firstAlias = first.as() != null ? first.as() : first.request();
        List<ObjectNode> seedRows = rowsByRequest.getOrDefault(first.request(), List.of());
        List<ObjectNode> accumulated = new ArrayList<>();
        for (ObjectNode row : seedRows) {
            accumulated.add(decorateRow(row, firstAlias, mapper));
        }

        for (int idx = 1; idx < sources.size(); idx++) {
            CustomTableJoinSource rightSource = sources.get(idx);
            String rightAlias = rightSource.as() != null ? rightSource.as() : rightSource.request();
            List<ObjectNode> rightRows = rowsByRequest.getOrDefault(rightSource.request(), List.of());

            List<CustomTableJoinCondition> hopConditions = resolveJoinConditionsForHop(tableSpec.joinOn(), sources.size(), idx);
            accumulated = joinHop(accumulated, rightRows, rightAlias, hopConditions, joinType, mapper);
        }

        return accumulated;
    }

    private List<CustomTableJoinCondition> resolveJoinConditionsForHop(List<CustomTableJoinCondition> all,
                                                                       int sourceCount,
                                                                       int hopIndex) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (sourceCount <= 2) {
            return all;
        }
        if (all.size() == 1) {
            return all;
        }
        // one condition per hop when sourceCount > 2
        return List.of(all.get(hopIndex - 1));
    }

    private List<ObjectNode> joinHop(List<ObjectNode> leftRows,
                                     List<ObjectNode> rightRows,
                                     String rightAlias,
                                     List<CustomTableJoinCondition> conditions,
                                     String joinType,
                                     ObjectMapper mapper) {
        List<ObjectNode> out = new ArrayList<>();
        Set<Integer> matchedRight = new HashSet<>();

        for (ObjectNode left : leftRows) {
            boolean found = false;
            for (int idx = 0; idx < rightRows.size(); idx++) {
                ObjectNode right = rightRows.get(idx);
                if (!matchesAll(left, right, conditions)) {
                    continue;
                }
                found = true;
                matchedRight.add(idx);
                out.add(mergeRows(left, right, rightAlias, mapper));
            }
            if (!found && ("LEFT".equals(joinType) || "FULL".equals(joinType))) {
                out.add(left.deepCopy());
            }
        }

        if ("RIGHT".equals(joinType) || "FULL".equals(joinType)) {
            for (int idx = 0; idx < rightRows.size(); idx++) {
                if (matchedRight.contains(idx)) {
                    continue;
                }
                out.add(decorateRow(rightRows.get(idx), rightAlias, mapper));
            }
        }

        return out;
    }

    private boolean matchesAll(ObjectNode left, ObjectNode right, List<CustomTableJoinCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (CustomTableJoinCondition condition : conditions) {
            JsonNode leftValue = resolveField(left, condition.leftField(), true);
            JsonNode rightValue = resolveField(right, condition.rightField(), false);
            if (leftValue == null || rightValue == null || !leftValue.asText("").equals(rightValue.asText(""))) {
                return false;
            }
        }
        return true;
    }

    private JsonNode resolveField(ObjectNode row, String field, boolean searchAliasSuffix) {
        if (field == null || field.isBlank()) {
            return null;
        }
        JsonNode direct = row.get(field);
        if (direct != null) {
            return direct;
        }

        String trimmed = field;
        int dot = field.indexOf('.');
        if (dot >= 0 && dot < field.length() - 1) {
            trimmed = field.substring(dot + 1);
            JsonNode aliasDirect = row.get(field);
            if (aliasDirect != null) {
                return aliasDirect;
            }
            JsonNode unprefixed = row.get(trimmed);
            if (unprefixed != null) {
                return unprefixed;
            }
        }

        if (searchAliasSuffix) {
            Iterator<String> fields = row.fieldNames();
            while (fields.hasNext()) {
                String candidate = fields.next();
                if (candidate.endsWith("." + trimmed)) {
                    return row.get(candidate);
                }
            }
        }
        return null;
    }

    private ObjectNode decorateRow(ObjectNode row, String alias, ObjectMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        row.fieldNames().forEachRemaining(field -> {
            JsonNode value = row.get(field);
            out.set(alias + "." + field, value);
            if (!out.has(field)) {
                out.set(field, value);
            }
        });
        return out;
    }

    private ObjectNode mergeRows(ObjectNode left, ObjectNode right, String rightAlias, ObjectMapper mapper) {
        ObjectNode merged = left.deepCopy();
        right.fieldNames().forEachRemaining(field -> {
            JsonNode value = right.get(field);
            merged.set(rightAlias + "." + field, value);
            if (!merged.has(field)) {
                merged.set(field, value);
            }
        });
        return merged;
    }

    /**
     * Builds rows for a lookup (nested) custom table.
     *
     * <p>For each row in {@code sourceRows}, the value of {@code tableSpec.lookupParam()} is
     * extracted and injected as a variable override into {@code tableSpec.lookupRequest()}.
     * The detail response is fetched, merged with the source row, and added to the result.
     *
     * <p>Conflicting field names from the detail response are stored with a {@code "detail."}
     * prefix; unambiguous fields are stored both with and without the prefix for convenience.
     */
    private List<ObjectNode> buildLookupRows(CustomTableSpec tableSpec,
                                             List<ObjectNode> sourceRows,
                                             PostmanCollection collection,
                                             RuntimeConfig config,
                                             RequestExecutor executor,
                                             ObjectMapper mapper) {
        String lookupRequestName = tableSpec.lookupRequest();
        String lookupParam       = tableSpec.lookupParam();

        RequestSpec lookupSpec = collection.requests().stream()
                .filter(r -> r.name().equals(lookupRequestName))
                .findFirst()
                .orElse(null);
        if (lookupSpec == null) {
            System.err.printf("[WARN] Lookup table \"%s\": lookupRequest \"%s\" not found in collection — skipping.%n",
                    tableSpec.name(), lookupRequestName);
            return List.of();
        }

        // Build base variables once (collection defaults + config overrides)
        Map<String, String> baseVars = new LinkedHashMap<>(collection.variables());
        baseVars.putAll(config.variables());

        int timeoutSeconds   = parseIntVar(config.variables(), "REQUEST_TIMEOUT_SECONDS", 30);
        int maxResponseBytes = parseMbVar(config.variables(), "MAX_RESPONSE_MB", 10 * 1024 * 1024);

        List<ObjectNode> result = new ArrayList<>();
        int total   = sourceRows.size();
        int success = 0;
        int failed  = 0;

        System.out.printf("[INFO] Lookup table \"%s\": executing \"%s\" for %d source rows…%n",
                tableSpec.name(), lookupRequestName, total);

        for (ObjectNode sourceRow : sourceRows) {
            JsonNode paramNode = sourceRow.get(lookupParam);
            if (paramNode == null || paramNode.isNull()) {
                failed++;
                continue; // skip rows where the lookup key is absent
            }
            String paramValue = paramNode.asText();

            // Inject the param value as a variable so {{lookupParam}} in the URL is resolved
            Map<String, String> overrideVars = Map.of(lookupParam, paramValue);

            ExecutionResult lookupResult = executor.executeSingle(
                    lookupSpec, baseVars, overrideVars, timeoutSeconds, maxResponseBytes);

            if (!lookupResult.success() || lookupResult.responseBody() == null
                    || lookupResult.responseBody().isBlank()) {
                failed++;
                continue;
            }

            // Parse the detail response — expect a single object or the first element of an array
            List<ObjectNode> detailRows;
            try {
                JsonNode detailRoot = mapper.readTree(lookupResult.responseBody());
                detailRows = extractResponseRows(detailRoot);
            } catch (Exception e) {
                failed++;
                continue;
            }

            if (detailRows.isEmpty()) {
                failed++;
                continue;
            }

            // Use the first (or only) detail object and merge with the source row
            ObjectNode detailRow = detailRows.get(0);

            Set<String> sourceFields = new LinkedHashSet<>();
            sourceRow.fieldNames().forEachRemaining(sourceFields::add);
            Set<String> detailFields = new LinkedHashSet<>();
            detailRow.fieldNames().forEachRemaining(detailFields::add);

            ObjectNode merged = mapper.createObjectNode();
            // All source fields go in as-is
            sourceFields.forEach(f -> merged.set(f, sourceRow.get(f)));
            // Detail fields: always store with "detail." prefix; also store without prefix if no clash
            detailFields.forEach(f -> {
                merged.set("detail." + f, detailRow.get(f));
                if (!sourceFields.contains(f)) {
                    merged.set(f, detailRow.get(f));
                }
            });

            result.add(merged);
            success++;
        }

        System.out.printf("[INFO] Lookup table \"%s\": %d merged, %d skipped/failed (total %d).%n",
                tableSpec.name(), success, failed, total);
        return result;
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

    private static int parseIntVar(Map<String, String> vars, String key, int defaultValue) {
        String val = vars.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Integer.parseInt(val.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static int parseMbVar(Map<String, String> vars, String key, int defaultBytes) {
        String val = vars.get(key);
        if (val == null || val.isBlank()) return defaultBytes;
        try { return (int) (Double.parseDouble(val.trim()) * 1024 * 1024); } catch (NumberFormatException e) { return defaultBytes; }
    }
}