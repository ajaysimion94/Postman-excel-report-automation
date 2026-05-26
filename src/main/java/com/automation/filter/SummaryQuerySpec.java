package com.automation.filter;

/**
 * A named query used only on the Summary sheet. Rows are resolved from a request response
 * and filtered with the attached {@link RowFilterGroup} (not written to {@code rowFilters}).
 */
public record SummaryQuerySpec(
        String variableName,
        String requestKey,
        RowFilterGroup filter
) {
}
