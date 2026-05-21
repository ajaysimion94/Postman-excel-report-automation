package com.automation.filter;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * Defines a custom table sheet built from one or more request responses.
 *
 * <p>There are three usage modes:
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
 *   "where": { ... }
 * }
 * </pre>
 *
 * <h3>Multi-source (join) table</h3>
 * <p>Performs an inner join between two pre-fetched requests on matching key fields.
 *
 * <pre>
 * {
 *   "name":    "Posts with User Names",
 *   "sources": [
 *     { "request": "List all posts", "as": "p" },
 *     { "request": "List users",     "as": "u" }
 *   ],
 *   "joinType": "LEFT",
 *   "joinOn":  [{ "leftField": "userId", "rightField": "id" }],
 *   "columns": ["p.id", "p.title", "u.name", "p.modifiedDate"],
 *   "where": { ... }
 * }
 * </pre>
 *
 * <h3>Lookup (nested) table</h3>
 * <p>For each row from {@code sourceRequest}, re-executes {@code lookupRequest} with the
 * value of {@code lookupParam} injected as a URL/variable substitution. This is the
 * equivalent of a SQL correlated subquery: call a detail API once per item from a list API.
 * Fields from both sides are merged into one row; conflicting field names are prefixed with
 * {@code "detail."}.
 *
 * <pre>
 * {
 *   "name":          "Items with Details",
 *   "sourceRequest": "List items",
 *   "lookupRequest": "Get item details",
 *   "lookupParam":   "id",
 *   "columns":       ["id", "name", "detail.description", "detail.price"],
 *   "where": {
 *     "logic": "AND",
 *     "rules": [
 *       { "field": "detail.price", "op": "GT", "value": "100" }
 *     ]
 *   }
 * }
 * </pre>
 */
public record CustomTableSpec(
        /** Sheet name for this custom table. Must be non-blank and unique. */
        String name,

        /**
         * Single source request name. Used for single-source and lookup tables.
         * Exclusive with {@code sources}.
         */
        @JsonAlias("request") String sourceRequest,

        /**
         * List of source requests for join-style tables. Exclusive with {@code sourceRequest}.
         * Currently supports exactly two sources (inner join).
         */
        List<CustomTableJoinSource> sources,

        /**
         * Join type for multi-source tables: INNER (default), LEFT, RIGHT, or FULL.
         */
        String joinType,

        /**
         * Join conditions between the first and second source in {@code sources}.
         * Required when {@code sources} is provided.
         */
        List<CustomTableJoinCondition> joinOn,

        /**
         * Detail request to execute once per row from {@code sourceRequest}.
         * The value of {@code lookupParam} from each source row is injected as a variable
         * (e.g., {@code {{id}}}) into the lookup request URL before execution.
         * Required when {@code lookupParam} is set.
         */
        String lookupRequest,

        /**
         * Field name (or dot-separated JSON path) from the source row whose value is extracted
         * for the lookup. For example, {@code "id"} reads the top-level {@code id} field;
         * {@code "data.id"} traverses into the nested {@code data} object.
         * Required when {@code lookupRequest} is set.
         */
        String lookupParam,

        /**
         * URL variable name to inject into {@code lookupRequest} when it differs from
         * {@code lookupParam}. If {@code null}, the injected variable name defaults to
         * {@code lookupParam}. Use this when the source field is named differently from the
         * placeholder in the detail request URL (e.g. source has {@code id} but the URL
         * uses {@code {{itemid}}} — set {@code lookupParam = "id"},
         * {@code lookupVar = "itemid"}).
         */
        String lookupVar,

        /**
         * Ordered list of column names to include in the output.
         * For join tables, use {@code "alias.field"} notation to disambiguate.
         * For lookup tables, conflicting fields from the detail response are prefixed
         * with {@code "detail."} (e.g., {@code "detail.description"}).
         * When {@code null} or empty, all columns are included.
         */
        List<String> columns,

        /**
         * Optional row filter applied after joining/lookup (or directly for single-source tables).
         * Uses the same {@link RowFilterGroup} rule syntax.
         */
        RowFilterGroup where
) {
        public CustomTableSpec(
                String name,
                String sourceRequest,
                List<CustomTableJoinSource> sources,
                List<CustomTableJoinCondition> joinOn,
                String lookupRequest,
                String lookupParam,
                List<String> columns,
                RowFilterGroup where
        ) {
                this(name, sourceRequest, sources, null, joinOn, lookupRequest, lookupParam, null, columns, where);
        }
}
