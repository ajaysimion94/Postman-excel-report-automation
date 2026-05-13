package com.automation.filter;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * Defines a custom table sheet built from one or more request responses.
 *
 * <p>There are two usage modes:
 *
 * <h3>Single-source table</h3>
 * <p>Uses {@code sourceRequest} to pull rows from a single request. Applies optional
 * {@code where} conditions and then selects {@code columns}.
 *
 * <pre>
 * {
 *   "name":          "Yesterday Active Posts",
 *   "sourceRequest": "List all posts",
 *   "columns":       ["id", "title", "modifiedDate"],
 *   "where": {
 *     "logic": "AND",
 *     "rules": [
 *       { "field": "enabled",      "op": "EQ",         "value": "true"      },
 *       { "field": "modifiedDate", "op": "DATE_PRESET", "value": "YESTERDAY" }
 *     ]
 *   }
 * }
 * </pre>
 *
 * <h3>Multi-source (join) table</h3>
 * <p>Performs an inner join between two or more requests on matching key fields.
 * Column references may use {@code "alias.field"} notation when sources share field names.
 *
 * <pre>
 * {
 *   "name":    "Posts with User Names",
 *   "sources": [
 *     { "request": "List all posts", "as": "p" },
 *     { "request": "List users",     "as": "u" }
 *   ],
 *   "joinOn":  [{ "leftField": "userId", "rightField": "id" }],
 *   "columns": ["p.id", "p.title", "u.name", "p.modifiedDate"],
 *   "where": {
 *     "logic": "AND",
 *     "rules": [
 *       { "field": "p.enabled", "op": "EQ", "value": "true" }
 *     ]
 *   }
 * }
 * </pre>
 */
public record CustomTableSpec(
        /** Sheet name for this custom table. Must be non-blank and unique. */
        String name,

        /**
         * Single source request name. Exclusive with {@code sources}.
         * Use this for single-request tables.
         */
        @JsonAlias("request") String sourceRequest,

        /**
         * List of source requests for join-style tables. Exclusive with {@code sourceRequest}.
         * Currently supports exactly two sources (inner join).
         */
        List<CustomTableJoinSource> sources,

        /**
         * Join conditions between the first and second source in {@code sources}.
         * Required when {@code sources} is provided.
         */
        List<CustomTableJoinCondition> joinOn,

        /**
         * Ordered list of column names to include in the output.
         * For join tables, use {@code "alias.field"} notation to disambiguate.
         * When {@code null} or empty, all columns are included.
         */
        List<String> columns,

        /**
         * Optional row filter applied after joining (or directly for single-source tables).
         * Uses the same {@link RowFilterGroup} rule syntax.
         */
        RowFilterGroup where
) {}
