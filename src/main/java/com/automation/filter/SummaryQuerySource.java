package com.automation.filter;

/**
 * Where a summary {@code $variable} gets its rows.
 */
public sealed interface SummaryQuerySource permits SummaryQuerySource.FilterRows, SummaryQuerySource.NamedTable,
        SummaryQuerySource.UnionRows, SummaryQuerySource.SetOpRows, SummaryQuerySource.CompareRows,
        SummaryQuerySource.DerivedFilter {
    /** Rows from a request response with an optional filter (summary-only). */
    record FilterRows(String requestKey, RowFilterGroup filter) implements SummaryQuerySource {
    }

    /** Rows from a {@link CustomTableSpec} already defined in the same filter file (lookup / join). */
    record NamedTable(String tableName) implements SummaryQuerySource {
    }

    /** Rows from an inline UNION / UNION ALL across request outputs. */
    record UnionRows(UnionSpec spec) implements SummaryQuerySource {
    }

    /** Rows from an inline INTERSECT / EXCEPT / DIFF across request outputs. */
    record SetOpRows(SetOpSpec spec) implements SummaryQuerySource {
    }

    /** Rows from an inline COMPARE value matrix across request outputs. */
    record CompareRows(CompareSpec spec) implements SummaryQuerySource {
    }

    /** Rows derived from another summary {@code $variable}, with an optional filter applied. */
    record DerivedFilter(String sourceVariable, RowFilterGroup filter) implements SummaryQuerySource {
    }
}
