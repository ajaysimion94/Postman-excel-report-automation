package com.automation.excel;

import com.automation.model.ExecutionResult;
import com.automation.model.RuntimeConfig;
import com.automation.postman.PostmanCollection;
import com.automation.postman.RequestSpec;
import com.automation.filter.ColumnSpec;
import com.automation.filter.CustomTableJoinCondition;
import com.automation.filter.CustomTableJoinSource;
import com.automation.filter.CustomTableSpec;
import com.automation.filter.AggregateSpec;
import com.automation.filter.DataShapeSpec;
import com.automation.filter.DateFieldConfig;
import com.automation.filter.ExpandSpec;
import com.automation.filter.FilterSpec;
import com.automation.filter.RowConditionEvaluator;
import com.automation.filter.RowFilterGroup;
import com.automation.filter.SortSpec;
import com.automation.filter.SummaryItem.InlineTableRow;
import com.automation.filter.SummaryItem;
import com.automation.filter.SummaryQuerySource;
import com.automation.filter.SummaryQuerySpec;
import com.automation.filter.SummarySpec;
import com.automation.filter.SummaryTextPart;
import com.automation.filter.UnionSpec;
import com.automation.filter.SetOpSpec;
import com.automation.filter.CompareSpec;
import com.automation.http.RequestExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class ExcelReportGenerator {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    /** Excel hard limit is 1,048,576 rows. Reserve 3 for title + blank + header. */
    private static final int MAX_DATA_ROWS = 1_048_573;
    /** Excel hard limit for cell text content. */
    private static final int MAX_CELL_LENGTH = 32_767;

    /** Runtime variable map ({@code $name} references in WHERE values resolve against this). */
    private Map<String, String> runtimeVars = Map.of();

    /** Pre-computed data for a single response-data sheet. */
    private record SheetPayload(String requestName, String baseSheetName,
                                List<ColumnSpec> columns, List<ObjectNode> rows) {}

    public List<Path> generate(PostmanCollection collection, List<ExecutionResult> results,
                               RuntimeConfig config, RequestExecutor executor) throws IOException {
        this.runtimeVars = effectiveVars(config);
        Path outputPath = config.outputPath();
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        // Collect and partition response data across files before opening any workbook
        Set<String> globalSheetNames = new HashSet<>();
        List<SheetPayload> payloads = prepareResponseSheets(results, config.filterSpec(), globalSheetNames);
        List<List<SheetPayload>> parts = partitionPayloads(payloads);
        if (parts.isEmpty()) parts = List.of(List.of());

        List<Path> outputPaths = buildPartPaths(outputPath, parts.size());

        for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
            List<SheetPayload> partPayloads = parts.get(partIndex);
            Path partPath = outputPaths.get(partIndex);

            try (Workbook workbook = new XSSFWorkbook()) {
                SheetStyleFactory styleFactory = new SheetStyleFactory();
                Set<String> partSheetNames = new HashSet<>();

                if (partIndex == 0) {
                    // Reserve the fixed sheet names so folder/response sheets never collide with them.
                    partSheetNames.add("Summary");
                    partSheetNames.add("Index");
                    partSheetNames.add("Results");
                    if (config.filterSpec() != null && config.filterSpec().summary() != null) {
                        createCustomSummarySheet(workbook, styleFactory, collection, results,
                                config.filterSpec(), config, executor);
                    } else {
                        createSummarySheet(workbook, styleFactory, collection, results);
                    }
                    createResultsSheet(workbook, styleFactory, results, config.includeResponseBody());
                    createFolderSheets(workbook, styleFactory, results, config.includeResponseBody(), partSheetNames);
                }

                for (SheetPayload payload : partPayloads) {
                    writeResponseDataSheet(workbook, styleFactory, payload, partSheetNames);
                }

                if (partIndex == 0) {
                    createCustomTableSheets(workbook, styleFactory, results, config.filterSpec(),
                            partSheetNames, collection, config, executor);
                    createUnionSheets(workbook, styleFactory, results, config.filterSpec(), partSheetNames);
                    createSetOpSheets(workbook, styleFactory, results, config.filterSpec(), partSheetNames);
                    createCompareSheets(workbook, styleFactory, results, config.filterSpec(), partSheetNames);
                    createIndexSheet(workbook, styleFactory);
                }

                try (OutputStream outputStream = Files.newOutputStream(partPath)) {
                    workbook.write(outputStream);
                }
            }

            if (parts.size() > 1) {
                System.out.printf("[INFO] Written part %d/%d → %s%n",
                        partIndex + 1, parts.size(), partPath.toAbsolutePath());
            }
        }

        return outputPaths;
    }

    /**
     * Prepares all response-data payloads (filtered, shaped, column-projected) without writing
     * anything to disk. Each payload holds all rows for one request; very large responses that
     * exceed {@link #MAX_DATA_ROWS} are kept intact here — partitioning is done by
     * {@link #partitionPayloads}.
     */
    private List<SheetPayload> prepareResponseSheets(List<ExecutionResult> results,
                                                     FilterSpec filterSpec,
                                                     Set<String> usedNames) {
        ObjectMapper mapper = new ObjectMapper();
        List<SheetPayload> payloads = new ArrayList<>();

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
            rows = expandRows(rows, result.requestName(), filterSpec, mapper);
            if (rows.isEmpty()) continue;

            rows = applyRowFilter(rows, result.requestName(), filterSpec);
            rows = applyDataShape(rows, result.requestName(), filterSpec);

            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (ObjectNode row : rows) row.fieldNames().forEachRemaining(keys::add);
            if (keys.isEmpty()) continue;

            String sheetName = uniqueSheetName(safeSheetName(result.requestName()), usedNames);
            usedNames.add(sheetName);

            List<ColumnSpec> columns = ColumnSpec.project(keys,
                    filterSpec != null && filterSpec.responseColumns() != null
                            ? filterSpec.responseColumns().getOrDefault(
                            result.requestName(),
                            filterSpec.responseColumns().get("*"))
                            : null);

            payloads.add(new SheetPayload(result.requestName(), sheetName, columns, rows));
        }
        return payloads;
    }

    /**
     * Splits payloads into file groups so that no single group exceeds
     * {@link #MAX_DATA_ROWS} rows in total. When a single payload's rows exceed
     * {@code MAX_DATA_ROWS} it is split across multiple consecutive groups.
     */
    private List<List<SheetPayload>> partitionPayloads(List<SheetPayload> payloads) {
        List<List<SheetPayload>> parts = new ArrayList<>();
        List<SheetPayload> current = new ArrayList<>();
        int usedRows = 0;

        for (SheetPayload payload : payloads) {
            int offset = 0;
            int remaining = payload.rows().size();

            while (remaining > 0) {
                int available = MAX_DATA_ROWS - usedRows;
                if (available <= 0) {
                    parts.add(current);
                    current = new ArrayList<>();
                    usedRows = 0;
                    available = MAX_DATA_ROWS;
                }
                int take = Math.min(available, remaining);
                List<ObjectNode> chunk = new ArrayList<>(payload.rows().subList(offset, offset + take));
                current.add(new SheetPayload(payload.requestName(), payload.baseSheetName(),
                        payload.columns(), chunk));
                offset += take;
                remaining -= take;
                usedRows += take;
            }
        }
        if (!current.isEmpty()) parts.add(current);
        return parts;
    }

    /**
     * Derives output file paths for each part. Single-part output keeps the original name;
     * multi-part output produces {@code stem-part1.ext}, {@code stem-part2.ext}, …
     */
    private List<Path> buildPartPaths(Path base, int partCount) {
        if (partCount == 1) return List.of(base);
        String filename = base.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = dot >= 0 ? filename.substring(0, dot) : filename;
        String ext  = dot >= 0 ? filename.substring(dot) : "";
        Path dir = base.getParent();
        return IntStream.rangeClosed(1, partCount)
                .mapToObj(i -> {
                    String name = stem + "-part" + i + ext;
                    return dir != null ? dir.resolve(name) : Path.of(name);
                })
                .collect(Collectors.toList());
    }

    /** Writes a single {@link SheetPayload} into the given workbook. */
    private void writeResponseDataSheet(Workbook workbook, SheetStyleFactory styleFactory,
                                        SheetPayload payload, Set<String> usedSheetNames) {
        String sheetName = uniqueSheetName(payload.baseSheetName(), usedSheetNames);
        usedSheetNames.add(sheetName);

        Sheet sheet = workbook.createSheet(sheetName);
        CellStyle titleStyle  = styleFactory.createTitleStyle(workbook, IndexedColors.TEAL);
        CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.DARK_TEAL);
        CellStyle textStyle   = styleFactory.createTextStyle(workbook, false);

        createTitleRow(sheet, titleStyle, payload.requestName() + " — Response Data");

        final int HEADER_START = 2;
        int headerDepth = writeHierarchicalHeaders(sheet, payload.columns(), HEADER_START, headerStyle);
        int dataStartRow = HEADER_START + headerDepth;

        int rowIndex = dataStartRow;
        for (ObjectNode rowNode : payload.rows()) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < payload.columns().size(); i++) {
                JsonNode val = rowNode.get(payload.columns().get(i).field());
                setCell(row, i, jsonNodeToString(val), textStyle);
            }
        }

        sheet.createFreezePane(0, dataStartRow);
        if (!payload.columns().isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(
                    dataStartRow - 1, dataStartRow - 1, 0, payload.columns().size() - 1));
        }
        autoSize(sheet, payload.columns().size());
    }

    private void createSummarySheet(Workbook workbook, SheetStyleFactory styleFactory, PostmanCollection collection, List<ExecutionResult> results) {
        Sheet sheet = workbook.createSheet("Summary");
        CellStyle titleStyle = styleFactory.createTitleStyle(workbook, IndexedColors.DARK_BLUE);

        createTitleRow(sheet, titleStyle, "Execution Summary");
        SummarySheetStyles styles = new SummarySheetStyles(workbook, styleFactory);
        int nextRow = writeExecutionMetricsBlock(sheet, 2, styles, collection, results);
        writeRequestStatusBlock(sheet, nextRow + 1, styles, results, null, workbook);
        sheet.setDisplayGridlines(false);

        for (int column = 0; column < 5; column++) {
            sheet.autoSizeColumn(column);
            sheet.setColumnWidth(column, Math.max(4_500, Math.min(sheet.getColumnWidth(column) + 1_200, 22_000)));
        }
        if (nextRow > 2) {
            sheet.createFreezePane(0, 2);
        }
    }

    private void createCustomSummarySheet(Workbook workbook, SheetStyleFactory styleFactory,
                                          PostmanCollection collection, List<ExecutionResult> results,
                                          FilterSpec filterSpec, RuntimeConfig config,
                                          RequestExecutor executor) {
        SummarySpec summary = filterSpec.summary();
        Sheet sheet = workbook.createSheet("Summary");
        SummarySheetStyles styles = new SummarySheetStyles(workbook, styleFactory);
        CellStyle paragraphStyle = workbook.createCellStyle();
        paragraphStyle.cloneStyleFrom(styles.valueStyle());
        paragraphStyle.setWrapText(true);
        int rowCursor = 0;

        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<ObjectNode>> rowsByRequest = buildRowsByRequest(results, mapper);

        Map<String, SummaryTablePayload> resolvedTables = new LinkedHashMap<>();
        Set<String> resolving = new HashSet<>();
        for (String name : summary.queries().keySet()) {
            resolveSummaryVar(name, summary.queries(), resolvedTables, resolving,
                    rowsByRequest, filterSpec, collection, config, executor, mapper);
        }

        for (SummaryItem item : summary.items()) {
            if (item instanceof SummaryItem.Title title) {
                IndexedColors color = parseIndexedColor(title.colorName(), IndexedColors.DARK_BLUE);
                CellStyle titleStyle;
                if (title.colorName() != null && (title.colorName().startsWith("#") || isHexString(title.colorName()))) {
                    titleStyle = styleFactory.createTitleStyle(workbook, color, title.colorName());
                } else {
                    titleStyle = styleFactory.createTitleStyle(workbook, color);
                }
                rowCursor = writeSummaryBanner(sheet, rowCursor, titleStyle, title.text());
                continue;
            }
            if (item instanceof SummaryItem.Description desc) {
                IndexedColors color = parseIndexedColor(desc.colorName(), IndexedColors.GREY_50_PERCENT);
                CellStyle descStyle;
                if (desc.colorName() != null && (desc.colorName().startsWith("#") || isHexString(desc.colorName()))) {
                    descStyle = styleFactory.createStatusStyle(workbook, color);
                    // Apply custom hex fill
                    byte[] rgb = hexToRgb(desc.colorName());
                    if (workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook) {
                        org.apache.poi.xssf.usermodel.XSSFColor customColor =
                                new org.apache.poi.xssf.usermodel.XSSFColor(rgb, null);
                        ((org.apache.poi.xssf.usermodel.XSSFCellStyle) descStyle).setFillForegroundColor(customColor);
                    }
                } else {
                    descStyle = styleFactory.createStatusStyle(workbook, color);
                }
                rowCursor = writeSummaryBanner(sheet, rowCursor, descStyle, desc.text());
                continue;
            }
            if (item instanceof SummaryItem.KeyValue kv) {
                rowCursor = writeSummaryKeyValue(sheet, rowCursor, kv.label(),
                        renderSummaryValue(kv.valueParts(), resolvedTables, filterSpec), styles);
                continue;
            }
            if (item instanceof SummaryItem.Paragraph paragraph) {
                String value = renderSummaryValue(paragraph.parts(), resolvedTables, filterSpec);
                Row row = sheet.createRow(rowCursor++);
                Cell cell = row.createCell(0);
                cell.setCellValue(value);
                cell.setCellStyle(paragraphStyle);
                row.createCell(1).setCellStyle(paragraphStyle);
                row.setHeightInPoints(Math.min(160, 20 * Math.max(2, (value.length() + 79) / 80)));
                sheet.addMergedRegion(new CellRangeAddress(rowCursor - 1, rowCursor - 1, 0, 1));
                continue;
            }
            if (item instanceof SummaryItem.Text text) {
                KeyValueParts kv = textToKeyValue(text);
                if (kv != null) {
                    rowCursor = writeSummaryAutoKeyValue(sheet, rowCursor, kv.label(),
                            renderSummaryValue(kv.valueParts(), resolvedTables, filterSpec), styles);
                } else {
                    Row row = sheet.createRow(rowCursor++);
                    Cell cell = row.createCell(0);
                    String textValue = renderSummaryValue(text.parts(), resolvedTables, filterSpec);
                    cell.setCellValue(textValue);
                    cell.setCellStyle(styles.valueStyle());
                    row.createCell(1).setCellStyle(styles.valueStyle());
                    sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(
                            rowCursor - 1, rowCursor - 1, 0, 1));
                }
                continue;
            }
            if (item instanceof SummaryItem.LabelValue lv) {
                rowCursor = writeSummaryAutoKeyValue(sheet, rowCursor, lv.label(),
                        renderSummaryValue(lv.valueParts(), resolvedTables, filterSpec), styles);
                continue;
            }
            if (item instanceof SummaryItem.Metrics) {
                rowCursor = blockSpacer(sheet, rowCursor);
                rowCursor = writeExecutionMetricsBlock(sheet, rowCursor, styles, collection, results);
                continue;
            }
            if (item instanceof SummaryItem.Table table) {
                rowCursor = blockSpacer(sheet, rowCursor);
                SummaryTablePayload payload = resolvedTables.get(table.variableName());
                if (payload == null || payload.columns().isEmpty()) {
                    rowCursor = writeSummaryKeyValue(sheet, rowCursor, tableTitleLabel(table),
                            "No rows", styles);
                    continue;
                }
                List<ColumnSpec> columns = payload.columns();
                if (table.columns() != null && !table.columns().isEmpty()) {
                    LinkedHashSet<String> keys = new LinkedHashSet<>();
                    payload.rows().forEach(r -> r.fieldNames().forEachRemaining(keys::add));
                    columns = ColumnSpec.project(keys, table.columns());
                }
                rowCursor = writeSummaryDataTable(sheet, rowCursor, tableTitleLabel(table),
                        columns, payload.rows(), payload.flatHeaders(), styles);
                continue;
            }
            if (item instanceof SummaryItem.QuickTable qt) {
                rowCursor = blockSpacer(sheet, rowCursor);
                rowCursor = writeQuickTable(sheet, rowCursor, qt.title(), qt.headers(), qt.rows(),
                        resolvedTables, filterSpec, styles, workbook);
                continue;
            }
            if (item instanceof SummaryItem.Status statusItem) {
                rowCursor = blockSpacer(sheet, rowCursor);
                rowCursor = writeRequestStatusBlock(sheet, rowCursor, styles, results, statusItem.colorName(), workbook);
            }
        }

        if (summary.items().isEmpty()) {
            writeSummaryBanner(sheet, 0, styles.sectionStyle(), "Summary");
        }
        int maxCol = 0;
        for (SummaryItem item : summary.items()) {
            if (item instanceof SummaryItem.Table table) {
                SummaryTablePayload payload = resolvedTables.get(table.variableName());
                if (payload != null && !payload.columns().isEmpty()) {
                    maxCol = Math.max(maxCol, payload.columns().size() - 1);
                }
            }
            if (item instanceof SummaryItem.QuickTable qt) {
                int qtCols = (qt.headers() != null && !qt.headers().isEmpty()) ? qt.headers().size() : 2;
                maxCol = Math.max(maxCol, qtCols - 1);
            }
            if (item instanceof SummaryItem.Status) {
                maxCol = Math.max(maxCol, 4); // 5 columns for STATUS block
            }
        }
        // Always size the label (A) and value (B) columns; size extra table columns when present.
        sheet.setDisplayGridlines(false);
        sheet.createFreezePane(0, 1);
        for (int c = 0; c <= Math.max(1, maxCol); c++) {
            sheet.autoSizeColumn(c);
            int width = sheet.getColumnWidth(c);
            // Clamp to a readable range so short labels aren't cramped and long text isn't huge.
            sheet.setColumnWidth(c, Math.max(4_500, Math.min(width + 1_200, 22_000)));
        }
    }

    /**
     * Inserts a single (physical) blank row before a section block for visual separation.
     * No-op at the very top of the sheet. The blank row is materialized so the sheet has no
     * gaps in its row indices.
     */
    private int blockSpacer(Sheet sheet, int rowCursor) {
        if (rowCursor == 0) {
            return 0;
        }
        sheet.createRow(rowCursor);
        return rowCursor + 1;
    }

    private record SummarySheetStyles(
            CellStyle labelStyle,
            CellStyle autoLabelStyle,
            CellStyle valueStyle,
            CellStyle numericStyle,
            CellStyle zebraValueStyle,
            CellStyle zebraNumericStyle,
            CellStyle trueStyle,
            CellStyle falseStyle,
            CellStyle sectionStyle,
            CellStyle tableHeaderStyle
    ) {
        SummarySheetStyles(Workbook workbook, SheetStyleFactory factory) {
            this(
                    factory.createSummaryLabelStyle(workbook),
                    factory.createSummaryAutoLabelStyle(workbook),
                    factory.createSummaryValueStyle(workbook),
                    factory.createSummaryNumericStyle(workbook),
                    factory.createSummaryZebraStyle(workbook, false),
                    factory.createSummaryZebraStyle(workbook, true),
                    factory.createBooleanTrueStyle(workbook),
                    factory.createBooleanFalseStyle(workbook),
                    factory.createSummarySectionStyle(workbook),
                    factory.createSummaryTableHeaderStyle(workbook)
            );
        }

        CellStyle valueStyleFor(String value) {
            return valueStyleFor(value, false);
        }

        /** Picks the cell style for a value: boolean coloring wins, then numeric right-align,
         *  with an alternate ("zebra") fill on odd table rows. */
        CellStyle valueStyleFor(String value, boolean zebra) {
            Boolean b = parseBooleanValue(value);
            if (b != null) {
                return b ? trueStyle : falseStyle;
            }
            boolean numeric = isNumericValue(value);
            if (zebra) {
                return numeric ? zebraNumericStyle : zebraValueStyle;
            }
            return numeric ? numericStyle : valueStyle;
        }
    }

    /** True when {@code value} parses as a number (used to right-align summary cells). */
    private static boolean isNumericValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private record SummaryTablePayload(List<ColumnSpec> columns, List<ObjectNode> rows, boolean flatHeaders) {}

    private record KeyValueParts(String label, List<SummaryTextPart> valueParts) {}

    private Map<String, List<ObjectNode>> buildRowsByRequest(List<ExecutionResult> results, ObjectMapper mapper) {
        Map<String, List<ObjectNode>> rowsByRequest = new LinkedHashMap<>();
        for (ExecutionResult result : results) {
            String body = result.responseBody();
            if (body == null || body.isBlank()) {
                continue;
            }
            try {
                JsonNode root = mapper.readTree(body);
                List<ObjectNode> rows = extractResponseRows(root);
                if (!rows.isEmpty()) {
                    rowsByRequest.put(result.requestName(), rows);
                }
            } catch (Exception ignored) {
            }
        }
        return rowsByRequest;
    }

    /**
     * Resolves a summary {@code $variable} to its rows, resolving dependencies on demand and
     * memoizing into {@code cache}. {@code resolving} tracks the in-progress chain to detect cycles.
     */
    private SummaryTablePayload resolveSummaryVar(String name,
                                                  Map<String, SummaryQuerySpec> specs,
                                                  Map<String, SummaryTablePayload> cache,
                                                  Set<String> resolving,
                                                  Map<String, List<ObjectNode>> rowsByRequest,
                                                  FilterSpec filterSpec,
                                                  PostmanCollection collection,
                                                  RuntimeConfig config,
                                                  RequestExecutor executor,
                                                  ObjectMapper mapper) {
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        SummaryQuerySpec spec = specs.get(name);
        if (spec == null) {
            SummaryTablePayload empty = new SummaryTablePayload(List.of(), List.of(), false);
            cache.put(name, empty);
            return empty;
        }
        if (!resolving.add(name)) {
            throw new IllegalArgumentException("Summary variable $" + name + " has a circular definition.");
        }

        SummaryTablePayload payload;
        if (spec.source() instanceof SummaryQuerySource.DerivedFilter derived) {
            SummaryTablePayload base = resolveSummaryVar(derived.sourceVariable(), specs, cache, resolving,
                    rowsByRequest, filterSpec, collection, config, executor, mapper);
            List<ObjectNode> rows = base.rows();
            if (derived.filter() != null) {
                Instant now = Instant.now();
                rows = rows.stream()
                        .filter(row -> RowConditionEvaluator.evaluate(row, derived.filter(), Collections.emptyMap(), now, runtimeVars))
                        .collect(Collectors.toList());
            }
            payload = new SummaryTablePayload(base.columns(), rows, base.flatHeaders());
        } else {
            payload = resolveSummaryQuery(spec, rowsByRequest, filterSpec, collection, config, executor, mapper);
        }

        resolving.remove(name);
        cache.put(name, payload);
        return payload;
    }

    private SummaryTablePayload resolveSummaryQuery(SummaryQuerySpec query,
                                                    Map<String, List<ObjectNode>> rowsByRequest,
                                                    FilterSpec filterSpec,
                                                    PostmanCollection collection,
                                                    RuntimeConfig config,
                                                    RequestExecutor executor,
                                                    ObjectMapper mapper) {
        if (query.source() instanceof SummaryQuerySource.FilterRows filterRows) {
            return resolveSummaryFilterRows(
                    filterRows, rowsByRequest, filterSpec, mapper);
        } else if (query.source() instanceof SummaryQuerySource.NamedTable named) {
            return resolveSummaryNamedTable(
                    named, filterSpec, rowsByRequest, collection, config, executor, mapper);
        } else if (query.source() instanceof SummaryQuerySource.UnionRows union) {
            return payloadFromRows(buildUnionRows(union.spec(), rowsByRequest, filterSpec));
        } else if (query.source() instanceof SummaryQuerySource.SetOpRows setOp) {
            return payloadFromRows(buildSetOpRows(setOp.spec(), rowsByRequest, filterSpec));
        } else if (query.source() instanceof SummaryQuerySource.CompareRows compare) {
            return payloadFromRows(buildCompareRows(compare.spec(), rowsByRequest, filterSpec, mapper));
        }
        throw new IllegalArgumentException("Unknown summary query source: " + query.source());
    }

    /** Wraps a row list into a summary payload, deriving flat column specs from the row keys. */
    private SummaryTablePayload payloadFromRows(List<ObjectNode> rows) {
        if (rows == null || rows.isEmpty()) {
            return new SummaryTablePayload(List.of(), List.of(), true);
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        rows.forEach(r -> r.fieldNames().forEachRemaining(keys::add));
        return new SummaryTablePayload(ColumnSpec.project(keys, null), rows, true);
    }

    private SummaryTablePayload resolveSummaryFilterRows(SummaryQuerySource.FilterRows query,
                                                         Map<String, List<ObjectNode>> rowsByRequest,
                                                         FilterSpec filterSpec,
                                                         ObjectMapper mapper) {
        List<ObjectNode> rows = new ArrayList<>(rowsByRequest.getOrDefault(query.requestKey(), List.of()));
        rows = expandRows(rows, query.requestKey(), filterSpec, mapper);
        if (query.filter() != null) {
            rows = applyExplicitRowFilter(rows, query.filter(), query.requestKey(), filterSpec);
        }
        rows = applyDataShape(rows, query.requestKey(), filterSpec);
        if (rows.isEmpty()) {
            return new SummaryTablePayload(List.of(), List.of(), false);
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        rows.forEach(r -> r.fieldNames().forEachRemaining(keys::add));
        List<ColumnSpec> columns = ColumnSpec.project(keys,
                filterSpec.responseColumns() != null
                        ? filterSpec.responseColumns().getOrDefault(
                        query.requestKey(), filterSpec.responseColumns().get("*"))
                        : null);
        return new SummaryTablePayload(columns, rows, false);
    }

    private SummaryTablePayload resolveSummaryNamedTable(SummaryQuerySource.NamedTable named,
                                                         FilterSpec filterSpec,
                                                         Map<String, List<ObjectNode>> rowsByRequest,
                                                         PostmanCollection collection,
                                                         RuntimeConfig config,
                                                         RequestExecutor executor,
                                                         ObjectMapper mapper) {
        if (filterSpec.customTables() == null) {
            return new SummaryTablePayload(List.of(), List.of(), false);
        }
        CustomTableSpec tableSpec = filterSpec.customTables().stream()
                .filter(t -> named.tableName().equals(t.name()))
                .findFirst()
                .orElse(null);
        if (tableSpec == null) {
            return new SummaryTablePayload(List.of(), List.of(), false);
        }
        List<ObjectNode> rows = materializeCustomTableRows(tableSpec, rowsByRequest, filterSpec,
                collection, config, executor, mapper);
        if (rows.isEmpty()) {
            return new SummaryTablePayload(List.of(), List.of(), tableSpec.sources() != null);
        }
        List<ColumnSpec> columns = resolveCustomTableColumns(rows, tableSpec);
        boolean flat = tableSpec.sources() != null && !tableSpec.sources().isEmpty();
        return new SummaryTablePayload(columns, rows, flat);
    }

    private List<ObjectNode> materializeCustomTableRows(CustomTableSpec tableSpec,
                                                        Map<String, List<ObjectNode>> rowsByRequest,
                                                        FilterSpec filterSpec,
                                                        PostmanCollection collection,
                                                        RuntimeConfig config,
                                                        RequestExecutor executor,
                                                        ObjectMapper mapper) {
        List<ObjectNode> tableRows;
        String sourceRequestName = null;

        if (tableSpec.lookupRequest() != null) {
            sourceRequestName = tableSpec.sourceRequest();
            List<ObjectNode> sourceRows = new ArrayList<>(rowsByRequest.getOrDefault(sourceRequestName, List.of()));
            tableRows = buildLookupRows(tableSpec, sourceRows, collection, config, executor, mapper);
        } else if (tableSpec.sourceRequest() != null) {
            sourceRequestName = tableSpec.sourceRequest();
            tableRows = new ArrayList<>(rowsByRequest.getOrDefault(sourceRequestName, List.of()));
        } else {
            tableRows = buildJoinedRows(tableSpec, rowsByRequest, filterSpec);
        }

        if (tableSpec.where() != null && !tableRows.isEmpty()) {
            Map<String, DateFieldConfig> dateFields = sourceRequestName != null
                    ? resolveDateConfig(sourceRequestName, filterSpec)
                    : (filterSpec.dateConfig() != null
                    ? filterSpec.dateConfig().getOrDefault("*", Collections.emptyMap())
                    : Collections.emptyMap());
            Instant now = Instant.now();
            tableRows = tableRows.stream()
                    .filter(row -> RowConditionEvaluator.evaluate(row, tableSpec.where(), dateFields, now, runtimeVars))
                    .collect(Collectors.toList());
        }
        return applyDataShape(tableRows, tableSpec.name(), filterSpec);
    }

    private int writeSummaryBanner(Sheet sheet, int rowIndex, CellStyle style, String text) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(20);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(style);
        if (!text.isBlank()) {
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 1));
        }
        return rowIndex + 1;
    }

    private int writeSummaryKeyValue(Sheet sheet, int rowIndex, String label, String value, SummarySheetStyles styles) {
        Row row = sheet.createRow(rowIndex);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label == null || label.isBlank() ? "" : label);
        labelCell.setCellStyle(styles.labelStyle());
        Cell valueCell = row.createCell(1);
        String safeValue = value == null ? "" : value;
        valueCell.setCellValue(safeValue);
        valueCell.setCellStyle(styles.valueStyleFor(safeValue));
        return rowIndex + 1;
    }

    private int writeSummaryAutoKeyValue(Sheet sheet, int rowIndex, String label, String value, SummarySheetStyles styles) {
        Row row = sheet.createRow(rowIndex);
        Cell labelCell = row.createCell(0);
        String safeLabel = label == null || label.isBlank() ? "" : label;
        labelCell.setCellValue(safeLabel);
        labelCell.setCellStyle(styles.autoLabelStyle());
        Cell valueCell = row.createCell(1);
        String safeValue = value == null ? "" : value;
        valueCell.setCellValue(safeValue);
        valueCell.setCellStyle(styles.valueStyleFor(safeValue));
        return rowIndex + 1;
    }

    private int writeSummaryDataTable(Sheet sheet, int rowIndex, String sectionTitle,
                                      List<ColumnSpec> columns, List<ObjectNode> rows,
                                      boolean flatHeaders, SummarySheetStyles styles) {
        if (sectionTitle != null && !sectionTitle.isBlank()) {
            rowIndex = writeSummaryBanner(sheet, rowIndex, styles.sectionStyle(), sectionTitle);
        }
        int headerStart = rowIndex;
        if (flatHeaders) {
            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns.get(i).header());
                cell.setCellStyle(styles.tableHeaderStyle());
            }
        } else {
            rowIndex += writeHierarchicalHeaders(sheet, columns, headerStart, styles.tableHeaderStyle());
        }
        int dataRow = 0;
        for (ObjectNode rowNode : rows) {
            boolean zebra = (dataRow++ % 2) == 1;
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < columns.size(); i++) {
                String raw = jsonNodeToString(rowNode.get(columns.get(i).field()));
                setCell(row, i, raw, styles.valueStyleFor(raw, zebra));
            }
        }
        return rowIndex + 1;
    }

    private int writeQuickTable(Sheet sheet, int rowIndex, String title, List<String> headers,
                                List<InlineTableRow> rows,
                                Map<String, SummaryTablePayload> resolvedTables,
                                FilterSpec filterSpec, SummarySheetStyles styles, Workbook workbook) {
        if (title != null && !title.isBlank()) {
            rowIndex = writeSummaryBanner(sheet, rowIndex, styles.sectionStyle(), title);
        }
        int colCount = (headers != null && !headers.isEmpty()) ? headers.size() : 2;
        if (headers != null && !headers.isEmpty()) {
            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(styles.tableHeaderStyle());
            }
        }
        int dataRow = 0;
        for (InlineTableRow r : rows) {
            boolean zebra = (dataRow++ % 2) == 1;
            Row row = sheet.createRow(rowIndex++);
            List<List<SummaryTextPart>> effectiveCols = r.effectiveColumns();
            for (int col = 0; col < colCount; col++) {
                List<SummaryTextPart> parts = col < effectiveCols.size() ? effectiveCols.get(col) : List.of();
                String value = renderSummaryValue(parts, resolvedTables, filterSpec);
                Cell cell = row.createCell(col);
                cell.setCellValue(value);
                // Use auto-label style for column 0 in classic 2-col mode, value style otherwise
                if (col == 0 && r.columns() == null) {
                    cell.setCellStyle(styles.autoLabelStyle());
                } else {
                    cell.setCellStyle(styles.valueStyleFor(value, zebra));
                }
            }
        }
        return rowIndex;
    }

    private static String tableTitleLabel(SummaryItem.Table table) {
        if (table.title() != null && !table.title().isBlank()) {
            return table.title();
        }
        return humanizeVariableName(table.variableName());
    }

    private static String humanizeVariableName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String withSpaces = name.replace('_', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : withSpaces.toCharArray()) {
            if (c == ' ') {
                capitalizeNext = true;
                sb.append(c);
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private KeyValueParts textToKeyValue(SummaryItem.Text text) {
        List<SummaryTextPart> parts = text.parts();
        if (parts.isEmpty()) {
            return null;
        }
        boolean hasVariable = parts.stream().anyMatch(p -> p instanceof SummaryTextPart.Variable);
        if (!hasVariable) {
            return null;
        }
        if (parts.size() == 1 && parts.get(0) instanceof SummaryTextPart.Variable var) {
            return new KeyValueParts(humanizeVariableName(var.name()), List.of(var));
        }
        if (parts.get(0) instanceof SummaryTextPart.Literal literal) {
            String label = literal.value().trim();
            if (!label.isBlank()) {
                List<SummaryTextPart> valueParts = parts.subList(1, parts.size());
                return new KeyValueParts(label, valueParts);
            }
        }
        // If literal is blank but we have variables, use first variable as label
        if (parts.get(0) instanceof SummaryTextPart.Variable var) {
            return new KeyValueParts(humanizeVariableName(var.name()), parts);
        }
        return null;
    }

    private static Boolean parseBooleanValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized) || "yes".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized) || "no".equalsIgnoreCase(normalized)) {
            return false;
        }
        return null;
    }

    private List<ObjectNode> applyExplicitRowFilter(List<ObjectNode> rows, RowFilterGroup group,
                                                    String requestName, FilterSpec filterSpec) {
        Map<String, DateFieldConfig> dateFields = resolveDateConfig(requestName, filterSpec);
        Instant now = Instant.now();
        return rows.stream()
                .filter(row -> RowConditionEvaluator.evaluate(row, group, dateFields, now, runtimeVars))
                .collect(Collectors.toList());
    }

    private String renderSummaryValue(List<SummaryTextPart> parts, Map<String, SummaryTablePayload> tables,
                                      FilterSpec filterSpec) {
        StringBuilder sb = new StringBuilder();
        for (SummaryTextPart part : parts) {
            if (part instanceof SummaryTextPart.Literal literal) {
                sb.append(literal.value());
            } else if (part instanceof SummaryTextPart.Variable var) {
                sb.append(resolveSummaryScalar(var.name(), tables, filterSpec));
            } else if (part instanceof SummaryTextPart.IfElse ifElse) {
                sb.append(resolveSummaryIfElse(ifElse, tables, filterSpec));
            }
        }
        return sb.toString();
    }

    /**
     * Evaluates a summary IF/ELSE conditional by resolving the variable, comparing
     * it against the target value, and then rendering either the THEN or ELSE branch.
     *
     * <p>The variable resolves using the same logic as {@link SummaryTextPart.Variable}:
     * single-cell scalars are compared directly; multi-row results use the row count.
     */
    private String resolveSummaryIfElse(SummaryTextPart.IfElse ifElse,
                                        Map<String, SummaryTablePayload> tables,
                                        FilterSpec filterSpec) {
        boolean conditionMet;
        if (ifElse.condition() != null) {
            conditionMet = evaluateSummaryConditionTree(ifElse.condition(), tables, filterSpec);
        } else {
            String resolvedValue = resolveSummaryScalar(ifElse.variableName(), tables, filterSpec);
            conditionMet = evaluateSummaryCondition(resolvedValue, ifElse.op(), ifElse.value());
        }
        List<SummaryTextPart> branch = conditionMet ? ifElse.thenParts() : ifElse.elseParts();
        return renderSummaryValue(branch != null ? branch : List.of(), tables, filterSpec);
    }

    /** Evaluates a (possibly compound) summary IF condition tree. */
    private boolean evaluateSummaryConditionTree(SummaryTextPart.Condition cond,
                                                 Map<String, SummaryTablePayload> tables,
                                                 FilterSpec filterSpec) {
        if (cond instanceof SummaryTextPart.Condition.Term term) {
            String resolved = resolveSummaryScalar(term.variableName(), tables, filterSpec);
            return evaluateSummaryCondition(resolved, term.op(), term.value());
        }
        if (cond instanceof SummaryTextPart.Condition.And and) {
            return evaluateSummaryConditionTree(and.left(), tables, filterSpec)
                    && evaluateSummaryConditionTree(and.right(), tables, filterSpec);
        }
        if (cond instanceof SummaryTextPart.Condition.Or or) {
            return evaluateSummaryConditionTree(or.left(), tables, filterSpec)
                    || evaluateSummaryConditionTree(or.right(), tables, filterSpec);
        }
        return false;
    }

    /**
     * Compares a resolved variable value against a target using the given operator.
     * Tries numeric comparison first; falls back to case-insensitive string comparison.
     */
    private boolean evaluateSummaryCondition(String resolvedValue, String op, String target) {
        if (resolvedValue == null || resolvedValue.isBlank()) {
            resolvedValue = "0"; // Empty/missing variables default to 0 for numeric comparisons
        }
        int cmp;
        try {
            double leftNum  = Double.parseDouble(resolvedValue.trim());
            double rightNum = Double.parseDouble(target.trim());
            cmp = Double.compare(leftNum, rightNum);
        } catch (NumberFormatException ignored) {
            cmp = resolvedValue.compareToIgnoreCase(target);
        }
        return switch (op) {
            case "=", "==" -> cmp == 0;
            case "!=", "<>" -> cmp != 0;
            case ">"  -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<"  -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> {
                System.err.printf("[WARN] Unknown IF operator \"%s\" in summary — defaulting to false.%n", op);
                yield false;
            }
        };
    }

    private String resolveSummaryScalar(String varName, Map<String, SummaryTablePayload> tables, FilterSpec filterSpec) {
        SummaryTablePayload table = tables.get(varName);
        if (table != null) {
            if (table.rows().size() == 1 && table.columns().size() == 1) {
                ObjectNode row = table.rows().get(0);
                return jsonNodeToString(row.get(table.columns().get(0).field()));
            }
            return String.valueOf(table.rows().size());
        }
        if (filterSpec.vars() != null) {
            if (filterSpec.vars().containsKey(varName)) {
                return filterSpec.vars().get(varName);
            }
            if (filterSpec.vars().containsKey("$" + varName)) {
                return filterSpec.vars().get("$" + varName);
            }
        }
        return "";
    }

    private int writeExecutionMetricsBlock(Sheet sheet, int startRow, SummarySheetStyles styles,
                                           PostmanCollection collection, List<ExecutionResult> results) {
        long successCount = results.stream().filter(ExecutionResult::success).count();
        long failureCount = results.size() - successCount;
        long averageDuration = results.stream().mapToLong(ExecutionResult::durationMillis).sum() / Math.max(results.size(), 1);

        int row = startRow;
        row = writeSummaryKeyValue(sheet, row, "Collection", collection.name(), styles);
        row = writeSummaryKeyValue(sheet, row, "Requests", String.valueOf(results.size()), styles);
        row = writeSummaryKeyValue(sheet, row, "Passed", String.valueOf(successCount), styles);
        row = writeSummaryKeyValue(sheet, row, "Failed", String.valueOf(failureCount), styles);
        row = writeSummaryKeyValue(sheet, row, "Average Duration (ms)", String.valueOf(averageDuration), styles);

        // Per-server status — deduplicated hosts with aggregate success/failure
        Map<String, Boolean> serverStatus = extractServerStatus(results);
        List<String> sortedServers = new ArrayList<>(serverStatus.keySet());
        Collections.sort(sortedServers);
        for (String server : sortedServers) {
            String status = serverStatus.get(server) ? "Success" : "Failed";
            row = writeSummaryKeyValue(sheet, row, "Server: " + server, status, styles);
        }

        row = writeSummaryKeyValue(sheet, row, "Generated At", TIMESTAMP_FORMAT.format(Instant.now()), styles);
        return row;
    }

    /**
     * Extracts unique server hosts from all execution result URLs and determines
     * per-server status. A server is marked Failed if ANY request to it failed.
     * Returns a deduplicated map of host → success (true=all passed, false=at least one failed),
     * in insertion order.
     */
    private static Map<String, Boolean> extractServerStatus(List<ExecutionResult> results) {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (ExecutionResult result : results) {
            String host = extractHost(result.url());
            if (host == null || host.isBlank()) {
                continue;
            }
            Boolean current = status.get(host);
            if (current == null) {
                status.put(host, result.success());
            } else if (current && !result.success()) {
                status.put(host, false);
            }
        }
        return status;
    }

    /** Extracts the host (domain) from a URL string, or null if parsing fails. */
    private static String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int writeRequestStatusBlock(Sheet sheet, int startRow, SummarySheetStyles styles,
                                         List<ExecutionResult> results, String colorName, Workbook workbook) {
        int row = startRow;
        CellStyle sectionStyle = styles.sectionStyle();
        if (colorName != null && !colorName.isBlank()) {
            sectionStyle = createColorAwareSectionStyle(workbook, colorName);
        }
        row = writeSummaryBanner(sheet, row, sectionStyle, "Request Status");

        // Header row
        Row headerRow = sheet.createRow(row++);
        String[] statusHeaders = {"Request", "Method", "Status Code", "Success", "Duration (ms)"};
        for (int i = 0; i < statusHeaders.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(statusHeaders[i]);
            cell.setCellStyle(styles.tableHeaderStyle());
        }

        // Data rows
        for (ExecutionResult result : results) {
            Row dataRow = sheet.createRow(row++);
            setCell(dataRow, 0, result.requestName(), styles.valueStyle());
            setCell(dataRow, 1, result.method(), styles.valueStyle());
            setCell(dataRow, 2, String.valueOf(result.statusCode()), result.success() ? styles.trueStyle() : styles.falseStyle());
            setCell(dataRow, 3, String.valueOf(result.success()), result.success() ? styles.trueStyle() : styles.falseStyle());
            setCell(dataRow, 4, String.valueOf(result.durationMillis()), styles.valueStyle());
        }
        return row;
    }

    /**
     * Creates a section-style CellStyle that supports any color: named IndexedColors or custom hex RGB.
     * Hex colors (e.g. "#FF5500", "FF5500") are converted to the closest matching IndexedColor
     * for maximum compatibility. Named colors are resolved via {@link #parseIndexedColor}.
     */
    private CellStyle createColorAwareSectionStyle(Workbook workbook, String colorName) {
        // Try indexed color first
        IndexedColors indexed = parseIndexedColor(colorName, null);
        if (indexed != null) {
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            font.setFontHeightInPoints((short) 11);
            style.setFont(font);
            style.setFillForegroundColor(indexed.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            return style;
        }
        // Try custom hex color — convert to XSSFColor for direct RGB support
        if (colorName.startsWith("#") || isHexString(colorName)) {
            try {
                byte[] rgb = hexToRgb(colorName);
                CellStyle style = workbook.createCellStyle();
                Font font = workbook.createFont();
                font.setBold(true);
                font.setFontHeightInPoints((short) 11);
                style.setFont(font);
                if (workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook xwb) {
                    org.apache.poi.xssf.usermodel.XSSFColor customColor =
                            new org.apache.poi.xssf.usermodel.XSSFColor(rgb, null);
                    ((org.apache.poi.xssf.usermodel.XSSFCellStyle) style).setFillForegroundColor(customColor);
                } else {
                    // Fallback: closest indexed color
                    style.setFillForegroundColor(closestIndexedColor(rgb).getIndex());
                }
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                style.setVerticalAlignment(VerticalAlignment.TOP);
                style.setBorderBottom(BorderStyle.THIN);
                style.setBorderTop(BorderStyle.THIN);
                style.setBorderLeft(BorderStyle.THIN);
                style.setBorderRight(BorderStyle.THIN);
                return style;
            } catch (IllegalArgumentException ignored) {
                // Fall through to default
            }
        }
        // Default fallback
        return styles(workbook, new SheetStyleFactory()).sectionStyle();
    }

    /** Temporary helper to get a SummarySheetStyles instance. */
    private SummarySheetStyles styles(Workbook workbook, SheetStyleFactory factory) {
        return new SummarySheetStyles(workbook, factory);
    }

    private static boolean isHexString(String value) {
        String clean = value.startsWith("#") ? value.substring(1) : value;
        return clean.length() == 6 && clean.chars().allMatch(
                c -> Character.digit(c, 16) >= 0);
    }

    private static byte[] hexToRgb(String hex) {
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        if (clean.length() != 6) {
            throw new IllegalArgumentException("Invalid hex color: " + hex);
        }
        int r = Integer.parseInt(clean.substring(0, 2), 16);
        int g = Integer.parseInt(clean.substring(2, 4), 16);
        int b = Integer.parseInt(clean.substring(4, 6), 16);
        return new byte[]{(byte) r, (byte) g, (byte) b};
    }

    /** Finds the closest IndexedColor to the given RGB value using Euclidean distance. */
    private static IndexedColors closestIndexedColor(byte[] rgb) {
        int r = rgb[0] & 0xFF;
        int g = rgb[1] & 0xFF;
        int b = rgb[2] & 0xFF;
        // Simple approach: map common hue ranges to nearby indexed colors
        // Since XSSFWorkbook supports custom XSSFColor natively, this is only a fallback
        // for HSSFWorkbook which we don't use in this project.
        if (r > 200 && g < 100 && b < 100) return IndexedColors.RED;
        if (r < 100 && g > 200 && b < 100) return IndexedColors.BRIGHT_GREEN;
        if (r < 100 && g < 100 && b > 200) return IndexedColors.BLUE;
        if (r > 200 && g > 200 && b < 100) return IndexedColors.YELLOW;
        if (r > 200 && g > 100 && b < 100) return IndexedColors.ORANGE;
        if (r > 200 && g < 100 && b > 200) return IndexedColors.VIOLET;
        if (r > 200 && g > 200 && b > 200) return IndexedColors.GREY_50_PERCENT;
        if (r < 80 && g < 80 && b < 80)    return IndexedColors.BLACK;
        return IndexedColors.GREY_40_PERCENT;
    }

    private void createIndexSheet(Workbook workbook, SheetStyleFactory styleFactory) {
        Sheet sheet = workbook.createSheet("Index");
        CellStyle titleStyle = styleFactory.createTitleStyle(workbook, IndexedColors.INDIGO);
        CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.INDIGO);
        CellStyle linkStyle = styleFactory.createTextStyle(workbook, false);
        Font linkFont = workbook.createFont();
        linkFont.setUnderline(Font.U_SINGLE);
        linkFont.setColor(IndexedColors.BLUE.getIndex());
        linkStyle.setFont(linkFont);

        createTitleRow(sheet, titleStyle, "Sheet Index");
        Row headerRow = sheet.createRow(2);
        setCell(headerRow, 0, "Sheet", headerStyle);
        setCell(headerRow, 1, "Go to", headerStyle);

        CreationHelper helper = workbook.getCreationHelper();
        int rowIndex = 3;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            String sheetName = workbook.getSheetName(i);
            if ("Index".equals(sheetName)) {
                continue;
            }
            Row row = sheet.createRow(rowIndex++);
            setCell(row, 0, sheetName, linkStyle);
            Cell linkCell = row.createCell(1);
            linkCell.setCellValue("Open");
            linkCell.setCellStyle(linkStyle);
            Hyperlink link = helper.createHyperlink(HyperlinkType.DOCUMENT);
            link.setAddress("'" + sheetName.replace("'", "''") + "'!A1");
            linkCell.setHyperlink(link);
        }

        sheet.createFreezePane(0, 3);
        sheet.setAutoFilter(new CellRangeAddress(2, 2, 0, 1));
        autoSize(sheet, 2);

        int indexPos = workbook.getSheetIndex(sheet);
        if (indexPos > 1) {
            workbook.setSheetOrder(sheet.getSheetName(), 1);
        }
    }

    private static IndexedColors parseIndexedColor(String colorName, IndexedColors fallback) {
        if (colorName == null || colorName.isBlank()) {
            return fallback;
        }
        // If this is a hex color (e.g. "#FF5500" or "FF5500"), don't try to match IndexedColors;
        // callers should use hex-aware rendering instead.
        String trimmed = colorName.trim();
        if (trimmed.startsWith("#") || isHexString(trimmed)) {
            return fallback; // Let caller handle custom RGB
        }
        String normalized = trimmed.toUpperCase().replace(' ', '_').replace('-', '_');
        try {
            return IndexedColors.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
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

    private void createFolderSheets(Workbook workbook, SheetStyleFactory styleFactory, List<ExecutionResult> results,
                                    boolean includeBody, Set<String> usedNames) {
        Map<String, List<ExecutionResult>> grouped = results.stream()
                .filter(result -> result.folderPath() != null && !result.folderPath().isBlank())
                .collect(Collectors.groupingBy(ExecutionResult::folderPath, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<ExecutionResult>> entry : grouped.entrySet()) {
            String sheetName = uniqueSheetName(safeSheetName(entry.getKey()), usedNames);
            usedNames.add(sheetName);
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

    private void setCell(Row row, int columnIndex, String value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        String safe = value == null ? "" : value;
        if (safe.length() > MAX_CELL_LENGTH) {
            safe = safe.substring(0, MAX_CELL_LENGTH);
        }
        cell.setCellValue(safe);
        cell.setCellStyle(style);
    }

    private void autoSize(Sheet sheet, int columns) {
        for (int index = 0; index < columns; index++) {
            sheet.autoSizeColumn(index);
            int currentWidth = sheet.getColumnWidth(index);
            sheet.setColumnWidth(index, Math.min(currentWidth + 1000, 20000));
        }
    }

    // ── hierarchical header support ───────────────────────────────────────────────

    /**
     * One node in the column-header tree built from dot-notation column paths.
     * A leaf node (no children) maps to a single Excel column; an internal node
     * groups its children under a single horizontally-merged label cell.
     */
    private static final class HeaderNode {
        String label;
        final java.util.LinkedHashMap<String, HeaderNode> children = new java.util.LinkedHashMap<>();
        int leafCount = 0;  // 1 for a leaf; sum of child leafCounts for internal nodes
        int treeDepth = 0;  // 1 for a leaf; max(child.treeDepth) + 1 for internal nodes
        HeaderNode(String label) { this.label = label; }
        boolean isLeaf() { return children.isEmpty(); }
    }

    /** Builds a header tree from column specs ({@link ColumnSpec#field()} paths, {@link ColumnSpec#header()} labels). */
    private HeaderNode buildHeaderTree(List<ColumnSpec> columns) {
        HeaderNode root = new HeaderNode("");
        for (ColumnSpec col : columns) {
            String[] parts = col.field().split("\\.", -1);
            HeaderNode current = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean isLeaf = i == parts.length - 1;
                String nodeLabel = isLeaf ? hierarchicalLeafLabel(col, part) : part;
                HeaderNode child = current.children.get(part);
                if (child == null) {
                    child = new HeaderNode(nodeLabel);
                    current.children.put(part, child);
                } else if (isLeaf) {
                    child.label = nodeLabel;
                }
                current = child;
            }
        }
        computeHeaderTreeStats(root);
        return root;
    }

    private void computeHeaderTreeStats(HeaderNode node) {
        if (node.isLeaf()) {
            node.leafCount = 1;
            node.treeDepth = 1;
            return;
        }
        for (HeaderNode child : node.children.values()) {
            computeHeaderTreeStats(child);
            node.leafCount += child.leafCount;
            node.treeDepth = Math.max(node.treeDepth, child.treeDepth + 1);
        }
    }

    /**
     * Writes multi-level column headers onto {@code sheet} starting at row
     * {@code headerStartRow} and returns the number of header rows written.
     * <p>
     * For flat column names (no dots) exactly one row is written — identical to the
     * previous behaviour.  For nested paths such as {@code spec.dimensions.width}
     * parent segments appear as horizontally-merged cells, and leaves that sit above
     * the deepest level are merged vertically downward to fill the header area:
     * <pre>
     *  row 0: | id ↕ | name ↕ |        spec (merged → 4 cols)          |
     *  row 1: |      |        | weight ↕ | color ↕ | dimensions (→ 2) |
     *  row 2: |      |        |          |         |  width  |  height |
     * </pre>
     */
    /** Leaf header: explicit {@code AS} label, else last path segment for dotted fields, else field name. */
    private static String hierarchicalLeafLabel(ColumnSpec col, String leafSegment) {
        if (col.label() != null && !col.label().isBlank()) {
            return col.label();
        }
        return col.field().contains(".") ? leafSegment : col.field();
    }

    private int writeHierarchicalHeaders(Sheet sheet, List<ColumnSpec> columns,
                                         int headerStartRow, CellStyle style) {
        if (columns.isEmpty()) return 1;
        HeaderNode root = buildHeaderTree(columns);
        // root.treeDepth counts the root itself; subtract 1 for the actual row count.
        int headerDepth = Math.max(root.treeDepth - 1, 1);
        renderHeaderNode(sheet, root, 0, new int[]{0}, headerStartRow, headerDepth, style);
        return headerDepth;
    }

    /**
     * Recursive DFS renderer for one level of {@link HeaderNode} children.
     *
     * @param node        current node whose children to render
     * @param globalDepth 0-indexed depth of the children being rendered
     * @param colCursor   single-element array acting as a mutable column counter
     * @param headerStart 0-indexed sheet row of the first header row
     * @param maxDepth    total number of header rows (drives vertical merge extent)
     * @param style       cell style applied to every header cell
     */
    private void renderHeaderNode(Sheet sheet, HeaderNode node, int globalDepth,
                                  int[] colCursor, int headerStart, int maxDepth,
                                  CellStyle style) {
        for (HeaderNode child : node.children.values()) {
            int col    = colCursor[0];
            int rowIdx = headerStart + globalDepth;
            Row row = sheet.getRow(rowIdx);
            if (row == null) row = sheet.createRow(rowIdx);

            Cell cell = row.createCell(col);
            cell.setCellValue(child.label);
            cell.setCellStyle(style);

            if (child.isLeaf()) {
                // Merge downward to the last header row when this leaf sits above it
                if (globalDepth < maxDepth - 1) {
                    sheet.addMergedRegion(
                            new CellRangeAddress(rowIdx, headerStart + maxDepth - 1, col, col));
                }
                colCursor[0]++;
            } else {
                // Merge rightward to cover all leaf descendants
                if (child.leafCount > 1) {
                    sheet.addMergedRegion(
                            new CellRangeAddress(rowIdx, rowIdx, col, col + child.leafCount - 1));
                }
                renderHeaderNode(sheet, child, globalDepth + 1, colCursor,
                        headerStart, maxDepth, style);
            }
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
                .filter(row -> RowConditionEvaluator.evaluate(row, group, dateFields, now, runtimeVars))
                .collect(Collectors.toList());
    }

    /** Merges the runtime/env variable map with any filter-level vars (filter vars win). */
    private static Map<String, String> effectiveVars(RuntimeConfig config) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (config.variables() != null) {
            merged.putAll(config.variables());
        }
        if (config.filterSpec() != null && config.filterSpec().vars() != null) {
            merged.putAll(config.filterSpec().vars());
        }
        return merged;
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
                        .filter(row -> RowConditionEvaluator.evaluate(row, shape.having(), Collections.emptyMap(), now, runtimeVars))
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

        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<ObjectNode>> rowsByRequest = buildRowsByRequest(results, mapper);

        for (CustomTableSpec tableSpec : filterSpec.customTables()) {
            List<ObjectNode> tableRows = materializeCustomTableRows(
                    tableSpec, rowsByRequest, filterSpec, collection, config, executor, mapper);

            if (tableRows.isEmpty()) {
                System.out.printf("[INFO] Custom table \"%s\" produced 0 rows after filtering — sheet skipped.%n", tableSpec.name());
                continue;
            }

            // Determine columns to display
            List<ColumnSpec> columnSpecs = resolveCustomTableColumns(tableRows, tableSpec);

            // Create sheet
            String sheetName = uniqueSheetName(safeSheetName(tableSpec.name()), usedNames);
            usedNames.add(sheetName);
            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle titleStyle  = styleFactory.createTitleStyle(workbook, IndexedColors.VIOLET);
            CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.PLUM);
            CellStyle textStyle   = styleFactory.createTextStyle(workbook, false);

            createTitleRow(sheet, titleStyle, tableSpec.name() + " — Custom Table");
            final int HEADER_START = 2;
            int dataStartRow;
            // Join tables use alias.field column notation (e.g. "o.id", "u.name") — dots are
            // join-alias separators, not JSON hierarchy, so write a flat single-row header.
            boolean isJoinTable = tableSpec.sources() != null && !tableSpec.sources().isEmpty();
            if (isJoinTable) {
                Row headerRow = sheet.createRow(HEADER_START);
                for (int i = 0; i < columnSpecs.size(); i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(columnSpecs.get(i).header());
                    cell.setCellStyle(headerStyle);
                }
                dataStartRow = HEADER_START + 1;
            } else {
                int headerDepth = writeHierarchicalHeaders(sheet, columnSpecs, HEADER_START, headerStyle);
                dataStartRow = HEADER_START + headerDepth;
            }

            int rowIndex = dataStartRow;
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
                for (int i = 0; i < columnSpecs.size(); i++) {
                    String colRef = columnSpecs.get(i).field();
                    JsonNode val  = rowNode.get(colRef);
                    setCell(row, i, jsonNodeToString(val), textStyle);
                }
                written++;
            }

            sheet.createFreezePane(0, dataStartRow);
            if (!columnSpecs.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(dataStartRow - 1, dataStartRow - 1, 0, columnSpecs.size() - 1));
            }
            autoSize(sheet, columnSpecs.size());
        }
    }

    /** Builds the deduplicated, shaped rows for a UNION (reused by sheets and summary $vars). */
    private List<ObjectNode> buildUnionRows(UnionSpec union, Map<String, List<ObjectNode>> rowsByRequest,
                                            FilterSpec filterSpec) {
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
        return applyDataShape(unionRows, union.name(), filterSpec);
    }

    private void createUnionSheets(Workbook workbook, SheetStyleFactory styleFactory,
                                   List<ExecutionResult> results, FilterSpec filterSpec,
                                   Set<String> usedNames) {
        if (filterSpec == null || filterSpec.unions() == null || filterSpec.unions().isEmpty()) {
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<ObjectNode>> rowsByRequest = buildRowsByRequest(results, mapper);

        for (UnionSpec union : filterSpec.unions()) {
            List<ObjectNode> unionRows = buildUnionRows(union, rowsByRequest, filterSpec);
            if (unionRows.isEmpty()) {
                continue;
            }

            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (ObjectNode row : unionRows) {
                row.fieldNames().forEachRemaining(keys::add);
            }
            List<ColumnSpec> columnSpecs = ColumnSpec.project(keys, null);

            String sheetName = uniqueSheetName(safeSheetName(union.name()), usedNames);
            usedNames.add(sheetName);

            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle titleStyle = styleFactory.createTitleStyle(workbook, IndexedColors.DARK_RED);
            CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.RED);
            CellStyle textStyle = styleFactory.createTextStyle(workbook, false);

            createTitleRow(sheet, titleStyle, union.name() + " — UNION");
            final int HEADER_START = 2;
            int headerDepth = writeHierarchicalHeaders(sheet, columnSpecs, HEADER_START, headerStyle);
            int dataStartRow = HEADER_START + headerDepth;

            int rowIndex = dataStartRow;
            int written = 0;
            for (ObjectNode rowNode : unionRows) {
                if (written >= MAX_DATA_ROWS) {
                    break;
                }
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < columnSpecs.size(); i++) {
                    JsonNode val = rowNode.get(columnSpecs.get(i).field());
                    setCell(row, i, jsonNodeToString(val), textStyle);
                }
                written++;
            }

            sheet.createFreezePane(0, dataStartRow);
            if (!columnSpecs.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(dataStartRow - 1, dataStartRow - 1, 0, columnSpecs.size() - 1));
            }
            autoSize(sheet, columnSpecs.size());
        }
    }

    /** Builds the provenance-tagged, shaped rows for a set operation (reused by sheets and summary $vars). */
    private List<ObjectNode> buildSetOpRows(SetOpSpec op, Map<String, List<ObjectNode>> rowsByRequest,
                                            FilterSpec filterSpec) {
        String type = op.type().toUpperCase();
        List<String> sources = op.sources();
        Map<String, Set<String>> sigsBySource = new LinkedHashMap<>();
        Map<String, List<ObjectNode>> rowsBySource = new LinkedHashMap<>();
        for (String source : sources) {
            List<ObjectNode> sourceRows = new ArrayList<>(rowsByRequest.getOrDefault(source, List.of()));
            sourceRows = applyRowFilter(sourceRows, source, filterSpec);
            rowsBySource.put(source, sourceRows);
            Set<String> sigs = new LinkedHashSet<>();
            for (ObjectNode row : sourceRows) {
                sigs.add(row.toString());
            }
            sigsBySource.put(source, sigs);
        }

        List<ObjectNode> outRows = new ArrayList<>();
        if ("INTERSECT".equals(type)) {
            String firstSource = sources.get(0);
            Set<String> filteredSigs = new LinkedHashSet<>(sigsBySource.get(firstSource));
            for (int i = 1; i < sources.size(); i++) {
                filteredSigs.retainAll(sigsBySource.get(sources.get(i)));
            }
            for (ObjectNode row : rowsBySource.get(firstSource)) {
                String sig = row.toString();
                if (!filteredSigs.contains(sig)) continue;
                ObjectNode out = row.deepCopy();
                for (String source : sources) {
                    out.put("_in_" + source, true);
                }
                out.put("_source", "ALL");
                outRows.add(out);
            }
        } else if ("EXCEPT".equals(type)) {
            String firstSource = sources.get(0);
            Set<String> filteredSigs = new LinkedHashSet<>(sigsBySource.get(firstSource));
            for (int i = 1; i < sources.size(); i++) {
                filteredSigs.removeAll(sigsBySource.get(sources.get(i)));
            }
            for (ObjectNode row : rowsBySource.get(firstSource)) {
                String sig = row.toString();
                if (!filteredSigs.contains(sig)) continue;
                ObjectNode out = row.deepCopy();
                for (String source : sources) {
                    out.put("_in_" + source, sigsBySource.get(source).contains(sig));
                }
                out.put("_source", firstSource);
                outRows.add(out);
            }
        } else if ("DIFF".equals(type)) {
            for (String source : sources) {
                Set<String> otherSigs = new HashSet<>();
                for (String other : sources) {
                    if (!other.equals(source)) {
                        otherSigs.addAll(sigsBySource.get(other));
                    }
                }
                for (ObjectNode row : rowsBySource.get(source)) {
                    String sig = row.toString();
                    if (otherSigs.contains(sig)) continue;
                    ObjectNode out = row.deepCopy();
                    for (String s : sources) {
                        out.put("_in_" + s, sigsBySource.get(s).contains(sig));
                    }
                    out.put("_source", source);
                    outRows.add(out);
                }
            }
        }
        return applyDataShape(outRows, op.name(), filterSpec);
    }

    private void createSetOpSheets(Workbook workbook, SheetStyleFactory styleFactory,
                                   List<ExecutionResult> results, FilterSpec filterSpec,
                                   Set<String> usedNames) {
        if (filterSpec == null || filterSpec.setOps() == null || filterSpec.setOps().isEmpty()) {
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<ObjectNode>> rowsByRequest = buildRowsByRequest(results, mapper);

        for (SetOpSpec op : filterSpec.setOps()) {
            String type = op.type().toUpperCase();
            List<String> sources = op.sources();

            List<ObjectNode> outRows = buildSetOpRows(op, rowsByRequest, filterSpec);
            if (outRows.isEmpty()) {
                System.out.printf("[INFO] Set operation \"%s\" produced 0 rows — sheet skipped.%n", op.name());
                continue;
            }

            // For DIFF, group rows by source for section labels (post-shape order).
            Map<String, List<ObjectNode>> groupRows = new LinkedHashMap<>();
            if ("DIFF".equals(type)) {
                for (ObjectNode row : outRows) {
                    String src = row.has("_source") ? row.get("_source").asText() : sources.get(0);
                    groupRows.computeIfAbsent(src, k -> new ArrayList<>()).add(row);
                }
            }

            // Collect all keys including provenance columns
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (ObjectNode row : outRows) {
                row.fieldNames().forEachRemaining(keys::add);
            }
            List<ColumnSpec> columnSpecs = ColumnSpec.project(keys, null);

            String sheetName = uniqueSheetName(safeSheetName(op.name()), usedNames);
            usedNames.add(sheetName);

            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle titleStyle  = styleFactory.createTitleStyle(workbook, IndexedColors.DARK_BLUE);
            CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.BLUE_GREY);
            CellStyle sectionStyle = styleFactory.createTitleStyle(workbook, IndexedColors.LIGHT_BLUE);
            CellStyle textStyle   = styleFactory.createTextStyle(workbook, false);

            createTitleRow(sheet, titleStyle, op.name() + " \u2014 " + type);
            final int HEADER_START = 2;
            int headerDepth = writeHierarchicalHeaders(sheet, columnSpecs, HEADER_START, headerStyle);
            int dataStartRow = HEADER_START + headerDepth;
            int lastCol = Math.max(0, columnSpecs.size() - 1);

            int rowIndex = dataStartRow;
            int written = 0;

            if ("DIFF".equals(type)) {
                // Write section labels and grouped rows for DIFF
                for (String source : sources) {
                    List<ObjectNode> group = groupRows.get(source);
                    if (group == null || group.isEmpty()) continue;

                    // Section label row
                    if (written >= MAX_DATA_ROWS) break;
                    Row sectionRow = sheet.createRow(rowIndex++);
                    Cell sectionCell = sectionRow.createCell(0);
                    sectionCell.setCellValue("\u2014\u2014 " + source + " unique \u2014\u2014");
                    sectionCell.setCellStyle(sectionStyle);
                    if (lastCol > 0) {
                        sheet.addMergedRegion(new CellRangeAddress(rowIndex - 1, rowIndex - 1, 0, lastCol));
                    }
                    written++;

                    // Data rows for this source
                    for (ObjectNode rowNode : group) {
                        if (written >= MAX_DATA_ROWS) break;
                        Row row = sheet.createRow(rowIndex++);
                        for (int i = 0; i < columnSpecs.size(); i++) {
                            JsonNode val = rowNode.get(columnSpecs.get(i).field());
                            setCell(row, i, jsonNodeToString(val), textStyle);
                        }
                        written++;
                    }
                }
            } else {
                // Flat table for INTERSECT / EXCEPT
                for (ObjectNode rowNode : outRows) {
                    if (written >= MAX_DATA_ROWS) break;
                    Row row = sheet.createRow(rowIndex++);
                    for (int i = 0; i < columnSpecs.size(); i++) {
                        JsonNode val = rowNode.get(columnSpecs.get(i).field());
                        setCell(row, i, jsonNodeToString(val), textStyle);
                    }
                    written++;
                }
            }

            sheet.createFreezePane(0, dataStartRow);
            if (!columnSpecs.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(dataStartRow - 1, dataStartRow - 1, 0, columnSpecs.size() - 1));
            }
            autoSize(sheet, columnSpecs.size());
        }
    }

    /** Builds the value-matrix rows for a COMPARE (reused by sheets and summary $vars). */
    private List<ObjectNode> buildCompareRows(CompareSpec cmp, Map<String, List<ObjectNode>> rowsByRequest,
                                              FilterSpec filterSpec, ObjectMapper mapper) {
        String field = cmp.field();
        List<String> sources = cmp.sources();

        Map<String, Set<String>> valueToSources = new LinkedHashMap<>();
        Instant compareNow = Instant.now();
        for (String source : sources) {
            List<ObjectNode> sourceRows = rowsByRequest.getOrDefault(source, List.of());
            for (ObjectNode row : sourceRows) {
                if (cmp.where() != null) {
                    Map<String, DateFieldConfig> dateFields = resolveDateConfig(source, filterSpec);
                    if (!RowConditionEvaluator.evaluate(row, cmp.where(), dateFields, compareNow, runtimeVars)) {
                        continue;
                    }
                }
                JsonNode val = row.get(field);
                if (val != null && !val.isNull()) {
                    valueToSources.computeIfAbsent(val.asText(), k -> new LinkedHashSet<>()).add(source);
                }
            }
        }

        List<ObjectNode> outRows = new ArrayList<>();
        List<String> sortedValues = new ArrayList<>(valueToSources.keySet());
        Collections.sort(sortedValues);
        for (String value : sortedValues) {
            Set<String> present = valueToSources.get(value);
            ObjectNode row = mapper.createObjectNode();
            row.put(field, value);
            for (String source : sources) {
                row.put("_in_" + source, present.contains(source));
            }
            row.put("_count", present.size());
            outRows.add(row);
        }

        if (cmp.having() != null) {
            outRows.removeIf(row -> !RowConditionEvaluator.evaluate(row, cmp.having(), Collections.emptyMap(), compareNow, runtimeVars));
        }
        return applyDataShape(outRows, cmp.name(), filterSpec);
    }

    private void createCompareSheets(Workbook workbook, SheetStyleFactory styleFactory,
                                     List<ExecutionResult> results, FilterSpec filterSpec,
                                     Set<String> usedNames) {
        if (filterSpec == null || filterSpec.compares() == null || filterSpec.compares().isEmpty()) {
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<ObjectNode>> rowsByRequest = buildRowsByRequest(results, mapper);

        for (CompareSpec cmp : filterSpec.compares()) {
            String field = cmp.field();
            List<String> sources = cmp.sources();

            List<ObjectNode> outRows = buildCompareRows(cmp, rowsByRequest, filterSpec, mapper);
            if (outRows.isEmpty()) {
                System.out.printf("[INFO] Compare \"%s\" on field \"%s\" produced no values — sheet skipped.%n", cmp.name(), field);
                continue;
            }

            // Column specs: field, then _in_<source> for each, then _count
            List<ColumnSpec> columnSpecs = new ArrayList<>();
            columnSpecs.add(new ColumnSpec(field, null));
            for (String source : sources) {
                columnSpecs.add(new ColumnSpec("_in_" + source, null));
            }
            columnSpecs.add(new ColumnSpec("_count", null));

            String sheetName = uniqueSheetName(safeSheetName(cmp.name()), usedNames);
            usedNames.add(sheetName);

            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle titleStyle  = styleFactory.createTitleStyle(workbook, IndexedColors.DARK_BLUE);
            CellStyle headerStyle = styleFactory.createHeaderStyle(workbook, IndexedColors.BLUE_GREY);
            CellStyle textStyle   = styleFactory.createTextStyle(workbook, false);

            createTitleRow(sheet, titleStyle, cmp.name() + " \u2014 COMPARE (" + field + ")");
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < columnSpecs.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columnSpecs.get(i).header());
                cell.setCellStyle(headerStyle);
            }
            int dataStartRow = 3;

            int rowIndex = dataStartRow;
            int written = 0;
            for (ObjectNode rowNode : outRows) {
                if (written >= MAX_DATA_ROWS) break;
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < columnSpecs.size(); i++) {
                    JsonNode val = rowNode.get(columnSpecs.get(i).field());
                    setCell(row, i, jsonNodeToString(val), textStyle);
                }
                written++;
            }

            sheet.createFreezePane(0, dataStartRow);
            sheet.setAutoFilter(new CellRangeAddress(dataStartRow - 1, dataStartRow - 1, 0, columnSpecs.size() - 1));
            autoSize(sheet, columnSpecs.size());
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
        // If the detail URL uses a different variable name (e.g. {{itemid}} vs source field "id"),
        // lookupVar holds that name; otherwise fall back to the source field name.
        String urlVarName        = tableSpec.lookupVar() != null ? tableSpec.lookupVar() : lookupParam;

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
            JsonNode paramNode = extractNestedField(sourceRow, lookupParam);
            if (paramNode == null || paramNode.isNull()) {
                failed++;
                continue; // skip rows where the lookup key is absent
            }
            String paramValue = paramNode.asText();

            // Inject the param value as a variable so {{urlVarName}} in the URL is resolved.
            // urlVarName defaults to lookupParam unless BY ... AS <var> was used in the filter.
            Map<String, String> overrideVars = Map.of(urlVarName, paramValue);

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
    private List<ColumnSpec> resolveCustomTableColumns(List<ObjectNode> rows, CustomTableSpec tableSpec) {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        rows.forEach(r -> r.fieldNames().forEachRemaining(available::add));
        if (tableSpec.columns() != null && !tableSpec.columns().isEmpty()) {
            return ColumnSpec.project(available, tableSpec.columns());
        }
        return ColumnSpec.project(available, null);
    }

    /**
     * Extracts a value from {@code row} using a dot-separated path.
     * {@code "id"} reads the top-level {@code id} field; {@code "data.id"} traverses
     * the nested {@code data} object before reading {@code id}.
     * Falls back to a flat key look-up first so that already-merged rows (whose nested
     * fields are stored as {@code "detail.id"} strings) still resolve correctly.
     */
    private JsonNode extractNestedField(ObjectNode row, String path) {
        if (path == null || path.isBlank()) return null;
        // Fast path: field stored flat (covers both simple fields and already-prefixed ones)
        JsonNode direct = row.get(path);
        if (direct != null) return direct;
        // Traverse the actual JSON tree for true nested objects
        String[] parts = path.split("\\.", -1);
        JsonNode current = row;
        for (String part : parts) {
            if (current == null || !current.isObject()) return null;
            current = current.get(part);
        }
        return current;
    }

    /**
     * Unnests a named array field within each row into individual rows — one row per
     * array element. The parent row's scalar fields are repeated for every child row.
     *
     * <p>Child fields present in <em>every</em> child object are named
     * {@code "<arrayField>.<childField>"} (e.g. {@code "items.itemid"}).
     * Child fields that appear in only <em>some</em> child objects (sparse / schema-variant
     * rows) are placed at the end, named {@code "<exceptionPrefix>.<childField>"}.
     *
     * <p>Does nothing when no {@link ExpandSpec} is configured for the request.
     */
    private List<ObjectNode> expandRows(List<ObjectNode> rows, String requestName,
                                        FilterSpec spec, ObjectMapper mapper) {
        if (spec == null || spec.expands() == null || spec.expands().isEmpty()) return rows;
        ExpandSpec expandSpec = spec.expands().containsKey(requestName)
                ? spec.expands().get(requestName)
                : spec.expands().get("*");
        if (expandSpec == null) return rows;

        String arrayField  = expandSpec.field();
        String childPrefix = arrayField;
        String exPrefix    = expandSpec.exceptionPrefix() != null ? expandSpec.exceptionPrefix() : "exceptions";

        // First pass: collect all child field names and count how many child items contain each one.
        LinkedHashSet<String> allChildFields = new LinkedHashSet<>();
        Map<String, Integer> fieldCounts = new LinkedHashMap<>();
        int totalItems = 0;
        for (ObjectNode row : rows) {
            JsonNode arr = row.get(arrayField);
            if (arr == null || !arr.isArray()) continue;
            for (JsonNode item : arr) {
                if (!item.isObject()) continue;
                totalItems++;
                ObjectNode flat = flattenRow((ObjectNode) item);
                flat.fieldNames().forEachRemaining(f -> {
                    allChildFields.add(f);
                    fieldCounts.merge(f, 1, Integer::sum);
                });
            }
        }

        // Core fields: present in every child item. Sparse fields: not in every child item.
        LinkedHashSet<String> coreFields   = new LinkedHashSet<>();
        LinkedHashSet<String> sparseFields = new LinkedHashSet<>();
        for (String f : allChildFields) {
            if (totalItems > 0 && fieldCounts.getOrDefault(f, 0) == totalItems) {
                coreFields.add(f);
            } else {
                sparseFields.add(f);
            }
        }

        // Second pass: build the expanded row list.
        List<ObjectNode> expanded = new ArrayList<>();
        for (ObjectNode row : rows) {
            JsonNode arr = row.get(arrayField);
            if (arr == null || !arr.isArray()) {
                // No array value — keep parent row and add empty child columns so headers are uniform.
                ObjectNode copy = mapper.createObjectNode();
                addParentFields(row, arrayField, copy);
                for (String f : coreFields)   copy.putNull(childPrefix + "." + f);
                for (String f : sparseFields)  copy.putNull(exPrefix    + "." + f);
                expanded.add(copy);
                continue;
            }
            boolean hadItems = false;
            for (JsonNode item : arr) {
                if (!item.isObject()) continue;
                hadItems = true;
                ObjectNode flat   = flattenRow((ObjectNode) item);
                ObjectNode newRow = mapper.createObjectNode();
                addParentFields(row, arrayField, newRow);
                for (String f : coreFields) {
                    JsonNode v = flat.get(f);
                    newRow.set(childPrefix + "." + f, v != null ? v : mapper.nullNode());
                }
                for (String f : sparseFields) {
                    JsonNode v = flat.get(f);
                    newRow.set(exPrefix + "." + f, v != null ? v : mapper.nullNode());
                }
                expanded.add(newRow);
            }
            if (!hadItems) {
                // Empty array — keep parent row with empty child columns.
                ObjectNode copy = mapper.createObjectNode();
                addParentFields(row, arrayField, copy);
                for (String f : coreFields)   copy.putNull(childPrefix + "." + f);
                for (String f : sparseFields)  copy.putNull(exPrefix    + "." + f);
                expanded.add(copy);
            }
        }
        return expanded;
    }

    /** Copies all fields from {@code source} into {@code target}, skipping {@code excludeField}. */
    private static void addParentFields(ObjectNode source, String excludeField, ObjectNode target) {
        source.fieldNames().forEachRemaining(f -> {
            if (!f.equals(excludeField)) target.set(f, source.get(f));
        });
    }

    private List<ObjectNode> extractResponseRows(JsonNode root) {
        if (root.isArray()) {
            List<ObjectNode> list = new ArrayList<>();
            for (JsonNode item : root) {
                if (item.isObject()) list.add(flattenRow((ObjectNode) item));
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
                        if (item.isObject()) list.add(flattenRow((ObjectNode) item));
                    }
                    if (!list.isEmpty()) return list;
                }
            }
            return List.of(flattenRow((ObjectNode) root));
        }
        return List.of();
    }

    /**
     * Recursively flattens a JSON object into a single-level {@link ObjectNode} using
     * dot-notation keys. Nested objects are expanded: {@code {"a":{"b":1}}} becomes
     * {@code {"a.b": 1}}. Arrays are kept as-is (they appear as JSON strings in Excel
     * cells via {@link #jsonNodeToString}).
     */
    private ObjectNode flattenRow(ObjectNode row) {
        ObjectNode flat = row.objectNode(); // same node factory as the source
        flattenInto("", row, flat);
        return flat;
    }

    private void flattenInto(String prefix, JsonNode node, ObjectNode target) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenInto(key, entry.getValue(), target);
            }
        } else {
            // Scalar, null, or array — store directly; arrays render as JSON strings via jsonNodeToString
            target.set(prefix, node);
        }
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
