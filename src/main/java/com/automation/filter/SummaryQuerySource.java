package com.automation.filter;

/**
 * Where a summary {@code $variable} gets its rows.
 */
public sealed interface SummaryQuerySource permits SummaryQuerySource.FilterRows, SummaryQuerySource.NamedTable {
    /** Rows from a request response with an optional filter (summary-only). */
    record FilterRows(String requestKey, RowFilterGroup filter) implements SummaryQuerySource {
    }

    /** Rows from a {@link CustomTableSpec} already defined in the same filter file (lookup / join). */
    record NamedTable(String tableName) implements SummaryQuerySource {
    }
}
