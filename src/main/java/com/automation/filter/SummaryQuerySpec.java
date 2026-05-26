package com.automation.filter;

/**
 * A named dataset for the Summary sheet ({@code $name = FILTER ...} or {@code $name = TABLE "..."}).
 */
public record SummaryQuerySpec(
        String variableName,
        SummaryQuerySource source
) {
}
