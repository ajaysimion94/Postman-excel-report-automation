package com.automation.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * A response or table column: {@code field} is the JSON path used to read cell values;
 * {@code label} is an optional Excel header override from {@code field AS "Label"}.
 */
public record ColumnSpec(String field, String label) {
    public ColumnSpec {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("column field cannot be blank");
        }
    }

    /** Header text shown in Excel; defaults to {@link #field()} when no label is set. */
    public String header() {
        return label != null && !label.isBlank() ? label : field;
    }

    /** Applies configured columns in order, keeping only fields present in {@code availableFields}. */
    public static List<ColumnSpec> project(Collection<String> availableFields, List<ColumnSpec> configured) {
        if (configured == null || configured.isEmpty()) {
            return availableFields.stream().map(f -> new ColumnSpec(f, null)).toList();
        }
        Set<String> available = Set.copyOf(availableFields);
        List<ColumnSpec> out = new ArrayList<>();
        for (ColumnSpec spec : configured) {
            if (available.contains(spec.field())) {
                out.add(spec);
            }
        }
        return out;
    }
}
