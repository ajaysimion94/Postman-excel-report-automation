package com.automation.filter;

/**
 * A single join condition between two source requests in a multi-request custom table.
 *
 * <p>The join performs an inner join: for each row in the left (first) source, all matching
 * rows from the right (second) source are merged. Rows without a match are excluded.
 *
 * <pre>
 * { "leftField": "userId", "rightField": "id" }
 * </pre>
 *
 * <p>The values are field names without alias prefixes.
 * Multiple join conditions are combined with AND.
 */
public record CustomTableJoinCondition(
        /** Field name in the left (first) source. */
        String leftField,

        /** Field name in the right (second) source. */
        String rightField
) {}
