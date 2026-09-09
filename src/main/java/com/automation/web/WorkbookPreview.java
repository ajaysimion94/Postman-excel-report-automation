package com.automation.web;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Reads the generated workbook itself so web and Excel share values, merges, and formatting. */
final class WorkbookPreview {
    static Map<String, Object> read(Path path, int sheetIndex, int offset, int limit) throws IOException {
        if (sheetIndex < 0 || offset < 0 || limit < 1 || limit > 500) throw new WebException(400, "Invalid worksheet page.");
        try (XSSFWorkbook workbook = new XSSFWorkbook(org.apache.poi.openxml4j.opc.OPCPackage.open(
                path.toFile(), org.apache.poi.openxml4j.opc.PackageAccess.READ))) {
            if (sheetIndex >= workbook.getNumberOfSheets()) throw new WebException(404, "Worksheet not found.");
            List<Map<String, Object>> sheets = new ArrayList<>();
            for (Sheet sheet : workbook) sheets.add(Map.of("name", sheet.getSheetName(), "rows", sheet.getLastRowNum() + 1));
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            DataFormatter formatter = new DataFormatter(Locale.US);
            List<Map<String, Object>> rows = new ArrayList<>();
            int columnCount = 2;
            int end = (int) Math.min((long) offset + limit, (long) sheet.getLastRowNum() + 1);
            Map<Integer, Map<String, Object>> styles = new LinkedHashMap<>();
            for (int r = offset; r < end; r++) {
                Row row = sheet.getRow(r);
                List<Map<String, Object>> cells = new ArrayList<>();
                if (row != null) {
                    columnCount = Math.max(columnCount, row.getLastCellNum());
                    for (Cell cell : row) {
                        int styleIndex = Short.toUnsignedInt(cell.getCellStyle().getIndex());
                        styles.computeIfAbsent(styleIndex, k -> style((XSSFCellStyle) cell.getCellStyle()));
                        cells.add(Map.of("column", cell.getColumnIndex(), "text", formatter.formatCellValue(cell), "style", styleIndex));
                    }
                }
                rows.add(Map.of("index", r, "height", row == null ? 24 : row.getHeightInPoints() * 4 / 3, "cells", cells));
            }
            List<Map<String, Object>> merges = new ArrayList<>();
            for (CellRangeAddress merge : sheet.getMergedRegions()) {
                if (merge.getLastRow() >= offset && merge.getFirstRow() < end) {
                    Map<String, Object> region = new LinkedHashMap<>();
                    region.put("firstRow", merge.getFirstRow());
                    region.put("lastRow", merge.getLastRow());
                    region.put("firstColumn", merge.getFirstColumn());
                    region.put("lastColumn", merge.getLastColumn());
                    Row anchorRow = sheet.getRow(merge.getFirstRow());
                    Cell anchor = anchorRow == null ? null : anchorRow.getCell(merge.getFirstColumn());
                    if (anchor != null) {
                        int styleIndex = Short.toUnsignedInt(anchor.getCellStyle().getIndex());
                        styles.computeIfAbsent(styleIndex, k -> style((XSSFCellStyle) anchor.getCellStyle()));
                        region.put("text", formatter.formatCellValue(anchor));
                        region.put("style", styleIndex);
                    }
                    merges.add(region);
                    columnCount = Math.max(columnCount, merge.getLastColumn() + 1);
                }
            }
            List<Integer> widths = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) widths.add(Math.round(sheet.getColumnWidthInPixels(c)));
            return Map.of("sheets", sheets, "sheet", sheetIndex, "rows", rows, "styles", styles,
                    "merges", merges, "widths", widths, "offset", offset, "totalRows", sheet.getLastRowNum() + 1);
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new WebException(400, "This file is not a valid Excel workbook.");
        }
    }

    private static Map<String, Object> style(XSSFCellStyle style) {
        XSSFFont font = style.getFont();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bold", font.getBold());
        result.put("italic", font.getItalic());
        result.put("fontSize", font.getFontHeightInPoints());
        result.put("color", color(font.getXSSFColor()));
        result.put("background", style.getFillPattern() == FillPatternType.NO_FILL ? "" : color(style.getFillForegroundXSSFColor()));
        result.put("align", switch (style.getAlignment()) {
            case CENTER, CENTER_SELECTION -> "center";
            case RIGHT -> "right";
            default -> "left";
        });
        result.put("wrap", style.getWrapText());
        return result;
    }

    private static String color(XSSFColor color) {
        if (color == null || color.isAuto()) return "";
        byte[] rgb = color.getRGBWithTint();
        if (rgb == null) rgb = color.getRGB();
        if (rgb == null && color.isIndexed()) rgb = new DefaultIndexedColorMap().getRGB(color.getIndexed());
        return rgb == null ? "" : "#" + HexFormat.of().formatHex(Arrays.copyOfRange(rgb, Math.max(0, rgb.length - 3), rgb.length));
    }
}
