package com.automation.filter;

/**
 * Instructs the report generator to expand (unnest) a named array field within each
 * response row into individual rows — one row per array element.
 *
 * <p>For example, given a response like:
 * <pre>
 *   [{"category":"fruits","items":[{"itemid":1,"name":"apple"},{"itemid":2,"name":"orange"}]},...]
 * </pre>
 * {@code EXPAND "My Request" ON items;} produces one row per item, with parent fields
 * ({@code category}) repeated and child fields prefixed with the array field name
 * ({@code items.itemid}, {@code items.name}).
 *
 * <p>Child fields that appear in only some items (but not all) are treated as "sparse"
 * and are placed in the last columns, prefixed with {@link #exceptionPrefix}
 * (default: {@code "exceptions"}).
 *
 * <p>DSL syntax:
 * <pre>
 *   EXPAND "Request Name" ON fieldName;
 *   EXPAND "Request Name" ON fieldName AS exceptionLabel;
 * </pre>
 */
public record ExpandSpec(
        /** The array field in each row to expand into individual rows. */
        String field,

        /**
         * Column prefix for child fields not present in every child object.
         * Defaults to {@code "exceptions"} when not specified in the DSL.
         */
        String exceptionPrefix) {

    /** Convenience constructor using the default exception prefix {@code "exceptions"}. */
    public ExpandSpec(String field) {
        this(field, "exceptions");
    }
}
