package com.automation.filter;

import java.util.List;

/** One rendered block on the customizable Summary sheet. */
public sealed interface SummaryItem permits SummaryItem.Title, SummaryItem.Description,
        SummaryItem.Text, SummaryItem.KeyValue, SummaryItem.LabelValue,
        SummaryItem.Table, SummaryItem.QuickTable, SummaryItem.Metrics {
    record Title(String text, String colorName) implements SummaryItem {
    }

    record Description(String text, String colorName) implements SummaryItem {
    }

    /** Free-form text on one line (use {@link KeyValue} or {@code KV} for label/value rows). */
    record Text(List<SummaryTextPart> parts) implements SummaryItem {
    }

    /** Label in column A and value in column B — no header row, bold+grey label. */
    record KeyValue(String label, List<SummaryTextPart> valueParts) implements SummaryItem {
    }

    /** Label in column A and value in column B — no header row, plain label style. */
    record LabelValue(String label, List<SummaryTextPart> valueParts) implements SummaryItem {
    }

    /** Tabular block from a {@link SummaryQuerySpec} variable. */
    record Table(String variableName, String title, List<ColumnSpec> columns) implements SummaryItem {
        public Table(String variableName) {
            this(variableName, null, null);
        }
    }

    /** Inline label-value table with optional title and custom headers. Null headers means no header row. */
    record QuickTable(String title, List<String> headers, List<InlineTableRow> rows) implements SummaryItem {
        public QuickTable(String title, List<InlineTableRow> rows) {
            this(title, List.of("Label", "Value"), rows);
        }
        public QuickTable(String title) {
            this(title, null, List.of());
        }
        boolean hasHeaders() {
            return headers != null && !headers.isEmpty();
        }
    }

    /** One row within a {@link QuickTable}. */
    record InlineTableRow(String label, List<SummaryTextPart> valueParts) {
    }

    /** Execution metrics as label/value rows (no Metric/Value header). */
    record Metrics() implements SummaryItem {
    }
}
