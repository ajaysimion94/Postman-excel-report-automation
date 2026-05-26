package com.automation.filter;

/** One fragment of a {@link SummaryItem.Text} expression. */
public sealed interface SummaryTextPart permits SummaryTextPart.Literal, SummaryTextPart.Variable {
    record Literal(String value) implements SummaryTextPart {
    }

    /** References a summary query variable ({@code $name}) or a filter {@code vars} entry. */
    record Variable(String name) implements SummaryTextPart {
    }
}
