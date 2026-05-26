package com.automation.filter;

import java.util.List;
import java.util.Map;

/**
 * Declarative layout for the Summary sheet, defined at the end of a {@code .filter} file.
 *
 * <pre>
 * TITLE "Daily Report" COLOR DARK_BLUE;
 * TEXT "Welcome";
 * $POSTS = FILTER "List posts" WHERE id > 10;
 * TABLE $POSTS;
 * METRICS;
 * </pre>
 */
public record SummarySpec(
        List<SummaryItem> items,
        Map<String, SummaryQuerySpec> queries
) {
}
