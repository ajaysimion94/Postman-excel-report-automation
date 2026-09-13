package com.automation.web;

import com.automation.postman.PostmanCollection;
import com.automation.postman.PostmanCollectionParser;
import com.automation.postman.RequestSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Searches real workspace files and delegates Documents searches to their source of truth. */
public final class SearchService {
    private final WorkspaceFiles files;
    private final DocumentsService documents;

    public SearchService(WorkspaceFiles files, DocumentsService documents) {
        this.files = files;
        this.documents = documents;
    }

    public Map<String, Object> search(String query, String typeFilter, String scope) throws IOException {
        String original = Objects.toString(query, "");
        String needle = original.toLowerCase(Locale.ROOT);
        boolean searchName = scope == null || scope.equals("all") || scope.equals("name");
        boolean searchContent = scope == null || scope.equals("all") || scope.equals("content");
        List<Map<String, Object>> results = new ArrayList<>();
        for (WorkspaceFiles.Entry entry : files.list()) {
            if (entry.directory()) continue;
            String type = fileType(entry.path());
            if (typeFilter != null && !typeFilter.isBlank() && !typeFilter.equals("all") && !type.equals(typeFilter)) continue;
            boolean match = searchName && entry.name().toLowerCase(Locale.ROOT).contains(needle);
            List<Map<String, Object>> highlights = new ArrayList<>();
            if (searchContent && !needle.isBlank() && !type.equals("report")) {
                try {
                    String[] lines = files.read(entry.path()).content().split("\\R");
                    for (int index = 0; index < lines.length && highlights.size() < 5; index++) {
                        if (lines[index].toLowerCase(Locale.ROOT).contains(needle)) {
                            match = true;
                            highlights.add(Map.of("line", index + 1, "snippet", lines[index].trim()));
                        }
                    }
                } catch (Exception ignored) { }
            }
            if (needle.isBlank()) match = true;
            if (match) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("path", entry.path()); result.put("name", entry.name()); result.put("type", type);
                result.put("modified", entry.modified()); result.put("size", entry.size()); result.put("highlights", highlights);
                results.add(result);
            }
        }
        return Map.of("query", original, "results", results, "count", results.size());
    }

    public Map<String, Object> executeQuery(JsonNode body) throws IOException {
        String query = body.path("query").asText("");
        String needle = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkspaceFiles.Entry entry : files.list()) {
            if (!entry.directory() && entry.path().startsWith("collections/") && entry.path().endsWith(".json")) {
                try {
                    PostmanCollection collection = new PostmanCollectionParser().parse(files.resolve(entry.path()));
                    for (RequestSpec request : collection.requests()) {
                        String url = Objects.toString(request.url(), "");
                        if (needle.isBlank() || request.name().toLowerCase(Locale.ROOT).contains(needle) || url.toLowerCase(Locale.ROOT).contains(needle)) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("collection", collection.name()); row.put("request", request.name());
                            row.put("method", request.method()); row.put("url", url); rows.add(row);
                        }
                    }
                } catch (Exception ignored) { }
            }
        }
        return Map.of("query", query, "columns", List.of("collection", "request", "method", "url"), "rows", rows, "count", rows.size());
    }

    public Map<String, Object> searchDocuments(String query) throws IOException { return documents.noteSearch(query); }
    public Map<String, Object> searchCatalogs(String query) throws IOException { return documents.catalogSearch(query); }

    public byte[] exportToExcel(JsonNode body) throws IOException {
        JsonNode rows = body.path("rows");
        JsonNode columns = body.path("columns");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Search Results");
            Row header = sheet.createRow(0);
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            List<String> names = new ArrayList<>();
            if (columns.isArray()) for (int index = 0; index < columns.size(); index++) {
                names.add(columns.get(index).asText());
                Cell cell = header.createCell(index); cell.setCellValue(names.get(index)); cell.setCellStyle(headerStyle);
            }
            if (rows.isArray()) for (int index = 0; index < rows.size(); index++) {
                Row row = sheet.createRow(index + 1);
                for (int column = 0; column < names.size(); column++) {
                    JsonNode value = rows.get(index).path(names.get(column));
                    if (!value.isMissingNode() && !value.isNull()) {
                        if (value.isNumber()) row.createCell(column).setCellValue(value.asDouble());
                        else if (value.isBoolean()) row.createCell(column).setCellValue(value.asBoolean());
                        else row.createCell(column).setCellValue(value.isContainerNode() ? value.toString() : value.asText());
                    }
                }
            }
            for (int index = 0; index < names.size(); index++) sheet.autoSizeColumn(index);
            workbook.write(out); return out.toByteArray();
        }
    }

    private String fileType(String path) {
        if (path.startsWith("collections/")) return "collection";
        if (path.startsWith("filters/")) return "filter";
        if (path.startsWith("queries/")) return "query";
        if (path.startsWith("reports/")) return "report";
        if (path.startsWith("documents/opds/")) return "opd";
        if (path.startsWith("documents/catalogs/types/")) return "catalog-type";
        if (path.startsWith("documents/catalogs/records/")) return "catalog-record";
        return "other";
    }
}
