package com.automation.filter;

import java.util.List;

/**
 * Column-wise comparison across multiple request result sets.
 * Produces a matrix: unique column values x boolean presence per source.
 */
public record CompareSpec(
        String name,
        String field,
        List<String> sources,
        RowFilterGroup where,
        RowFilterGroup having
) {
}
