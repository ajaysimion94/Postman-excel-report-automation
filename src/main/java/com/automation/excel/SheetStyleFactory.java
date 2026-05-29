package com.automation.excel;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;

public final class SheetStyleFactory {
    public CellStyle createTitleStyle(Workbook workbook, IndexedColors fillColor) {
        return createTitleStyle(workbook, fillColor, null);
    }

    /** Creates a title style with either a named IndexedColor or a custom hex color string. */
    public CellStyle createTitleStyle(Workbook workbook, IndexedColors indexedFill, String hexColor) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        applyFill(workbook, style, indexedFill, hexColor);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyBorders(style);
        return style;
    }

    public CellStyle createHeaderStyle(Workbook workbook, IndexedColors fillColor) {
        return createHeaderStyle(workbook, fillColor, null);
    }

    public CellStyle createHeaderStyle(Workbook workbook, IndexedColors indexedFill, String hexColor) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        applyFill(workbook, style, indexedFill, hexColor);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyBorders(style);
        return style;
    }

    /** Applies the correct fill color: custom hex if provided, otherwise indexed. */
    private void applyFill(Workbook workbook, CellStyle style, IndexedColors indexedFill, String hexColor) {
        if (hexColor != null && !hexColor.isBlank()) {
            byte[] rgb = hexToRgb(hexColor);
            if (workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook) {
                org.apache.poi.xssf.usermodel.XSSFColor customColor =
                        new org.apache.poi.xssf.usermodel.XSSFColor(rgb, null);
                ((org.apache.poi.xssf.usermodel.XSSFCellStyle) style).setFillForegroundColor(customColor);
            } else if (indexedFill != null) {
                style.setFillForegroundColor(indexedFill.getIndex());
            } else {
                style.setFillForegroundColor(IndexedColors.AUTOMATIC.getIndex());
            }
        } else if (indexedFill != null) {
            style.setFillForegroundColor(indexedFill.getIndex());
        }
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    public CellStyle createTextStyle(Workbook workbook, boolean wrap) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setWrapText(wrap);
        applyBorders(style);
        return style;
    }

    public CellStyle createStatusStyle(Workbook workbook, IndexedColors fillColor) {
        CellStyle style = createTextStyle(workbook, false);
        style.setFillForegroundColor(fillColor.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Summary sheet label column (column A) — bold + grey fill. */
    public CellStyle createSummaryLabelStyle(Workbook workbook) {
        CellStyle style = createTextStyle(workbook, false);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Lighter label style for auto-derived labels from TEXT / LV items — normal weight, light fill. */
    public CellStyle createSummaryAutoLabelStyle(Workbook workbook) {
        CellStyle style = createTextStyle(workbook, false);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Summary sheet section subtitle (smaller than workbook title rows). */
    public CellStyle createSummarySectionStyle(Workbook workbook) {
        CellStyle style = createTextStyle(workbook, false);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Compact table header on the Summary sheet. */
    public CellStyle createSummaryTableHeaderStyle(Workbook workbook) {
        CellStyle style = createTextStyle(workbook, false);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Boolean true — bright green fill, white text for high visibility. */
    public CellStyle createBooleanTrueStyle(Workbook workbook) {
        CellStyle style = createTextStyle(workbook, false);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.BRIGHT_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    /** Boolean false — red fill, white text for high visibility. */
    public CellStyle createBooleanFalseStyle(Workbook workbook) {
        CellStyle style = createTextStyle(workbook, false);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.RED.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private void applyBorders(CellStyle style) {
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    /** Parses a hex color string (with or without leading #) into RGB bytes. */
    static byte[] hexToRgb(String hex) {
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        if (clean.length() != 6) {
            throw new IllegalArgumentException("Invalid hex color: " + hex);
        }
        int r = Integer.parseInt(clean.substring(0, 2), 16);
        int g = Integer.parseInt(clean.substring(2, 4), 16);
        int b = Integer.parseInt(clean.substring(4, 6), 16);
        return new byte[]{(byte) r, (byte) g, (byte) b};
    }
}