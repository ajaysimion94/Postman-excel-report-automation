package com.automation.filter;

import java.util.List;

/** One rendered block on the customizable Summary sheet. */
public sealed interface SummaryItem permits SummaryItem.Title, SummaryItem.Description,
        SummaryItem.Text, SummaryItem.Table, SummaryItem.Metrics {
    record Title(String text, String colorName) implements SummaryItem {
    }

    record Description(String text, String colorName) implements SummaryItem {
    }

    record Text(List<SummaryTextPart> parts) implements SummaryItem {
    }

    /** Embeds rows from a {@link SummaryQuerySpec} variable. */
    record Table(String variableName) implements SummaryItem {
    }

    /** Embeds the default execution metrics block (collection, pass/fail counts, etc.). */
    record Metrics() implements SummaryItem {
    }
}
