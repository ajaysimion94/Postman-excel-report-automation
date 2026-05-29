package com.automation.filter;

import java.util.List;

/** One rendered block on the customizable Summary sheet. */
public sealed interface SummaryItem permits SummaryItem.Title, SummaryItem.Description,
        SummaryItem.Text, SummaryItem.KeyValue, SummaryItem.LabelValue,
        SummaryItem.Table, SummaryItem.QuickTable, SummaryItem.Metrics, SummaryItem.Status {
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

    /** Inline table with optional title, N custom headers, and rows with N columns.
     *  Supports both the classic 2-column label/value mode and multi-column mode.
     *  When {@code headers} is null or empty, no header row is rendered.
     *  Each {@link InlineTableRow} carries a list of cell values (one per header column).
     *  Backward-compatible: 2-arg constructor uses classic Label/Value headers. */
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

    /** One row within a {@link QuickTable}. Supports any number of columns.
     *  Backward-compatible: 2-arg constructor wraps classic label + single valueParts
     *  into the multi-column format. */
    record InlineTableRow(String label, List<SummaryTextPart> valueParts, List<List<SummaryTextPart>> columns) {
        /** Classic 2-column constructor (Label + Value). */
        public InlineTableRow(String label, List<SummaryTextPart> valueParts) {
            this(label, valueParts, null);
        }
        /** Returns the effective column values: if multi-column mode, uses {@code columns};
         *  otherwise falls back to the classic label-in-col0, valueParts-in-col1 pattern. */
        public List<List<SummaryTextPart>> effectiveColumns() {
            if (columns != null && !columns.isEmpty()) {
                return columns;
            }
            // Fallback: classic 2-column mode
            List<SummaryTextPart> labelPart = label != null
                    ? List.of(new SummaryTextPart.Literal(label))
                    : List.of();
            return List.of(labelPart, valueParts != null ? valueParts : List.of());
        }
    }

    /** Execution metrics as label/value rows (no Metric/Value header). */
    record Metrics() implements SummaryItem {
    }

    /** Per-request status table showing each request's name, status code, success, and duration.
     *  Functions like METRICS but at the individual request level instead of aggregate.
     *  Optional COLOR clause sets the section title color. */
    record Status(String colorName) implements SummaryItem {
        public Status() {
            this(null);
        }
    }
}
