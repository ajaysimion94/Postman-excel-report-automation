package com.automation.filter;

import java.util.List;
import java.util.Map;

/**
 * Defines which requests to run, which response columns to include, row-level conditions,
 * date parsing hints, and custom table definitions.
 *
 * <pre>
 * {
 *   // ── existing fields ──────────────────────────────────────────────────────────
 *   "collection":      "jsonplaceholder",
 *   "requests":        ["List all posts"],
 *   "responseColumns": { "*": [{ "field": "id", "label": "Post ID" }, { "field": "title" }] },
 *   "outputPrefix":    "daily",
 *
 *   // ── row-level filters (new) ───────────────────────────────────────────────────
 *   // Per-request or wildcard "*" row conditions applied to the Response Data sheet.
 *   "rowFilters": {
 *     "*": {
 *       "logic": "AND",
 *       "rules": [{ "field": "enabled", "op": "EQ", "value": "true" }]
 *     },
 *     "List all posts": {
 *       "logic": "AND",
 *       "rules": [{ "field": "modifiedDate", "op": "DATE_PRESET", "value": "YESTERDAY" }]
 *     }
 *   },
 *
 *   // ── date parsing config (new) ─────────────────────────────────────────────────
 *   // Tells the evaluator how to parse date strings for DATE_PRESET / DATE_RANGE rules.
 *   // Outer key: request name or "*" (wildcard). Inner key: field name.
 *   "dateConfig": {
 *     "*": {
 *       "modifiedDate": { "format": "yyyy-MM-dd'T'HH:mm:ss'Z'", "timezone": "UTC" }
 *     },
 *     "List all posts": {
 *       "createdAt": { "format": "dd/MM/yyyy", "timezone": "Asia/Kolkata" }
 *     }
 *   },
 *
 *   // ── custom table definitions (new) ────────────────────────────────────────────
 *   // Each entry produces a new sheet sourced from one or more request responses.
 *   "customTables": [
 *     {
 *       "name":          "Yesterday Active Posts",
 *       "sourceRequest": "List all posts",
 *       "columns":       ["id", "title", "modifiedDate"],
 *       "where": { "logic": "AND", "rules": [
 *         { "field": "enabled",      "op": "EQ",         "value": "true"      },
 *         { "field": "modifiedDate", "op": "DATE_PRESET", "value": "YESTERDAY" }
 *       ]}
 *     },
 *     {
 *       "name":    "Posts with User Names",
 *       "sources": [
 *         { "request": "List all posts", "as": "p" },
 *         { "request": "List users",     "as": "u" }
 *       ],
 *       "joinOn":  [{ "leftField": "userId", "rightField": "id" }],
 *       "columns": ["p.id", "p.title", "u.name"],
 *       "where": { "logic": "AND", "rules": [
 *         { "field": "p.enabled", "op": "EQ", "value": "true" }
 *       ]}
 *     }
 *   ]
 *
 *   // ── summary sheet (new) ──────────────────────────────────────────────────────
 *   // STATUS keyword: per-request status block (like METRICS but per-request)
 *   // QT / QUICK_TABLE: now supports N columns via HEADERS clause
 *   // COLOR: accepts hex colors ("#FF5500") in addition to named IndexedColors
 *   // IF/ELSE: conditional logic in WHERE expressions
 * }
 * </pre>
 */
public record FilterSpec(
        String collection,
        List<String> requests,
        Map<String, List<ColumnSpec>> responseColumns,
        String outputPrefix,
        FilterAuthSpec auth,
        Map<String, String> vars,

        /**
         * Per-request row-level filter conditions applied to the Response Data sheet.
         * Key: request name or {@code "*"} wildcard.
         * Request-specific rules take priority over the wildcard.
         */
        Map<String, RowFilterGroup> rowFilters,

        /**
         * Date parsing hints, scoped per request and per field.
         * Outer key: request name or {@code "*"} wildcard.
         * Inner key: field name in the response JSON.
         */
        Map<String, Map<String, DateFieldConfig>> dateConfig,

        /**
         * Custom table definitions. Each entry generates a dedicated sheet with filtered,
         * joined, and projected rows derived from one or more request responses.
         */
        List<CustomTableSpec> customTables,

        /**
         * Optional output shaping controls by request name/table name or wildcard {@code "*"}.
         * Supports DISTINCT, ORDER BY, LIMIT and OFFSET semantics.
         */
        Map<String, DataShapeSpec> dataShapes,

        /**
         * Optional UNION/UNION ALL sheet definitions built from multiple request outputs.
         */
        List<UnionSpec> unions,

        /**
         * Optional array-expansion specs, keyed by request name or {@code "*"} wildcard.
         * Each entry tells the generator to unnest a named array field into individual rows.
         */
        Map<String, ExpandSpec> expands,

        /**
         * Optional customizable Summary sheet layout (title, text, embedded tables, metrics).
         */
        SummarySpec summary
) {
    public FilterSpec(
            String collection,
            List<String> requests,
            Map<String, List<ColumnSpec>> responseColumns,
            String outputPrefix,
            FilterAuthSpec auth,
            Map<String, String> vars,
            Map<String, RowFilterGroup> rowFilters,
            Map<String, Map<String, DateFieldConfig>> dateConfig,
            List<CustomTableSpec> customTables
    ) {
        this(collection, requests, responseColumns, outputPrefix, auth, vars, rowFilters, dateConfig, customTables, null, null, null, null);
    }

    public FilterSpec(
            String collection,
            List<String> requests,
            Map<String, List<ColumnSpec>> responseColumns,
            String outputPrefix,
            FilterAuthSpec auth,
            Map<String, String> vars,
            Map<String, RowFilterGroup> rowFilters,
            Map<String, Map<String, DateFieldConfig>> dateConfig,
            List<CustomTableSpec> customTables,
            Map<String, DataShapeSpec> dataShapes
    ) {
        this(collection, requests, responseColumns, outputPrefix, auth, vars, rowFilters, dateConfig, customTables, dataShapes, null, null, null);
    }

    public FilterSpec(
            String collection,
            List<String> requests,
            Map<String, List<ColumnSpec>> responseColumns,
            String outputPrefix,
            FilterAuthSpec auth,
            Map<String, String> vars,
            Map<String, RowFilterGroup> rowFilters,
            Map<String, Map<String, DateFieldConfig>> dateConfig,
            List<CustomTableSpec> customTables,
            Map<String, DataShapeSpec> dataShapes,
            List<UnionSpec> unions
    ) {
        this(collection, requests, responseColumns, outputPrefix, auth, vars, rowFilters, dateConfig, customTables, dataShapes, unions, null, null);
    }
}
