package com.automation.filter;

/**
 * One ORDER BY term for response/custom table shaping.
 */
public record SortSpec(String field, boolean descending) {
}
